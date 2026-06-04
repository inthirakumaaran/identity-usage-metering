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
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserOperationEventListener;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.util.Map;

/**
 * Counts administrative CRUD and status-change operations on agent identities
 * managed in the configured agent userstore.
 *
 * <h3>What this counter captures</h3>
 * <ul>
 *   <li><b>AGENT_PROVISION</b> – a new agent identity is created
 *       ({@link #doPostAddUser}).</li>
 *   <li><b>AGENT_DELETE</b> – an agent identity is removed
 *       ({@link #doPostDeleteUser}).</li>
 *   <li><b>AGENT_UPDATE</b> – an agent's credentials are changed, either by
 *       the agent itself ({@link #doPostUpdateCredential}) or by an admin
 *       ({@link #doPostUpdateCredentialByAdmin}).</li>
 *   <li><b>AGENT_STATUS_CHANGE</b> – an agent's account is locked or disabled
 *       ({@link #doPostSetUserClaimValues} when claims
 *       {@code accountLocked} or {@code accountDisabled} are updated).</li>
 * </ul>
 *
 * <h3>Agent identification</h3>
 * <p>Only operations on users whose userstore domain matches
 * {@link UsageTrackingConfig#getAgentUserStoreDomain()} (case-insensitive)
 * are counted. The listener is also silently skipped when
 * {@link UsageTrackingConfig#isAgentEnabled()} is {@code false}.
 *
 * <h3>How to enable</h3>
 * <ol>
 *   <li><b>Deploy the bundle</b> — copy
 *       {@code org.wso2.carbon.identity.usage.metering-1.0.0.jar} to
 *       {@code <IS_HOME>/repository/components/dropins/}.
 *       The listener is automatically registered as an OSGi
 *       {@code UserOperationEventListener} service on bundle activation;
 *       no extra wiring is required.</li>
 *
 *   <li><b>Configure {@code deployment.toml}</b> — add the agent handler
 *       block so the bundle knows which userstore holds agent identities:
 *       <pre>
 * [[event_handler]]
 * name          = "agentLoginUsageHandler"
 * subscriptions = ["AUTHENTICATION_SUCCESS"]
 *
 * [event_handler.properties]
 * enabled         = true
 * userStoreDomain = "AGENTS"   # must match the exact userstore domain name
 * nodeId          = ""         # leave empty to auto-detect from hostname
 *       </pre>
 *       The {@code userStoreDomain} value is case-insensitive and must match
 *       the domain name shown in the IS Console under
 *       <em>User Management → User Stores</em>.</li>
 *
 *   <li><b>Restart IS</b> — the listener begins counting immediately after
 *       the bundle activates and the event handler {@code init()} is called
 *       with the above properties.</li>
 * </ol>
 *
 * <h3>Registration</h3>
 * <p>Registered as an OSGi service of type {@code UserOperationEventListener}
 * by {@link org.wso2.carbon.identity.usage.metering.internal.UsageTrackingServiceComponent}.
 * Carbon's User Management framework discovers and invokes it automatically
 * with execution order {@code 9001} (after all core IS handlers).
 */
