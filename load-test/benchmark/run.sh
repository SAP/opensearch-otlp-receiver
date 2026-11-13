#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
BENCHMARK_PLUGIN_CONFIG="${BENCHMARK_PLUGIN_CONFIG:-$SCRIPT_DIR/opensearch-plugin.yml}"
GENERATOR_IMAGE="opensearch-otlp-benchmark-generator:local"
BENCHMARK_HTTP_PORT="${BENCHMARK_HTTP_PORT:-19200}"
BENCHMARK_OTLP_PORT="${BENCHMARK_OTLP_PORT:-14317}"
BENCHMARK_OTLP_SECONDARY_PORT="${BENCHMARK_OTLP_SECONDARY_PORT:-14318}"
BENCHMARK_DATA_PREPPER_API_PORT="${BENCHMARK_DATA_PREPPER_API_PORT:-14900}"
BENCHMARK_DATA_PREPPER_SECONDARY_API_PORT="${BENCHMARK_DATA_PREPPER_SECONDARY_API_PORT:-14901}"
BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED="${BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED:-true}"
export BENCHMARK_HTTP_PORT BENCHMARK_OTLP_PORT BENCHMARK_OTLP_SECONDARY_PORT
export BENCHMARK_DATA_PREPPER_API_PORT BENCHMARK_DATA_PREPPER_SECONDARY_API_PORT
export BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED BENCHMARK_PLUGIN_CONFIG
OS_URL="http://localhost:$BENCHMARK_HTTP_PORT"
INDEX_PATTERN="otel-v1-apm-span*"
SERVICE_MAP_INDEX_PATTERN="otel-v*-apm-service-map*"

SCENARIOS="plugin,dataprepper"
RATES="1000,2500,5000,10000"
WARMUP_SECONDS=30
DURATION_SECONDS=60
REPETITIONS=3
BATCH_SIZE=100
CONCURRENCY=4
DRAIN_TIMEOUT=60
PEER_DRAIN_TIMEOUT=300
SAMPLE_INTERVAL=2
SERVICE_MAP_SETTLE_SECONDS=5
KEEP=false
SKIP_GENERATOR_BUILD=false
ENABLE_JFR=false
RESULTS_DIR=""

usage() {
    cat <<'USAGE'
Usage: ./load-test/benchmark/run.sh [options]

  --scenarios LIST       plugin,dataprepper,dataprepper-peer (default: first two)
  --rates LIST           Target spans/s, comma-separated (default: 1000,2500,5000,10000)
  --warmup SECONDS       Warm-up per scenario (default: 30)
  --duration SECONDS     Measurement duration (default: 60)
  --repetitions N        Runs at each rate (default: 3)
  --batch-size N         Spans per OTLP request (default: 100)
  --concurrency N        Concurrent OTLP clients (default: 4)
  --drain-timeout SEC    Maximum indexing drain wait (default: 60)
  --peer-drain-timeout S Two-peer indexing drain wait (default: 300)
  --sample-interval SEC  Resource sampling interval (default: 2)
  --service-map-settle S Wait after span drain for service-map output (default: 5)
  --disable-plugin-service-map
                         Disable plugin service-map generation for an A/B run
  --results-dir PATH     Output directory (default: results/<UTC timestamp>)
  --skip-generator-build Reuse the local benchmark generator image
  --jfr                  Record plugin CPU/profile events during measured load
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
        --peer-drain-timeout) PEER_DRAIN_TIMEOUT="$2"; shift 2 ;;
        --sample-interval) SAMPLE_INTERVAL="$2"; shift 2 ;;
        --service-map-settle) SERVICE_MAP_SETTLE_SECONDS="$2"; shift 2 ;;
        --disable-plugin-service-map)
            BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED=false
            BENCHMARK_PLUGIN_CONFIG="$SCRIPT_DIR/opensearch-plugin-no-service-map.yml"
            export BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED BENCHMARK_PLUGIN_CONFIG
            shift
            ;;
        --results-dir) RESULTS_DIR="$2"; shift 2 ;;
        --skip-generator-build) SKIP_GENERATOR_BUILD=true; shift ;;
        --jfr) ENABLE_JFR=true; shift ;;
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
JFR_ACTIVE=false
JFR_CONTAINER_ID=""
JFR_JAVA_PID=""
JFR_OUTPUT=""
JFR_CONTAINER_FILE="/tmp/otlp-benchmark.jfr"
JFR_NAME="otlp_benchmark"

