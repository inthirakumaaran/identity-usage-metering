# MAU Tracking Extension — Development Summary

## Project Overview

A WSO2 Identity Server 7.2 OSGi bundle that tracks **Monthly Active Users (MAU)** by
listening to `AUTHENTICATION_SUCCESS` events.  Login events are buffered in-memory and
periodically flushed to the IS identity database, where per-tenant and system-wide MAU
counts are summarised and optionally published to a WSO2 central reporting endpoint.

---

## Architecture

```
AUTHENTICATION_SUCCESS event
        │
        ▼
MAULoginEventHandler.handleEvent()   ← AbstractEventHandler (IS event framework)
        │  (async, non-blocking submit to virtual thread pool)
        ▼
MAUCacheManager                      ← ConcurrentHashMap<"userId:tenant|MMYYYY", lastLoginTs>
        │
        │  (ScheduledExecutorService — fires every N minutes/hours/days)
        ▼
MAUFlushTask.run()
        ├─ snapshotGroupedByMonth()  ← drains cache entries per month-partition
        ├─ MAUDataAccessObject.batchUpsertLogins()   → IDN_MAU_TRACKING (upsert)
        └─ MAUMonthlyAggregationTask.run()
               ├─ MAUDataAccessObject.summarizeAndStoreMAU()  → IDN_MAU_SUMMARY
               ├─ MAUDataAccessObject.purgeOldTrackingRows()  → cleanup
               └─ MAUDataPublisher.publish()  → WSO2 central endpoint (optional)
```

### Database Tables

| Table | Purpose |
|-------|---------|
| `IDN_MAU_TRACKING` | One row per `(USER_ID, TENANT_DOMAIN, MONTH_YEAR)`. Stores `LAST_LOGIN` timestamp. Rows older than `dbRetentionDays` (default 60) are purged monthly. |
| `IDN_MAU_SUMMARY` | One row per `(TENANT_DOMAIN, MONTH_YEAR)`. Stores the final `MAU_COUNT`. Kept indefinitely. |

Schema: [src/main/resources/db/mau_schema.sql](../src/main/resources/db/mau_schema.sql)

---

## Key Design Decisions

### 1. Asynchronous, Non-Blocking Event Handling
`handleEvent()` submits cache updates to a single-thread virtual executor immediately
and returns.  Login latency is unaffected.

### 2. Single-Map Cache (not dual odd/even maps)
Cache key: `"userId:tenantDomain|MMYYYY"`.  A single `ConcurrentHashMap` with
`merge(key, now, Math::max)` guarantees last-write-wins.
`snapshotGroupedByMonth()` partitions entries by month for atomic per-month flushes.

### 3. Configurable Flush Interval
`flushInterval` + `flushIntervalUnit` (MINUTES / HOURS / DAYS) in `deployment.toml`.
The scheduler is **not** started in `activate()`.  It starts in `MAULoginEventHandler.init()`
**after** the IS framework has injected `deployment.toml` properties, ensuring the
configured value is always used (not the compile-time default).

### 4. Per-Tenant and System-Level MAU Queries
- `MAUDataAccessObject.summarizeAndStoreMAU(tenant, month)` — per-tenant count
- `MAUDataAccessObject.getTotalSystemMAU(month)` — sum across all tenants
- `MAUDataAccessObject.getAllTenantsMAUSummary(month)` — sorted map (highest MAU first)

---

## Bugs Found and Fixed

### Compile-Time Fixes
| File | Bug | Fix |
|------|-----|-----|
| `MAULoginEventHandler` | `InitConfig` imported from wrong package (`identity.base`) | Changed to `org.wso2.carbon.identity.core.handler.InitConfig` |
| `MAULoginEventHandler` | `HandlerConfiguration` doesn't exist | Replaced with `ModuleConfiguration` from `org.wso2.carbon.identity.event.bean`; `getModuleConfigurations()` → `getModuleProperties()` returning `java.util.Properties` |
| `MAULoginEventHandler` | `getPriority(Event event)` — wrong override signature | Changed to `getPriority(MessageContext messageContext)` |
| `MAUCacheManager` | Missing `snapshotBothMonths()` method called by flush task | Added method |

### Test Fixes
| File | Bug | Fix |
|------|-----|-----|
| `MAUDataAccessObjectTest` | `thenCallRealMethod()` on `commitTransaction` tried to call real Carbon infrastructure | Changed to `thenAnswer(inv -> { c.commit(); return null; })` |
| `MAUDataAccessObjectTest` | Same `Connection` returned for every `getDBConnection()` call; DAO closes it after use, breaking subsequent calls | Changed mock to return a fresh `DriverManager.getConnection(H2_URL)` per invocation |
| `MAUDataAccessObjectTest` | `Map.of()` (Java 9+) incompatible with `-target 1.8` | Replaced with `new HashMap<>()` + explicit `put()` calls |

### Scheduler Initialization Bug
**Bug:** `MAUTrackingServiceComponent.activate()` was calling `scheduleAtFixedRate()` with
the default 15-minute interval _before_ `init()` was called with `deployment.toml` values.

**Fix:** The `ScheduledExecutorService` and `MAUFlushTask` are injected into
`MAULoginEventHandler` via constructor.  The handler starts the scheduler inside `init()`
only after the configured interval is known.

### Runtime OSGi Deployment Error
**Error:**
```
Could not resolve module: org.wso2.is.analytics.mau [552]
Unresolved requirement: Require-Capability: osgi.extender;
  filter:="(&(osgi.extender=osgi.component)(version>=1.3.0)(!(version>=2.0.0)))"
```

