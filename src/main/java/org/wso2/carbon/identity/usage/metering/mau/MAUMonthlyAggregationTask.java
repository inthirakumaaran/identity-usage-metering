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
import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.UsageTrackingException;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;
import org.wso2.carbon.identity.usage.metering.common.dao.UsageDAO;

import java.util.Collections;
import java.util.List;

/**
 * Month-end aggregation task for MAU.
 *
 * <p>Triggered by {@link MAUFlushTask} once per month in the last-day 23:xx
 * UTC window.  For each tenant with login data in the previous month:
 * <ol>
 *   <li>Counts distinct users in {@code IDN_MAU_COUNT}.</li>
 *   <li>Writes the count to {@code IDN_USAGE_COUNT} (COUNT_TYPE='MAU') via
 *       {@link UsageDAO}.</li>
 *   <li>Purges stale rows from {@code IDN_MAU_COUNT}.</li>
 * </ol>
 */
public class MAUMonthlyAggregationTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MAUMonthlyAggregationTask.class);

    private final MAUDataAccessObject mauDao;
    private final UsageDAO usageDao;

    public MAUMonthlyAggregationTask(MAUDataAccessObject mauDao, UsageDAO usageDao) {
        this.mauDao   = mauDao;
        this.usageDao = usageDao;
    }

    @Override
    public void run() {
        String previousMonth = MAUCacheManager.previousMonthYear();
        LOG.info("[MAU-Agg] Starting aggregation for month={}.", previousMonth);

        try {
            aggregate(previousMonth);
        } catch (Exception e) {
            LOG.error("[MAU-Agg] Aggregation failed for month={}.", previousMonth, e);
        }

        try {
            mauDao.purgeOldEntries(UsageTrackingConfig.getMauDbRetentionDays());
        } catch (UsageTrackingException e) {
            LOG.error("[MAU-Agg] Purge of old DB entries failed.", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void aggregate(String monthYear) throws UsageTrackingException {
        List<String> tenants = mauDao.getDistinctTenants(monthYear);
        LOG.info("[MAU-Agg] {} tenant(s) with activity for month={}.", tenants.size(), monthYear);

        for (String tenant : tenants) {
            try {
                int mauCount = mauDao.countDistinctUsers(tenant, monthYear);
                LOG.info("[MAU-Agg] tenant={} month={} MAU={}.", tenant, monthYear, mauCount);
                usageDao.upsertCounts(CountType.MAU,
                        Collections.singletonMap(tenant, (long) mauCount), monthYear);
            } catch (UsageTrackingException e) {
                LOG.error("[MAU-Agg] Failed to aggregate for tenant={}.", tenant, e);
            }
        }
    }
}