stop_jfr() {
    if [[ "$JFR_ACTIVE" != true ]]; then
        return
    fi
    local jcmd="/usr/share/opensearch/jdk/bin/jcmd"
    docker exec "$JFR_CONTAINER_ID" "$jcmd" "$JFR_JAVA_PID" \
        JFR.stop name="$JFR_NAME" filename="$JFR_CONTAINER_FILE" >/dev/null
    docker cp "$JFR_CONTAINER_ID:$JFR_CONTAINER_FILE" "$JFR_OUTPUT" >/dev/null
    JFR_ACTIVE=false
    echo "  JFR recording: $JFR_OUTPUT"
}

start_jfr() {
    JFR_OUTPUT="$1"
    JFR_CONTAINER_ID=$(compose --profile plugin ps -q opensearch-plugin)
    local jcmd="/usr/share/opensearch/jdk/bin/jcmd"
    JFR_JAVA_PID=$(docker exec "$JFR_CONTAINER_ID" "$jcmd" -l |
        awk '/org.opensearch.bootstrap.OpenSearch/ { print $1; exit }')
    if [[ -z "$JFR_JAVA_PID" ]]; then
        echo "Unable to locate the OpenSearch JVM for JFR" >&2
        return 1
    fi
    docker exec "$JFR_CONTAINER_ID" "$jcmd" "$JFR_JAVA_PID" \
        JFR.start name="$JFR_NAME" settings=profile disk=true maxsize=512m >/dev/null
    JFR_ACTIVE=true
}

cleanup() {
    if [[ "$JFR_ACTIVE" == true ]]; then
        stop_jfr 2>/dev/null || true
    fi
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
compose --profile plugin --profile dataprepper --profile dataprepper-peer \
    down --volumes --remove-orphans >/dev/null 2>&1 || true

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
    local port="$1"
    for _ in $(seq 1 60); do
        if python3 -c "import socket; s=socket.create_connection(('127.0.0.1', $port), 1); s.close()" 2>/dev/null; then
            return 0
        fi
        sleep 2
    done
    echo "Timed out waiting for OTLP/gRPC port $port" >&2
    return 1
}

