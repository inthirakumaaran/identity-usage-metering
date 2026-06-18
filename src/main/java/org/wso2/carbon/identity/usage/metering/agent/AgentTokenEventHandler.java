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
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;

import java.util.Map;
import java.util.Properties;

/**
 * Counts access tokens issued to agent identities via the authorization-code
 * grant flow.
 *
 * <h3>What this counter captures</h3>
 * <p>A {@code POST_ISSUE_ACCESS_TOKEN_V2} event is counted toward
 * {@code AGENT_TOKEN} when both of the following are true:
 * <ul>
 *   <li>The OAuth2 grant type is {@code authorization_code}.</li>
 *   <li>The event property {@code ACTOR_TOKEN_PRESENT} is {@code true},
 *       indicating that the token was obtained on behalf of an agent
 *       (token-exchange / impersonation flow).</li>
 * </ul>
 * <p>Counts are accumulated in {@link GenericCounterCache} and flushed hourly
 * to {@code IDN_USAGE_COUNT} (COUNT_TYPE='AGENT_TOKEN') together with all
 * other agent counters.  The flush interval is controlled by the shared
 * {@code flushIntervalSeconds} property in the {@code agentLoginUsageHandler}
 * block of {@code deployment.toml}.
 */
public class AgentTokenEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTokenEventHandler.class);

    private static final String EVENT_POST_ISSUE_TOKEN   = "POST_ISSUE_ACCESS_TOKEN_V2";
    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";

    /**
     * Event property set by the IS token endpoint when actor-token exchange is
     * in progress (token-exchange / impersonation flows).
     */
    private static final String PROP_ACTOR_TOKEN_PRESENT = "ACTOR_TOKEN_PRESENT";

    private final GenericCounterCache cache;

    public AgentTokenEventHandler(GenericCounterCache cache) {
        this.cache = cache;
    }

    @Override
    public String getName() { return "agentTokenUsageHandler"; }

    @Override
    public int getPriority(MessageContext messageContext) { return 200; }

    @Override
    public void init(InitConfig configuration) throws IdentityRuntimeException {
        super.init(configuration);
        if (this.configs instanceof ModuleConfiguration) {
            Properties props = ((ModuleConfiguration) this.configs).getModuleProperties();
            if (props != null && !props.isEmpty()) {
                LOG.info("[Agent-Token] Handler initialised.");
            }
        }
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || !EVENT_POST_ISSUE_TOKEN.equals(event.getEventName())) return;

        Map<String, Object> props = event.getEventProperties();
        if (props == null || props.isEmpty()) return;

        // Require authorization_code grant — client_credentials are counted as M2M.
        Object grantType = props.get(IdentityEventConstants.EventProperty.GRANT_TYPE);
        if (!GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            LOG.debug("[Agent-Token] Grant type '{}' is not authorization_code. Skipping.", grantType);
            return;
        }

        // Require ACTOR_TOKEN_PRESENT == true — distinguishes agent token-exchange
        // from regular authorization-code flows.
        Object actorTokenPresent = props.get(PROP_ACTOR_TOKEN_PRESENT);
        if (!Boolean.parseBoolean(String.valueOf(actorTokenPresent))) {
            LOG.debug("[Agent-Token] ACTOR_TOKEN_PRESENT is not true. Skipping.");
            return;
        }

        String tenantDomain = (String) props.get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);
        if (tenantDomain == null || tenantDomain.isBlank()) {
            LOG.warn("[Agent-Token] Missing tenant domain in event. Skipping.");
            return;
        }

        cache.increment(tenantDomain);
        LOG.debug("[Agent-Token] Incremented AGENT_TOKEN count for tenant={}.", tenantDomain);
    }
}
