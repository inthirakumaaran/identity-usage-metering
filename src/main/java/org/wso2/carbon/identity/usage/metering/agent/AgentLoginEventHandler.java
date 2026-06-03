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

import java.util.Map;
import java.util.Properties;

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
 * <p><b>Status:</b> Stub — flush scheduling wiring is pending in
 * {@link org.wso2.carbon.identity.usage.metering.internal.UsageTrackingServiceComponent}.
 */
public class AgentLoginEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoginEventHandler.class);
    private static final String EVENT_AUTHENTICATION_SUCCESS = "AUTHENTICATION_SUCCESS";

    private final GenericCounterCache cache;

    public AgentLoginEventHandler(GenericCounterCache cache) {
        this.cache = cache;
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
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (!UsageTrackingConfig.isAgentEnabled()) return;
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

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setNodeId(props.getProperty(UsageTrackingConfig.PROP_NODE_ID));
        String enabled = props.getProperty(UsageTrackingConfig.PROP_AGENT_ENABLED);
        if (enabled != null && !enabled.isBlank())
            UsageTrackingConfig.setAgentEnabled(Boolean.parseBoolean(enabled));
        UsageTrackingConfig.setAgentUserStoreDomain(
                props.getProperty(UsageTrackingConfig.PROP_AGENT_USERSTORE_DOMAIN));
        LOG.info("[Agent] Config loaded: enabled={}, userStoreDomain={}, nodeId={}.",
                UsageTrackingConfig.isAgentEnabled(),
                UsageTrackingConfig.getAgentUserStoreDomain(),
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