wait_for_data_prepper() {
    local service="$1" port="$2"
    for _ in $(seq 1 120); do
        if curl -sf "http://localhost:$port/list" >/dev/null 2>&1; then
            return 0
        fi
        if [[ -z "$(compose --profile "$ACTIVE_PROFILE" ps --status running -q "$service")" ]]; then
            echo "$service exited during startup. Recent logs:" >&2
            compose --profile "$ACTIVE_PROFILE" logs --no-color --tail=80 "$service" >&2 || true
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
        wait_for_data_prepper data-prepper "$BENCHMARK_DATA_PREPPER_API_PORT"
    elif [[ "$scenario" == dataprepper-peer ]]; then
        wait_for_data_prepper data-prepper-peer-1 "$BENCHMARK_DATA_PREPPER_API_PORT"
        wait_for_data_prepper data-prepper-peer-2 "$BENCHMARK_DATA_PREPPER_SECONDARY_API_PORT"
    fi
    wait_for_grpc_port "$BENCHMARK_OTLP_PORT"
    if [[ "$scenario" == dataprepper-peer ]]; then
        wait_for_grpc_port "$BENCHMARK_OTLP_SECONDARY_PORT"
    fi
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

capture_peer_forwarder_metrics() {
    local prefix="$1"
    curl -sf "http://localhost:$BENCHMARK_DATA_PREPPER_API_PORT/metrics/prometheus" \
        > "${prefix}-peer-1.prom"
    curl -sf "http://localhost:$BENCHMARK_DATA_PREPPER_SECONDARY_API_PORT/metrics/prometheus" \
        > "${prefix}-peer-2.prom"
}

peer_forwarder_metric() {
    local prefix="$1" metric="$2" format="${3:-decimal}"
    awk -v metric="$metric" -v format="$format" '
        $1 ~ ("^" metric "(\\{|$)") { total += $2 }
        END {
            if (format == "integer") printf "%.0f\n", total + 0
            else printf "%.12g\n", total + 0
        }
    ' "${prefix}-peer-1.prom" "${prefix}-peer-2.prom"
}

run_generator() {
    local rate="$1" duration="$2" run_id="$3" output="$4"
    local endpoint_args=(--endpoint "localhost:$BENCHMARK_OTLP_PORT")
    if [[ "$ACTIVE_PROFILE" == dataprepper-peer ]]; then
        endpoint_args+=(--endpoint "localhost:$BENCHMARK_OTLP_SECONDARY_PORT")
    fi
    docker run --rm --network host "$GENERATOR_IMAGE" \
        "${endpoint_args[@]}" \
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
    local baseline="$1" expected="$2" timeout="${3:-$DRAIN_TIMEOUT}"
    local started current delta elapsed
    started=$(date +%s)
    while true; do
        current=$(live_documents)
        delta=$((current - baseline))
        elapsed=$(( $(date +%s) - started ))
        if (( delta >= expected || elapsed >= timeout )); then
            echo "$delta $elapsed"
            return
        fi
        sleep 1
    done
}

SUMMARY_CSV="$RESULTS_DIR/summary.csv"
SUMMARY_JSONL="$RESULTS_DIR/summary.jsonl"
echo 'scenario,service_map_enabled,target_rate,repetition,offered_records,attempted_records,unsent_records,accepted_records,rejected_records,failed_requests,source_records_per_second,rpc_p50_ms,rpc_p95_ms,rpc_p99_ms,schedule_lag_p95_ms,index_operations,index_failures,write_rejections,live_documents,drain_seconds,drain_complete,service_map_operations,service_map_live_documents,service_map_settle_seconds,peer_forwarded_records,peer_forwarding_failed_records,peer_forwarding_successful_requests,peer_forwarding_failed_requests,peer_forwarding_request_timeouts,peer_forwarding_requests_too_large,peer_forwarding_average_latency_ms,end_to_end_records_per_second,average_total_cpu_percent,peak_total_cpu_percent,average_total_memory_mib,peak_total_memory_mib' > "$SUMMARY_CSV"
: > "$SUMMARY_JSONL"

IFS=',' read -r -a scenario_list <<< "$SCENARIOS"
IFS=',' read -r -a rate_list <<< "$RATES"

for scenario in "${scenario_list[@]}"; do
    if [[ "$scenario" != plugin && "$scenario" != dataprepper && "$scenario" != dataprepper-peer ]]; then
        echo "Unsupported scenario: $scenario" >&2
        exit 2
    fi
    start_scenario "$scenario"
    service_map_expected=true
    if [[ "$scenario" == plugin && "$BENCHMARK_PLUGIN_SERVICE_MAP_ENABLED" != true ]]; then
        service_map_expected=false
    fi
    scenario_drain_timeout="$DRAIN_TIMEOUT"
    if [[ "$scenario" == dataprepper-peer ]]; then
        scenario_drain_timeout="$PEER_DRAIN_TIMEOUT"
    fi

    warmup_rate="${rate_list[0]}"
    echo "Warming up $scenario at $warmup_rate spans/s for ${WARMUP_SECONDS}s..."
    warmup_baseline=$(live_documents)
    warmup_service_map_ops=$(service_map_operations)
    run_generator "$warmup_rate" "$WARMUP_SECONDS" "warmup-$scenario-$(date +%s)" "$RESULTS_DIR/${scenario}-warmup.json" >/dev/null
    warmup_accepted=$(jq '.accepted_records' "$RESULTS_DIR/${scenario}-warmup.json")
    read -r warmup_indexed warmup_drain < <(wait_for_drain \
        "$warmup_baseline" "$warmup_accepted" "$scenario_drain_timeout")
    if (( warmup_indexed < warmup_accepted )); then
        echo "Warm-up drain timed out: accepted=$warmup_accepted indexed=$warmup_indexed" >&2
        exit 1
    fi
    echo "Warm-up complete: indexed=$warmup_indexed drain=${warmup_drain}s"
    sleep "$SERVICE_MAP_SETTLE_SECONDS"
    warmup_service_map_delta=$(( $(service_map_operations) - warmup_service_map_ops ))
    if [[ "$service_map_expected" == true ]] && (( warmup_service_map_delta <= 0 )); then
        echo "Warm-up produced no service-map indexing operations for $scenario" >&2
        exit 1
    fi
    echo "Service-map warm-up complete: enabled=$service_map_expected operations=$warmup_service_map_delta"
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
            if [[ "$scenario" == dataprepper-peer ]]; then
                capture_peer_forwarder_metrics "$run_dir/peer-forwarder-before"
            fi

            start_sampler "$run_dir/resources.csv" "$run_dir/opensearch-nodes.jsonl"
            if [[ "$ENABLE_JFR" == true && "$scenario" == plugin ]]; then
                start_jfr "$run_dir/plugin.jfr"
            fi
            run_generator "$rate" "$DURATION_SECONDS" "$run_name-$(date +%s)" "$run_dir/generator.json" >/dev/null
            if [[ "$JFR_ACTIVE" == true ]]; then
                stop_jfr
            fi
            accepted=$(jq '.accepted_records' "$run_dir/generator.json")
            read -r docs_delta drain_seconds < <(wait_for_drain \
                "$baseline_docs" "$accepted" "$scenario_drain_timeout")
            drain_complete=true
            if (( docs_delta < accepted )); then
                drain_complete=false
            fi
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
            if [[ "$service_map_expected" == true ]] && (( service_map_ops_delta <= 0 )); then
                echo "Run produced no service-map indexing operations: $run_name" >&2
                exit 1
            fi

            peer_forwarded_delta=0
            peer_failed_records_delta=0
            peer_successful_requests_delta=0
            peer_failed_requests_delta=0
            peer_request_timeouts_delta=0
            peer_requests_too_large_delta=0
            peer_average_latency_ms=0
            if [[ "$scenario" == dataprepper-peer ]]; then
                capture_peer_forwarder_metrics "$run_dir/peer-forwarder-after"
                forwarded_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_recordsSuccessfullyForwarded_total integer)
                forwarded_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_recordsSuccessfullyForwarded_total integer)
                failed_records_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_recordsFailedForwarding_total integer)
                failed_records_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_recordsFailedForwarding_total integer)
                successful_requests_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_requestsSuccessful_total integer)
                successful_requests_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_requestsSuccessful_total integer)
                failed_requests_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_requestsFailed_total integer)
                failed_requests_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_requestsFailed_total integer)
                request_timeouts_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_requestTimeouts_total integer)
                request_timeouts_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_requestTimeouts_total integer)
                requests_too_large_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_requestsTooLarge_total integer)
                requests_too_large_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_requestsTooLarge_total integer)
                latency_sum_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_clientRequestForwardingLatency_seconds_sum)
                latency_sum_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_clientRequestForwardingLatency_seconds_sum)
                latency_count_before=$(peer_forwarder_metric "$run_dir/peer-forwarder-before" \
                    core_peerForwarder_clientRequestForwardingLatency_seconds_count integer)
                latency_count_after=$(peer_forwarder_metric "$run_dir/peer-forwarder-after" \
                    core_peerForwarder_clientRequestForwardingLatency_seconds_count integer)

                peer_forwarded_delta=$((forwarded_after - forwarded_before))
                peer_failed_records_delta=$((failed_records_after - failed_records_before))
                peer_successful_requests_delta=$((successful_requests_after - successful_requests_before))
                peer_failed_requests_delta=$((failed_requests_after - failed_requests_before))
                peer_request_timeouts_delta=$((request_timeouts_after - request_timeouts_before))
                peer_requests_too_large_delta=$((requests_too_large_after - requests_too_large_before))
                peer_average_latency_ms=$(awk \
                    -v before_sum="$latency_sum_before" -v after_sum="$latency_sum_after" \
                    -v before_count="$latency_count_before" -v after_count="$latency_count_after" \
                    'BEGIN { count = after_count - before_count; if (count > 0) printf "%.3f", 1000 * (after_sum - before_sum) / count; else print 0 }')
            fi

            python3 "$SCRIPT_DIR/summarize_resources.py" "$run_dir/resources.csv" > "$run_dir/resources-summary.json"
            generator_elapsed=$(jq '.duration_seconds' "$run_dir/generator.json")
            end_to_end_rps=$(awk -v docs="$docs_delta" -v elapsed="$generator_elapsed" -v drain="$drain_seconds" 'BEGIN { if (elapsed + drain > 0) printf "%.3f", docs / (elapsed + drain); else print 0 }')

            record=$(jq -n \
                --arg scenario "$scenario" --argjson rate "$rate" --argjson repetition "$repetition" \
                --argjson service_map_enabled "$service_map_expected" \
                --argjson ops "$ops_delta" --argjson failures "$failures_delta" \
                --argjson rejections "$rejections_delta" --argjson docs "$docs_delta" \
                --argjson drain "$drain_seconds" --argjson end_to_end_rps "$end_to_end_rps" \
                --argjson drain_complete "$drain_complete" \
                --argjson service_map_ops "$service_map_ops_delta" \
                --argjson service_map_docs "$service_map_docs_delta" \
                --argjson service_map_settle "$SERVICE_MAP_SETTLE_SECONDS" \
                --argjson peer_forwarded "$peer_forwarded_delta" \
                --argjson peer_failed_records "$peer_failed_records_delta" \
                --argjson peer_successful_requests "$peer_successful_requests_delta" \
                --argjson peer_failed_requests "$peer_failed_requests_delta" \
                --argjson peer_request_timeouts "$peer_request_timeouts_delta" \
                --argjson peer_requests_too_large "$peer_requests_too_large_delta" \
                --argjson peer_average_latency_ms "$peer_average_latency_ms" \
                --slurpfile generator "$run_dir/generator.json" \
                --slurpfile resources "$run_dir/resources-summary.json" \
                '{scenario: $scenario, service_map_enabled: $service_map_enabled, target_rate: $rate, repetition: $repetition} + $generator[0] + {
                    index_operations: $ops, index_failures: $failures, write_rejections: $rejections,
                    live_documents: $docs, drain_seconds: $drain, drain_complete: $drain_complete,
                    service_map_operations: $service_map_ops,
                    service_map_live_documents: $service_map_docs,
                    service_map_settle_seconds: $service_map_settle,
                    peer_forwarded_records: $peer_forwarded,
                    peer_forwarding_failed_records: $peer_failed_records,
                    peer_forwarding_successful_requests: $peer_successful_requests,
                    peer_forwarding_failed_requests: $peer_failed_requests,
                    peer_forwarding_request_timeouts: $peer_request_timeouts,
                    peer_forwarding_requests_too_large: $peer_requests_too_large,
                    peer_forwarding_average_latency_ms: $peer_average_latency_ms,
                    end_to_end_records_per_second: $end_to_end_rps
                } + $resources[0]')
            echo "$record" | jq -c . >> "$SUMMARY_JSONL"
            echo "$record" | jq -r '[
                .scenario, .service_map_enabled, .target_rate, .repetition, .offered_records, .attempted_records,
                .unsent_records, .accepted_records,
                .rejected_records, .failed_requests, .accepted_records_per_second,
                .request_latency_ms.p50, .request_latency_ms.p95, .request_latency_ms.p99,
                .schedule_lag_ms.p95,
                .index_operations, .index_failures, .write_rejections, .live_documents,
                .drain_seconds, .drain_complete, .service_map_operations, .service_map_live_documents,
                .service_map_settle_seconds, .peer_forwarded_records,
                .peer_forwarding_failed_records, .peer_forwarding_successful_requests,
                .peer_forwarding_failed_requests, .peer_forwarding_request_timeouts,
                .peer_forwarding_requests_too_large, .peer_forwarding_average_latency_ms,
                .end_to_end_records_per_second, .average_total_cpu_percent,
                .peak_total_cpu_percent, .average_total_memory_mib, .peak_total_memory_mib
            ] | @csv' >> "$SUMMARY_CSV"
            echo "  accepted=$accepted indexed=$docs_delta service_map_ops=$service_map_ops_delta p95=$(echo "$record" | jq -r '.request_latency_ms.p95')ms failures=$failures_delta rejections=$rejections_delta"
            if [[ "$drain_complete" != true ]]; then
                echo "Run drain timed out; stopping to prevent backlog from contaminating later runs: $run_name" >&2
                exit 1
            fi
        done
    done
done

echo "Benchmark complete: $SUMMARY_CSV"