**Root cause:** `maven-bundle-plugin 5.1.9` (bndlib 5.x) + `_dsannotations` processes
the `@Component`/`@Reference` annotations from `osgi.cmpn 6.0.0` (DS 1.3 API) and
auto-generates a DS 1.3 component descriptor.  bndlib 5.x then stamps the manifest with
`Require-Capability: osgi.extender; osgi.component version>=1.3.0`.  IS 7.2's Equinox
SCR only provides this extender capability at version 1.2.x, so resolution fails.

**Fix (aligned with reference pom `org.wso2.carbon.usage.data.collector.identity`):**

| What changed | Before | After |
|---|---|---|
| `maven-bundle-plugin` version | `5.1.9` | `3.2.0` |
| DS component descriptor | Auto-generated DS 1.3 via `_dsannotations` | Handwritten DS 1.2 XML in `OSGI-INF/mau-tracking.xml` |
| `_dsannotations` instruction | `*` | Removed |
| `DynamicImport-Package` | Not set | `*` |
| `org.osgi.framework.*` range | `[1.8,2)` | `[1.7.0, 2.0.0)` |
| `org.osgi.service.component.*` range | `[1.2,2)` | `[1.2.0, 2.0.0)` |
| Compiler source/target | `11` | `1.8` |
| `@Component`,`@Reference`,`@Activate`,`@Deactivate` | On `MAUTrackingServiceComponent` | Removed; descriptor is now in XML |

**Result** (verified in `META-INF/MANIFEST.MF`):
- `Require-Capability: osgi.extender; osgi.component` — **completely absent** (bndlib 3.x does not generate it)
- `Require-Capability: osgi.ee; filter:="(&(osgi.ee=JavaSE)(version=1.8))"` — trivially satisfied by IS 7.2 (Java 11)
- `DynamicImport-Package: *` — present
- `Service-Component: OSGI-INF/*.xml` — present, points to the DS 1.2 XML

---

## Files Changed / Created

```
MAU/
├── pom.xml                                            ← Updated (versions, plugin, ranges)
├── docs/
│   ├── deployment.toml.snippet                        ← Updated (flushIntervalUnit examples)
│   └── development-summary.md                         ← This file
└── src/
    ├── main/
    │   ├── java/org/wso2/is/analytics/mau/
    │   │   ├── MAULoginEventHandler.java              ← Fixed imports, init(), scheduler start
    │   │   ├── MAUCacheManager.java                   ← Refactored to single ConcurrentHashMap
    │   │   ├── MAUFlushTask.java                      ← Simplified; uses snapshotGroupedByMonth()
    │   │   ├── MAUTrackingConfig.java                 ← Added flushIntervalUnit (TimeUnit)
    │   │   ├── MAUDataAccessObject.java               ← Added getTotalSystemMAU(), getAllTenantsMAUSummary()
    │   │   ├── MAUMonthlyAggregationTask.java         ← Uses config for DB retention days
    │   │   └── internal/
    │   │       └── MAUTrackingServiceComponent.java   ← Removed DS annotations
    │   └── resources/
    │       ├── db/mau_schema.sql                      ← Unchanged
    │       └── OSGI-INF/
    │           └── mau-tracking.xml                   ← NEW: handwritten DS 1.2 descriptor
    └── test/
        └── java/org/wso2/is/analytics/mau/
            ├── MAUDataAccessObjectTest.java           ← Fixed mocks, added 4 new tests
            ├── MAULoginEventHandlerTest.java          ← Updated constructor call
            └── MAUCacheManagerTest.java               ← Unchanged
```

---

## Test Results

```
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Test Class | Tests | Coverage |
|---|---|---|
| `MAUDataAccessObjectTest` | 11 | batchUpsert, summarize, getCount, getDistinctTenants, getTotalSystemMAU, getAllTenantsMAUSummary |
| `MAULoginEventHandlerTest` | 7 | async, non-auth events, null event, missing fields, non-blocking |
| `MAUCacheManagerTest` | 9 | singleton, recordLogin, snapshotCurrentMonth, snapshotGroupedByMonth, purge |

---

## Deployment Instructions

### 1. Build
```bash
mvn clean package
# Output: target/org.wso2.is.analytics.mau-1.0.0.jar
```

### 2. Create DB Tables
Run [src/main/resources/db/mau_schema.sql](../src/main/resources/db/mau_schema.sql)
against the WSO2 IS identity database.

### 3. Deploy Bundle
```bash
cp target/org.wso2.is.analytics.mau-1.0.0.jar \
   <IS_HOME>/repository/components/dropins/
```

### 4. Configure
Add the snippet from [docs/deployment.toml.snippet](deployment.toml.snippet) to
`<IS_HOME>/repository/conf/deployment.toml`.

### 5. Restart IS
```bash
<IS_HOME>/bin/wso2server.sh restart
```

Verify in `wso2carbon.log`:
```
[MAU] Activating MAU Tracking Service Component.
[MAU] MAULoginEventHandler registered. Scheduler will start after init().
[MAU] Flush scheduler started: interval=15 MINUTES
```

---

## configuration Reference (`deployment.toml`)

```toml
[[event_handler]]
name          = "mauLoginEventHandler"
subscriptions = ["AUTHENTICATION_SUCCESS"]

[event_handler.properties]
flushInterval     = 15          # numeric value
flushIntervalUnit = "MINUTES"   # MINUTES | HOURS | DAYS
cacheRetentionDays = 40
dbRetentionDays    = 60
centralEndpoint    = "https://central.wso2.com/api/v1/mau"
centralAuthToken   = ""
```
