/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 */

package org.wso2.carbon.identity.usage.metering.mau;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link MAUDataAccessObject} using an in-memory H2
 * database that mimics MySQL with {@code MODE=MySQL}.
 */
class MAUDataAccessObjectTest {

    private static final String H2_URL = "jdbc:h2:mem:usage_test;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private Connection schemaConn;
    private MAUDataAccessObject dao;
    private MockedStatic<IdentityDatabaseUtil> mockUtil;

    @BeforeEach
    void setUp() throws Exception {
        schemaConn = DriverManager.getConnection(H2_URL, "sa", "");
        createSchema(schemaConn);
        dao = new MAUDataAccessObject();

        mockUtil = mockStatic(IdentityDatabaseUtil.class);
        mockUtil.when(() -> IdentityDatabaseUtil.getDBConnection(anyBoolean()))
                .thenAnswer(inv -> {
                    Connection c = DriverManager.getConnection(H2_URL, "sa", "");
                    c.setAutoCommit(false);
                    return c;
                });
        mockUtil.when(() -> IdentityDatabaseUtil.rollbackTransaction(any(Connection.class)))
                .thenAnswer(inv -> { inv.<Connection>getArgument(0).rollback(); return null; });
        mockUtil.when(() -> IdentityDatabaseUtil.closeConnection(any(Connection.class)))
                .thenAnswer(inv -> { inv.<Connection>getArgument(0).close(); return null; });
    }

    @AfterEach
    void tearDown() throws Exception {
        mockUtil.close();
        try (Statement st = schemaConn.createStatement()) { st.execute("DROP ALL OBJECTS"); }
        schemaConn.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void testBatchUpsertInsertsRows() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:alpha.com", System.currentTimeMillis());
        entries.put("u2:alpha.com", System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "062026");

        long count = queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT WHERE MONTH_YEAR='062026'");
        assertEquals(2, count);
    }

    @Test
    void testBatchUpsertUpdatesLastLogin() throws Exception {
        Map<String, Long> first = new HashMap<>();
        first.put("u1:beta.com", 1000L);
        dao.batchUpsertLogins(first, "062026");

        Map<String, Long> second = new HashMap<>();
        second.put("u1:beta.com", 9999L);
        dao.batchUpsertLogins(second, "062026");

        long ts = queryLong("SELECT LAST_LOGIN FROM IDN_MAU_COUNT " +
                "WHERE USER_ID='u1' AND TENANT_DOMAIN='beta.com'");
        assertEquals(9999L, ts, "LAST_LOGIN should be updated to the larger value");
    }

    @Test
    void testBatchUpsertEmptyMapDoesNothing() throws Exception {
        dao.batchUpsertLogins(new HashMap<>(), "062026");
        assertEquals(0, queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT"));
    }

    @Test
    void testCountDistinctUsers() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:acme.com", System.currentTimeMillis());
        entries.put("u2:acme.com", System.currentTimeMillis());
        entries.put("u1:other.com", System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "062026");

        int count = dao.countDistinctUsers("acme.com", "062026");
        assertEquals(2, count, "Should count only distinct users for acme.com");
    }

    @Test
    void testCountDistinctUsersReturnsZeroWhenNoData() throws Exception {
        assertEquals(0, dao.countDistinctUsers("nobody.com", "012000"));
    }

    @Test
    void testGetDistinctTenants() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:alpha.com", System.currentTimeMillis());
        entries.put("u1:beta.com",  System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "032026");

        List<String> tenants = dao.getDistinctTenants("032026");
        assertEquals(2, tenants.size());
        assertTrue(tenants.contains("alpha.com"));
        assertTrue(tenants.contains("beta.com"));
    }

    @Test
    void testPurgeOldEntriesRemovesExpiredRows() throws Exception {
        // Use the previous calendar month — always within the 24-month scan window
        // and always older than 1 day from today.
        String expiredMonth = MAUCacheManager.previousMonthYear();
        try (Statement st = schemaConn.createStatement()) {
            st.execute("INSERT INTO IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR, LAST_LOGIN) " +
                    "VALUES ('u1','old.com','" + expiredMonth + "',1)");
            schemaConn.commit();
        }

        dao.purgeOldEntries(1);

        assertEquals(0, queryLong(
                "SELECT COUNT(*) FROM IDN_MAU_COUNT WHERE MONTH_YEAR='" + expiredMonth + "'"),
                "Expired rows should be purged");
    }

    @Test
    void testMalformedCacheKeyIsSkipped() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("malformed-no-colon", 123L);
        entries.put("u1:good.com", 456L);
        dao.batchUpsertLogins(entries, "062026");

        assertEquals(1, queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT"),
                "Malformed key should be skipped; valid key should be inserted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void createSchema(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS IDN_MAU_COUNT (" +
                    "  ID BIGINT NOT NULL AUTO_INCREMENT," +
                    "  USER_ID VARCHAR(255) NOT NULL," +
                    "  TENANT_DOMAIN VARCHAR(255) NOT NULL," +
                    "  MONTH_YEAR CHAR(6) NOT NULL," +
                    "  LAST_LOGIN BIGINT NOT NULL," +
                    "  PRIMARY KEY (ID)," +
                    "  UNIQUE KEY UK_MAU_TRACK (USER_ID, TENANT_DOMAIN, MONTH_YEAR)" +
                    ")");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS IDN_USAGE_COUNT (" +
                    "  NODE_ID VARCHAR(128) NOT NULL," +
                    "  TENANT_DOMAIN VARCHAR(256) NOT NULL," +
                    "  COUNT_TYPE VARCHAR(50) NOT NULL," +
                    "  COUNT INT NOT NULL DEFAULT 0," +
                    "  COUNT_PERIOD VARCHAR(20) NOT NULL," +
                    "  CREATED_TIME TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  CONSTRAINT PK_IDN_USAGE_COUNT" +
                    "    PRIMARY KEY (NODE_ID, TENANT_DOMAIN, COUNT_TYPE, COUNT_PERIOD)" +
                    ")");
        }
        c.setAutoCommit(false);
    }

    private long queryLong(String sql) throws Exception {
        try (Statement st = schemaConn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
