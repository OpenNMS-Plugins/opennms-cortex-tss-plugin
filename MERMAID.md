# Cortex TSS Plugin - System Architecture

> Visual architecture of the OpenNMS Cortex TSS Plugin.
> See [PLUGIN-ARCHITECTURE.md](PLUGIN-ARCHITECTURE.md) for detailed documentation.

---

## 1. High-Level Overview

How the plugin fits between OpenNMS and a Prometheus-compatible backend.

```mermaid
graph LR
    subgraph OpenNMS["OpenNMS Horizon"]
        COLL["Collectors<br/>(Collectd, Pollerd,<br/>Telemetryd)"]
        REST["REST APIs<br/>(/measurements,<br/>/resources)"]
    end

    subgraph Plugin["Cortex TSS Plugin"]
        TSS["CortexTSS<br/>TimeSeriesStorage"]
    end

    subgraph Backend["Prometheus-Compatible Backend"]
        PROM["Prometheus"]
        THANOS["Thanos"]
        CORTEX["Cortex / Mimir"]
    end

    COLL -->|"samples"| TSS
    REST -->|"queries"| TSS
    TSS -->|"Protobuf + Snappy<br/>(remote write)"| PROM
    TSS -->|"Protobuf + Snappy<br/>(remote write)"| THANOS
    TSS -->|"Protobuf + Snappy<br/>(remote write)"| CORTEX
    PROM -->|"PromQL JSON"| TSS
    THANOS -->|"PromQL JSON"| TSS
    CORTEX -->|"PromQL JSON"| TSS

    classDef core fill:#e8f4fd,stroke:#2196F3,color:#000
    classDef plugin fill:#fff3e0,stroke:#FF9800,color:#000
    classDef backend fill:#e8f5e9,stroke:#4CAF50,color:#000

    class COLL,REST core
    class TSS plugin
    class PROM,THANOS,CORTEX backend
```

---

## 2. Write Path

How a metric sample flows from OpenNMS collectors to the backend.

```mermaid
sequenceDiagram
    participant C as Collectors
    participant TSM as TimeseriesStorageManager
    participant TSS as CortexTSS.store()
    participant B as Bulkhead
    participant HTTP as OkHttp
    participant BE as Backend

    C->>TSM: List<Sample>
    TSM->>TSS: store(samples)

    Note over TSS: Filter NaN values
    Note over TSS: Sort by timestamp
    Note over TSS: Sanitize metric + label names
    Note over TSS: Sort labels lexicographically

    TSS->>TSS: persistExternalTags() → KeyValueStore

    Note over TSS: Build Protobuf WriteRequest
    Note over TSS: Snappy compress

    TSS->>B: executeCompletionStage()
    B->>HTTP: async POST

    HTTP->>BE: POST /api/v1/write<br/>Content-Type: x-protobuf<br/>Content-Encoding: snappy<br/>X-Scope-OrgID: {orgId}

    alt Success (HTTP 2xx)
        BE-->>HTTP: 200 OK
        HTTP-->>TSS: samplesWritten.mark()
    else Failure
        BE-->>HTTP: error / timeout
        HTTP-->>TSS: samplesLost.mark()
        Note over TSS: Log error, continue<br/>(non-blocking)
    end
```

---

## 3. Read Path

How a measurements query flows from the REST API through to PromQL.

```mermaid
sequenceDiagram
    participant UI as OpenNMS UI / REST
    participant TSM as TimeseriesStorageManager
    participant TSS as CortexTSS.getTimeseries()
    participant Cache as Metric Cache (Guava)
    participant HTTP as OkHttp
    participant BE as Backend
    participant RM as ResultMapper

    UI->>TSM: GET /rest/measurements
    TSM->>TSS: getTimeseries(request)

    TSS->>Cache: lookup metric metadata
    alt Cache miss
        Cache-->>TSS: miss
        TSS->>BE: findMetrics() → /series
        BE-->>TSS: metric with mtype, tags
        TSS->>Cache: populate
    else Cache hit
        Cache-->>TSS: metric
    end

    Note over TSS: Build PromQL selector<br/>{resourceId="...", __name__="..."}

    alt mtype = counter
        Note over TSS: Wrap with rate({...}[interval])
    end

    alt aggregation requested
        Note over TSS: Wrap with avg/max/min(...)
    end

    Note over TSS: Step = max(1, ceil(duration / 1200))

    TSS->>HTTP: GET /query_range?query={PromQL}&start=...&end=...&step=...
    HTTP->>BE: PromQL range query
    BE-->>HTTP: JSON matrix result
    HTTP-->>TSS: response body

    TSS->>RM: fromRangeQueryResult(json, metric)
    RM-->>TSS: List<Sample>
    TSS-->>UI: time series data
```

