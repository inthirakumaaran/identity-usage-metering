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
import org.wso2.carbon.identity.usage.metering.common.UsageTrackingException;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled task: flushes the MAU cache to {@code IDN_MAU_COUNT} and,
 * at month-end, triggers {@link MAUMonthlyAggregationTask}.
 */
public class MAUFlushTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MAUFlushTask.class);

    private final MAUDataAccessObject dao;
    private final MAUMonthlyAggregationTask aggregationTask;
    private final AtomicReference<String> lastAggregatedMonth = new AtomicReference<>("");

    public MAUFlushTask(MAUDataAccessObject dao, MAUMonthlyAggregationTask aggregationTask) {
        this.dao             = dao;
        this.aggregationTask = aggregationTask;
    }

    @Override
    public void run() {
        LOG.debug("[MAU-Flush] Starting flush cycle.");
        try {
            flushAll();
            MAUCacheManager.getInstance()
                    .purgeExpiredEntries(UsageTrackingConfig.getMauCacheRetentionDays());

            if (isMonthEndFlushTime() && !hasAggregatedThisMonth()) {
                LOG.info("[MAU-Flush] Month-end detected. Triggering aggregation.");
                aggregationTask.run();
                lastAggregatedMonth.set(MAUCacheManager.currentMonthYear());
            }
        } catch (Exception e) {
            LOG.error("[MAU-Flush] Unexpected error during flush cycle.", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void flushAll() {
        Map<String, Map<String, Long>> byMonth =
                MAUCacheManager.getInstance().snapshotGroupedByMonth();
        if (byMonth.isEmpty()) {
            LOG.debug("[MAU-Flush] Cache empty; nothing to flush.");
            return;
        }
        for (Map.Entry<String, Map<String, Long>> entry : byMonth.entrySet()) {
            try {
                dao.batchUpsertLogins(entry.getValue(), entry.getKey());
            } catch (UsageTrackingException e) {
                LOG.error("[MAU-Flush] Flush failed for month {}.", entry.getKey(), e);
            }
        }
    }

    private static boolean isMonthEndFlushTime() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return now.getDayOfMonth() == now.toLocalDate().lengthOfMonth()
                && now.getHour() == 23;
    }

    private boolean hasAggregatedThisMonth() {
        return MAUCacheManager.currentMonthYear().equals(lastAggregatedMonth.get());
    }
}
