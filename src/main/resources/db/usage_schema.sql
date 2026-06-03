-- ============================================================
-- Usage Tracking Metrics Schema for WSO2 Identity Server 7.2
-- Apply to the WSO2 Identity DB (WSO2IDENTITY_DB)
-- ============================================================

-- ── MAU Login Tracking ────────────────────────────────────────────────────────
-- One row per user per month.
-- Purpose: counts DISTINCT users for MAU — identity is needed here.
-- Retention: configurable (default 60 days / ~2 months), purged by MAUFlushTask.
CREATE TABLE IF NOT EXISTS IDN_MAU_COUNT (
    ID            BIGINT        NOT NULL AUTO_INCREMENT,
    USER_ID       VARCHAR(255)  NOT NULL,
    TENANT_DOMAIN VARCHAR(255)  NOT NULL,
    MONTH_YEAR    CHAR(6)       NOT NULL,   -- Format: MMYYYY  e.g. "062026"
    LAST_LOGIN    BIGINT        NOT NULL,   -- Epoch millis of most recent login this month
    PRIMARY KEY (ID),
    UNIQUE KEY UK_IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR),
    INDEX IDX_IDN_MAU_COUNT_MONTH (MONTH_YEAR)
);

-- ── Unified Usage Counter ─────────────────────────────────────────────────────
-- One row per (node, tenant, count type, time bucket).
-- In a cluster each node writes its own rows; counts accumulate across nodes
-- via ON DUPLICATE KEY UPDATE COUNT = COUNT + VALUES(COUNT).
--
-- COUNT_TYPE      │ COUNT_PERIOD format │ Example
-- ────────────────┼─────────────────────┼──────────────
-- MAU             │ MMYYYY              │ 062026
-- M2M_TOKEN       │ YYYYMMDDHHmm        │ 202606021400
-- AGENT_PROVISION │ YYYYMMDD            │ 20260602
-- AGENT_UPDATE    │ YYYYMMDD            │ 20260602
-- AGENT_DELETE    │ YYYYMMDD            │ 20260602
-- AGENT_LOGIN     │ YYYYMMDD            │ 20260602
-- AGENT_TOKEN     │ YYYYMMDDHHmm        │ 202606021400
--
-- NODE_ID resolution order: deployment.toml nodeId → hostname → random hex.
CREATE TABLE IF NOT EXISTS IDN_USAGE_COUNT (
    NODE_ID       VARCHAR(128)  NOT NULL,
    TENANT_DOMAIN VARCHAR(256)  NOT NULL,
    COUNT_TYPE    VARCHAR(50)   NOT NULL,
    COUNT         INT           NOT NULL DEFAULT 0,
    COUNT_PERIOD  VARCHAR(20)   NOT NULL,
    CREATED_TIME  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT PK_IDN_USAGE_COUNT
        PRIMARY KEY (NODE_ID, TENANT_DOMAIN, COUNT_TYPE, COUNT_PERIOD),
    INDEX IDX_IDN_USAGE_COUNT_TYPE (COUNT_TYPE, COUNT_PERIOD)
);
