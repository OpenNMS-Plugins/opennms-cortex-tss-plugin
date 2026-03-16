# CLAUDE.md

## Project Overview

Cortex TSS Plugin for OpenNMS — a `TimeSeriesStorage` implementation that stores metrics in Cortex/Prometheus-compatible backends via remote write protocol. Deployed as a KAR file into OpenNMS Karaf.

- **Package**: `org.opennms.timeseries.cortex`
- **Core classes**: `CortexTSS` (implements `TimeSeriesStorage`), `CortexTSSConfig`, `ResultMapper`
- **Build**: `mvn clean install` (standard Maven, Java 17)
- **Unit tests**: `mvn test` — 14 tests (CortexTSSTest + ResultMapperTest)
- **KAR output**: `assembly/kar/target/opennms-cortex-tss-plugin.kar`
- **License**: AGPL v3 — all Java files MUST have the license header

## E2E Test Harness

Located in `e2e/`. Uses docker-compose with Prometheus or Thanos backends.

```bash
cd e2e
# Thanos backend:
docker-compose --profile thanos up -d
# (wait for OpenNMS + install plugin feature + wait for data)
./smoke-test.sh --backend thanos

# Prometheus backend:
docker-compose --profile prometheus up -d
./smoke-test.sh --backend prometheus
```

### Critical Rules for E2E Infrastructure

1. **Pin all image versions explicitly.** Never use `:latest` tags. Never reference `localhost/` images. Every image in docker-compose.yml must use a specific released version tag (e.g., `opennms/horizon:35.0.4`, `thanosio/thanos:v0.35.1`).

2. **Develop and test against released OpenNMS versions only.** Never develop against SNAPSHOT builds. The E2E harness must pass against the current stable release. If a feature requires unreleased OpenNMS behavior, the test must be marked as `skip` with a comment noting the required version.

3. **Tests must assert observable behavior.** Test assertions must check API responses, config file contents, metric data, HTTP status codes, or feature status. Never grep log files for specific messages — log output is an implementation detail that varies by version and log configuration.

4. **Collection intervals must be 30 seconds for testing.** Override the default 300s interval in overlay configs. Waiting 5 minutes per collection cycle during E2E is unacceptable.

5. **KAR must be pre-built and placed in `e2e/opennms-overlay/deploy/`.** The E2E harness does not build the plugin — it deploys a pre-built KAR. After `mvn install`, copy the KAR:
   ```bash
   cp assembly/kar/target/opennms-cortex-tss-plugin.kar e2e/opennms-overlay/deploy/
   ```

6. **The Cortex plugin feature must be explicitly installed** after OpenNMS starts. The KAR auto-deploys and registers the feature repo, but the feature itself needs `feature:install opennms-plugins-cortex-tss` via Karaf SSH (port 8101, admin/admin).

## Key Conventions

- Config PID: `org.opennms.plugins.tss.cortex` — properties go in `.cfg` files
- Blueprint XML wires the OSGi service — constructor args must match `CortexTSSConfig`
- Wire protocol: Prometheus remote write (protobuf + Snappy) for writes; Prometheus HTTP API for reads
- The pre-existing `FIXME: Data loss` in `CortexTSS.java` is an upstream issue — do not remove or "fix" it without addressing the actual retry/backpressure problem
- Never remove code outside the scope of the current task. If something is brittle, fix it — don't delete it.
