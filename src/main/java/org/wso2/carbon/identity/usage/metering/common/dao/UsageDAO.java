/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.usage.metering.common.CountType;
import org.wso2.carbon.identity.usage.metering.common.UsageTrackingException;
import org.wso2.carbon.identity.usage.metering.common.config.UsageTrackingConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Data Access Object for the unified {@code IDN_USAGE_COUNT} table.
 *
 * <p>All write operations use {@code ON DUPLICATE KEY UPDATE COUNT = COUNT + VALUES(COUNT)}
 * so counts from multiple cluster nodes accumulate naturally without
 * overwriting each other.
 */
public class UsageDAO {

    private static final Logger LOG = LoggerFactory.getLogger(UsageDAO.class);

    private static final String SQL_UPSERT_COUNT =
            "INSERT INTO IDN_USAGE_COUNT (NODE_ID, TENANT_DOMAIN, COUNT_TYPE, COUNT, COUNT_PERIOD) " +
            "VALUES (?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE COUNT = COUNT + VALUES(COUNT)";

    private static final String SQL_GET_NODE_COUNT =
            "SELECT COUNT FROM IDN_USAGE_COUNT " +
            "WHERE NODE_ID = ? AND TENANT_DOMAIN = ? AND COUNT_TYPE = ? AND COUNT_PERIOD = ?";

    private static final String SQL_GET_TOTAL_COUNT =
            "SELECT COALESCE(SUM(COUNT), 0) FROM IDN_USAGE_COUNT " +
            "WHERE TENANT_DOMAIN = ? AND COUNT_TYPE = ? AND COUNT_PERIOD = ?";

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Increments (or inserts) the count for each tenant in the supplied map.
     * All rows are written in a single batch under one transaction.
     *
     * @param type         metric type
     * @param tenantCounts {@code tenantDomain → delta} to add to the stored count
     * @param period       COUNT_PERIOD key (format depends on {@code type})
     * @throws UsageTrackingException on any SQL failure
     */
    public void upsertCounts(CountType type, Map<String, Long> tenantCounts, String period)
            throws UsageTrackingException {

        if (tenantCounts == null || tenantCounts.isEmpty()) {
            return;
        }

        String nodeId = UsageTrackingConfig.getNodeId();
        Connection conn = null;
        try {
            conn = IdentityDatabaseUtil.getDBConnection(false);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_COUNT)) {
                for (Map.Entry<String, Long> entry : tenantCounts.entrySet()) {
                    ps.setString(1, nodeId);
                    ps.setString(2, entry.getKey());
                    ps.setString(3, type.getValue());
                    ps.setLong(4, entry.getValue());
                    ps.setString(5, period);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            LOG.debug("[UsageDAO] Upserted {} {} counts for period {}.",
                    tenantCounts.size(), type.getValue(), period);

        } catch (SQLException e) {
            IdentityDatabaseUtil.rollbackTransaction(conn);
            throw new UsageTrackingException(
                    "Failed to upsert " + type.getValue() + " counts for period " + period, e);
        } finally {
            IdentityDatabaseUtil.closeConnection(conn);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the count recorded by THIS node for the given tenant/type/period.
     *
     * @return stored count, or {@code 0} if no row exists
     */
    public long getNodeCount(CountType type, String tenantDomain, String period)
            throws UsageTrackingException {

        try (Connection conn = IdentityDatabaseUtil.getDBConnection(true);
             PreparedStatement ps = conn.prepareStatement(SQL_GET_NODE_COUNT)) {

            ps.setString(1, UsageTrackingConfig.getNodeId());
            ps.setString(2, tenantDomain);
            ps.setString(3, type.getValue());
            ps.setString(4, period);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }

        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to read " + type.getValue() + " count for tenant " + tenantDomain, e);
        }
    }

    /**
     * Returns the total count across ALL cluster nodes for the given
     * tenant/type/period.
     *
     * @return sum across nodes, or {@code 0} if no rows exist
     */
    public long getTotalCount(CountType type, String tenantDomain, String period)
            throws UsageTrackingException {

        try (Connection conn = IdentityDatabaseUtil.getDBConnection(true);
             PreparedStatement ps = conn.prepareStatement(SQL_GET_TOTAL_COUNT)) {

            ps.setString(1, tenantDomain);
            ps.setString(2, type.getValue());
            ps.setString(3, period);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }

        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to read total " + type.getValue() + " count for tenant " + tenantDomain, e);
        }
    }
}
