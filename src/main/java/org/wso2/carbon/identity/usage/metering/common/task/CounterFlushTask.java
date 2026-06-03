/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.UsageTrackingException;
import org.wso2.carbon.identity.usage.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.identity.usage.metering.common.dao.UsageDAO;
import org.wso2.carbon.identity.usage.metering.common.util.PeriodUtil;

import java.util.Map;

/**
 * Generic periodic flush task that drains a {@link GenericCounterCache} and
 * writes the accumulated counts to {@code IDN_USAGE_COUNT} via {@link UsageDAO}.
 *
 * <p>Used by both the M2M token handler and all agent action handlers.
 * Each instance is bound to a single {@link CountType}.
 */
public class CounterFlushTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(CounterFlushTask.class);

    private final CountType countType;
    private final GenericCounterCache cache;
    private final UsageDAO usageDAO;

    public CounterFlushTask(CountType countType, GenericCounterCache cache, UsageDAO usageDAO) {
        this.countType = countType;
        this.cache     = cache;
        this.usageDAO  = usageDAO;
    }

    @Override
    public void run() {
        Map<String, Long> snapshot = cache.snapshotAndReset();
        if (snapshot.isEmpty()) {
            LOG.debug("[{}] Nothing to flush.", countType.getValue());
            return;
        }

        String period = PeriodUtil.currentPeriod(countType);
        try {
            usageDAO.upsertCounts(countType, snapshot, period);
            LOG.info("[{}] Flushed {} tenant(s) for period {}.",
                    countType.getValue(), snapshot.size(), period);
        } catch (UsageTrackingException e) {
            LOG.error("[{}] Flush failed for period {}.", countType.getValue(), period, e);
        }
    }
}
