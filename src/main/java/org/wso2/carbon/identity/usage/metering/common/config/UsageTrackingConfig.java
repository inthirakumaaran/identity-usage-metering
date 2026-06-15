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
    public static final String PROP_M2M_FLUSH_INTERVAL_SECONDS   = "flushIntervalSeconds";

    // Agent
    public static final String PROP_AGENT_USERSTORE_DOMAIN        = "userStoreDomain";
    public static final String PROP_AGENT_FLUSH_INTERVAL_SECONDS  = "flushIntervalSeconds";

    // ── Defaults ──────────────────────────────────────────────────────────────

    private static final AtomicReference<String>   nodeId              = new AtomicReference<>(resolveDefaultNodeId());

    // MAU
    private static final AtomicInteger             mauFlushInterval    = new AtomicInteger(15);
    private static final AtomicReference<TimeUnit> mauFlushIntervalUnit = new AtomicReference<>(TimeUnit.MINUTES);
    private static final AtomicInteger             mauCacheRetentionDays = new AtomicInteger(40);
    private static final AtomicInteger             mauDbRetentionDays  = new AtomicInteger(60);

    // M2M
    private static final AtomicInteger             m2mFlushIntervalSeconds  = new AtomicInteger(3600);

    // Agent
    private static final AtomicReference<String>   agentUserStoreDomain      = new AtomicReference<>("AGENT");
    private static final AtomicInteger             agentFlushIntervalSeconds = new AtomicInteger(3600);

    private UsageTrackingConfig() {}

    // ── Getters ───────────────────────────────────────────────────────────────

    public static String   getNodeId()                { return nodeId.get(); }
    public static int      getMauFlushInterval()      { return mauFlushInterval.get(); }
    public static TimeUnit getMauFlushIntervalUnit()  { return mauFlushIntervalUnit.get(); }
    public static int      getMauCacheRetentionDays() { return mauCacheRetentionDays.get(); }
    public static int      getMauDbRetentionDays()    { return mauDbRetentionDays.get(); }
    public static int      getM2mFlushIntervalSeconds()    { return m2mFlushIntervalSeconds.get(); }
    public static String   getAgentUserStoreDomain()       { return agentUserStoreDomain.get(); }
    public static int      getAgentFlushIntervalSeconds()  { return agentFlushIntervalSeconds.get(); }

    // ── Package-private setters (called from handler init methods) ────────────

    public static void setNodeId(String v) {
        if (v != null && !v.isBlank()) nodeId.set(v);
    }

    public static void setMauFlushInterval(int v)            { mauFlushInterval.set(v); }
    public static void setMauFlushIntervalUnit(TimeUnit v)   { mauFlushIntervalUnit.set(v != null ? v : TimeUnit.MINUTES); }
    public static void setMauCacheRetentionDays(int v)       { mauCacheRetentionDays.set(v); }
    public static void setMauDbRetentionDays(int v)          { mauDbRetentionDays.set(v); }
    public static void setM2mFlushIntervalSeconds(int v)        { if (v > 0) m2mFlushIntervalSeconds.set(v); }
    public static void setAgentUserStoreDomain(String v)        { if (v != null && !v.isBlank()) agentUserStoreDomain.set(v); }
    public static void setAgentFlushIntervalSeconds(int v)      { if (v > 0) agentFlushIntervalSeconds.set(v); }

    // ── Node ID resolution ────────────────────────────────────────────────────

    /**
     * Resolves the default node ID at class-load time.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit {@code nodeId} in {@code deployment.toml} (set via
     *       {@link #setNodeId(String)} from a handler's {@code init()}).</li>
     *   <li>{@code hostname_portOffset} — e.g. {@code "is-macbook_1"}.
     *       The {@code portOffset} system property is set by Carbon when starting
     *       a second IS instance locally with {@code -DportOffset=N}, making each
     *       local node produce a unique ID without any manual config.</li>
     *   <li>Random 16-char hex (container / unknown-host fallback).</li>
     * </ol>
     */
    private static String resolveDefaultNodeId() {
        try {
            String hostname   = InetAddress.getLocalHost().getHostName();
            String portOffset = System.getProperty("portOffset", "0");
            return hostname + "_" + portOffset;
        } catch (UnknownHostException e) {
            return "node-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }
}
