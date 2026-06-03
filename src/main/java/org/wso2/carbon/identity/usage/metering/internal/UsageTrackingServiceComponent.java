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
import org.wso2.carbon.identity.usage.metering.agent.AgentLoginEventHandler;
import org.wso2.carbon.identity.usage.metering.agent.AgentManagementListener;
import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.identity.usage.metering.common.dao.UsageDAO;
import org.wso2.carbon.identity.usage.metering.common.task.CounterFlushTask;
import org.wso2.carbon.identity.usage.metering.m2m.M2MTokenEventHandler;
import org.wso2.carbon.identity.usage.metering.mau.MAUDataAccessObject;
import org.wso2.carbon.identity.usage.metering.mau.MAUFlushTask;
import org.wso2.carbon.identity.usage.metering.mau.MAULoginEventHandler;
import org.wso2.carbon.identity.usage.metering.mau.MAUMonthlyAggregationTask;

import java.util.Dictionary;
import java.util.Hashtable;
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
        scheduler = Executors.newScheduledThreadPool(3, r -> {
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
        GenericCounterCache m2mCache   = new GenericCounterCache();
        M2MTokenEventHandler m2mHandler = new M2MTokenEventHandler(m2mCache);
        CounterFlushTask m2mFlush      = new CounterFlushTask(CountType.M2M_TOKEN, m2mCache, usageDao);
        scheduler.scheduleAtFixedRate(m2mFlush, 1, 1, TimeUnit.HOURS);

        registerHandler(ctx, m2mHandler, m2mHandler.getName());
        LOG.info("[Usage] M2M Token handler registered (hourly flush).");

        // ── Agent Login pipeline (stub) ───────────────────────────────────────
        GenericCounterCache agentLoginCache   = new GenericCounterCache();
        AgentLoginEventHandler agentLoginHandler =
                new AgentLoginEventHandler(agentLoginCache);
        CounterFlushTask agentLoginFlush =
                new CounterFlushTask(CountType.AGENT_LOGIN, agentLoginCache, usageDao);
        scheduler.scheduleAtFixedRate(agentLoginFlush, 1, 1, TimeUnit.HOURS);

        registerHandler(ctx, agentLoginHandler, agentLoginHandler.getName());
        LOG.info("[Usage] Agent Login handler registered (hourly flush).");

        // ── Agent CRUD listener (stub) ────────────────────────────────────────
        // TODO: implement AgentManagementListener as UserOperationEventListener
        // and register it as an OSGi service once the interface is wired.
        GenericCounterCache agentProvCache = new GenericCounterCache();
        GenericCounterCache agentUpdCache  = new GenericCounterCache();
        GenericCounterCache agentDelCache  = new GenericCounterCache();
        @SuppressWarnings("unused")
        AgentManagementListener agentMgmt =
                new AgentManagementListener(agentProvCache, agentUpdCache, agentDelCache);

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
