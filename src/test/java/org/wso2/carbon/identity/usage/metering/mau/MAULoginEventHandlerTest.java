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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MAULoginEventHandlerTest {

    private MAULoginEventHandler handler;
    private ScheduledExecutorService scheduler;
    private AutoCloseable mocks;

    @Mock private MAUFlushTask mockFlushTask;

    @BeforeEach
    void setUp() throws Exception {
        mocks     = MockitoAnnotations.openMocks(this);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        handler   = new MAULoginEventHandler(scheduler, mockFlushTask);

        Field field = MAUCacheManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        handler.shutdown();
        scheduler.shutdownNow();
        mocks.close();
    }

    @Test
    void testHandlerNameIsCorrect() {
        assertEquals("mauLoginEventHandler", handler.getName());
    }

    @Test
    void testNonAuthSuccessEventIsIgnored() throws IdentityEventException, InterruptedException {
        handler.handleEvent(buildEvent("PRE_AUTHENTICATION", "alice", "example.com"));
        TimeUnit.MILLISECONDS.sleep(100);
        assertEquals(0, MAUCacheManager.getInstance().size());
    }

    @Test
    void testNullEventIsIgnored() {
        assertDoesNotThrow(() -> handler.handleEvent(null));
    }

    @Test
    void testAuthSuccessUpdatesCache() throws IdentityEventException, InterruptedException {
        handler.handleEvent(buildEvent("AUTHENTICATION_SUCCESS", "bob@corp.com", "corp.com"));
        TimeUnit.MILLISECONDS.sleep(300);
        String key = MAUCacheManager.buildKey("bob@corp.com", "corp.com");
        assertTrue(MAUCacheManager.getInstance().snapshotCurrentMonth().containsKey(key));
    }

    @Test
    void testMissingParamsDoesNotThrow() {
        Event event = new Event("AUTHENTICATION_SUCCESS", new HashMap<>());
        assertDoesNotThrow(() -> handler.handleEvent(event));
    }

    @Test
    void testMissingUserInParamsDoesNotThrow() {
        Map<String, Object> props = new HashMap<>();
        props.put("params", new HashMap<>());
        assertDoesNotThrow(() -> handler.handleEvent(new Event("AUTHENTICATION_SUCCESS", props)));
    }

    @Test
    void testHandleEventIsNonBlocking() throws IdentityEventException {
        Event event = buildEvent("AUTHENTICATION_SUCCESS", "fast@user.com", "fast.com");
        long start = System.currentTimeMillis();
        handler.handleEvent(event);
        assertTrue(System.currentTimeMillis() - start < 100,
                "handleEvent must return within 100 ms");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Event buildEvent(String name, String userName, String tenantDomain) {
        AuthenticatedUser user = new AuthenticatedUser();
        user.setUserName(userName);
        user.setTenantDomain(tenantDomain);

        Map<String, Object> params = new HashMap<>();
        params.put("user", user);

        Map<String, Object> props = new HashMap<>();
        props.put("params", params);
        return new Event(name, props);
    }
}
