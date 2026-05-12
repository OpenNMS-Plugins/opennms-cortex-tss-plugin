#!/usr/bin/env bash
#
# Full E2E orchestration: build plugin, deploy KAR, start stack, install feature,
# wait for data, run smoke tests, tear down.
#
# Usage:
#   ./run-e2e.sh [--backend prometheus|thanos] [--no-build] [--no-teardown] [--timeout 600]
#
# Requires: mvn, docker/podman, docker-compose/podman-compose, curl, python3, sshpass
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND="thanos"
BUILD=true
TEARDOWN=true
TIMEOUT=600  # max seconds to wait for OpenNMS + data
JAVA_HOME="${JAVA_HOME:-}"

# ── Parse args ────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend)    BACKEND="$2"; shift 2 ;;
    --backend=*)  BACKEND="${1#--backend=}"; shift ;;
    --no-build)   BUILD=false; shift ;;
    --no-teardown) TEARDOWN=false; shift ;;
    --timeout)    TIMEOUT="$2"; shift 2 ;;
    --timeout=*)  TIMEOUT="${1#--timeout=}"; shift ;;
    -h|--help)
      echo "Usage: $0 [--backend prometheus|thanos] [--no-build] [--no-teardown] [--timeout N]"
      exit 0
      ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# ── Detect container runtime ─────────────────────────────────────────
if command -v podman-compose &>/dev/null && command -v podman &>/dev/null; then
  COMPOSE_CMD="podman-compose"
  CONTAINER_CMD="podman"
elif command -v docker-compose &>/dev/null; then
  COMPOSE_CMD="docker-compose"
  CONTAINER_CMD="docker"
elif command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
  CONTAINER_CMD="docker"
elif command -v podman &>/dev/null && podman compose version &>/dev/null 2>&1; then
  COMPOSE_CMD="podman compose"
  CONTAINER_CMD="podman"
else
  echo "ERROR: No docker-compose or podman-compose found"
  exit 1
fi

echo "=== E2E Orchestrator ==="
echo "  Backend:   $BACKEND"
echo "  Build:     $BUILD"
echo "  Teardown:  $TEARDOWN"
echo "  Timeout:   ${TIMEOUT}s"
echo "  Compose:   $COMPOSE_CMD"
echo "  Container: $CONTAINER_CMD"
echo ""

# ── Cleanup function ─────────────────────────────────────────────────
cleanup() {
  if [ "$TEARDOWN" = true ]; then
    echo ""
    echo "=== Tearing down stack ==="
    cd "$SCRIPT_DIR"
    $COMPOSE_CMD --profile "$BACKEND" down -v 2>/dev/null || true
  fi
}

if [ "$TEARDOWN" = true ]; then
  trap cleanup EXIT
fi

# ── Step 1: Build plugin ─────────────────────────────────────────────
if [ "$BUILD" = true ]; then
  echo "=== Step 1: Building plugin ==="
  cd "$PROJECT_DIR"
  MVN_ARGS=(-DskipTests)
  if [ -n "$JAVA_HOME" ]; then
    export JAVA_HOME
    echo "  JAVA_HOME=$JAVA_HOME"
  fi
  mvn clean install "${MVN_ARGS[@]}" 2>&1 | tail -5
  echo ""
fi

# ── Step 2: Deploy KAR ───────────────────────────────────────────────
echo "=== Step 2: Deploying KAR ==="
KAR_SOURCE="$PROJECT_DIR/assembly/kar/target/opennms-cortex-tss-plugin.kar"
KAR_DEST="$SCRIPT_DIR/opennms-overlay/deploy/opennms-cortex-tss-plugin.kar"
if [ ! -f "$KAR_SOURCE" ]; then
  echo "ERROR: KAR not found at $KAR_SOURCE"
  echo "  Run with --no-build only if you've already built the plugin."
  exit 1
fi
cp "$KAR_SOURCE" "$KAR_DEST"
echo "  Deployed $(basename "$KAR_SOURCE") ($(du -h "$KAR_DEST" | cut -f1))"
echo ""

