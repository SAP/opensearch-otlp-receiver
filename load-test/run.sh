#!/usr/bin/env bash
# Load test for the opensearch-otlp-receiver.
#
# Starts an OpenSearch node with the plugin installed, runs telemetrygen for
# all three OTLP signals concurrently, polls _nodes/stats every few seconds,
# and prints a throughput + error summary on exit.
#
# Prerequisites:
#   - Docker and Docker Compose
#   - Plugin zip already built: ../build/distributions/opensearch-otlp-receiver-*.zip
#
# Usage:
#   ./run.sh [options]
#
# Options:
#   --workers N      Concurrent telemetrygen workers per signal (default: 4)
#   --rate R         Signals per second per worker, 0=unlimited (default: 50)
#   --duration D     How long to run, e.g. 30s, 2m (default: 60s)
#   --grpc-only      Only use gRPC transport (skip HTTP)
#   --http-only      Only use HTTP transport (skip gRPC)
#   --no-start       Skip starting OpenSearch (assumes it is already running)
#   --no-stop        Leave OpenSearch running after the test
#   --os-url URL     OpenSearch base URL (default: http://localhost:9200)
#   --poll-interval  Seconds between _nodes/stats polls (default: 5)

set -euo pipefail

# ── defaults ─────────────────────────────────────────────────────────────────

WORKERS=4
RATE=50
DURATION=60s
TRANSPORT=both        # both | grpc | http
START_OS=true
STOP_OS=true
OS_URL="http://localhost:9200"
GRPC_ENDPOINT="localhost:4317"
HTTP_ENDPOINT="localhost:4318"
POLL_INTERVAL=5
TELEMETRYGEN_IMAGE="ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v0.123.0"

# ── argument parsing ──────────────────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
    case "$1" in
        --workers)      WORKERS="$2";       shift 2 ;;
        --rate)         RATE="$2";          shift 2 ;;
        --duration)     DURATION="$2";      shift 2 ;;
        --grpc-only)    TRANSPORT=grpc;     shift ;;
        --http-only)    TRANSPORT=http;     shift ;;
        --no-start)     START_OS=false;     shift ;;
        --no-stop)      STOP_OS=false;      shift ;;
        --os-url)       OS_URL="$2";        shift 2 ;;
        --poll-interval) POLL_INTERVAL="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SAMPLES_DIR="$REPO_ROOT/samples"
STATS_CSV="$SCRIPT_DIR/stats.csv"
TELEMETRYGEN_PIDS=()
POLLER_PID=""

# ── helpers ───────────────────────────────────────────────────────────────────

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }

require() {
    command -v "$1" &>/dev/null || die "'$1' not found — please install it"
}

