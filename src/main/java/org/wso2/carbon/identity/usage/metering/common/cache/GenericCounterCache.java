/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory counter cache used by M2M token and agent action
 * handlers.
 *
 * <p>Each counter is keyed by {@code tenantDomain}. Counters are incremented
 * on every qualifying event and atomically drained by
 * {@link #snapshotAndReset()} when the periodic flush task runs.
 *
 * <p>Unlike {@link org.wso2.carbon.identity.usage.metering.mau.MAUCacheManager}, this
 * cache does not track individual user identities — it only accumulates a
 * count per tenant, making it suitable for M2M tokens and agent actions where
 * only the volume matters, not distinct-user identity.
 */
public class GenericCounterCache {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * Increments the counter for {@code tenantDomain} by one.
     *
     * @param tenantDomain tenant to credit (must not be null)
     */
    public void increment(String tenantDomain) {
        counters.computeIfAbsent(tenantDomain, k -> new AtomicLong(0L)).incrementAndGet();
    }

    /**
     * Atomically snapshots and resets all non-zero counters.
     *
     * <p>Entries that were zero at the time of the snapshot are omitted from
     * the result so the DAO never writes zero-count rows.
     *
     * @return {@code tenantDomain → count} for all tenants with count &gt; 0
     */
    public Map<String, Long> snapshotAndReset() {
        Map<String, Long> snapshot = new HashMap<>();
        counters.forEach((tenant, counter) -> {
            long value = counter.getAndSet(0L);
            if (value > 0) {
                snapshot.put(tenant, value);
            }
        });
        return snapshot;
    }

    /** Total number of distinct tenants currently tracked (for monitoring). */
    public int size() {
        return counters.size();
    }
}
