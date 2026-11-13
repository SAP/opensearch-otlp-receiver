#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
GENERATOR_IMAGE="opensearch-otlp-benchmark-generator:local"
BENCHMARK_HTTP_PORT="${BENCHMARK_HTTP_PORT:-19200}"
BENCHMARK_OTLP_PORT="${BENCHMARK_OTLP_PORT:-14317}"
BENCHMARK_DATA_PREPPER_API_PORT="${BENCHMARK_DATA_PREPPER_API_PORT:-14900}"
export BENCHMARK_HTTP_PORT BENCHMARK_OTLP_PORT BENCHMARK_DATA_PREPPER_API_PORT
OS_URL="http://localhost:$BENCHMARK_HTTP_PORT"
INDEX_PATTERN="otel-v1-apm-span*"
SERVICE_MAP_INDEX_PATTERN="otel-v*-apm-service-map*"

SCENARIOS="plugin,dataprepper"
RATES="1000,2500,5000"
WARMUP_SECONDS=30
DURATION_SECONDS=60
REPETITIONS=3
BATCH_SIZE=100
CONCURRENCY=4
DRAIN_TIMEOUT=60
SAMPLE_INTERVAL=2
SERVICE_MAP_SETTLE_SECONDS=5
KEEP=false
SKIP_GENERATOR_BUILD=false
RESULTS_DIR=""

usage() {
    cat <<'USAGE'
Usage: ./load-test/benchmark/run.sh [options]

  --scenarios LIST       plugin,dataprepper (default: both)
  --rates LIST           Target spans/s, comma-separated (default: 1000,2500,5000)
  --warmup SECONDS       Warm-up per scenario (default: 30)
  --duration SECONDS     Measurement duration (default: 60)
  --repetitions N        Runs at each rate (default: 3)
  --batch-size N         Spans per OTLP request (default: 100)
  --concurrency N        Concurrent OTLP clients (default: 4)
  --drain-timeout SEC    Maximum indexing drain wait (default: 60)
  --sample-interval SEC  Resource sampling interval (default: 2)
  --service-map-settle S Wait after span drain for service-map output (default: 5)
  --results-dir PATH     Output directory (default: results/<UTC timestamp>)
  --skip-generator-build Reuse the local benchmark generator image
  --keep                 Leave the final scenario running
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenarios) SCENARIOS="$2"; shift 2 ;;
        --rates) RATES="$2"; shift 2 ;;
        --warmup) WARMUP_SECONDS="$2"; shift 2 ;;
        --duration) DURATION_SECONDS="$2"; shift 2 ;;
        --repetitions) REPETITIONS="$2"; shift 2 ;;
        --batch-size) BATCH_SIZE="$2"; shift 2 ;;
        --concurrency) CONCURRENCY="$2"; shift 2 ;;
        --drain-timeout) DRAIN_TIMEOUT="$2"; shift 2 ;;
        --sample-interval) SAMPLE_INTERVAL="$2"; shift 2 ;;
        --service-map-settle) SERVICE_MAP_SETTLE_SECONDS="$2"; shift 2 ;;
        --results-dir) RESULTS_DIR="$2"; shift 2 ;;
        --skip-generator-build) SKIP_GENERATOR_BUILD=true; shift ;;
        --keep) KEEP=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

for command in docker curl jq python3; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
docker compose version >/dev/null

if [[ -z "$RESULTS_DIR" ]]; then
    RESULTS_DIR="$SCRIPT_DIR/results/$(date -u '+%Y%m%dT%H%M%SZ')"
fi
mkdir -p "$RESULTS_DIR"

PLUGIN_ZIP=$(find "$REPO_ROOT/build/distributions" -maxdepth 1 -name 'opensearch-otlp-receiver-*.zip' -print 2>/dev/null | sort | tail -1)
if [[ -z "$PLUGIN_ZIP" ]]; then
    echo "Plugin zip not found; run ./gradlew assemble first." >&2
    exit 1
fi
export BENCHMARK_PLUGIN_ZIP="$PLUGIN_ZIP"

compose() {
    docker compose --project-name otlp-benchmark -f "$COMPOSE_FILE" "$@"
}

