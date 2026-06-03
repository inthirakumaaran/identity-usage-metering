# WSO2 IS Usage Tracking Metrics

An OSGi bundle for **WSO2 Identity Server 7.2** that tracks usage metrics and stores them
in the IS identity database. No data is sent to external endpoints.

---

## Metrics Overview

| Counter | Captures | Granularity | Storage |
|---|---|---|---|
| **MAU** | Distinct human users who authenticated at least once | Monthly per tenant | `IDN_MAU_COUNT` → `IDN_USAGE_COUNT` |
| **M2M Token** | New `client_credentials` token issuances | Hourly per tenant | `IDN_USAGE_COUNT` |
| **Agent CRUD** | Provision / update / delete on agent identities | Daily per tenant | `IDN_USAGE_COUNT` (stub) |
| **Agent Login** | Logins completed by agent identities | Daily per tenant | `IDN_USAGE_COUNT` (stub) |
| **Agent Token** | Tokens obtained by agent identities | Hourly per tenant | `IDN_USAGE_COUNT` (design phase) |

---

## What Each Counter Captures

### 1. MAU — Monthly Active Users

**Trigger:** `AUTHENTICATION_SUCCESS`
**Counted:** Every unique human user who completes at least one successful login in a
calendar month. Repeated logins by the same user do not increment the count.
**User identity:** `AuthenticatedUser.getUserId()` (UUID); falls back to `getUserName()`.

**Storage flow:**
```
Login event → MAUCacheManager (userId:tenant|MMYYYY → lastLoginTs)
                    │
          MAUFlushTask (scheduled, configurable interval)
                    │
      IDN_MAU_COUNT (one row per user per month)
                    │
  MAUMonthlyAggregationTask (last day of month, 23:xx UTC)
                    ├─ countDistinctUsers() from IDN_MAU_COUNT
                    └─ UsageDAO.upsertCounts(MAU) → IDN_USAGE_COUNT
```

**Retention:** `IDN_MAU_COUNT` rows purged after `dbRetentionDays` (default 60 days).
`IDN_USAGE_COUNT` MAU rows are kept indefinitely.

---

### 2. M2M Token Counter

**Trigger:** `POST_ISSUE_ACCESS_TOKEN_V2`
**Counted:** Every **new** OAuth2 access token issued via the `client_credentials` grant type.

**NOT counted:**
- Authorization code, password, or refresh-token grants
- Reuse of an existing valid token (`EXISTING_TOKEN_USED = true`)

**Storage flow:**
```
Token issue event → GenericCounterCache(M2M_TOKEN) (tenant → count)
                         │
                CounterFlushTask (hourly)
                         │
          IDN_USAGE_COUNT (COUNT_TYPE='M2M_TOKEN', PERIOD='YYYYMMDDHHmm')
```

---

### 3. Agent CRUD Counter (`AgentManagementListener`) — Stub

**Trigger:** `UserOperationEventListener` on the configured agent userstore
**Counted per operation:**

| Operation | `COUNT_TYPE` | Trigger |
|---|---|---|
| New agent provisioned | `AGENT_PROVISION` | `doPreAddUser` |
| Agent profile/credentials/roles changed | `AGENT_UPDATE` | `doPreUpdateCredential`, `doPreSetUserClaimValues`, `doPreUpdateRoleListOfUser` |
| Agent deleted | `AGENT_DELETE` | `doPreDeleteUser` |

**Agent identification:** Only operations on the userstore matching `agent.userStoreDomain` are counted.

---

### 4. Agent Login Counter (`AgentLoginEventHandler`) — Stub

**Trigger:** `AUTHENTICATION_SUCCESS`
**Filter:** `AuthenticatedUser.getUserStoreDomain()` matches `agent.userStoreDomain`
**Counted:** Every login event by an agent identity (frequency, not distinct count).

---

### 5. Agent Token Counter (`AgentTokenEventHandler`) — Design phase

**Design options under evaluation:**
- **Option A:** Check `AuthenticatedUser.getUserStoreDomain()` on the token subject (consistent with Agent Login handler)
- **Option B:** Check if the OAuth2 client application has a metadata flag `isAgentClient=true`

---

## Node ID

`NODE_ID` in `IDN_USAGE_COUNT` identifies which IS cluster node wrote a count row.
In a cluster, all nodes write to the same table and counts accumulate via
`ON DUPLICATE KEY UPDATE COUNT = COUNT + VALUES(COUNT)`.

**Resolution order:**

| Priority | Source |
|---|---|
| 1 | `nodeId` property in `deployment.toml` |
| 2 | Machine hostname (`InetAddress.getLocalHost().getHostName()`) |
| 3 | Random 16-char hex (container / unknown-host fallback) |

---

## Database Schema

Apply `src/main/resources/db/usage_schema.sql` to the WSO2 identity database.

### `IDN_MAU_COUNT`

| Column | Type | Description |
|---|---|---|
| `USER_ID` | VARCHAR(255) | User identifier (UUID or username) |
| `TENANT_DOMAIN` | VARCHAR(255) | Tenant |
| `MONTH_YEAR` | CHAR(6) | MMYYYY, e.g. `"062026"` |
| `LAST_LOGIN` | BIGINT | Epoch millis of the most recent login this month |

### `IDN_USAGE_COUNT`

| Column | Type | Description |
|---|---|---|
| `NODE_ID` | VARCHAR(128) | IS cluster node identifier |
| `TENANT_DOMAIN` | VARCHAR(256) | Tenant |
| `COUNT_TYPE` | VARCHAR(50) | `MAU` / `M2M_TOKEN` / `AGENT_*` |
| `COUNT` | INT | Accumulated count (incremented per flush) |
| `PERIOD` | VARCHAR(20) | Period key (format per COUNT_TYPE) |
| `CREATED_TIME` | TIMESTAMP | Last update time |

