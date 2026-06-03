/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.wso2.carbon.identity.usage.metering.common.util;

import org.wso2.carbon.identity.usage.metering.common.CountType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generates the PERIOD key stored in {@code IDN_USAGE_COUNT} for each
 * {@link CountType}.
 *
 * <pre>
 *   MAU             → MMYYYY        e.g. "062026"
 *   M2M_TOKEN       → YYYYMMDDHHmm  e.g. "202606021400"  (truncated to hour)
 *   AGENT_TOKEN     → YYYYMMDDHHmm  e.g. "202606021400"
 *   AGENT_PROVISION → YYYYMMDD      e.g. "20260602"
 *   AGENT_UPDATE    → YYYYMMDD
 *   AGENT_DELETE    → YYYYMMDD
 *   AGENT_LOGIN     → YYYYMMDD
 * </pre>
 */
public final class PeriodUtil {

    private static final DateTimeFormatter MONTHLY = DateTimeFormatter.ofPattern("MMyyyy");
    private static final DateTimeFormatter DAILY   = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HOURLY  = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private PeriodUtil() {}

    /** Returns the PERIOD string for the given count type at the current instant. */
    public static String currentPeriod(CountType type) {
        switch (type) {
            case MAU:
                return LocalDate.now().format(MONTHLY);
            case M2M_TOKEN:
            case AGENT_TOKEN:
                return LocalDateTime.now(ZoneOffset.UTC).format(HOURLY) + "00";
            default:
                return LocalDate.now().format(DAILY);
        }
    }

    /** Returns the monthly period string for the previous calendar month. */
    public static String previousMonthPeriod() {
        return LocalDate.now().minusMonths(1).format(MONTHLY);
    }
}
