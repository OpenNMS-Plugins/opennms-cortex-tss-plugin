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

### One-command E2E (preferred)
```bash
# Builds plugin, deploys KAR, starts stack, installs feature, waits for data, runs 45 tests, tears down:
./e2e/run-e2e.sh --backend thanos

# Skip rebuild if KAR already exists:
./e2e/run-e2e.sh --backend thanos --no-build

# Keep stack running after tests (for debugging):
./e2e/run-e2e.sh --backend thanos --no-teardown
```

### Manual steps (if needed)
```bash
cd e2e
docker-compose --profile thanos up -d
# (wait for OpenNMS + install plugin feature + wait for data)
./smoke-test.sh --backend thanos
```

### CI
GitHub Actions runs E2E on every PR against both Prometheus and Thanos backends. See `.github/workflows/e2e.yml`.

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

### Docker Compose Gotchas (CI vs Local)

The E2E harness runs on both podman (macOS local) and Docker (GitHub Actions CI). Key differences:

- **Volume permissions**: Docker named volumes are root-owned. Images running as non-root (e.g., Thanos runs as uid 1001) will get `permission denied`. Fix: `user: "0:0"` in docker-compose.yml for affected services.
- **Container naming**: podman-compose uses `_` separators (`e2e_opennms_1`), Docker Compose v2 uses `-` separators (`e2e-opennms-1`). Scripts must handle both: `grep -E "[_-]opennms"`.
- **`set -e` + `grep -c`**: `grep -c` returns exit code 1 on zero matches, which kills `set -e` scripts. Use `|| true` on grep commands, or avoid `set -e`.
- **Matrix `fail-fast`**: GitHub Actions matrix defaults to `fail-fast: true`, canceling sibling jobs on first failure. Always set `fail-fast: false` for independent E2E profiles.
- **Always collect ALL container logs on failure** — not just OpenNMS. A crashed sidecar (thanos-receive, postgres) can cause misleading symptoms (DNS failures, connection refused).

## Key Conventions

- Config PID: `org.opennms.plugins.tss.cortex` — properties go in `.cfg` files
- Blueprint XML wires the OSGi service — constructor args must match `CortexTSSConfig`
- Wire protocol: Prometheus remote write (protobuf + Snappy) for writes; Prometheus HTTP API for reads
- The pre-existing `FIXME: Data loss` in `CortexTSS.java` is an upstream issue — do not remove or "fix" it without addressing the actual retry/backpressure problem
- Never remove code outside the scope of the current task. If something is brittle, fix it — don't delete it.
