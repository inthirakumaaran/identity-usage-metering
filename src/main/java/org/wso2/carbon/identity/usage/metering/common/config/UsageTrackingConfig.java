/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for all usage-tracking runtime configuration.
 *
 * <p>Defaults are applied at class-load time. Each event handler's
 * {@code init()} method calls the appropriate setters once the IS framework
 * has supplied the {@code [event_handler.properties]} block from
 * {@code deployment.toml}.
 *
 * <h3>Node ID resolution order</h3>
 * <ol>
 *   <li>Explicit {@code nodeId} property in {@code deployment.toml}</li>
 *   <li>Machine hostname ({@link InetAddress#getLocalHost()})</li>
 *   <li>Random 16-char hex string (container / unknown-host fallback)</li>
 * </ol>
 */
public final class UsageTrackingConfig {

    // ── Property key names ────────────────────────────────────────────────────

    /** Shared across all handlers. */
    public static final String PROP_NODE_ID = "nodeId";

    // MAU
    public static final String PROP_MAU_FLUSH_INTERVAL      = "flushInterval";
    public static final String PROP_MAU_FLUSH_INTERVAL_UNIT = "flushIntervalUnit";
    public static final String PROP_MAU_CACHE_RETENTION     = "cacheRetentionDays";
    public static final String PROP_MAU_DB_RETENTION        = "dbRetentionDays";

    // M2M
    public static final String PROP_M2M_ENABLED             = "enabled";

    // Agent
    public static final String PROP_AGENT_ENABLED           = "enabled";
    public static final String PROP_AGENT_USERSTORE_DOMAIN  = "userStoreDomain";

    // ── Defaults ──────────────────────────────────────────────────────────────

    private static final AtomicReference<String>   nodeId              = new AtomicReference<>(resolveDefaultNodeId());

    // MAU
    private static final AtomicInteger             mauFlushInterval    = new AtomicInteger(15);
    private static final AtomicReference<TimeUnit> mauFlushIntervalUnit = new AtomicReference<>(TimeUnit.MINUTES);
    private static final AtomicInteger             mauCacheRetentionDays = new AtomicInteger(40);
    private static final AtomicInteger             mauDbRetentionDays  = new AtomicInteger(60);

    // M2M
    private static final AtomicBoolean             m2mEnabled          = new AtomicBoolean(true);

    // Agent
    private static final AtomicBoolean             agentEnabled        = new AtomicBoolean(false);
    private static final AtomicReference<String>   agentUserStoreDomain = new AtomicReference<>("AGENTS");

    private UsageTrackingConfig() {}

    // ── Getters ───────────────────────────────────────────────────────────────

    public static String   getNodeId()                { return nodeId.get(); }
    public static int      getMauFlushInterval()      { return mauFlushInterval.get(); }
    public static TimeUnit getMauFlushIntervalUnit()  { return mauFlushIntervalUnit.get(); }
    public static int      getMauCacheRetentionDays() { return mauCacheRetentionDays.get(); }
    public static int      getMauDbRetentionDays()    { return mauDbRetentionDays.get(); }
    public static boolean  isM2mEnabled()             { return m2mEnabled.get(); }
    public static boolean  isAgentEnabled()           { return agentEnabled.get(); }
    public static String   getAgentUserStoreDomain()  { return agentUserStoreDomain.get(); }

    // ── Package-private setters (called from handler init methods) ────────────

    public static void setNodeId(String v) {
        if (v != null && !v.isBlank()) nodeId.set(v);
    }

    public static void setMauFlushInterval(int v)            { mauFlushInterval.set(v); }
    public static void setMauFlushIntervalUnit(TimeUnit v)   { mauFlushIntervalUnit.set(v != null ? v : TimeUnit.MINUTES); }
    public static void setMauCacheRetentionDays(int v)       { mauCacheRetentionDays.set(v); }
    public static void setMauDbRetentionDays(int v)          { mauDbRetentionDays.set(v); }
    public static void setM2mEnabled(boolean v)              { m2mEnabled.set(v); }
    public static void setAgentEnabled(boolean v)            { agentEnabled.set(v); }
    public static void setAgentUserStoreDomain(String v)     { if (v != null && !v.isBlank()) agentUserStoreDomain.set(v); }

    // ── Node ID resolution ────────────────────────────────────────────────────

    private static String resolveDefaultNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "node-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }
}
