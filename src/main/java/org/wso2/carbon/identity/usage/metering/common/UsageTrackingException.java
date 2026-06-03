/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common;

/** Checked exception thrown by all usage-tracking data access operations. */
public class UsageTrackingException extends Exception {

    public UsageTrackingException(String message) {
        super(message);
    }

    public UsageTrackingException(String message, Throwable cause) {
        super(message, cause);
    }
}
