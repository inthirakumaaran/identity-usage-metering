/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.agent;

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
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;
import org.wso2.carbon.identity.usage.metering.common.task.CounterFlushTask;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts successful logins by agent identities.
 *
 * <h3>What this counter captures</h3>
 * <p>Every {@code AUTHENTICATION_SUCCESS} event where the authenticated user
 * resides in the configured agent userstore is counted once per tenant per day
 * toward {@code AGENT_LOGIN}.  Unlike MAU, the same agent logging in multiple
 * times in a day increments the counter each time — this measures login
 * frequency, not distinct agents.
 *
 * <h3>How agents are identified</h3>
 * <p>The {@code AuthenticatedUser.getUserStoreDomain()} value is compared
 * (case-insensitive) against the {@code agent.userStoreDomain} configuration
 * property.
 *
 * <p>The flush interval for ALL agent counters (login + CRUD) is controlled
 * by {@code flushIntervalSeconds} in the {@code agentLoginUsageHandler}
 * properties block (default 3600 s). Set it to a small value (e.g. 10)
 * for local testing.
 */
public class AgentLoginEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoginEventHandler.class);
    private static final String EVENT_AUTHENTICATION_SUCCESS = "AUTHENTICATION_SUCCESS";

    private final ScheduledExecutorService scheduler;
    /** All agent flush tasks: login + provision + update + delete + status-change. */
    private final List<CounterFlushTask> agentFlushTasks;
    private final GenericCounterCache cache;
    private final AtomicBoolean schedulerStarted = new AtomicBoolean(false);

    public AgentLoginEventHandler(ScheduledExecutorService scheduler,
                                  List<CounterFlushTask> agentFlushTasks,
                                  GenericCounterCache cache) {
        this.scheduler        = scheduler;
        this.agentFlushTasks  = agentFlushTasks;
        this.cache            = cache;
    }

    @Override
    public String getName() { return "agentLoginUsageHandler"; }

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
        if (event == null || !EVENT_AUTHENTICATION_SUCCESS.equals(event.getEventName())) return;

        Map<String, Object> eventProps = event.getEventProperties();
        if (eventProps == null) return;

        AuthenticatedUser user = extractAuthenticatedUser(eventProps);
        if (user == null) return;

        String userStoreDomain = user.getUserStoreDomain();
        if (!UsageTrackingConfig.getAgentUserStoreDomain().equalsIgnoreCase(userStoreDomain)) {
            LOG.debug("[Agent-Login] Userstore '{}' is not the agent userstore. Skipping.",
                    userStoreDomain);
            return;
        }

        String tenantDomain = user.getTenantDomain();
        if (tenantDomain == null || tenantDomain.isBlank()) {
            LOG.warn("[Agent-Login] Missing tenant domain. Skipping.");
            return;
        }

        cache.increment(tenantDomain);
        LOG.debug("[Agent-Login] Incremented AGENT_LOGIN count for tenant={}.", tenantDomain);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startSchedulerOnce() {
        if (schedulerStarted.compareAndSet(false, true)) {
            int seconds = UsageTrackingConfig.getAgentFlushIntervalSeconds();
            for (CounterFlushTask task : agentFlushTasks) {
                scheduler.scheduleAtFixedRate(task, seconds, seconds, TimeUnit.SECONDS);
            }
            LOG.info("[Agent] Flush schedulers started: interval={}s, tasks={}.",
                    seconds, agentFlushTasks.size());
        }
    }

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setNodeId(props.getProperty(UsageTrackingConfig.PROP_NODE_ID));
        UsageTrackingConfig.setAgentUserStoreDomain(
                props.getProperty(UsageTrackingConfig.PROP_AGENT_USERSTORE_DOMAIN));
        String flushSecs = props.getProperty(UsageTrackingConfig.PROP_AGENT_FLUSH_INTERVAL_SECONDS);
        if (flushSecs != null && !flushSecs.isBlank()) {
            try {
                UsageTrackingConfig.setAgentFlushIntervalSeconds(Integer.parseInt(flushSecs.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("[Agent] Invalid flushIntervalSeconds '{}'; using default {}s.",
                        flushSecs, UsageTrackingConfig.getAgentFlushIntervalSeconds());
            }
        }
        LOG.info("[Agent] Config loaded: userStoreDomain={}, flushIntervalSeconds={}s, nodeId={}.",
                UsageTrackingConfig.getAgentUserStoreDomain(),
                UsageTrackingConfig.getAgentFlushIntervalSeconds(),
                UsageTrackingConfig.getNodeId());
    }

    private static AuthenticatedUser extractAuthenticatedUser(Map<String, Object> eventProps) {
        Object paramsObj = eventProps.get("params");
        if (paramsObj instanceof Map) {
            Object userObj = ((Map<?, ?>) paramsObj).get("user");
            if (userObj instanceof AuthenticatedUser) return (AuthenticatedUser) userObj;
        }
        return null;
    }
}