---

## 4. Metric Discovery

How `findMetrics()` routes between the standard path and the Thanos-optimized two-phase path.

```mermaid
flowchart TB
    START["findMetrics(tagMatchers)"] --> CHECK{"useLabelValuesForDiscovery<br/>AND wildcard regex<br/>on resourceId?"}

    CHECK -->|no| STANDARD
    CHECK -->|yes| TWOPHASE

    subgraph STANDARD["Standard Path"]
        direction TB
        S1["GET /series?match[]={query}<br/>with regex matchers"]
        S2["ResultMapper.fromSeriesQueryResult()"]
        S3["Append external tags<br/>from KeyValueStore"]
        S1 --> S2 --> S3
    end

    subgraph TWOPHASE["Two-Phase Path (Thanos Optimized)"]
        direction TB
        P1["Phase 1<br/>GET /label/resourceId/values?match[]={query}<br/><i>Index-only scan — no chunk decompression</i>"]
        P2["Chunk into batches<br/>(size = discoveryBatchSize)"]
        P3["Phase 2<br/>GET /series?match[]={resourceId=~'^(id1|id2|...)$'}<br/><i>Exact-match alternation — index lookup</i>"]
        P4["Append external tags<br/>from KeyValueStore"]
        P1 --> P2 --> P3 --> P4
    end

    STANDARD --> RESULT["List&lt;Metric&gt;<br/>with intrinsic + meta + external tags"]
    TWOPHASE --> RESULT

    classDef decision fill:#fff3e0,stroke:#FF9800,color:#000
    classDef phase1 fill:#e8f5e9,stroke:#4CAF50,color:#000
    classDef phase2 fill:#e8f4fd,stroke:#2196F3,color:#000

    class CHECK decision
    class P1 phase1
    class P3 phase2
```

---

## 5. Infrastructure

Caching, external tags persistence, concurrency control, and observability.

```mermaid
graph TB
    subgraph Caching["Caches (Guava)"]
        MC["Metric Cache<br/>max: metricCacheSize<br/><i>Avoids repeat /series lookups<br/>on read path</i>"]
        ETC["External Tags Cache<br/>max: externalTagsCacheSize<br/><i>Preloaded from KV store<br/>on startup</i>"]
    end

    subgraph Concurrency["Concurrency Control"]
        DISP["OkHttp Dispatcher<br/>maxRequests: maxConns<br/>maxRequestsPerHost: maxConns"]
        POOL["Connection Pool<br/>maxIdle: maxConns<br/>keepAlive: 5 min"]
        BULKHEAD["Resilience4j Bulkhead<br/>permits: maxConns x 4<br/>fair FIFO ordering"]

        DISP --- POOL
        BULKHEAD -->|"gates writes"| DISP
    end

    subgraph ExtTags["External Tags Persistence"]
        KVS["KeyValueStore<br/>context: CORTEX_TSS"]
        WRITE_ET["Write path:<br/>persistExternalTags()<br/><i>additive-only upsert</i>"]
        READ_ET["Read path:<br/>appendExternalTags()<br/><i>merge into Metric</i>"]

        WRITE_ET --> ETC
        READ_ET --> ETC
        ETC <-->|"putAsync / get"| KVS
    end

    subgraph Metrics["Observability (Dropwizard)"]
        RATES["Rates (events/sec)<br/>samplesWritten<br/>samplesLost<br/>extTagsModified<br/>extTagPutTransactionFailed"]
        GAUGES["Gauges (current state)<br/>connectionCount<br/>idleConnectionCount<br/>queuedCallsCount<br/>runningCallsCount<br/>availableConcurrentCalls"]
        SHELL["Karaf Shell<br/>opennms-cortex:stats<br/>opennms-cortex:query-metrics"]

        SHELL --> RATES
        SHELL --> GAUGES
    end

    classDef cache fill:#fce4ec,stroke:#E91E63,color:#000
    classDef concurrency fill:#f3e5f5,stroke:#9C27B0,color:#000
    classDef storage fill:#e8f4fd,stroke:#2196F3,color:#000
    classDef metrics fill:#fff3e0,stroke:#FF9800,color:#000

    class MC,ETC cache
    class DISP,POOL,BULKHEAD concurrency
    class KVS,WRITE_ET,READ_ET storage
    class RATES,GAUGES,SHELL metrics
```

