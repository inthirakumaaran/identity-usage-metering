/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.m2m;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;
import org.wso2.carbon.identity.usage.metering.common.task.CounterFlushTask;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IS event handler that counts new M2M token issuances.
 *
 * <h3>What this counter captures</h3>
 * <p>Every NEW OAuth2 access token issued via the {@code client_credentials}
 * grant type is counted once per tenant per hour.  Specifically:
 * <ul>
 *   <li>Only {@code POST_ISSUE_ACCESS_TOKEN_V2} events are processed.</li>
 *   <li>Only {@code client_credentials} grant type is counted — authorization
 *       code, password, and refresh-token grants are ignored.</li>
 *   <li>Reuse of an existing valid token ({@code EXISTING_TOKEN_USED=true})
 *       does not increment the counter.</li>
 * </ul>
 * <p>Counts are accumulated in {@link GenericCounterCache} and flushed
 * to {@code IDN_USAGE_COUNT} (COUNT_TYPE='M2M_TOKEN') at a configurable
 * interval (default 3600 s). Set {@code flushIntervalSeconds} in
 * {@code deployment.toml} to override — use a small value (e.g. 10) for
 * local testing.
 */
public class M2MTokenEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(M2MTokenEventHandler.class);
    private static final String EVENT_POST_ISSUE_TOKEN    = "POST_ISSUE_ACCESS_TOKEN_V2";
    private static final String GRANT_CLIENT_CREDENTIALS  = "client_credentials";

    private final ScheduledExecutorService scheduler;
    private final CounterFlushTask flushTask;
    private final GenericCounterCache cache;
    private final AtomicBoolean schedulerStarted = new AtomicBoolean(false);

    public M2MTokenEventHandler(ScheduledExecutorService scheduler,
                                CounterFlushTask flushTask,
                                GenericCounterCache cache) {
        this.scheduler = scheduler;
        this.flushTask  = flushTask;
        this.cache      = cache;
    }

    @Override
    public String getName() { return "m2mTokenUsageHandler"; }

    @Override
    public int getPriority(MessageContext messageContext) { return 200; }

    @Override
    public void init(InitConfig configuration) throws IdentityRuntimeException {
        super.init(configuration);
        if (this.configs instanceof ModuleConfiguration) {
            Properties props = ((ModuleConfiguration) this.configs).getModuleProperties();
            if (props != null && !props.isEmpty()) applyConfig(props);
        }
        startSchedulerOnce();
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || !EVENT_POST_ISSUE_TOKEN.equals(event.getEventName())) return;

        Map<String, Object> props = event.getEventProperties();
        if (props == null || props.isEmpty()) {
            LOG.warn("[M2M] Event properties are null or empty. Skipping.");
            return;
        }

        // Skip if this is a reuse of an existing token — only new issuances count.
        Object existingTokenUsed = props.get(IdentityEventConstants.EventProperty.EXISTING_TOKEN_USED);
        if (Boolean.parseBoolean(String.valueOf(existingTokenUsed))) {
            LOG.debug("[M2M] Existing token reused. Skipping.");
            return;
        }

        // Only client_credentials grants are M2M tokens.
        Object grantType = props.get(IdentityEventConstants.EventProperty.GRANT_TYPE);
        if (!GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
            LOG.debug("[M2M] Grant type '{}' is not client_credentials. Skipping.", grantType);
            return;
        }

        String tenantDomain = (String) props.get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);
        if (tenantDomain == null || tenantDomain.isBlank()) {
            LOG.warn("[M2M] Missing tenant domain in event. Skipping.");
            return;
        }

        cache.increment(tenantDomain);
        LOG.debug("[M2M] Incremented M2M token count for tenant={}.", tenantDomain);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startSchedulerOnce() {
        if (schedulerStarted.compareAndSet(false, true)) {
            int seconds = UsageTrackingConfig.getM2mFlushIntervalSeconds();
            scheduler.scheduleAtFixedRate(flushTask, seconds, seconds, TimeUnit.SECONDS);
            LOG.info("[M2M] Flush scheduler started: interval={}s.", seconds);
        }
    }

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setNodeId(props.getProperty(UsageTrackingConfig.PROP_NODE_ID));
        String enabled = props.getProperty(UsageTrackingConfig.PROP_M2M_ENABLED);
        if (enabled != null && !enabled.isBlank()) {
            UsageTrackingConfig.setM2mEnabled(Boolean.parseBoolean(enabled));
        }
        String flushSecs = props.getProperty(UsageTrackingConfig.PROP_M2M_FLUSH_INTERVAL_SECONDS);
        if (flushSecs != null && !flushSecs.isBlank()) {
            try {
                UsageTrackingConfig.setM2mFlushIntervalSeconds(Integer.parseInt(flushSecs.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("[M2M] Invalid flushIntervalSeconds '{}'; using default {}s.",
                        flushSecs, UsageTrackingConfig.getM2mFlushIntervalSeconds());
            }
        }
        LOG.info("[M2M] Config loaded: enabled={}, flushIntervalSeconds={}s, nodeId={}.",
                UsageTrackingConfig.isM2mEnabled(),
                UsageTrackingConfig.getM2mFlushIntervalSeconds(),
                UsageTrackingConfig.getNodeId());
    }
}