cleanup() {
    log "Cleaning up..."
    # Stop background pollers and telemetrygen containers
    [[ -n "$POLLER_PID" ]] && kill "$POLLER_PID" 2>/dev/null || true
    for pid in "${TELEMETRYGEN_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true

    if [[ "$STOP_OS" == true ]]; then
        log "Stopping OpenSearch..."
        docker compose -f "$SAMPLES_DIR/docker-compose.yml" \
            --profile load-test stop opensearch 2>/dev/null || \
        docker compose -f "$SAMPLES_DIR/docker-compose.yml" stop opensearch 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# ── preflight ─────────────────────────────────────────────────────────────────

require docker
require curl
require jq

PLUGIN_ZIP=$(ls "$REPO_ROOT"/build/distributions/opensearch-otlp-receiver-*.zip 2>/dev/null | head -1)
[[ -n "$PLUGIN_ZIP" ]] || die "Plugin zip not found. Run './gradlew assemble' first."
log "Using plugin: $(basename "$PLUGIN_ZIP")"

# ── start OpenSearch ──────────────────────────────────────────────────────────

if [[ "$START_OS" == true ]]; then
    log "Starting OpenSearch..."
    docker compose -f "$SAMPLES_DIR/docker-compose.yml" up -d opensearch

    log "Waiting for OpenSearch to be ready..."
    for i in $(seq 1 60); do
        if curl -sf "$OS_URL/_cluster/health" >/dev/null 2>&1; then
            log "OpenSearch is ready."
            break
        fi
        [[ $i -eq 60 ]] && die "OpenSearch did not become ready in time."
        sleep 2
    done
fi

# ── snapshot baseline stats ───────────────────────────────────────────────────

fetch_stats() {
    curl -sf "$OS_URL/_nodes/stats/indices,thread_pool" 2>/dev/null
}

fetch_index_stats() {
    # Per-index indexing stats — used for per-signal doc counts
    curl -sf "$OS_URL/_stats/indexing" 2>/dev/null
}

extract_totals() {
    local json="$1"
    # Sum across all nodes
    echo "$json" | jq '{
        docs_indexed:  ( [.nodes[].indices.indexing.index_total]  | add // 0 ),
        index_failed:  ( [.nodes[].indices.indexing.index_failed] | add // 0 ),
        bulk_rejected: ( [.nodes[].thread_pool.write.rejected]    | add // 0 ),
        bulk_queue:    ( [.nodes[].thread_pool.write.queue]       | add // 0 )
    }'
}

extract_index_totals() {
    local json="$1"
    echo "$json" | jq '{
        spans_indexed:   ( [.indices | to_entries[] | select(.key | startswith("otel-v1-apm-span"))   | .value.total.indexing.index_total] | add // 0 ),
        logs_indexed:    ( [.indices | to_entries[] | select(.key | startswith("logs-otel-v1"))        | .value.total.indexing.index_total] | add // 0 ),
        metrics_indexed: ( [.indices | to_entries[] | select(.key | startswith("metrics-otel-v1"))     | .value.total.indexing.index_total] | add // 0 )
    }'
}

BASELINE_JSON=$(fetch_stats)
BASELINE=$(extract_totals "$BASELINE_JSON")
BASELINE_IDX_JSON=$(fetch_index_stats)
BASELINE_IDX=$(extract_index_totals "$BASELINE_IDX_JSON")
log "Baseline stats captured."

# ── start background stats poller ─────────────────────────────────────────────

echo "timestamp,docs_indexed,index_failed,bulk_rejected,bulk_queue" > "$STATS_CSV"

poll_stats() {
    while true; do
        sleep "$POLL_INTERVAL"
        local json ts totals docs failed rejected queue
        json=$(fetch_stats) || continue
        ts=$(date '+%H:%M:%S')
        totals=$(extract_totals "$json")
        docs=$(     echo "$totals" | jq '.docs_indexed')
        failed=$(   echo "$totals" | jq '.index_failed')
        rejected=$( echo "$totals" | jq '.bulk_rejected')
        queue=$(    echo "$totals" | jq '.bulk_queue')
        echo "$ts,$docs,$failed,$rejected,$queue" >> "$STATS_CSV"
    done
}

poll_stats &
POLLER_PID=$!
log "Stats poller started (every ${POLL_INTERVAL}s) → $STATS_CSV"

# ── run telemetrygen ──────────────────────────────────────────────────────────

TGEN_COMMON=(
    --rm
    --network host
    "$TELEMETRYGEN_IMAGE"
)

TGEN_FLAGS=(
    --workers "$WORKERS"
    --rate "$RATE"
    --duration "$DURATION"
    --otlp-insecure
)

log "Starting telemetrygen (workers=$WORKERS rate=$RATE/worker duration=$DURATION transport=$TRANSPORT)..."

START_EPOCH=$(date +%s)

if [[ "$TRANSPORT" == both || "$TRANSPORT" == grpc ]]; then
    log "  traces  → gRPC $GRPC_ENDPOINT"
    docker run "${TGEN_COMMON[@]}" traces \
        "${TGEN_FLAGS[@]}" \
        --otlp-endpoint "$GRPC_ENDPOINT" \
        --child-spans 2 \
        --service load-test-traces \
        2>&1 | sed 's/^/  [traces-grpc] /' &
    TELEMETRYGEN_PIDS+=($!)

    log "  logs    → gRPC $GRPC_ENDPOINT"
    docker run "${TGEN_COMMON[@]}" logs \
        "${TGEN_FLAGS[@]}" \
        --otlp-endpoint "$GRPC_ENDPOINT" \
        --service load-test-logs \
        2>&1 | sed 's/^/  [logs-grpc]   /' &
    TELEMETRYGEN_PIDS+=($!)
fi

if [[ "$TRANSPORT" == both || "$TRANSPORT" == http ]]; then
    log "  metrics → HTTP $HTTP_ENDPOINT"
    docker run "${TGEN_COMMON[@]}" metrics \
        "${TGEN_FLAGS[@]}" \
        --otlp-http \
        --otlp-endpoint "$HTTP_ENDPOINT" \
        --service load-test-metrics \
        2>&1 | sed 's/^/  [metrics-http] /' &
    TELEMETRYGEN_PIDS+=($!)
fi

# Wait for all telemetrygen instances to finish
for pid in "${TELEMETRYGEN_PIDS[@]}"; do
    wait "$pid" || true
done
END_EPOCH=$(date +%s)
ELAPSED=$(( END_EPOCH - START_EPOCH ))

log "telemetrygen finished (${ELAPSED}s elapsed)."

# Allow final bulk requests to flush
sleep 3

# ── collect final stats ───────────────────────────────────────────────────────

FINAL_JSON=$(fetch_stats)
FINAL=$(extract_totals "$FINAL_JSON")
FINAL_IDX_JSON=$(fetch_index_stats)
FINAL_IDX=$(extract_index_totals "$FINAL_IDX_JSON")

BASE_DOCS=$(     echo "$BASELINE" | jq '.docs_indexed')
BASE_FAILED=$(   echo "$BASELINE" | jq '.index_failed')
BASE_REJECTED=$( echo "$BASELINE" | jq '.bulk_rejected')

FINAL_DOCS=$(     echo "$FINAL" | jq '.docs_indexed')
FINAL_FAILED=$(   echo "$FINAL" | jq '.index_failed')
FINAL_REJECTED=$( echo "$FINAL" | jq '.bulk_rejected')

DELTA_DOCS=$(     echo "$FINAL_DOCS     - $BASE_DOCS"     | bc)
DELTA_FAILED=$(   echo "$FINAL_FAILED   - $BASE_FAILED"   | bc)
DELTA_REJECTED=$( echo "$FINAL_REJECTED - $BASE_REJECTED" | bc)

# Per-signal deltas from _stats/indexing
BASE_SPANS=$(    echo "$BASELINE_IDX" | jq '.spans_indexed')
BASE_LOGS=$(     echo "$BASELINE_IDX" | jq '.logs_indexed')
BASE_METRICS=$(  echo "$BASELINE_IDX" | jq '.metrics_indexed')

FINAL_SPANS=$(   echo "$FINAL_IDX" | jq '.spans_indexed')
FINAL_LOGS=$(    echo "$FINAL_IDX" | jq '.logs_indexed')
FINAL_METRICS=$( echo "$FINAL_IDX" | jq '.metrics_indexed')

DELTA_SPANS=$(   echo "$FINAL_SPANS   - $BASE_SPANS"   | bc)
DELTA_LOGS=$(    echo "$FINAL_LOGS    - $BASE_LOGS"    | bc)
DELTA_METRICS=$( echo "$FINAL_METRICS - $BASE_METRICS" | bc)

THROUGHPUT=0
[[ $ELAPSED -gt 0 ]] && THROUGHPUT=$(echo "scale=1; $DELTA_DOCS / $ELAPSED" | bc)

# ── print summary ─────────────────────────────────────────────────────────────

echo ""
echo "════════════════════════════════════════════"
echo "  OTLP Plugin Load Test — Summary"
echo "════════════════════════════════════════════"
printf "  Duration              : %ds\n"    "$ELAPSED"
printf "  Workers per signal    : %s\n"     "$WORKERS"
printf "  Rate per worker       : %s/s\n"   "$RATE"
printf "  Transport             : %s\n"     "$TRANSPORT"
echo "────────────────────────────────────────────"
printf "  Docs indexed (total)  : %s\n"     "$DELTA_DOCS"
printf "    spans               : %s\n"     "$DELTA_SPANS"
printf "    logs                : %s\n"     "$DELTA_LOGS"
printf "    metrics             : %s\n"     "$DELTA_METRICS"
printf "  Throughput            : %s docs/s\n" "$THROUGHPUT"
echo "────────────────────────────────────────────"
printf "  Index failures        : %s\n"     "$DELTA_FAILED"
printf "  Bulk rejections (429) : %s\n"     "$DELTA_REJECTED"
echo "────────────────────────────────────────────"
echo "  Stats over time: $STATS_CSV"
echo "════════════════════════════════════════════"
echo ""

# Exit non-zero if there were unexpected failures (rejections are ok)
[[ "$DELTA_FAILED" -eq 0 ]] || { log "WARNING: $DELTA_FAILED index failures detected."; exit 1; }
