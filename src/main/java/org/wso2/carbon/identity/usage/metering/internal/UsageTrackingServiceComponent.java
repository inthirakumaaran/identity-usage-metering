/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.internal;

import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.event.services.IdentityEventService;
import org.wso2.carbon.user.core.listener.UserOperationEventListener;
import org.wso2.carbon.identity.usage.metering.agent.AgentLoginEventHandler;
import org.wso2.carbon.identity.usage.metering.agent.AgentManagementListener;
import org.wso2.carbon.identity.usage.metering.agent.AgentTokenEventHandler;
import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;
import org.wso2.carbon.identity.usage.metering.common.dao.UsageDAO;
import org.wso2.carbon.identity.usage.metering.common.task.CounterFlushTask;
import org.wso2.carbon.identity.usage.metering.m2m.M2MTokenEventHandler;
import org.wso2.carbon.identity.usage.metering.mau.MAUDataAccessObject;
import org.wso2.carbon.identity.usage.metering.mau.MAUFlushTask;
import org.wso2.carbon.identity.usage.metering.mau.MAULoginEventHandler;
import org.wso2.carbon.identity.usage.metering.mau.MAUMonthlyAggregationTask;

import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OSGi DS component that wires and activates all usage-tracking handlers.
 *
 * <p>Deploy the bundle by copying
 * {@code org.wso2.carbon.identity.usage.metering-1.0.0.jar} to
 * {@code <IS_HOME>/repository/components/dropins/} and restarting IS.
 * All handlers are registered automatically on bundle activation.
 *
 * <h2>Handlers and deployment.toml configuration</h2>
 *
 * <h3>1. MAU – Monthly Active Users</h3>
 * <p>Counts distinct human users who authenticate at least once per calendar month.
 * Event: {@code AUTHENTICATION_SUCCESS}.
 * <pre>
 * [[event_handler]]
 * name          = "mauUsageHandler"
 * subscriptions = ["AUTHENTICATION_SUCCESS"]
 *
 * [event_handler.properties]
 * nodeId              = ""   # leave empty to auto-detect (sha256 of ip:portOffset)
 * flushInterval       = 15   # how often to flush the in-memory cache to DB
 * flushIntervalUnit   = "MINUTES"   # SECONDS | MINUTES | HOURS
 * cacheRetentionDays  = 40   # how long to keep per-user rows in IDN_MAU_COUNT
 * dbRetentionDays     = 60   # how long to keep aggregated rows in IDN_USAGE_COUNT
 * </pre>
 *
 * <h3>2. M2M Token – Machine-to-Machine token issuances</h3>
 * <p>Counts new OAuth2 access tokens issued via the {@code client_credentials}
 * grant. Reuse of existing valid tokens is not counted.
 * Event: {@code POST_ISSUE_ACCESS_TOKEN_V2}.
 * <pre>
 * [[event_handler]]
 * name          = "m2mTokenUsageHandler"
 * subscriptions = ["POST_ISSUE_ACCESS_TOKEN_V2"]
 *
 * [event_handler.properties]
 * nodeId                = ""     # leave empty to auto-detect
 * flushIntervalSeconds  = 3600   # flush interval in seconds (default 1 hour)
 * </pre>
 *
 * <h3>3. Agent Login – Successful logins by agent identities</h3>
 * <p>Counts every {@code AUTHENTICATION_SUCCESS} event where the authenticated
 * user resides in the configured agent userstore.  Also controls the flush
 * interval for ALL other agent counters (token, provision, update, delete,
 * status-change) via the same properties block.
 * <pre>
 * [[event_handler]]
 * name          = "agentLoginUsageHandler"
 * subscriptions = ["AUTHENTICATION_SUCCESS"]
 *
 * [event_handler.properties]
 * nodeId                = ""       # leave empty to auto-detect
 * userStoreDomain       = "AGENT"  # userstore domain where agent identities reside
 * flushIntervalSeconds  = 3600     # flush interval for ALL agent counters
 * </pre>
 *
 * <h3>4. Agent Token – Tokens obtained on behalf of agent identities</h3>
 * <p>Counts {@code POST_ISSUE_ACCESS_TOKEN_V2} events where the grant type is
 * {@code authorization_code} AND the event property {@code ACTOR_TOKEN_PRESENT}
 * is {@code true} (token-exchange / impersonation flows).  Flush is shared with
 * the agent login scheduler above.
 * <pre>
 * [[event_handler]]
 * name          = "agentTokenUsageHandler"
 * subscriptions = ["POST_ISSUE_ACCESS_TOKEN_V2"]
 * </pre>
 * <p><em>No separate properties block needed</em> — the flush interval is taken
 * from {@code agentLoginUsageHandler.flushIntervalSeconds}.
 *
 * <h3>5. Agent Management – CRUD and status changes on agent identities</h3>
 * <p>Registered as an OSGi {@code UserOperationEventListener}; no
 * {@code [[event_handler]]} block is required.  It activates automatically
 * alongside the bundle and counts the following operations on users whose
 * userstore domain matches {@code agentLoginUsageHandler.userStoreDomain}:
 * <ul>
 *   <li>{@code AGENT_PROVISION} – new agent identity created.</li>
 *   <li>{@code AGENT_DELETE} – agent identity removed.</li>
 *   <li>{@code AGENT_UPDATE} – credential changed (self or admin reset).</li>
 *   <li>{@code AGENT_STATUS_CHANGE} – account locked or disabled claim updated.</li>
 * </ul>
 *
 * <h2>Where counts are stored</h2>
 * <ul>
 *   <li>{@code IDN_MAU_COUNT} – per-user login rows used for distinct-user
 *       counting (MAU only).</li>
 *   <li>{@code IDN_USAGE_COUNT} – aggregated counts for all metrics, keyed by
 *       {@code (NODE_ID, TENANT_DOMAIN, COUNT_TYPE, COUNT_PERIOD)}.
 *       Multi-node writes use {@code ON DUPLICATE KEY UPDATE COUNT = COUNT + VALUES(COUNT)}
 *       so counts accumulate safely across nodes without overwriting each other.</li>
 * </ul>
 */
