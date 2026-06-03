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

/**
 * Counts access tokens obtained by or on behalf of agent identities.
 *
 * <h3>What this counter will capture</h3>
 * <p>Each new token issuance where the token subject or requesting client is
 * identified as an agent will be counted once per tenant per hour toward
 * {@link org.wso2.carbon.identity.usage.metering.common.CountType#AGENT_TOKEN}.
 *
 * <h3>Design options under evaluation</h3>
 * <ol>
 *   <li><b>Userstore-based (Option A):</b> Check
 *       {@code AuthenticatedUser.getUserStoreDomain()} on the token subject —
 *       consistent with {@link AgentLoginEventHandler}.</li>
 *   <li><b>Client-metadata flag (Option B):</b> Check whether the OAuth2
 *       client application has a metadata attribute {@code isAgentClient=true}
 *       registered against it.</li>
 * </ol>
 *
 * <p><b>Status:</b> Pending design decision — not yet implemented.
 */
public class AgentTokenEventHandler {
    // TODO: implement once Option A / Option B design is confirmed.
}