public class AgentManagementListener extends AbstractUserOperationEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(AgentManagementListener.class);

    /** Claim URI set when an account is locked. */
    private static final String CLAIM_ACCOUNT_LOCKED   = "http://wso2.org/claims/identity/accountLocked";
    /** Claim URI set when an account is administratively disabled. */
    private static final String CLAIM_ACCOUNT_DISABLED = "http://wso2.org/claims/identity/accountDisabled";

    private final String agentUserStoreDomain;
    private final GenericCounterCache provisionCache;
    private final GenericCounterCache updateCache;
    private final GenericCounterCache deleteCache;
    private final GenericCounterCache statusChangeCache;

    /**
     * @param agentUserStoreDomain the userstore domain where agent identities reside
     *                             (e.g. {@code "AGENTS"}). Injected at construction
     *                             time from {@code deployment.toml} via
     *                             {@link UsageTrackingConfig#getAgentUserStoreDomain()},
     *                             so the listener has no implicit dependency on another
     *                             handler's {@code init()} being called first.
     */
    public AgentManagementListener(String agentUserStoreDomain,
                                   GenericCounterCache provisionCache,
                                   GenericCounterCache updateCache,
                                   GenericCounterCache deleteCache,
                                   GenericCounterCache statusChangeCache) {
        this.agentUserStoreDomain = agentUserStoreDomain;
        this.provisionCache       = provisionCache;
        this.updateCache          = updateCache;
        this.deleteCache          = deleteCache;
        this.statusChangeCache    = statusChangeCache;
    }

    /** Runs after all core IS handlers (which use 10–1000) so only completed operations are counted. */
    @Override
    public int getExecutionOrderId() {
        return 9001;
    }

    // ── Agent Creation ────────────────────────────────────────────────────────

    @Override
    public boolean doPostAddUser(String userName, Object credential, String[] roleList,
                                 Map<String, String> claims, String profile,
                                 UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) return true;
        provisionCache.increment(resolveTenantDomain(userStoreManager));
        LOG.debug("[AgentMgmt] AGENT_PROVISION: user={}", userName);
        return true;
    }

    // ── Agent Deletion ────────────────────────────────────────────────────────

    @Override
    public boolean doPostDeleteUser(String userName,
                                    UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) return true;
        deleteCache.increment(resolveTenantDomain(userStoreManager));
        LOG.debug("[AgentMgmt] AGENT_DELETE: user={}", userName);
        return true;
    }

    // ── Credential Update ─────────────────────────────────────────────────────

    /** Agent changes its own credentials. */
    @Override
    public boolean doPostUpdateCredential(String userName, Object credential,
                                          UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) return true;
        updateCache.increment(resolveTenantDomain(userStoreManager));
        LOG.debug("[AgentMgmt] AGENT_UPDATE (self credential update): user={}", userName);
        return true;
    }

    /** Admin resets an agent's credentials. */
    @Override
    public boolean doPostUpdateCredentialByAdmin(String userName, Object credential,
                                                 UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) return true;
        updateCache.increment(resolveTenantDomain(userStoreManager));
        LOG.debug("[AgentMgmt] AGENT_UPDATE (admin credential reset): user={}", userName);
        return true;
    }

    // ── Account Lock / Disable State Change ───────────────────────────────────

    /**
     * Fires after any claim update. Only counted when the
     * {@code accountLocked} or {@code accountDisabled} claim is included.
     */
    @Override
    public boolean doPostSetUserClaimValues(String userName, Map<String, String> claims,
                                            String profileName,
                                            UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) return true;
        if (!hasStatusChangeClaim(claims)) return true;

        String tenantDomain = resolveTenantDomain(userStoreManager);
        statusChangeCache.increment(tenantDomain);
        LOG.debug("[AgentMgmt] AGENT_STATUS_CHANGE: user={} tenant={}", userName, tenantDomain);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAgentUserStore(UserStoreManager userStoreManager) {
        String domain = UserCoreUtil.getDomainName(userStoreManager.getRealmConfiguration());
        return agentUserStoreDomain.equalsIgnoreCase(domain);
    }

    private String resolveTenantDomain(UserStoreManager userStoreManager) {
        try {
            return IdentityTenantUtil.getTenantDomain(userStoreManager.getTenantId());
        } catch (Exception e) {
            LOG.warn("[AgentMgmt] Could not resolve tenant domain: {}", e.getMessage());
            return "unknown";
        }
    }

    private boolean hasStatusChangeClaim(Map<String, String> claims) {
        return claims != null
                && (claims.containsKey(CLAIM_ACCOUNT_LOCKED)
                || claims.containsKey(CLAIM_ACCOUNT_DISABLED));
    }
}
