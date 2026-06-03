/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 */

package org.wso2.carbon.identity.usage.metering.mau;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MAUCacheManagerTest {

    @BeforeEach
    void resetSingleton() throws Exception {
        Field field = MAUCacheManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void testSingletonReturnsSameInstance() {
        assertSame(MAUCacheManager.getInstance(), MAUCacheManager.getInstance());
    }

    @Test
    void testRecordLoginUpdatesCache() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("user1", "example.com");
        assertEquals(1, cache.size());
    }

    @Test
    void testRecordLoginKeepsLatestTimestamp() throws InterruptedException {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("user1", "example.com");
        Thread.sleep(5);
        cache.recordLogin("user1", "example.com");
        assertEquals(1, cache.size(), "Same user same month → still one entry");
    }

    @Test
    void testSnapshotCurrentMonthContainsEntry() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("alice", "corp.com");
        Map<String, Long> snapshot = cache.snapshotCurrentMonth();
        assertTrue(snapshot.containsKey(MAUCacheManager.buildKey("alice", "corp.com")));
    }

    @Test
    void testSnapshotGroupedByMonthPartitionsCorrectly() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "a.com");
        cache.recordLogin("u2", "b.com");
        String month = MAUCacheManager.currentMonthYear();
        Map<String, Map<String, Long>> grouped = cache.snapshotGroupedByMonth();
        assertTrue(grouped.containsKey(month));
        assertEquals(2, grouped.get(month).size());
    }

    @Test
    void testBuildKeyFormat() {
        assertEquals("user:tenant", MAUCacheManager.buildKey("user", "tenant"));
    }

    @Test
    void testPurgeExpiredDoesNotRemoveCurrentMonth() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "a.com");
        cache.purgeExpiredEntries(1);
        assertEquals(1, cache.size(), "Current month entry should not be purged");
    }

    @Test
    void testSnapshotBothMonthsMergesEntries() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "a.com");
        cache.recordLogin("u2", "b.com");
        Map<String, Long> merged = cache.snapshotBothMonths();
        assertEquals(2, merged.size());
    }

    @Test
    void testSizeReflectsRecordedLogins() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "x.com");
        cache.recordLogin("u2", "x.com");
        assertEquals(2, cache.size());
    }
}
