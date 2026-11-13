# OTLP ingestion benchmark

This harness compares OTLP/gRPC trace ingestion and service-map generation through
two paths:

1. `generator -> OpenSearch OTLP receiver plugin -> OpenSearch`
2. `generator -> Data Prepper raw + service-map pipelines -> OpenSearch`

The generator creates correlated frontend client and backend server spans, allowing
both implementations to build real service-map edges. Each span has deterministic,
run-specific trace and span IDs, which prevents overwrites from inflating
indexing-operation throughput. Both scenarios use the same OpenSearch version, heap,
CPU limit, memory limit, index family, and security settings.

Data Prepper 2.17.0 is pinned because it is the current `latest` release and publishes
native AMD64 and ARM64 images. Its trace processor, service-map processor, and
OpenSearch sinks use one-second windows/flush intervals. Longer production defaults
would dominate short benchmark drain measurements. The plugin uses the same
one-second service-map interval. Keep these values unchanged when comparing runs.

## Run

Build the plugin first, then execute the benchmark:

```bash
./gradlew assemble
./load-test/benchmark/run.sh
```

A short validation run is useful after changing the harness:

```bash
./load-test/benchmark/run.sh \
  --rates 100 \
  --warmup 5 \
  --duration 10 \
  --repetitions 1
```

Results are written below `load-test/benchmark/results/<timestamp>/` and include:

- `summary.csv` and `summary.jsonl` for cross-scenario comparison;
- generator counts, OTLP RPC failures, p50/p95/p99 response latency, and pacing lag;
- unique live-document and indexing-operation deltas;
- service-map indexing operations and live-document deltas;
- time required for accepted spans to become searchable;
- total CPU and memory across all containers in each scenario;
- raw per-container and OpenSearch node samples for later analysis.

Run `./load-test/benchmark/run.sh --help` for rate, duration, repetition, batching,
resource sampling, and lifecycle options. Image and resource defaults can be
overridden with `OPENSEARCH_VERSION`, `DATA_PREPPER_VERSION`, `OPENSEARCH_CPUS`,
`OPENSEARCH_MEMORY`, `DATA_PREPPER_CPUS`, and `DATA_PREPPER_MEMORY`. The isolated
host ports default to `19200`, `14317`, and `14900`; override them with
`BENCHMARK_HTTP_PORT`, `BENCHMARK_OTLP_PORT`, and
`BENCHMARK_DATA_PREPPER_API_PORT`.

## Interpreting the results

`accepted_records_per_second` and the RPC latency percentiles measure receiver
acknowledgement. `end_to_end_records_per_second` includes the post-generation wait
until accepted spans are searchable. This distinction matters because buffering
receivers can acknowledge before their OpenSearch sink has drained.

Increase the rates until latency, drain time, errors, or rejections rise sharply.
That throughput-versus-latency curve is more useful than a single maximum number.
Use multiple repetitions and compare medians; avoid running other heavy workloads
on the Docker host during a benchmark.

`schedule_lag_ms` shows when the configured clients can no longer maintain the
requested pacing. Increase `--concurrency` if schedule lag rises while receiver CPU
still has headroom; otherwise treat rising lag as another saturation signal.

The benchmark intentionally covers traces and their service-map output. Data Prepper
is the closest functional baseline for this transformation. Collector exporters do
not necessarily produce the same schema or service-map behavior, so a Collector path
would answer a different, relay-overhead question.