ACTIVE_PROFILE=""
SAMPLER_PID=""
cleanup() {
    if [[ -n "$SAMPLER_PID" ]]; then
        kill "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
    fi
    if [[ "$KEEP" != true && -n "$ACTIVE_PROFILE" ]]; then
        compose --profile "$ACTIVE_PROFILE" down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

# A previous interrupted or --keep run must not leak data into a new comparison.
compose --profile plugin --profile dataprepper down --volumes --remove-orphans >/dev/null 2>&1 || true

if [[ "$SKIP_GENERATOR_BUILD" != true ]]; then
    echo "Building benchmark generator..."
    docker build -t "$GENERATOR_IMAGE" "$SCRIPT_DIR/generator"
fi

wait_for_url() {
    local url="$1" label="$2"
    for _ in $(seq 1 120); do
        if curl -sf "$url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    echo "Timed out waiting for $label" >&2
    return 1
}

wait_for_grpc_port() {
    for _ in $(seq 1 60); do
        if python3 -c "import socket; s=socket.create_connection(('127.0.0.1', $BENCHMARK_OTLP_PORT), 1); s.close()" 2>/dev/null; then
            return 0
        fi
        sleep 2
    done
    echo "Timed out waiting for OTLP/gRPC port $BENCHMARK_OTLP_PORT" >&2
    return 1
}

wait_for_data_prepper() {
    for _ in $(seq 1 120); do
        if curl -sf "http://localhost:$BENCHMARK_DATA_PREPPER_API_PORT/list" >/dev/null 2>&1; then
            return 0
        fi
        if [[ -z "$(compose --profile dataprepper ps --status running -q data-prepper)" ]]; then
            echo "Data Prepper exited during startup. Recent logs:" >&2
            compose --profile dataprepper logs --no-color --tail=80 data-prepper >&2 || true
            return 1
        fi
        sleep 2
    done
    echo "Timed out waiting for Data Prepper core API" >&2
    return 1
}

start_scenario() {
    local scenario="$1"
    if [[ -n "$ACTIVE_PROFILE" ]]; then
        compose --profile "$ACTIVE_PROFILE" down --volumes --remove-orphans >/dev/null
    fi
    ACTIVE_PROFILE="$scenario"
    echo "Starting scenario: $scenario"
    compose --profile "$scenario" up -d
    wait_for_url "$OS_URL/_cluster/health" OpenSearch
    if [[ "$scenario" == dataprepper ]]; then
        wait_for_data_prepper
    fi
    wait_for_grpc_port
}

index_operations() {
    curl -s "$OS_URL/$INDEX_PATTERN/_stats/indexing" |
        jq '[.indices[]?.total.indexing.index_total] | add // 0'
}

live_documents() {
    curl -s -H 'Content-Type: application/json' "$OS_URL/$INDEX_PATTERN/_count" | jq '.count // 0'
}

service_map_operations() {
    curl -s "$OS_URL/$SERVICE_MAP_INDEX_PATTERN/_stats/indexing" |
        jq '[.indices[]?.total.indexing.index_total] | add // 0'
}

service_map_documents() {
    curl -s -H 'Content-Type: application/json' "$OS_URL/$SERVICE_MAP_INDEX_PATTERN/_count" | jq '.count // 0'
}

index_failures() {
    curl -s "$OS_URL/_nodes/stats/indices" |
        jq '[.nodes[].indices.indexing.index_failed] | add // 0'
}

write_rejections() {
    curl -s "$OS_URL/_nodes/stats/thread_pool" |
        jq '[.nodes[].thread_pool.write.rejected] | add // 0'
}

run_generator() {
    local rate="$1" duration="$2" run_id="$3" output="$4"
    docker run --rm --network host "$GENERATOR_IMAGE" \
        --endpoint "localhost:$BENCHMARK_OTLP_PORT" \
        --rate "$rate" \
        --duration "$duration" \
        --batch-size "$BATCH_SIZE" \
        --concurrency "$CONCURRENCY" \
        --run-id "$run_id" | tee "$output"
}

start_sampler() {
    local resource_file="$1" node_file="$2"
    echo 'timestamp,container,cpu_percent,memory_usage' > "$resource_file"
    : > "$node_file"
    (
        while true; do
            timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
            container_ids=$(compose --profile "$ACTIVE_PROFILE" ps -q | tr '\n' ' ')
            if [[ -n "$container_ids" ]]; then
                # Container IDs contain no whitespace; intentional splitting supplies them as arguments.
                # shellcheck disable=SC2086
                docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $container_ids 2>/dev/null |
                    while IFS= read -r sample; do echo "$timestamp,$sample"; done >> "$resource_file"
            fi
            curl -s "$OS_URL/_nodes/stats/jvm,process,indices,thread_pool" |
                jq -c --arg timestamp "$timestamp" '{timestamp: $timestamp, stats: .}' >> "$node_file" || true
            sleep "$SAMPLE_INTERVAL"
        done
    ) &
    SAMPLER_PID=$!
}

stop_sampler() {
    if [[ -n "$SAMPLER_PID" ]]; then
        kill "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
        SAMPLER_PID=""
    fi
}

wait_for_drain() {
    local baseline="$1" expected="$2"
    local started current delta elapsed
    started=$(date +%s)
    while true; do
        current=$(live_documents)
        delta=$((current - baseline))
        elapsed=$(( $(date +%s) - started ))
        if (( delta >= expected || elapsed >= DRAIN_TIMEOUT )); then
            echo "$delta $elapsed"
            return
        fi
        sleep 1
    done
}

SUMMARY_CSV="$RESULTS_DIR/summary.csv"
SUMMARY_JSONL="$RESULTS_DIR/summary.jsonl"
echo 'scenario,target_rate,repetition,attempted_records,accepted_records,rejected_records,failed_requests,source_records_per_second,rpc_p50_ms,rpc_p95_ms,rpc_p99_ms,schedule_lag_p95_ms,index_operations,index_failures,write_rejections,live_documents,drain_seconds,service_map_operations,service_map_live_documents,service_map_settle_seconds,end_to_end_records_per_second,average_total_cpu_percent,peak_total_cpu_percent,average_total_memory_mib,peak_total_memory_mib' > "$SUMMARY_CSV"
: > "$SUMMARY_JSONL"

IFS=',' read -r -a scenario_list <<< "$SCENARIOS"
IFS=',' read -r -a rate_list <<< "$RATES"

for scenario in "${scenario_list[@]}"; do
    if [[ "$scenario" != plugin && "$scenario" != dataprepper ]]; then
        echo "Unsupported scenario: $scenario" >&2
        exit 2
    fi
    start_scenario "$scenario"

    warmup_rate="${rate_list[0]}"
    echo "Warming up $scenario at $warmup_rate spans/s for ${WARMUP_SECONDS}s..."
    warmup_baseline=$(live_documents)
    warmup_service_map_ops=$(service_map_operations)
    run_generator "$warmup_rate" "$WARMUP_SECONDS" "warmup-$scenario-$(date +%s)" "$RESULTS_DIR/${scenario}-warmup.json" >/dev/null
    warmup_accepted=$(jq '.accepted_records' "$RESULTS_DIR/${scenario}-warmup.json")
    read -r warmup_indexed warmup_drain < <(wait_for_drain "$warmup_baseline" "$warmup_accepted")
    if (( warmup_indexed < warmup_accepted )); then
        echo "Warm-up drain timed out: accepted=$warmup_accepted indexed=$warmup_indexed" >&2
        exit 1
    fi
    echo "Warm-up complete: indexed=$warmup_indexed drain=${warmup_drain}s"
    sleep "$SERVICE_MAP_SETTLE_SECONDS"
    warmup_service_map_delta=$(( $(service_map_operations) - warmup_service_map_ops ))
    if (( warmup_service_map_delta <= 0 )); then
        echo "Warm-up produced no service-map indexing operations for $scenario" >&2
        exit 1
    fi
    echo "Service-map warm-up complete: operations=$warmup_service_map_delta"
    curl -s -X POST "$OS_URL/$INDEX_PATTERN/_refresh" >/dev/null 2>&1 || true

    for rate in "${rate_list[@]}"; do
        for repetition in $(seq 1 "$REPETITIONS"); do
            run_name="${scenario}-r${rate}-n${repetition}"
            echo "Running $run_name for ${DURATION_SECONDS}s..."
            run_dir="$RESULTS_DIR/$run_name"
            mkdir -p "$run_dir"

            curl -s -X POST "$OS_URL/$INDEX_PATTERN/_refresh" >/dev/null 2>&1 || true
            baseline_ops=$(index_operations)
            baseline_docs=$(live_documents)
            baseline_service_map_ops=$(service_map_operations)
            baseline_service_map_docs=$(service_map_documents)
            baseline_failures=$(index_failures)
            baseline_rejections=$(write_rejections)

            start_sampler "$run_dir/resources.csv" "$run_dir/opensearch-nodes.jsonl"
            run_generator "$rate" "$DURATION_SECONDS" "$run_name-$(date +%s)" "$run_dir/generator.json" >/dev/null
            accepted=$(jq '.accepted_records' "$run_dir/generator.json")
            read -r docs_delta drain_seconds < <(wait_for_drain "$baseline_docs" "$accepted")
            sleep "$SERVICE_MAP_SETTLE_SECONDS"
            stop_sampler

            final_ops=$(index_operations)
            final_failures=$(index_failures)
            final_rejections=$(write_rejections)
            ops_delta=$((final_ops - baseline_ops))
            failures_delta=$((final_failures - baseline_failures))
            rejections_delta=$((final_rejections - baseline_rejections))
            service_map_ops_delta=$(( $(service_map_operations) - baseline_service_map_ops ))
            service_map_docs_delta=$(( $(service_map_documents) - baseline_service_map_docs ))
            if (( service_map_ops_delta <= 0 )); then
                echo "Run produced no service-map indexing operations: $run_name" >&2
                exit 1
            fi

            python3 "$SCRIPT_DIR/summarize_resources.py" "$run_dir/resources.csv" > "$run_dir/resources-summary.json"
            generator_elapsed=$(jq '.duration_seconds' "$run_dir/generator.json")
            end_to_end_rps=$(awk -v docs="$docs_delta" -v elapsed="$generator_elapsed" -v drain="$drain_seconds" 'BEGIN { if (elapsed + drain > 0) printf "%.3f", docs / (elapsed + drain); else print 0 }')

            record=$(jq -n \
                --arg scenario "$scenario" --argjson rate "$rate" --argjson repetition "$repetition" \
                --argjson ops "$ops_delta" --argjson failures "$failures_delta" \
                --argjson rejections "$rejections_delta" --argjson docs "$docs_delta" \
                --argjson drain "$drain_seconds" --argjson end_to_end_rps "$end_to_end_rps" \
                --argjson service_map_ops "$service_map_ops_delta" \
                --argjson service_map_docs "$service_map_docs_delta" \
                --argjson service_map_settle "$SERVICE_MAP_SETTLE_SECONDS" \
                --slurpfile generator "$run_dir/generator.json" \
                --slurpfile resources "$run_dir/resources-summary.json" \
                '{scenario: $scenario, target_rate: $rate, repetition: $repetition} + $generator[0] + {
                    index_operations: $ops, index_failures: $failures, write_rejections: $rejections,
                    live_documents: $docs, drain_seconds: $drain,
                    service_map_operations: $service_map_ops,
                    service_map_live_documents: $service_map_docs,
                    service_map_settle_seconds: $service_map_settle,
                    end_to_end_records_per_second: $end_to_end_rps
                } + $resources[0]')
            echo "$record" | jq -c . >> "$SUMMARY_JSONL"
            echo "$record" | jq -r '[
                .scenario, .target_rate, .repetition, .attempted_records, .accepted_records,
                .rejected_records, .failed_requests, .accepted_records_per_second,
                .request_latency_ms.p50, .request_latency_ms.p95, .request_latency_ms.p99,
                .schedule_lag_ms.p95,
                .index_operations, .index_failures, .write_rejections, .live_documents,
                .drain_seconds, .service_map_operations, .service_map_live_documents,
                .service_map_settle_seconds, .end_to_end_records_per_second, .average_total_cpu_percent,
                .peak_total_cpu_percent, .average_total_memory_mib, .peak_total_memory_mib
            ] | @csv' >> "$SUMMARY_CSV"
            echo "  accepted=$accepted indexed=$docs_delta service_map_ops=$service_map_ops_delta p95=$(echo "$record" | jq -r '.request_latency_ms.p95')ms failures=$failures_delta rejections=$rejections_delta"
        done
    done
done

echo "Benchmark complete: $SUMMARY_CSV"
