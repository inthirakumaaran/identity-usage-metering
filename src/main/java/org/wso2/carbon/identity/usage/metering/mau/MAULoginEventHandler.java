/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.mau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IS event handler that listens for {@code AUTHENTICATION_SUCCESS} events and
 * asynchronously records each login in {@link MAUCacheManager}.
 *
 * <p>The flush scheduler is started inside {@link #init} — after the IS
 * framework has supplied {@code deployment.toml} properties — so the
 * configured interval is always used.
 *
 * <h3>What this counter captures</h3>
 * <p>Every unique human user who successfully authenticates at least once in a
 * calendar month is counted once toward that month's MAU.  Repeated logins by
 * the same user within the month do not increment the count.
 */
public class MAULoginEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MAULoginEventHandler.class);
    private static final String EVENT_AUTHENTICATION_SUCCESS = "AUTHENTICATION_SUCCESS";

    private final ExecutorService cacheWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mau-cache-writer");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService scheduler;
    private final MAUFlushTask flushTask;
    private final AtomicBoolean schedulerStarted = new AtomicBoolean(false);

    public MAULoginEventHandler(ScheduledExecutorService scheduler, MAUFlushTask flushTask) {
        this.scheduler = scheduler;
        this.flushTask = flushTask;
    }

    @Override
    public String getName() { return "mauLoginEventHandler"; }

    @Override
    public int getPriority(MessageContext messageContext) { return 200; }

    @Override
    public void init(InitConfig configuration) throws IdentityRuntimeException {
        super.init(configuration);
        if (this.configs instanceof ModuleConfiguration) {
            Properties props = ((ModuleConfiguration) this.configs).getModuleProperties();
            if (props != null && !props.isEmpty()) applyConfig(props);
            else LOG.info("[MAU] No handler properties in deployment.toml; using defaults.");
        } else {
            LOG.warn("[MAU] configs is not ModuleConfiguration; using defaults.");
        }
        startSchedulerOnce();
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || !EVENT_AUTHENTICATION_SUCCESS.equals(event.getEventName())) return;

        Map<String, Object> eventProps = event.getEventProperties();
        if (eventProps == null) {
            LOG.warn("[MAU] AUTHENTICATION_SUCCESS event has null properties. Skipping.");
            return;
        }

        AuthenticatedUser user = extractAuthenticatedUser(eventProps);
        if (user == null) {
            LOG.warn("[MAU] Unable to extract AuthenticatedUser from event. Skipping.");
            return;
        }

        String userId;
        try {
            userId = user.getUserId();
        } catch (Exception e) {
            userId = null;
        }
        if (userId == null || userId.isBlank()) userId = user.getUserName();
        String tenantDomain = user.getTenantDomain();

        if (userId == null || tenantDomain == null) {
            LOG.warn("[MAU] Missing userId or tenantDomain. Skipping.");
            return;
        }

        final String finalUserId = userId;
        final String finalTenant = tenantDomain;
        cacheWriter.submit(() -> {
            try {
                MAUCacheManager.getInstance().recordLogin(finalUserId, finalTenant);
                LOG.debug("[MAU] Cache updated: userId={} tenant={}", finalUserId, finalTenant);
            } catch (Exception e) {
                LOG.error("[MAU] Failed to record login: userId={} tenant={}",
                        finalUserId, finalTenant, e);
            }
        });
    }

    public void shutdown() {
        cacheWriter.shutdown();
        try {
            if (!cacheWriter.awaitTermination(10, TimeUnit.SECONDS)) {
                cacheWriter.shutdownNow();
            }
        } catch (InterruptedException ie) {
            cacheWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setNodeId(props.getProperty(UsageTrackingConfig.PROP_NODE_ID));
        UsageTrackingConfig.setMauFlushInterval(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_FLUSH_INTERVAL,
                        UsageTrackingConfig.getMauFlushInterval()));
        UsageTrackingConfig.setMauFlushIntervalUnit(
                parseTimeUnit(props.getProperty(UsageTrackingConfig.PROP_MAU_FLUSH_INTERVAL_UNIT)));
        UsageTrackingConfig.setMauCacheRetentionDays(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_CACHE_RETENTION,
                        UsageTrackingConfig.getMauCacheRetentionDays()));
        UsageTrackingConfig.setMauDbRetentionDays(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_DB_RETENTION,
                        UsageTrackingConfig.getMauDbRetentionDays()));
        LOG.info("[MAU] Config loaded: flushInterval={} {}, cacheRetention={}d, dbRetention={}d, nodeId={}.",
                UsageTrackingConfig.getMauFlushInterval(),
                UsageTrackingConfig.getMauFlushIntervalUnit(),
                UsageTrackingConfig.getMauCacheRetentionDays(),
                UsageTrackingConfig.getMauDbRetentionDays(),
                UsageTrackingConfig.getNodeId());
    }

    private void startSchedulerOnce() {
        if (schedulerStarted.compareAndSet(false, true)) {
            long interval = UsageTrackingConfig.getMauFlushInterval();
            TimeUnit unit = UsageTrackingConfig.getMauFlushIntervalUnit();
            scheduler.scheduleAtFixedRate(flushTask, interval, interval, unit);
            LOG.info("[MAU] Flush scheduler started: interval={} {}.", interval, unit);
        }
    }

    private static AuthenticatedUser extractAuthenticatedUser(Map<String, Object> eventProps) {
        Object paramsObj = eventProps.get("params");
        if (paramsObj instanceof Map) {
            Object userObj = ((Map<?, ?>) paramsObj).get("user");
            if (userObj instanceof AuthenticatedUser) return (AuthenticatedUser) userObj;
        }
        return null;
    }

    private static int parseIntProp(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("[MAU] Invalid integer '{}' for '{}'; using default {}.", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private static TimeUnit parseTimeUnit(String raw) {
        if (raw == null || raw.isBlank()) return TimeUnit.MINUTES;
        switch (raw.trim().toUpperCase()) {
            case "HOURS": return TimeUnit.HOURS;
            case "DAYS":  return TimeUnit.DAYS;
            default:
                if (!"MINUTES".equalsIgnoreCase(raw.trim()))
                    LOG.warn("[MAU] Unknown flushIntervalUnit '{}'; defaulting to MINUTES.", raw);
                return TimeUnit.MINUTES;
        }
    }
}
