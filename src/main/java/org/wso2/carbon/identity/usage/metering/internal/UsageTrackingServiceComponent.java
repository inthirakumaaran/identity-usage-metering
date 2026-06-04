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
 * <h3>Components activated</h3>
 * <ul>
 *   <li><b>MAU</b> – {@link MAULoginEventHandler} / {@link MAUFlushTask}</li>
 *   <li><b>M2M Token</b> – {@link M2MTokenEventHandler} /
 *       {@link CounterFlushTask}(M2M_TOKEN)</li>
 *   <li><b>Agent Login</b> – {@link AgentLoginEventHandler} /
 *       {@link CounterFlushTask}(AGENT_LOGIN) — stub</li>
 *   <li><b>Agent CRUD</b> – {@link AgentManagementListener} — stub</li>
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
        // 7 periodic tasks: MAU flush, M2M flush, agent login flush,
        // agent provision/update/delete/status-change flushes.
        scheduler = Executors.newScheduledThreadPool(7, r -> {
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
        GenericCounterCache agentProvCache   = new GenericCounterCache();
        GenericCounterCache agentUpdCache    = new GenericCounterCache();
        GenericCounterCache agentDelCache    = new GenericCounterCache();
        GenericCounterCache agentStatusCache = new GenericCounterCache();

        List<CounterFlushTask> agentFlushTasks = Arrays.asList(
                new CounterFlushTask(CountType.AGENT_LOGIN,         agentLoginCache,  usageDao),
                new CounterFlushTask(CountType.AGENT_PROVISION,     agentProvCache,   usageDao),
                new CounterFlushTask(CountType.AGENT_UPDATE,        agentUpdCache,    usageDao),
                new CounterFlushTask(CountType.AGENT_DELETE,        agentDelCache,    usageDao),
                new CounterFlushTask(CountType.AGENT_STATUS_CHANGE, agentStatusCache, usageDao)
        );

        AgentLoginEventHandler agentLoginHandler =
                new AgentLoginEventHandler(scheduler, agentFlushTasks, agentLoginCache);

        AgentManagementListener agentMgmt = new AgentManagementListener(
                UsageTrackingConfig.getAgentUserStoreDomain(),
                agentProvCache, agentUpdCache, agentDelCache, agentStatusCache);

        registerHandler(ctx, agentLoginHandler, agentLoginHandler.getName());
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