# ── Step 3: Start stack ──────────────────────────────────────────────
echo "=== Step 3: Starting $BACKEND stack ==="
cd "$SCRIPT_DIR"
$COMPOSE_CMD --profile "$BACKEND" down -v 2>/dev/null || true
$COMPOSE_CMD --profile "$BACKEND" up -d 2>&1 | grep -v "^$"
echo ""

# ── Step 4: Wait for OpenNMS ─────────────────────────────────────────
echo "=== Step 4: Waiting for OpenNMS ==="
DEADLINE=$((SECONDS + TIMEOUT))
OPENNMS_READY=false
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' -u admin:admin http://localhost:8980/opennms/rest/info 2>/dev/null || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "  OpenNMS is up (${SECONDS}s elapsed)"
    OPENNMS_READY=true
    break
  fi
  sleep 5
done
if [ "$OPENNMS_READY" = false ]; then
  echo "ERROR: OpenNMS did not start within ${TIMEOUT}s"
  $CONTAINER_CMD logs "$(cd "$SCRIPT_DIR" && $CONTAINER_CMD ps -a --format '{{.Names}}' | grep opennms | head -1)" 2>&1 | tail -30
  exit 1
fi
echo ""

# ── Step 5: Install Cortex plugin feature ─────────────────────────────
echo "=== Step 5: Installing Cortex plugin feature ==="
if ! command -v sshpass &>/dev/null; then
  echo "ERROR: sshpass not found. Install it:"
  echo "  macOS: brew install hudochenkov/sshpass/sshpass"
  echo "  Linux: apt-get install sshpass"
  exit 1
fi

# Clear any stale host keys for Karaf SSH
ssh-keygen -R "[localhost]:8101" 2>/dev/null || true

FEATURE_INSTALLED=false
for attempt in $(seq 1 12); do
  if sshpass -p admin ssh -o StrictHostKeyChecking=no -o LogLevel=ERROR -p 8101 admin@localhost \
    "feature:install opennms-plugins-cortex-tss" 2>&1 | grep -qv "Error"; then
    FEATURE_INSTALLED=true
    break
  fi
  sleep 5
done

if [ "$FEATURE_INSTALLED" = false ]; then
  echo "WARNING: Could not confirm feature install via SSH, checking status..."
fi

# Verify
FEATURE_STATUS=$(sshpass -p admin ssh -o StrictHostKeyChecking=no -o LogLevel=ERROR -p 8101 admin@localhost \
  "feature:list | grep cortex" 2>/dev/null || echo "unknown")
echo "  $FEATURE_STATUS"

HEALTH=$(sshpass -p admin ssh -o StrictHostKeyChecking=no -o LogLevel=ERROR -p 8101 admin@localhost \
  "opennms:health-check" 2>&1 | grep -o "Everything is awesome" || echo "NOT HEALTHY")
echo "  Health: $HEALTH"
if [ "$HEALTH" != "Everything is awesome" ]; then
  echo "ERROR: OpenNMS health check failed"
  exit 1
fi
echo ""

# ── Step 6: Wait for metrics ─────────────────────────────────────────
echo "=== Step 6: Waiting for metrics ==="
METRICS_READY=false
while [ $SECONDS -lt $DEADLINE ]; do
  COUNT=$(curl -s http://localhost:9090/api/v1/label/__name__/values 2>/dev/null \
    | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null || echo "0")
  if [ "$COUNT" -gt "10" ]; then
    echo "  $COUNT metrics available (${SECONDS}s elapsed)"
    METRICS_READY=true
    break
  fi
  sleep 5
done
if [ "$METRICS_READY" = false ]; then
  echo "ERROR: No metrics flowed to backend within ${TIMEOUT}s"
  exit 1
fi
echo ""

# ── Step 7: Run smoke tests ──────────────────────────────────────────
echo "=== Step 7: Running smoke tests ==="
echo ""
cd "$SCRIPT_DIR"
bash smoke-test.sh --backend "$BACKEND"
