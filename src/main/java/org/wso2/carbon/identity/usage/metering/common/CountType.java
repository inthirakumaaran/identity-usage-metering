/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common;

/**
 * All metric count types tracked by the usage-tracking extension.
 *
 * <p>The type also determines the PERIOD granularity stored in
 * {@code IDN_USAGE_COUNT}:
 * <ul>
 *   <li>MAU             → monthly  (MMYYYY,      e.g. "062026")</li>
 *   <li>M2M_TOKEN       → hourly   (YYYYMMDDHHmm, e.g. "202606021400")</li>
 *   <li>AGENT_PROVISION     → daily    (YYYYMMDD,     e.g. "20260602")</li>
 *   <li>AGENT_UPDATE       → daily</li>
 *   <li>AGENT_DELETE       → daily</li>
 *   <li>AGENT_STATUS_CHANGE → daily</li>
 *   <li>AGENT_LOGIN        → daily</li>
 *   <li>AGENT_TOKEN        → hourly</li>
 * </ul>
 */
public enum CountType {

    /** Unique human users who authenticated at least once in a calendar month. */
    MAU("MAU"),

    /**
     * New machine-to-machine access tokens issued via the {@code client_credentials}
     * OAuth2 grant type (reuse of existing tokens does not count).
     */
    M2M_TOKEN("M2M_TOKEN"),

    /** New agent identities provisioned into the agent userstore. */
    AGENT_PROVISION("AGENT_PROVISION"),

    /** Updates to agent profiles, credentials, or role assignments. */
    AGENT_UPDATE("AGENT_UPDATE"),

    /** Agent identities removed from the agent userstore. */
    AGENT_DELETE("AGENT_DELETE"),

    /**
     * Account lock or disable state change applied to an agent identity
     * (claims {@code accountLocked} or {@code accountDisabled} updated).
     */
    AGENT_STATUS_CHANGE("AGENT_STATUS_CHANGE"),

    /** Successful authentication events completed by agent identities. */
    AGENT_LOGIN("AGENT_LOGIN"),

    /**
     * Access tokens obtained by or on behalf of agent identities.
     * Tracking strategy is still under design.
     */
    AGENT_TOKEN("AGENT_TOKEN");

    private final String value;

    CountType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
