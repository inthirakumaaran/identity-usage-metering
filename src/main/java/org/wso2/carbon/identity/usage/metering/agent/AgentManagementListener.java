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

import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;

/**
 * Counts CRUD operations performed on agent identities in the agent userstore.
 *
 * <h3>What this counter captures</h3>
 * <ul>
 *   <li>{@link CountType#AGENT_PROVISION} – new agent identity created
 *       ({@code doPreAddUser})</li>
 *   <li>{@link CountType#AGENT_UPDATE} – agent profile, credentials, or role
 *       assignment modified ({@code doPreUpdateCredential},
 *       {@code doPreSetUserClaimValues}, {@code doPreUpdateRoleListOfUser})</li>
 *   <li>{@link CountType#AGENT_DELETE} – agent identity removed
 *       ({@code doPreDeleteUser})</li>
 * </ul>
 * <p>Each operation increments the appropriate {@link GenericCounterCache}
 * keyed by {@code tenantDomain}. The {@code CounterFlushTask} flushes counts
 * daily to {@code IDN_USAGE_COUNT}.
 *
 * <p><b>Status:</b> Stub pending full implementation.
 *
 * <p><b>Dependency required:</b> This listener will implement
 * {@code org.wso2.carbon.user.core.listener.UserOperationEventListener}
 * from {@code org.wso2.carbon:org.wso2.carbon.user.core}.
 * Add as a provided Maven dependency before implementing.
 *
 * <p><b>Registration:</b> Must be registered as an OSGi service of type
 * {@code UserOperationEventListener} in
 * {@link org.wso2.carbon.identity.usage.metering.internal.UsageTrackingServiceComponent}.
 */
public class AgentManagementListener {

    // TODO: implement UserOperationEventListener from org.wso2.carbon.user.core
    //
    // Steps:
    // 1. Add dependency: org.wso2.carbon:org.wso2.carbon.user.core (provided)
    // 2. Add Import-Package: org.wso2.carbon.user.core.*;version="[4.0,5)"
    // 3. Implement UserOperationEventListener interface
    // 4. In doPreAddUser:   check userStoreDomain, cache.increment(AGENT_PROVISION)
    // 5. In doPreDeleteUser: check userStoreDomain, cache.increment(AGENT_DELETE)
    // 6. In doPreUpdateCredential / doPreSetUserClaimValues / doPreUpdateRoleListOfUser:
    //      check userStoreDomain, cache.increment(AGENT_UPDATE)
    // 7. Register in UsageTrackingServiceComponent.activate() as OSGi service

    private final GenericCounterCache provisionCache;
    private final GenericCounterCache updateCache;
    private final GenericCounterCache deleteCache;

    public AgentManagementListener(GenericCounterCache provisionCache,
                                   GenericCounterCache updateCache,
                                   GenericCounterCache deleteCache) {
        this.provisionCache = provisionCache;
        this.updateCache    = updateCache;
        this.deleteCache    = deleteCache;
    }
}
