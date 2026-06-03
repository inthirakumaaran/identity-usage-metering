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
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.usage.metering.common.UsageTrackingException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DAO for the MAU-specific {@code IDN_MAU_COUNT} table.
 *
 * <p>Aggregated monthly counts are written to the shared
 * {@code IDN_USAGE_COUNT} table by
 * {@link MAUMonthlyAggregationTask} via {@link org.wso2.carbon.identity.usage.metering.common.dao.UsageDAO}.
 */
public class MAUDataAccessObject {

    private static final Logger LOG = LoggerFactory.getLogger(MAUDataAccessObject.class);

    private static final String SQL_UPSERT_TRACKING =
            "INSERT INTO IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR, LAST_LOGIN) " +
            "VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE LAST_LOGIN = GREATEST(LAST_LOGIN, VALUES(LAST_LOGIN))";

    private static final String SQL_PURGE_TRACKING =
            "DELETE FROM IDN_MAU_COUNT WHERE MONTH_YEAR = ?";

    private static final String SQL_COUNT_DISTINCT_USERS =
            "SELECT COUNT(DISTINCT USER_ID) FROM IDN_MAU_COUNT " +
            "WHERE TENANT_DOMAIN = ? AND MONTH_YEAR = ?";

    private static final String SQL_DISTINCT_TENANTS =
            "SELECT DISTINCT TENANT_DOMAIN FROM IDN_MAU_COUNT WHERE MONTH_YEAR = ?";

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Batch-upserts login entries into {@code IDN_MAU_COUNT}.
     *
     * @param entries   {@code "userId:tenantDomain" → lastLoginMillis}
     * @param monthYear MMYYYY string
     */
    public void batchUpsertLogins(Map<String, Long> entries, String monthYear)
            throws UsageTrackingException {

        if (entries == null || entries.isEmpty()) {
            LOG.debug("[MAU-DAO] Nothing to flush for month {}.", monthYear);
            return;
        }

        Connection conn = null;
        try {
            conn = IdentityDatabaseUtil.getDBConnection(false);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_TRACKING)) {
                for (Map.Entry<String, Long> entry : entries.entrySet()) {
                    String key = entry.getKey();
                    int sep = key.lastIndexOf(MAUCacheManager.KEY_SEPARATOR);
                    if (sep <= 0 || sep == key.length() - 1) {
                        LOG.warn("[MAU-DAO] Skipping malformed key: {}", key);
                        continue;
                    }
                    ps.setString(1, key.substring(0, sep));
                    ps.setString(2, key.substring(sep + 1));
                    ps.setString(3, monthYear);
                    ps.setLong(4, entry.getValue());
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                conn.commit();
                LOG.info("[MAU-DAO] Flushed {} entries to IDN_MAU_COUNT for month {}.",
                        results.length, monthYear);
            }

        } catch (SQLException e) {
            IdentityDatabaseUtil.rollbackTransaction(conn);
            throw new UsageTrackingException(
                    "Failed to batch-upsert MAU tracking entries for month " + monthYear, e);
        } finally {
            IdentityDatabaseUtil.closeConnection(conn);
        }
    }

    /**
     * Deletes {@code IDN_MAU_COUNT} rows for months older than
     * {@code maxAgeDays} from today.
     */
    public void purgeOldEntries(int maxAgeDays) throws UsageTrackingException {
        List<String> expiredMonths = buildExpiredMonthList(maxAgeDays);
        if (expiredMonths.isEmpty()) return;

        Connection conn = null;
        try {
            conn = IdentityDatabaseUtil.getDBConnection(false);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(SQL_PURGE_TRACKING)) {
                for (String month : expiredMonths) {
                    ps.setString(1, month);
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                conn.commit();
                LOG.info("[MAU-DAO] Purged rows for {} expired month(s): {}",
                        results.length, expiredMonths);
            }

        } catch (SQLException e) {
            IdentityDatabaseUtil.rollbackTransaction(conn);
            throw new UsageTrackingException("Failed to purge old MAU tracking entries.", e);
        } finally {
            IdentityDatabaseUtil.closeConnection(conn);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Counts distinct users who logged in for the given tenant and month.
     * Used by {@link MAUMonthlyAggregationTask} to compute the MAU value
     * before writing it to {@code IDN_USAGE_COUNT}.
     */
    public int countDistinctUsers(String tenantDomain, String monthYear)
            throws UsageTrackingException {

        try (Connection conn = IdentityDatabaseUtil.getDBConnection(true);
             PreparedStatement ps = conn.prepareStatement(SQL_COUNT_DISTINCT_USERS)) {

            ps.setString(1, tenantDomain);
            ps.setString(2, monthYear);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to count distinct MAU users for tenant " + tenantDomain, e);
        }
    }

    /** Returns all tenant domains that have login data for the given month. */
    public List<String> getDistinctTenants(String monthYear) throws UsageTrackingException {
        List<String> tenants = new ArrayList<>();
        try (Connection conn = IdentityDatabaseUtil.getDBConnection(true);
             PreparedStatement ps = conn.prepareStatement(SQL_DISTINCT_TENANTS)) {

            ps.setString(1, monthYear);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tenants.add(rs.getString(1));
            }

        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to query distinct tenants for month " + monthYear, e);
        }
        return tenants;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> buildExpiredMonthList(int maxAgeDays) {
        List<String> result = new ArrayList<>();
        LocalDate today     = LocalDate.now();
        LocalDate threshold = today.minusDays(maxAgeDays);
        for (int i = 1; i <= 24; i++) {
            LocalDate candidate = today.minusMonths(i).with(TemporalAdjusters.firstDayOfMonth());
            if (candidate.isBefore(threshold) || candidate.isEqual(threshold)) {
                result.add(String.format("%02d%04d",
                        candidate.getMonthValue(), candidate.getYear()));
            }
        }
        return result;
    }
}