---

## 6. E2E Test Stack

The Docker/Podman Compose smoke test environment (profiles are mutually exclusive).

```mermaid
graph TB
    subgraph Tests["smoke-test.sh (45 tests)"]
        T["12 sections:<br/>lifecycle, write, read, meta tags,<br/>sanitization, labels, discovery,<br/>two-phase, REST API, measurements,<br/>data consistency, health"]
    end

    subgraph PromProfile["--profile prometheus"]
        PP["PostgreSQL<br/>:5432"]
        PR["Prometheus<br/>:9090<br/>--web.enable-remote-write-receiver"]
        PO["OpenNMS Horizon<br/>:8980 / :8101<br/>+ Cortex TSS Plugin"]
        PO -->|"remote write"| PR
        PO -->|"query API"| PR
        PO --> PP
    end

    subgraph ThanosProfile["--profile thanos"]
        TP["PostgreSQL<br/>:5432"]
        TR["Thanos Receive<br/>:19291 write<br/>:10901 gRPC"]
        TQ["Thanos Query<br/>:10902 internal<br/>:9090 external"]
        TO["OpenNMS Horizon<br/>:8980 / :8101<br/>+ Cortex TSS Plugin"]
        TO -->|"remote write :19291"| TR
        TO -->|"query API :10902"| TQ
        TR -->|"gRPC store"| TQ
        TO --> TP
    end

    T -->|"REST + Prom API"| PO
    T -->|"REST + Prom API"| TO

    classDef test fill:#fff3e0,stroke:#FF9800,color:#000
    classDef prom fill:#e8f5e9,stroke:#4CAF50,color:#000
    classDef thanos fill:#e8f4fd,stroke:#2196F3,color:#000

    class T test
    class PP,PR,PO prom
    class TP,TR,TQ,TO thanos
```

---

<details>
<summary><strong>Full System Diagram</strong> (click to expand)</summary>

All components and connections in a single view.