**PERIOD format by COUNT_TYPE:**

| COUNT_TYPE | Granularity | Format | Example |
|---|---|---|---|
| `MAU` | Monthly | `MMYYYY` | `062026` |
| `M2M_TOKEN`, `AGENT_TOKEN` | Hourly | `YYYYMMDDHHmm` | `202606021400` |
| `AGENT_PROVISION`, `AGENT_UPDATE`, `AGENT_DELETE`, `AGENT_LOGIN` | Daily | `YYYYMMDD` | `20260602` |

---

## Architecture

```
AUTHENTICATION_SUCCESS
  ├─► MAULoginEventHandler
  │       └─ MAUCacheManager (userId:tenant|MMYYYY → ts)
  │               └─ MAUFlushTask (scheduled)
  │                       ├─ MAUDataAccessObject.batchUpsertLogins() → IDN_MAU_COUNT
  │                       └─ MAUMonthlyAggregationTask (month-end)
  │                               ├─ MAUDataAccessObject.countDistinctUsers()
  │                               └─ UsageDAO.upsertCounts(MAU) → IDN_USAGE_COUNT
  │
  └─► AgentLoginEventHandler  [stub: filters by userstore]
          └─ GenericCounterCache(AGENT_LOGIN)
                  └─ CounterFlushTask (hourly) → IDN_USAGE_COUNT

POST_ISSUE_ACCESS_TOKEN_V2
  └─► M2MTokenEventHandler  [client_credentials + new token only]
          └─ GenericCounterCache(M2M_TOKEN)
                  └─ CounterFlushTask (hourly) → IDN_USAGE_COUNT

UserOperationEventListener (agent userstore)  [stub]
  └─► AgentManagementListener
          └─ GenericCounterCache(AGENT_PROVISION / AGENT_UPDATE / AGENT_DELETE)
                  └─ CounterFlushTask (daily) → IDN_USAGE_COUNT
```

---

## Configuration (`deployment.toml`)

```toml
[[event_handler]]
name          = "mauLoginEventHandler"
subscriptions = ["AUTHENTICATION_SUCCESS"]

[event_handler.properties]
nodeId             = ""        # leave empty to auto-detect from hostname
flushInterval      = 15
flushIntervalUnit  = "MINUTES" # MINUTES | HOURS | DAYS
cacheRetentionDays = 40
dbRetentionDays    = 60

[[event_handler]]
name          = "m2mTokenUsageHandler"
subscriptions = ["POST_ISSUE_ACCESS_TOKEN_V2"]

[event_handler.properties]
nodeId  = ""
enabled = true

[[event_handler]]
name          = "agentLoginUsageHandler"
subscriptions = ["AUTHENTICATION_SUCCESS"]

[event_handler.properties]
nodeId          = ""
enabled         = true
userStoreDomain = "AGENTS"
```

---

## Implementation Status

| Component | Class | Status |
|---|---|---|
| MAU counter | `MAULoginEventHandler` | ✅ Complete |
| M2M token counter | `M2MTokenEventHandler` | ✅ Complete |
| Agent login counter | `AgentLoginEventHandler` | 🔧 Stub |
| Agent CRUD counter | `AgentManagementListener` | 🔧 Stub |
| Agent token counter | `AgentTokenEventHandler` | ⬜ Design phase |
| Unified DAO | `UsageDAO` | ✅ Complete |
| Common counter cache | `GenericCounterCache` | ✅ Complete |
| Generic flush task | `CounterFlushTask` | ✅ Complete |
| Node ID resolution | `UsageTrackingConfig` | ✅ Complete |
| DB schema | `usage_schema.sql` | ✅ Complete |

---

## Package Structure

```
org.wso2.carbon.identity.usage.metering/
├── common/
│   ├── CountType.java                  metric type enum (MAU, M2M_TOKEN, AGENT_*)
│   ├── UsageTrackingException.java     shared checked exception
│   ├── cache/GenericCounterCache.java  thread-safe increment/drain counter
│   ├── config/UsageTrackingConfig.java all runtime config + node ID resolution
│   ├── dao/UsageDAO.java               IDN_USAGE_COUNT read/write
│   ├── task/CounterFlushTask.java      generic periodic flush (M2M + Agent)
│   └── util/PeriodUtil.java            PERIOD key generation per CountType
├── mau/
│   ├── MAUCacheManager.java            user-login in-memory cache (singleton)
│   ├── MAUDataAccessObject.java        IDN_MAU_COUNT read/write
│   ├── MAUFlushTask.java               periodic flush + month-end trigger
│   ├── MAULoginEventHandler.java       AUTHENTICATION_SUCCESS handler
│   └── MAUMonthlyAggregationTask.java  counts distinct users → IDN_USAGE_COUNT
├── m2m/
│   └── M2MTokenEventHandler.java       POST_ISSUE_ACCESS_TOKEN_V2 handler
├── agent/
│   ├── AgentLoginEventHandler.java     stub: login counter filtered by userstore
│   ├── AgentManagementListener.java    stub: CRUD counter via UserOperationEventListener
│   └── AgentTokenEventHandler.java     design phase
└── internal/
    └── UsageTrackingServiceComponent.java  OSGi DS wiring
```

---

## Build & Deploy

```bash
mvn clean package
# Output: target/org.wso2.carbon.identity.usage.metering-1.0.0.jar

# Apply DB schema to WSO2 identity DB
# src/main/resources/db/usage_schema.sql

cp target/org.wso2.carbon.identity.usage.metering-1.0.0.jar \
   <IS_HOME>/repository/components/dropins/

# Add deployment.toml config, then restart IS
```
