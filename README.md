[![REUSE status](https://api.reuse.software/badge/github.com/SAP/opensearch-otlp-receiver)](https://api.reuse.software/info/github.com/SAP/opensearch-otlp-receiver)

# OpenSearch OTLP Receiver

> **Status: Proof of Concept.** This project is not production-ready. APIs, index mappings, and configuration settings may change without notice.

## About this project

Embeds OTLP ingestion directly into OpenSearch, letting OpenTelemetry SDKs and Collectors send traces, metrics, and logs without an intermediary like Data Prepper.

Today, ingesting OTLP data into OpenSearch requires running Data Prepper as a separate process. While Data Prepper is powerful and flexible, simpler deployments benefit from eliminating the extra hop. This plugin embeds OTLP ingestion directly into OpenSearch, reducing operational overhead for straightforward observability pipelines.

**Features:**

- **gRPC endpoint** (port 4317) — standard OTLP/gRPC for all three signals
- **HTTP endpoint** (port 4318) — OTLP/HTTP with protobuf and JSON content types
- **All OTLP signals** — traces, metrics, and logs
- **SS4O-compatible** — documents are indexed using the [Simple Schema for Observability](https://github.com/opensearch-project/opensearch-catalog/tree/main/docs/schema/observability) format, compatible with OpenSearch Dashboards Observability
- **Auto-provisioned index templates** — SS4O index templates are created on plugin startup

The plugin runs its own embedded gRPC and HTTP servers on dedicated ports (separate from OpenSearch's REST API). Incoming OTLP data is transformed into SS4O-compatible documents and bulk-indexed into OpenSearch:

- Traces to `otel-v1-apm-span-*`
- Logs to `logs-otel-v1-*`
- Metrics to `metrics-otel-v1-*`

## Requirements

- **[OpenSearch](https://opensearch.org/downloads.html) 3.6.0** — the plugin is built against and tested with this version
- **Java 21** — required to build the plugin from source
- **[Docker](https://www.docker.com/) and Docker Compose** — required to run the sample setup and load tests
- **[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) or any OTLP-compatible SDK** — to send telemetry data to the plugin

## Download and Installation

### Build from source

```bash
git clone https://github.com/SAP/opensearch-otlp-receiver.git
cd opensearch-otlp-receiver
./gradlew assemble
```

The plugin zip is created at `build/distributions/opensearch-otlp-receiver-<version>.zip`.

### Install into OpenSearch

```bash
bin/opensearch-plugin install file:///path/to/opensearch-otlp-receiver-<version>.zip
```

### Quick start with Docker

A ready-to-use Docker Compose setup is provided in the `samples/` directory:

```bash
./gradlew assemble
docker compose -f samples/docker-compose.yml up
```

This starts OpenSearch (with the plugin installed), OpenSearch Dashboards, and an OpenTelemetry Collector preconfigured to forward to the plugin.

## Configuration

Enable the plugin by adding the following to `opensearch.yml`:

```yaml
plugins.otlp.enabled: true
plugins.otlp.port: 4317          # gRPC port
plugins.otlp.http.port: 4318     # HTTP port
plugins.otlp.bind_host: 0.0.0.0
```

All available settings:

| Setting | Default | Description |
|---|---|---|
| `plugins.otlp.enabled` | `false` | Enable the plugin |
| `plugins.otlp.bind_host` | `0.0.0.0` | Host to bind the OTLP servers to |
| `plugins.otlp.grpc.port` | `4317` | gRPC server port |
| `plugins.otlp.http.port` | `4318` | HTTP server port |
| `plugins.otlp.index.traces` | `otel-v1-apm-span` | Write alias/index for traces |
| `plugins.otlp.index.metrics` | `metrics-otel-v1` | Write alias/index for metrics |
| `plugins.otlp.index.logs` | `logs-otel-v1` | Write alias/index for logs |
| `plugins.otlp.index.require_alias` | `false` | Reject writes to bare indices (enforce alias routing) |
| `plugins.otlp.max_inflight_requests` | `64` | Maximum concurrent bulk index requests; excess returns HTTP 429 / gRPC RESOURCE_EXHAUSTED |
| `plugins.otlp.max_request_bytes` | `4194304` | Maximum request body size in bytes (default 4 MiB) |
| `plugins.otlp.compression.enabled` | `true` | Accept gzip-compressed requests |

Point your OpenTelemetry Collector or SDK at the plugin's ports:

```yaml
# otel-collector-config.yaml
exporters:
  otlp:
    endpoint: "opensearch-host:4317"
    tls:
      insecure: true
  otlphttp:
    endpoint: "http://opensearch-host:4318"
```

### Plugin Metrics

The plugin emits the following metrics via OpenSearch's telemetry framework:

| Metric | Type | Description |
|---|---|---|
| `otlp.rejected_requests` | Counter | Number of requests rejected because `max_inflight_requests` was reached |
| `otlp.inflight_requests` | UpDownCounter | Current number of bulk index requests in flight |

Metrics require the `telemetry-otel` plugin (included with OpenSearch) and two settings in `opensearch.yml` (node-scoped; requires restart):

```yaml
telemetry.feature.metrics.enabled: true
telemetry.otel.metrics.exporter.class: io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
```

| Exporter class | Destination |
|---|---|
| `io.opentelemetry.sdk.metrics.export.LoggingMetricExporter` | OpenSearch log (default) |
| `io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter` | OTLP/gRPC collector |
| `io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter` | OTLP/HTTP collector |
| `io.opentelemetry.exporter.prometheus.PrometheusMetricReader` | Prometheus scrape endpoint |

## Limitations

- This project is a proof of concept and is not production-ready.
- Security (TLS, authentication) is not yet implemented for the OTLP endpoints.
- Only tested with OpenSearch 3.6.0.

## Support, Feedback, Contributing

This project is open to feature requests, suggestions, and bug reports via [GitHub issues](https://github.com/SAP/opensearch-otlp-receiver/issues). Contribution and feedback are encouraged and always welcome. For more information about how to contribute, the project structure, as well as additional contribution information, see our [Contribution Guidelines](CONTRIBUTING.md).

## Security / Disclosure

If you find any bug that may be a security problem, please follow our instructions at [in our security policy](https://github.com/SAP/opensearch-otlp-receiver/security/policy) on how to report it. Please do not create GitHub issues for security-related doubts or problems.

## Code of Conduct

We as members, contributors, and leaders pledge to make participation in our community a harassment-free experience for everyone. By participating in this project, you agree to abide by its [Code of Conduct](https://github.com/SAP/.github/blob/main/CODE_OF_CONDUCT.md) at all times.

## Licensing

Copyright 2026 SAP SE or an SAP affiliate company and opensearch-otlp-receiver contributors. Please see our [LICENSE](LICENSE) for copyright and license information. Detailed information including third-party components and their licensing/copyright information is available [via the REUSE tool](https://api.reuse.software/info/github.com/SAP/opensearch-otlp-receiver).