```mermaid
graph TB
    %% ── OpenNMS Core ──────────────────────────────────────────
    subgraph OpenNMS["OpenNMS Horizon"]
        direction TB
        COLL["Collectd / Pollerd / Telemetryd<br/>(data collectors)"]
        TSM["TimeseriesStorageManager<br/>(strategy=INTEGRATION)"]
        MAPI["Measurements REST API<br/>GET /rest/measurements"]
        RAPI["Resources REST API<br/>GET /rest/resources"]
        KVS["KeyValueStore<br/>(OSGi service)"]

        COLL -->|"List&lt;Sample&gt;"| TSM
        MAPI -->|"TimeSeriesFetchRequest"| TSM
        RAPI -->|"findMetrics()"| TSM
    end

    %% ── Plugin Internals ──────────────────────────────────────
    subgraph Plugin["CortexTSS Plugin (OSGi Bundle)"]
        direction TB

        subgraph WritePath["Write Path"]
            direction TB
            STORE["store(List&lt;Sample&gt;)"]
            FILT["Filter NaN values"]
            SORT["Sort by timestamp"]
            SANW["Sanitize metric &amp;<br/>label names"]
            LBLSORT["Sort labels<br/>lexicographically"]
            PROTO["Build Protobuf<br/>WriteRequest"]
            SNAP["Snappy compress"]
            BULK["Resilience4j Bulkhead<br/>(maxConns x 4 permits)"]
            HTTP_W["OkHttp async POST<br/>Content-Type: x-protobuf<br/>Content-Encoding: snappy<br/>X-Scope-OrgID: orgId"]

            STORE --> FILT --> SORT --> SANW --> LBLSORT --> PROTO --> SNAP --> BULK --> HTTP_W
        end

        subgraph ReadPath["Read Path"]
            direction TB
            GTS["getTimeseries()"]
            MCACHE["Metric Cache<br/>(Guava)"]
            PQBUILD["Build PromQL query"]
            RATE["Wrap rate() if counter"]
            AGG["Wrap avg/max/min<br/>if aggregation"]
            STEPCALC["Step = max(1,<br/>ceil(duration/1200))"]
            HTTP_R["OkHttp GET<br/>/query_range"]
            RMAP["ResultMapper<br/>parse JSON to List&lt;Sample&gt;"]

            GTS --> MCACHE --> PQBUILD --> RATE --> AGG --> STEPCALC --> HTTP_R --> RMAP
        end

        subgraph Discovery["Metric Discovery"]
            direction TB
            FM["findMetrics(TagMatchers)"]
            ROUTE{"useLabelValues<br/>AND wildcard?"}

            subgraph Standard["Standard Path"]
                SER["GET /series?match[]=..."]
                SPAR["ResultMapper<br/>parse series JSON"]
                SER --> SPAR
            end

            subgraph TwoPhase["Two-Phase Path (Thanos Optimized)"]
                direction TB
                PH1["Phase 1: GET /label/resourceId/values<br/>(index-only scan)"]
                PH2["Phase 2: Batch /series queries<br/>regex: ^(id1|id2|...)$<br/>(exact-match index lookup)"]
                PH1 --> PH2
            end

            FM --> ROUTE
            ROUTE -->|no| SER
            ROUTE -->|yes| PH1
        end

        subgraph ExtTags["External Tags"]
            direction TB
            ETCACHE["External Tags Cache<br/>(Guava)"]
            ETPERS["persistExternalTags()"]
            ETAPPEND["appendExternalTags()<br/>(read path)"]
        end

        subgraph Observability["Observability"]
            METRICS["Dropwizard MetricRegistry<br/>samplesWritten | samplesLost<br/>connectionCount | queuedCalls<br/>availableConcurrentCalls"]
            STATS["Karaf: opennms-cortex:stats"]
            MQUERY["Karaf: opennms-cortex:query-metrics"]
        end
    end

    %% ── Backends ──────────────────────────────────────────────
    subgraph Backends["Prometheus-Compatible Backend"]
        direction TB
        subgraph PromStack["Prometheus"]
            PWRITE["POST /api/v1/write<br/>(remote write receiver)"]
            PREAD["GET /api/v1/query_range<br/>GET /api/v1/series<br/>GET /api/v1/label/*/values"]
        end
        subgraph ThanosStack["Thanos"]
            TRECV["Thanos Receive<br/>POST :19291/api/v1/receive"]
            TQUERY["Thanos Query<br/>GET :10902/api/v1/*"]
            TRECV ---|"gRPC :10901"| TQUERY
        end
    end

    %% ── Connections ───────────────────────────────────────────
    TSM -->|"store()"| STORE
    TSM -->|"getTimeseries()"| GTS
    TSM -->|"findMetrics()"| FM

    KVS <-->|"put/get context=CORTEX_TSS"| ETCACHE
    STORE -.->|"persist tags"| ETPERS
    ETPERS --> ETCACHE
    SPAR -.->|"augment metrics"| ETAPPEND
    ETAPPEND --> ETCACHE
    PH2 -.->|"augment metrics"| ETAPPEND

    HTTP_W -->|"Protobuf + Snappy"| PWRITE
    HTTP_W -->|"Protobuf + Snappy"| TRECV
    HTTP_R --> PREAD
    HTTP_R --> TQUERY
    SER --> PREAD
    SER --> TQUERY
    PH1 --> PREAD
    PH1 --> TQUERY
    PH2 --> PREAD
    PH2 --> TQUERY

    RMAP -->|"List&lt;Sample&gt;"| MAPI
    SPAR -->|"List&lt;Metric&gt;"| RAPI
    PH2 -->|"List&lt;Metric&gt;"| RAPI

    HTTP_W -.->|"mark()"| METRICS
    STATS --> METRICS
    MQUERY --> FM

    %% ── Styling ───────────────────────────────────────────────
    classDef core fill:#e8f4fd,stroke:#2196F3,color:#000
    classDef plugin fill:#fff3e0,stroke:#FF9800,color:#000
    classDef backend fill:#e8f5e9,stroke:#4CAF50,color:#000
    classDef cache fill:#fce4ec,stroke:#E91E63,color:#000

    class COLL,TSM,MAPI,RAPI,KVS core
    class STORE,FILT,SORT,PROTO,SANW,LBLSORT,SNAP,BULK,HTTP_W,GTS,PQBUILD,RATE,AGG,STEPCALC,HTTP_R,RMAP,FM,ROUTE,SER,SPAR,PH1,PH2,ETPERS,ETAPPEND,METRICS,STATS,MQUERY plugin
    class PWRITE,PREAD,TRECV,TQUERY backend
    class MCACHE,ETCACHE cache
```

</details>
