/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.mau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Singleton in-memory cache for MAU login events.
 *
 * <p>Cache key: {@code userId:tenantDomain|MMYYYY} → last-login epoch ms.
 * The month suffix is embedded in the key so one map covers multiple months
 * without any parity logic. {@link #snapshotGroupedByMonth()} partitions
 * entries by month for the flush task.
 */
public final class MAUCacheManager {

    private static final Logger LOG = LoggerFactory.getLogger(MAUCacheManager.class);

    /** Separator between userId and tenantDomain. */
    public static final String KEY_SEPARATOR   = ":";
    /** Separator between the user key and the MMYYYY month tag. */
    public static final String MONTH_SEPARATOR = "|";

    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    private static volatile MAUCacheManager instance;

    private MAUCacheManager() {}

    public static MAUCacheManager getInstance() {
        if (instance == null) {
            synchronized (MAUCacheManager.class) {
                if (instance == null) {
                    instance = new MAUCacheManager();
                }
            }
        }
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Records a login; only the latest timestamp per user per month is kept. */
    public void recordLogin(String userId, String tenantDomain) {
        String key = cacheKey(userId, tenantDomain, currentMonthYear());
        cache.merge(key, System.currentTimeMillis(), Math::max);
        LOG.debug("[MAU] Recorded login: key={}", key);
    }

    /** Snapshot of the current month's entries (month suffix stripped). */
    public Map<String, Long> snapshotCurrentMonth() {
        String suffix = MONTH_SEPARATOR + currentMonthYear();
        return cache.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                e -> e.getKey().substring(0, e.getKey().lastIndexOf(MONTH_SEPARATOR)),
                                Map.Entry::getValue),
                        Collections::unmodifiableMap));
    }

    /** Snapshot merged across all cached months (latest ts wins). */
    public Map<String, Long> snapshotBothMonths() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, Long> e : cache.entrySet()) {
            result.merge(stripMonth(e.getKey()), e.getValue(), Math::max);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Snapshot partitioned by MMYYYY month — month suffix stripped inside each
     * partition. Used by {@link MAUFlushTask} to route entries to the right DB
     * partition.
     */
    public Map<String, Map<String, Long>> snapshotGroupedByMonth() {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Map.Entry<String, Long> e : cache.entrySet()) {
            String fullKey = e.getKey();
            int sep = fullKey.lastIndexOf(MONTH_SEPARATOR);
            if (sep <= 0) {
                LOG.warn("[MAU] Malformed cache key: {}", fullKey);
                continue;
            }
            String month  = fullKey.substring(sep + 1);
            String daoKey = fullKey.substring(0, sep);
            result.computeIfAbsent(month, k -> new HashMap<>()).put(daoKey, e.getValue());
        }
        return result;
    }

    /** Evicts entries whose month started more than {@code maxAgeDays} days ago. */
    public void purgeExpiredEntries(int maxAgeDays) {
        Set<String> expired = buildExpiredMonthSet(maxAgeDays);
        if (expired.isEmpty()) return;
        int[] removed = {0};
        cache.entrySet().removeIf(e -> {
            int sep = e.getKey().lastIndexOf(MONTH_SEPARATOR);
            if (sep <= 0) return false;
            if (expired.contains(e.getKey().substring(sep + 1))) { removed[0]++; return true; }
            return false;
        });
        if (removed[0] > 0) LOG.info("[MAU] Purged {} expired cache entries.", removed[0]);
    }

    /** Total entries across all months (for monitoring). */
    public int size() { return cache.size(); }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Builds the DAO-facing key (no month suffix): {@code userId:tenantDomain}. */
    public static String buildKey(String userId, String tenantDomain) {
        return userId + KEY_SEPARATOR + tenantDomain;
    }

    /** Current month in MMYYYY format, e.g. "062026". */
    public static String currentMonthYear() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("MMyyyy"));
    }

    /** Previous calendar month in MMYYYY format. */
    public static String previousMonthYear() {
        return LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("MMyyyy"));
    }

    private static String cacheKey(String userId, String tenantDomain, String monthYear) {
        return buildKey(userId, tenantDomain) + MONTH_SEPARATOR + monthYear;
    }

    private static String stripMonth(String fullKey) {
        int sep = fullKey.lastIndexOf(MONTH_SEPARATOR);
        return (sep > 0) ? fullKey.substring(0, sep) : fullKey;
    }

    private static Set<String> buildExpiredMonthSet(int maxAgeDays) {
        LocalDate today     = LocalDate.now();
        LocalDate threshold = today.minusDays(maxAgeDays);
        Set<String> expired = ConcurrentHashMap.newKeySet();
        for (int i = 1; i <= 24; i++) {
            LocalDate candidate = today.minusMonths(i).withDayOfMonth(1);
            if (!candidate.isAfter(threshold)) {
                expired.add(String.format("%02d%04d",
                        candidate.getMonthValue(), candidate.getYear()));
            }
        }
        return expired;
    }
}