public class UsageTrackingServiceComponent {

    private static final Logger LOG = LoggerFactory.getLogger(UsageTrackingServiceComponent.class);

    private ScheduledExecutorService scheduler;
    private MAULoginEventHandler mauHandler;

    // ── OSGi lifecycle ────────────────────────────────────────────────────────

    protected void activate(ComponentContext ctx) {
        LOG.info("[Usage] Activating Usage Tracking Service Component.");

        // Shared scheduler — one thread per registered periodic task.
        // 8 periodic tasks: MAU flush, M2M flush, agent login/token flush,
        // agent provision/update/delete/status-change flushes.
        scheduler = Executors.newScheduledThreadPool(8, r -> {
            Thread t = new Thread(r, "usage-tracking-scheduler");
            t.setDaemon(true);
            return t;
        });

        UsageDAO usageDao = new UsageDAO();

        // ── MAU pipeline ──────────────────────────────────────────────────────
        MAUDataAccessObject mauDao     = new MAUDataAccessObject();
        MAUMonthlyAggregationTask agg  = new MAUMonthlyAggregationTask(mauDao, usageDao);
        MAUFlushTask mauFlush          = new MAUFlushTask(mauDao, agg);
        mauHandler = new MAULoginEventHandler(scheduler, mauFlush);
        // MAU flush scheduler is started inside MAULoginEventHandler.init()
        // after deployment.toml values are available.

        registerHandler(ctx, mauHandler, mauHandler.getName());
        LOG.info("[Usage] MAU handler registered.");

        // ── M2M Token pipeline ────────────────────────────────────────────────
        // Flush scheduler is started inside M2MTokenEventHandler.init() after
        // deployment.toml properties are read — so flushIntervalSeconds is honoured.
        GenericCounterCache m2mCache = new GenericCounterCache();
        CounterFlushTask m2mFlush   = new CounterFlushTask(CountType.M2M_TOKEN, m2mCache, usageDao);
        M2MTokenEventHandler m2mHandler = new M2MTokenEventHandler(scheduler, m2mFlush, m2mCache);

        registerHandler(ctx, m2mHandler, m2mHandler.getName());
        LOG.info("[Usage] M2M Token handler registered (flush interval from deployment.toml).");

        // ── Agent pipelines ───────────────────────────────────────────────────
        // All agent flush tasks are started by AgentLoginEventHandler.init() using
        // the configured flushIntervalSeconds (default 3600s).
        // A single deployment.toml block controls the interval for ALL agent counters.
        GenericCounterCache agentLoginCache  = new GenericCounterCache();
        GenericCounterCache agentTokenCache  = new GenericCounterCache();
        GenericCounterCache agentProvCache   = new GenericCounterCache();
        GenericCounterCache agentUpdCache    = new GenericCounterCache();
        GenericCounterCache agentDelCache    = new GenericCounterCache();
        GenericCounterCache agentStatusCache = new GenericCounterCache();

        // All 6 agent flush tasks are scheduled by AgentLoginEventHandler.init()
        // using the single flushIntervalSeconds from deployment.toml.
        List<CounterFlushTask> agentFlushTasks = Arrays.asList(
                new CounterFlushTask(CountType.AGENT_LOGIN,         agentLoginCache,  usageDao),
                new CounterFlushTask(CountType.AGENT_TOKEN,         agentTokenCache,  usageDao),
                new CounterFlushTask(CountType.AGENT_PROVISION,     agentProvCache,   usageDao),
                new CounterFlushTask(CountType.AGENT_UPDATE,        agentUpdCache,    usageDao),
                new CounterFlushTask(CountType.AGENT_DELETE,        agentDelCache,    usageDao),
                new CounterFlushTask(CountType.AGENT_STATUS_CHANGE, agentStatusCache, usageDao)
        );

        AgentLoginEventHandler agentLoginHandler =
                new AgentLoginEventHandler(scheduler, agentFlushTasks, agentLoginCache);

        AgentTokenEventHandler agentTokenHandler = new AgentTokenEventHandler(agentTokenCache);

        AgentManagementListener agentMgmt = new AgentManagementListener(
                UsageTrackingConfig.getAgentUserStoreDomain(),
                agentProvCache, agentUpdCache, agentDelCache, agentStatusCache);

        registerHandler(ctx, agentLoginHandler, agentLoginHandler.getName());
        registerHandler(ctx, agentTokenHandler, agentTokenHandler.getName());
        ctx.getBundleContext().registerService(
                UserOperationEventListener.class.getName(), agentMgmt, null);

        LOG.info("[Usage] Agent handlers registered (flush interval from deployment.toml).");
        LOG.info("[Usage] Usage Tracking Service Component activated.");
    }

    protected void deactivate(ComponentContext ctx) {
        LOG.info("[Usage] Deactivating Usage Tracking Service Component.");
        if (mauHandler != null) mauHandler.shutdown();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warn("[Usage] Scheduler did not terminate in 30 s; forcing.");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("[Usage] Usage Tracking Service Component deactivated.");
    }

    // ── OSGi service reference ────────────────────────────────────────────────

    protected void setIdentityEventService(IdentityEventService identityEventService) {
        LOG.debug("[Usage] IdentityEventService bound.");
    }

    protected void unsetIdentityEventService(IdentityEventService identityEventService) {
        LOG.debug("[Usage] IdentityEventService unbound.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void registerHandler(ComponentContext ctx,
                                        AbstractEventHandler handler,
                                        String name) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("name", name);
        ctx.getBundleContext().registerService(
                AbstractEventHandler.class.getName(), handler, props);
    }
}
