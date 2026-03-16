# OpenNMS Cortex TSS Plugin - Architecture & Logic Reference

> Comprehensive documentation of the `opennms-cortex-tss-plugin`, a Prometheus-compatible time series storage backend for OpenNMS.

**Version**: 2.0.7-SNAPSHOT
**Package**: `org.opennms.timeseries.cortex`
**License**: AGPL v3
**Repository**: [OpenNMS/opennms-cortex-tss-plugin](https://github.com/OpenNMS/opennms-cortex-tss-plugin)

---

## System Diagram

See **[MERMAID.md](MERMAID.md)** for the full architecture diagram covering write path, read path, discovery routing, external tags, observability, and backend connectivity.

---

## Table of Contents

1. [Overview](#overview)
2. [Module Structure](#module-structure)
3. [Core Components](#core-components)
4. [Write Path](#write-path)
5. [Read Path](#read-path)
6. [Label Values Discovery (Thanos Optimization)](#label-values-discovery)
7. [External Tags Persistence](#external-tags-persistence)
8. [Concurrency & Resilience](#concurrency--resilience)
9. [Configuration Reference](#configuration-reference)
10. [OSGi & Karaf Integration](#osgi--karaf-integration)
11. [Karaf Shell Commands](#karaf-shell-commands)
12. [Sanitization Rules](#sanitization-rules)
13. [Observable Metrics](#observable-metrics)
14. [Error Handling & Failure Modes](#error-handling--failure-modes)
15. [Testing Infrastructure](#testing-infrastructure)
16. [E2E Smoke Test Harness](#e2e-smoke-test-harness)
17. [Performance Tuning](#performance-tuning)
18. [Known Limitations](#known-limitations)
19. [Dependencies](#dependencies)

---

## Overview

The Cortex TSS Plugin implements the OpenNMS Integration API `TimeSeriesStorage` interface, enabling OpenNMS to store and retrieve time series metrics in any Prometheus-compatible backend (Cortex, vanilla Prometheus, Thanos, Mimir, etc.).

**Data flow summary:**

```
OpenNMS Collectors/Pollers
       │
       ▼
TimeseriesStorageManager (core)
       │
       ▼
CortexTSS.store()                          CortexTSS.getTimeseries()
       │                                          │
       ▼                                          ▼
Protobuf + Snappy encode                   PromQL query build
       │                                          │
       ▼                                          ▼
POST /api/v1/write                         GET /api/v1/query_range
(Prometheus Remote Write)                  (Prometheus HTTP API)
       │                                          │
       ▼                                          ▼
Cortex / Prometheus / Thanos        Cortex / Prometheus / Thanos
```

To activate in OpenNMS, set `org.opennms.timeseries.strategy=integration` in `opennms.properties.d/`.

---

## Module Structure

```
cortex-parent/
├── plugin/                          # Core plugin (OSGi bundle)
│   ├── src/main/java/.../cortex/
│   │   ├── CortexTSS.java          # TimeSeriesStorage implementation (~760 lines)
│   │   ├── CortexTSSConfig.java     # Immutable config with builder pattern
│   │   ├── ResultMapper.java        # Prometheus JSON response parsing
│   │   └── shell/
│   │       ├── MetricQuery.java     # Karaf: opennms-cortex:query-metrics
│   │       └── Stats.java           # Karaf: opennms-cortex:stats
│   ├── src/main/proto/
│   │   ├── remote.proto             # PrometheusRemote.WriteRequest
│   │   └── types.proto              # PrometheusTypes.TimeSeries, Label, Sample
│   ├── src/main/resources/OSGI-INF/blueprint/
│   │   └── blueprint.xml            # OSGi service registration & config injection
│   └── src/test/
│       ├── java/.../cortex/
│       │   ├── CortexTSSTest.java           # Unit tests (sanitization, query building)
│       │   ├── ResultMapperTest.java        # Response parsing tests
│       │   ├── CortexTSSIntegrationTest.java # Testcontainers integration
│       │   └── NMS16271_IT.java             # NaN filtering regression test
│       └── resources/
│           ├── seriesQueryResult.json
│           ├── rangeQueryResult.json
│           └── labelValuesResult.json
│
├── karaf-features/                  # Karaf feature definitions
│   └── src/main/resources/features.xml
│
├── wrap/                            # OSGi wrapper for Resilience4j
│
├── assembly/                        # KAR file packaging
│
└── e2e/                             # End-to-end smoke test harness
    ├── smoke-test.sh                # 45-test validation script
    ├── docker-compose.yml           # Prometheus & Thanos profiles
    ├── prometheus.yml
    ├── opennms-overlay/             # Shared OpenNMS config overlays
    ├── opennms-overlay-prometheus/   # Prometheus-specific cortex.cfg
    └── opennms-overlay-thanos/      # Thanos-specific cortex.cfg
```

---

## Core Components

### CortexTSS.java

The central class. Implements `TimeSeriesStorage` from the OpenNMS Integration API.

**Constructor**: `CortexTSS(CortexTSSConfig config, KeyValueStore kvStore)`

Initializes:
- OkHttp client with configured dispatcher, connection pool, and timeouts
- Resilience4j bulkhead for write concurrency control
- Guava caches for metrics and external tags
- Dropwizard `MetricRegistry` for observability
- Background preload of external tags from `KeyValueStore`

**Key constants:**
| Constant | Value | Purpose |
|---|---|---|
| `METRIC_NAME_LABEL` | `__name__` | Prometheus metric name label |
| `MAX_SAMPLES` | `1200` | Max data points per range query |
| `X_SCOPE_ORG_ID_HEADER` | `X-Scope-OrgID` | Cortex multi-tenancy header |
| `METRIC_NAME_PATTERN` | `^[a-zA-Z_:][a-zA-Z0-9_:]*$` | Valid Prometheus metric name |
| `LABEL_NAME_PATTERN` | `^[a-zA-Z_][a-zA-Z0-9_]*$` | Valid Prometheus label name |

**Destroy lifecycle**: Shuts down the executor, evicts connections, cancels in-flight requests.

### CortexTSSConfig.java

Immutable configuration object with a builder pattern. All properties are set at construction (via OSGi Blueprint injection) and cannot change without plugin reload.

### ResultMapper.java

Parses three types of Prometheus HTTP API responses:

| Method | Endpoint | Returns |
|---|---|---|
| `fromRangeQueryResult()` | `/query_range` | `List<Sample>` — timestamped metric values |
| `fromSeriesQueryResult()` | `/series` | `List<Metric>` — discovered metric metadata |
| `parseLabelValuesResponse()` | `/label/*/values` | `List<String>` — unique label values |

Uses Jackson streaming parser for memory-efficient `/series` parsing.

---

## Write Path

### Flow: Sample to Backend

```
1. CortexTSS.store(List<Sample> samples)
   │
   ├─ Filter out NaN values (Prometheus can't store them)
   ├─ Sort samples by timestamp (Cortex requirement for in-order ingestion)
   │
   ├─ For each sample:
   │   ├─ toPrometheusTimeSeries(sample)
   │   │   ├─ Extract all tags (intrinsic + meta)
   │   │   ├─ Map "name" tag → "__name__" label
   │   │   ├─ Sanitize metric name (see Sanitization Rules)
   │   │   ├─ Sanitize all label names and values
   │   │   ├─ Create PrometheusTypes.Label for each tag
   │   │   ├─ SORT labels lexicographically by name (Prometheus spec requirement)
   │   │   └─ Build PrometheusTypes.TimeSeries with labels + sample(timestamp_ms, value)
   │   │
   │   └─ persistExternalTags(sample)
   │       └─ Upsert external tags to KeyValueStore (see External Tags section)
   │
   ├─ Build PrometheusRemote.WriteRequest containing all TimeSeries
   ├─ Serialize to protobuf bytes: writeRequest.toByteArray()
   ├─ Compress with Snappy: Snappy.compress(bytes)
   │
   └─ Async POST via bulkhead:
       POST {writeUrl}
       Headers:
         Content-Type: application/x-protobuf
         Content-Encoding: snappy
         X-Prometheus-Remote-Write-Version: 0.1.0
         X-Scope-OrgID: {organizationId}  (if configured)

2. Callback:
   ├─ Success (HTTP 2xx): samplesWritten.mark(count)
   └─ Failure: samplesLost.mark(count), log error with response body
```

### Protobuf Wire Format

Defined in `remote.proto` and `types.proto` (Prometheus standard):

```protobuf
message WriteRequest {
  repeated TimeSeries timeseries = 1;
}

message TimeSeries {
  repeated Label labels   = 1;  // sorted lexicographically by name
  repeated Sample samples = 2;
}

message Label {
  string name  = 1;
  string value = 2;
}

message Sample {
  double value    = 1;
  int64 timestamp = 2;  // milliseconds since epoch
}
```

The serialized protobuf is compressed with Snappy before transmission (~70-80% compression ratio for typical time series data).

---

## Read Path

### Time Series Fetching

```
CortexTSS.getTimeseries(TimeSeriesFetchRequest request)
  │
  ├─ 1. Load original Metric (for metadata like mtype):
  │      Check metricCache → if miss, call findMetrics() to populate
  │
  ├─ 2. Build PromQL query:
  │      Base selector: {resourceId="...", __name__="..."}
  │
  │      If mtype is "counter" or "count":
  │        Wrap with rate(): rate({...}[interval_s])
  │        interval = step * 2.1 (ensures >= 2 samples for rate calc)
  │
  │      If aggregation requested:
  │        AVERAGE → avg({...})
  │        MAX     → max({...})
  │        MIN     → min({...})
  │
  ├─ 3. Calculate step size:
  │      If request specifies step: use it
  │      Otherwise: step = max(1, ceil(duration_seconds / 1200))
  │      This caps results at MAX_SAMPLES (1200) data points
  │
  ├─ 4. Execute query:
  │      GET {readUrl}/query_range?query={PromQL}&start={ts}&end={ts}&step={s}
  │
  └─ 5. Parse response:
         ResultMapper.fromRangeQueryResult(json, metric)
         → List<Sample> with original metric metadata preserved
```

### Step Calculation Examples

| Duration | Calculation | Step | Points |
|---|---|---|---|
| 1 hour (3,600s) | ceil(3600 / 1200) = 3 | 3s | ~1,200 |
| 24 hours (86,400s) | ceil(86400 / 1200) = 72 | 72s | ~1,200 |
| 7 days (604,800s) | ceil(604800 / 1200) = 504 | 504s | ~1,200 |
| 90 days (7,776,000s) | ceil(7776000 / 1200) = 6,480 | 6,480s | ~1,200 |

### Metric Discovery

```
CortexTSS.findMetrics(Collection<TagMatcher> tagMatchers)
  │
  ├─ Route decision:
  │   If useLabelValuesForDiscovery=true AND query is a wildcard discovery:
  │     → findMetricsViaLabelValues()  (two-phase, Thanos-optimized)
  │   Else:
  │     → Direct /series?match[]={...}  (standard path)
  │
  └─ Return: List<Metric> with intrinsic + meta + external tags
```

A "wildcard discovery query" is detected when a `TagMatcher` of type `EQUALS_REGEX` targets the `resourceId` key — this indicates OpenNMS is scanning for resources rather than querying a specific one.

---

## Label Values Discovery

This is a Thanos-specific optimization that dramatically improves resource discovery performance on large datasets.

### The Problem

Standard `/series` queries with regex matchers (e.g., `{resourceId=~"snmp/1/.*"}`) force Thanos to:
1. Download chunks from Thanos Receive/Store
2. Decompress each chunk
3. Apply regex against every series
4. Return matching series metadata

This is extremely expensive at scale (millions of series).

### The Solution: Two-Phase Discovery

```
Phase 1: Index-only label values scan (cheap)
─────────────────────────────────────────────
GET {readUrl}/label/resourceId/values?match[]={query}&start=...&end=...

Returns: ["snmp/1/eth0/mib2-interfaces", "snmp/1/eth1/mib2-interfaces", "snmp/1/nodeSnmp", ...]

Cost: Index-only scan in Thanos — no chunk decompression needed.


Phase 2: Batched targeted /series queries (focused)
────────────────────────────────────────────────────
For each batch of resourceIds (size = discoveryBatchSize):

  1. Escape regex metacharacters in each resourceId:
     "mib2-interfaces" → "mib2\\-interfaces"
     Characters escaped: \ { } ( ) [ ] . + * ? ^ $ | -

  2. Build alternation regex:
     ^(snmp/1/eth0/mib2\\-interfaces|snmp/1/eth1/mib2\\-interfaces|snmp/1/nodeSnmp)$

  3. Query:
     GET /series?match[]={resourceId=~"^(batch)$"}

  4. Parse results with full metadata

Cost: Exact-match alternation allows Thanos to use index lookups
      instead of full regex scanning. No chunk decompression.


Result: 100-1000x faster for large resource trees on Thanos.
```

### Configuration

| Property | Default | Purpose |
|---|---|---|
| `useLabelValuesForDiscovery` | `false` | Enable two-phase discovery |
| `discoveryBatchSize` | `50` | Number of resourceIds per `/series` batch |

Both Prometheus and Thanos support the `/label/*/values` API, but the performance benefit is most dramatic on Thanos where chunk decompression is the bottleneck.

---

## External Tags Persistence

External tags are additional metadata that doesn't fit within Prometheus labels (which have strict naming/size constraints). They are stored in OpenNMS's `KeyValueStore` service.

### Storage Model

```
KeyValueStore context: "CORTEX_TSS"
Key:    metric.getKey()  (e.g., composite of intrinsic tags)
Value:  JSON string — {"tag1": "val1", "tag2": "val2", ...}
```

### Caching Strategy

```
externalTagsCache (Guava, max entries = externalTagsCacheSize)
  │
  ├─ Startup: Preload all entries via kvStore.enumerateContextAsync("CORTEX_TSS")
  │
  ├─ Write path (persistExternalTags):
  │   ├─ Cache HIT: Compare tags, upsert only new ones
  │   ├─ Cache MISS: Fetch from KV store, merge, update cache
  │   └─ If tags changed: kvStore.putAsync() + update cache on completion
  │
  └─ Read path (ResultMapper.appendExternalTagsToMetric):
      └─ Fetch from KV store by metric key, merge into Metric as externalTag()
```

### Upsert Logic

Tags are additive only — existing tags are never overwritten:
```java
for (Tag tag : sample.getMetric().getExternalTags()) {
    if (!existingJson.has(tag.getKey())) {
        existingJson.put(tag.getKey(), tag.getValue());
        needUpsert = true;
    }
}
```

---

## Concurrency & Resilience

### OkHttp Client

```
OkHttpClient
├── Dispatcher
│   ├── ExecutorService: FixedThreadPool(maxConcurrentHttpConnections)
│   ├── maxRequests: maxConcurrentHttpConnections
│   └── maxRequestsPerHost: maxConcurrentHttpConnections
│
├── ConnectionPool
│   ├── maxIdleConnections: maxConcurrentHttpConnections
│   └── keepAliveDuration: 5 minutes
│
└── Timeouts
    ├── readTimeout: readTimeoutInMs
    └── writeTimeout: writeTimeoutInMs
```

### Resilience4j Bulkhead

The bulkhead gates all write operations, preventing the OkHttp dispatcher from being overwhelmed:

```
Bulkhead "asyncHttpCalls"
├── maxConcurrentCalls: maxConcurrentHttpConnections × 4
│   (4x multiplier allows batching headroom)
├── maxWaitDuration: bulkheadMaxWaitDurationInMs
│   (default: Long.MAX_VALUE ≈ 292 million years — effectively unlimited)
├── fairCallHandling: true (FIFO ordering)
│
└── Usage:
    bulkhead.executeCompletionStage(() -> executeAsync(request))
      .whenComplete((result, ex) -> {
          if (ex == null) samplesWritten.mark();
          else            samplesLost.mark();
      })
```

### Shutdown Sequence

```java
void destroy() {
    executor.shutdown();
    connectionPool.evictAll();
    dispatcher.cancelAll();
}
```

---

## Configuration Reference

All properties are managed via OSGi Config Admin, PID: `org.opennms.plugins.tss.cortex`

| Property | Type | Default | Description |
|---|---|---|---|
| `writeUrl` | String | `http://localhost:9009/api/prom/push` | Prometheus remote write endpoint |
| `readUrl` | String | `http://localhost:9009/prometheus/api/v1` | Prometheus query API base URL |
| `maxConcurrentHttpConnections` | int | `100` | Thread pool, connection pool, and dispatcher size |
| `writeTimeoutInMs` | long | `5000` | HTTP write request timeout (ms) |
| `readTimeoutInMs` | long | `5000` | HTTP read request timeout (ms) |
| `metricCacheSize` | long | `1000` | Guava cache max entries for metric metadata |
| `externalTagsCacheSize` | long | `1000` | Guava cache max entries for external tags |
| `bulkheadMaxWaitDurationInMs` | long | `Long.MAX_VALUE` | Max time a write can wait for a bulkhead permit |
| `maxSeriesLookback` | long | `7776000` (90 days) | Time range for `/series` discovery queries (seconds) |
| `organizationId` | String | `""` (disabled) | Cortex multi-tenancy `X-Scope-OrgID` header value |
| `useLabelValuesForDiscovery` | boolean | `false` | Enable two-phase label values discovery |
| `discoveryBatchSize` | int | `50` | Batch size for `/series` queries during two-phase discovery |

### Backend-Specific Configurations

**Vanilla Prometheus:**
```properties
writeUrl = http://prometheus:9090/api/v1/write
readUrl = http://prometheus:9090/api/v1
```
Requires `--web.enable-remote-write-receiver` flag on Prometheus.

**Thanos (Receive + Query):**
```properties
writeUrl = http://thanos-receive:19291/api/v1/receive
readUrl = http://thanos-query:10902/api/v1
useLabelValuesForDiscovery = true
discoveryBatchSize = 50
```

**Cortex:**
```properties
writeUrl = http://cortex-distributor:9009/api/prom/push
readUrl = http://cortex-query-frontend:9009/prometheus/api/v1
```

---

## OSGi & Karaf Integration

### Blueprint Wiring (`blueprint.xml`)

```xml
<!-- Config Admin integration with live reload -->
<cm:property-placeholder persistent-id="org.opennms.plugins.tss.cortex"
                        update-strategy="reload">
  <cm:default-properties>
    <!-- all defaults listed in Configuration Reference -->
  </cm:default-properties>
</cm:property-placeholder>

<!-- Config object constructed from properties -->
<bean id="cortexTssConfig" class="...CortexTSSConfig">
  <argument value="${writeUrl}" />
  <argument value="${readUrl}" />
  <!-- ... 10 more constructor args ... -->
</bean>

<!-- KeyValueStore injected from OpenNMS core -->
<reference id="keyValueStore"
           interface="org.opennms.integration.api.v1.distributed.KeyValueStore" />

<!-- Plugin instance with lifecycle management -->
<bean id="timeSeriesStorage" class="...CortexTSS" destroy-method="destroy">
  <argument ref="cortexTssConfig" />
  <argument ref="keyValueStore" />
</bean>

<!-- Dual service registration -->
<service ref="timeSeriesStorage" interface="...CortexTSS" />
<service ref="timeSeriesStorage" interface="...TimeSeriesStorage">
  <service-properties>
    <entry key="registration.export" value="true" />
  </service-properties>
</service>
```

**Lifecycle**: `update-strategy="reload"` triggers full bean recreation on config changes via `config:edit` / `config:update` in the Karaf shell.

### Karaf Feature

Feature name: `opennms-plugins-cortex-tss`

Key bundle dependencies:
- `protobuf-java` (Prometheus wire format)
- `snappy-java` (compression)
- `okhttp` + `okio` (HTTP client, via Servicemix wrappers)
- `guava` + `failureaccess` (caching)
- `jackson-core/databind/annotations` (JSON streaming)
- `metrics-core` (Dropwizard observability)
- `json` (org.json for external tags)
- `resilience4j` (bulkhead, custom OSGi wrap)

### Installation

```bash
# KAR file deployment (simplest)
cp opennms-cortex-tss-plugin.kar ${OPENNMS_HOME}/deploy/

# OR feature repository + install
karaf> feature:repo-add mvn:org.opennms.plugins.timeseries/cortex-karaf-features/VERSION/xml
karaf> feature:install opennms-plugins-cortex-tss

# Verify
karaf> bundle:list | grep cortex
karaf> feature:list | grep cortex
```

---

## Karaf Shell Commands

### `opennms-cortex:query-metrics`

Discovers metrics matching tag criteria.

```bash
# Find all metrics for a resource (exact match)
karaf> opennms-cortex:query-metrics resourceId "snmp/1/nodeSnmp"

# Find metrics by name
karaf> opennms-cortex:query-metrics name "icmp"

# Multiple tag pairs
karaf> opennms-cortex:query-metrics resourceId "snmp/1/.*" name "ifInOctets"
```

Arguments are key-value pairs. Default matcher type is `EQUALS`.

### `opennms-cortex:stats`

Prints a Dropwizard metrics report to the console.

```bash
karaf> opennms-cortex:stats

# Output includes:
# - samplesWritten rate (samples/sec)
# - samplesLost rate
# - connectionCount, idleConnectionCount
# - queuedCallsCount, runningCallsCount
# - availableConcurrentCalls (bulkhead)
```

Uses `ConsoleReporter` for formatted output with rates in /sec and durations in ms.

---

## Sanitization Rules

Prometheus has strict requirements for metric and label names. The plugin sanitizes all names before storage.

### Metric Names (`__name__`)

```
Valid pattern: ^[a-zA-Z_:][a-zA-Z0-9_:]*$

Rules:
  - First character must be letter, underscore, or colon
  - Subsequent characters: letter, digit, underscore, or colon
  - All invalid characters replaced with underscore

Examples:
  "ifHCInOctets"           → "ifHCInOctets"        (valid, unchanged)
  "jmx-minion"             → "jmx_minion"           (hyphen → underscore)
  "response:127.0.0.1"     → "response:127_0_0_1"   (dots → underscores, colon kept)
  "1invalidStart"          → "_1invalidStart"        (digit start → prepend underscore)
```

### Label Names

```
Valid pattern: ^[a-zA-Z_][a-zA-Z0-9_]*$

Rules:
  - Same as metric names but colons are NOT allowed
  - All invalid characters (including colons) replaced with underscore

Examples:
  "resourceId"             → "resourceId"           (valid, unchanged)
  "SSH/127.0.0.1"          → "SSH_127_0_0_1"        (slash and dots → underscores)
```

### Label Values

```
Rules:
  - Truncated to 2048 characters (Prometheus hard limit)
  - No character replacement (values can contain any UTF-8)
```

---

## Observable Metrics

Exposed via Dropwizard `MetricRegistry`, accessible through `opennms-cortex:stats`.

### Rates (Meter — events/sec)

| Metric | Description |
|---|---|
| `samplesWritten` | Successful sample writes to backend |
| `samplesLost` | Failed writes (data loss) |
| `extTagsModified` | External tags upserted to KV store |
| `extTagsCacheUsed` | External tags cache hits |
| `extTagsCacheMissed` | External tags cache misses |
| `extTagPutTransactionFailed` | KV store write failures |

### Gauges (Current State)

| Metric | Description |
|---|---|
| `connectionCount` | Active HTTP connections |
| `idleConnectionCount` | Idle (keepalive) connections |
| `queuedCallsCount` | Requests waiting in OkHttp dispatcher |
| `runningCallsCount` | In-flight HTTP requests |
| `availableConcurrentCalls` | Available bulkhead permits |
| `maxAllowedConcurrentCalls` | Total bulkhead capacity |

---

## Error Handling & Failure Modes

### Write Failures

| Cause | Behavior | Recovery |
|---|---|---|
| Backend unreachable | `samplesLost++`, error logged | Next batch retries automatically |
| Request timeout | `samplesLost++`, error logged | Increase `writeTimeoutInMs` |
| HTTP 4xx/5xx | `samplesLost++`, response body logged | Check backend logs |
| Bulkhead full | `CompletionStage` fails | Increase `maxConcurrentHttpConnections` |
| NaN value | Silently filtered before write | Expected behavior, no action needed |

Write failures do **not** block the sample pipeline — failed samples are lost and tracked via metrics.

### Read Failures

| Cause | Behavior | Recovery |
|---|---|---|
| Backend unreachable | `StorageException` thrown to caller | Check network/backend health |
| HTTP non-2xx | Exception includes response body | Check PromQL syntax, backend logs |
| Empty response body | `StorageException`: "no body" | Backend may be misconfigured |
| JSON parse error | Exception from ResultMapper | Check response format version |

Read failures propagate as exceptions to the OpenNMS REST/Measurements API layer.

### External Tags Failures

KV store write failures are logged and tracked (`extTagPutTransactionFailed`) but do not block the write path. Tags will be retried on the next write for that metric.

---

## Testing Infrastructure

### Unit Tests

| Class | Tests | Coverage |
|---|---|---|
| `CortexTSSTest` | 9 | Sanitization, step calculation, query building, wildcard detection, batch regex |
| `ResultMapperTest` | 5 | Series/range/label-values parsing, external tags, empty results |

### Integration Tests (Testcontainers)

| Class | Tests | Backend | Notes |
|---|---|---|---|
| `CortexTSSIntegrationTest` | ~15 | Cortex (Docker) | Extends `AbstractStorageIntegrationTest` from integration-api |
| `NMS16271_IT` | 1 | Cortex (Docker) | NaN filtering regression (NMS-16271) |

The integration tests use a `docker-compose.yaml` bundled in test resources that spins up a single-process Cortex instance.

**Ignored tests** (known Cortex limitations):
- `shouldFindWithNotEquals` — Cortex `!=` operator issue
- `shouldFindOneMetricWithRegexNotMatching` — Cortex `!~` operator issue
- `shouldDeleteMetrics` — delete not implemented (requires admin API)

### Test JSON Fixtures

| File | Purpose |
|---|---|
| `seriesQueryResult.json` | Mock `/series` response for `fromSeriesQueryResult()` |
| `rangeQueryResult.json` | Mock `/query_range` response for `fromRangeQueryResult()` |
| `labelValuesResult.json` | Mock `/label/*/values` response for `parseLabelValuesResponse()` |

---

## E2E Smoke Test Harness

Located in `e2e/`. A full-stack validation environment using Docker/Podman Compose.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    smoke-test.sh (45 tests)                 │
│                                                             │
│  ┌─────────┐    ┌──────────┐    ┌────────────────────────┐  │
│  │ Postgres │◄───│ OpenNMS  │───►│ Prometheus OR Thanos   │  │
│  │  :5432   │    │  :8980   │    │  (profile-dependent)   │  │
│  └─────────┘    │  :8101   │    └────────────────────────┘  │
│                  └──────────┘                                │
└─────────────────────────────────────────────────────────────┘
```

### Profiles

**Prometheus profile** (`--profile prometheus`):
- Prometheus with `--web.enable-remote-write-receiver`
- Write: `http://prometheus:9090/api/v1/write`
- Read: `http://prometheus:9090/api/v1`

**Thanos profile** (`--profile thanos`):
- Thanos Receive (write) + Thanos Query (read)
- Write: `http://thanos-receive:19291/api/v1/receive`
- Read: `http://thanos-query:10902/api/v1`

### Test Sections (45 tests, 12 sections)

1. **Plugin Lifecycle** (3) — Feature installed, config loaded, Karaf started
2. **Write Path** (4) — Metrics flowing, resourceIds populated, multiple types, endpoint health
3. **Read Path** (4) — Query API, range queries, instant queries, exact match
4. **Meta Tags** (4) — Node/location labels, values populated, consistency
5. **Metric Sanitization** (3) — Valid names, valid labels, no illegal chars
6. **Label Ordering** (1) — Lexicographic sort verified
7. **Resource Discovery** (4) — Wildcard /series, /label/*/values, filtered, completeness
8. **Two-Phase Discovery** (4) — Label values match, batched /series, dedup, empty filter
9. **OpenNMS REST API** (4) — Resource tree, children, graph attrs, multiple types
10. **Measurements API** (6) — Data retrieval, AVERAGE/MAX/MIN aggregation, response time, metadata
11. **Data Consistency** (3) — resourceId uniqueness, valid mtype values, series integrity
12. **Plugin Health** (5) — Write errors, no exceptions, no pool exhaustion, feature registered, data freshness

### Usage

```bash
# Prerequisites: KAR in e2e/opennms-overlay/deploy/
cp assembly/kar/target/opennms-cortex-tss-plugin.kar e2e/opennms-overlay/deploy/

# Start stack
cd e2e
docker compose --profile prometheus up -d
# OR
docker compose --profile thanos up -d

# Run tests (auto-detects backend, or specify)
./smoke-test.sh
./smoke-test.sh --backend prometheus
./smoke-test.sh --backend thanos
```

### OpenNMS Config Overlays

**Shared** (`opennms-overlay/etc/`):
- `cortex.properties` — Enables `org.opennms.timeseries.strategy=integration` + meta tag mappings
- `featuresBoot.d/cortex.boot` — Auto-installs the cortex feature on startup
- `collectd-configuration.xml` — 30-second collection intervals (fast data for testing)
- `poller-configuration.xml` — 30-second polling with response time graphs

**Per-backend** cortex.cfg files provide the correct `writeUrl` and `readUrl` for each profile.

---

## Performance Tuning

### Recommended Settings by Deployment

| Scenario | `maxConcurrentHttpConnections` | Timeouts (ms) | `metricCacheSize` | Notes |
|---|---|---|---|---|
| Small (< 100 nodes) | 50 | 5,000 | 1,000 | Defaults are fine |
| Medium (100-1,000 nodes) | 100-200 | 10,000 | 5,000 | Increase cache |
| Large (1,000+ nodes) | 200+ | 30,000 | 10,000 | Thanos recommended |
| Thanos backend | Any | 30,000-60,000 | 5,000+ | Enable label values discovery |

### Thanos-Specific Optimization

Without `useLabelValuesForDiscovery`:
- Every resource discovery query triggers full `/series` scan with regex
- Thanos must download and decompress chunks from Receive/Store
- O(total_series) per discovery query

With `useLabelValuesForDiscovery=true`:
- Phase 1: Index-only scan — O(index_entries)
- Phase 2: Exact-match batched lookups — O(matching_series)
- **100-1000x improvement** for large resource trees

### Write Throughput

```
Typical performance characteristics:
├── Samples per batch: variable (OpenNMS batches internally)
├── Snappy compression ratio: ~70-80%
├── Network per batch: 100-300 KB compressed
├── Sustainable throughput: 5,000-20,000 samples/sec
│
└── Bottlenecks (in order of likelihood):
    1. Backend ingestion rate
    2. Network latency (RTT to backend)
    3. Bulkhead queue depth
    4. CPU (Snappy is very fast, rarely a factor)
```

---

## Known Limitations

1. **Delete operations**: Not implemented. Would require enabling Prometheus admin API (`--web.enable-admin-api`) and calling `POST /api/v1/admin/tsdb/delete_series`.

2. **Negative label matchers**: `!=` and `!~` operators have known issues with Cortex. Tests for these are `@Ignore`d.

3. **Counter queries require rate()**: Prometheus cannot return raw counter values meaningfully. The plugin automatically wraps counter metrics with `rate()`.

4. **Label value length**: Values are silently truncated to 2,048 characters (Prometheus limit).

5. **In-memory metric cache**: Not shared across plugin instances (only relevant if running multiple OpenNMS instances against the same backend).

6. **NaN values**: Silently discarded during writes. This creates gaps in time series where the collector produced NaN.

7. **Long-duration query precision**: Queries spanning more than 1,200 × step seconds will be capped at 1,200 data points, with step size automatically increased.

---

## Dependencies

### Runtime

| Library | Version | Purpose |
|---|---|---|
| opennms-integration-api | 2.0.0 | TimeSeriesStorage interface, KeyValueStore |
| OkHttp | 4.12.0 | HTTP client (write + read) |
| Protobuf | 3.25.3 | Prometheus remote write serialization |
| Snappy | 1.1.10.5 | Protobuf compression |
| Guava | 33.2.1-jre | In-memory caching (metrics, external tags) |
| Jackson | 2.16.1 | Streaming JSON parsing (series results) |
| Resilience4j | 1.7.1 | Bulkhead for write concurrency control |
| Dropwizard Metrics | 4.2.25 | Observability (rates, gauges) |
| org.json | 20240303 | External tags JSON serialization |

### Build & Test

| Library | Version | Purpose |
|---|---|---|
| JUnit | 4 | Test framework |
| Mockito | - | Mocking (KV store) |
| Hamcrest | - | Assertion matchers |
| Testcontainers | - | Docker-based integration tests |
| Awaitility | - | Async test assertions |

### OSGi Infrastructure

| Component | Purpose |
|---|---|
| Apache Aries Blueprint | Dependency injection + config management |
| Apache Karaf Shell | Shell command framework |
| Servicemix Bundles | OSGi wrappers for OkHttp, OkIO |
| Custom wrap module | OSGi wrapper for Resilience4j |
