# Distributed service-map creation

Service-map creation runs as 16 stable OpenSearch persistent tasks. Each task owns
one hexadecimal `traceId` prefix (`0` through `f`), so every span belonging to a
trace is processed by the same task.

## Assignment and failover

Every plugin node registers the task executor. The elected cluster-manager node
maintains one persistent task per prefix, and OpenSearch assigns those tasks to
eligible data nodes. A node can own multiple prefixes.

When an assigned node leaves, OpenSearch reassigns its tasks to surviving nodes.
Adding a node does not move healthy tasks automatically; it receives new or
previously unassigned work. This avoids interrupting active processing merely to
rebalance it.

## Processing

Each task runs immediately after assignment and then at the configured service-map
interval. Every ingested span carries an `indexedAt` timestamp. A task discovers
spans from its durable `(indexedAt, document ID)` cursor with a short overlap,
collects the affected trace IDs, and fetches the complete spans for those traces
before reconciliation. The cursor advances only after the service-map write
succeeds. See the [ServiceMapBuilder activity diagram](service-map-builder-activity.md)
for the complete run and failure flow.

Each run collects observations in a unit of work. Document IDs are hashes of the
source and target nodes, source and target operations, observation kind, and
event-time bucket. Equivalent traces therefore produce one edge or leaf per
relationship and bucket. The same relationship in a later bucket creates another
document, preserving service-map history.

Leaves and edges are append-only observations. A late span can add an edge or
corrected operation detail but does not delete an earlier leaf. Dashboards
deduplicates topology by `nodeConnectionHash` and operation detail by
`operationConnectionHash` within the selected time range; a leaf contributes a node
but no edge. A short-lived in-memory write cache suppresses identical writes caused
by cursor overlap. A per-task guard prevents overlapping runs.

Cursor IDs contain a namespace derived from the service-map algorithm version, trace
and service-map indices, interval, lookback, and grouping attributes. Changing any
effective configuration therefore starts from a fresh cursor and cannot reuse
incompatible state.

Disabling service-map creation removes the persistent tasks. Changes to task
configuration replace them sequentially with updated parameters.

## Observability

Service-map tasks publish metrics through the OpenSearch telemetry framework on
the node currently executing each task. Cluster-wide monitoring must aggregate
these metrics across nodes because persistent tasks can move after a failure.

| Metric | Type | Description |
|---|---|---|
| `otlp.service_map.run.duration` | Histogram | Run duration in seconds, tagged by `outcome` |
| `otlp.service_map.runs` | Counter | Runs, tagged by `outcome` |
| `otlp.service_map.traces.processed` | Counter | Traces evaluated by successful runs |
| `otlp.service_map.spans.processed` | Counter | Spans evaluated by successful runs |
| `otlp.service_map.span.processing_lag` | Histogram | Seconds between the newest discovered span's `indexedAt` timestamp and the start of its successful run |
| `otlp.service_map.failures` | Counter | Failures, tagged by `phase` |
| `otlp.service_map.runs.skipped` | Counter | Runs skipped because another run for the same task is active |

The `outcome` values are `success`, `failure`, and `cancelled`. Processing lag is
recorded only when a successful run discovers spans. Trace and span counters measure
evaluation work rather than unique documents: cursor overlap, retries, and late spans
can cause a trace to be evaluated more than once. Service-map document writes are
intentionally left to the native metrics of the service-map index.

Failures identify the execution phase in which the run stopped:

| Phase | Work |
|---|---|
| `state_load` | Load the durable cursor |
| `discovery` | Find newly indexed spans for the task's trace-ID prefix |
| `trace_load` | Load the complete affected traces and parse their spans |
| `reconcile` | Build and consolidate relationship observations |
| `service_map_write` | Bulk-index service-map edges and leaves |
| `state_commit` | Persist the updated cursor |

## Current limits

- The partition count is fixed at 16.
- Prefix searches fan out across the trace index because spans are not routed by
  `traceId`.
- Existing assignments are not proactively rebalanced when a node joins.
- One task holds the consolidated observations for its current run in memory until
  the service-map bulk writes complete.
