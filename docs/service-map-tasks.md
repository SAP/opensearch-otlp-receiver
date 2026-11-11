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
only spans after its durable `(indexedAt, document ID)` cursor, collects the affected
trace IDs, and fetches the complete spans for those traces before reconciliation.
The cursor advances only after the service-map write succeeds.

Relationship IDs are hashes of the relationship type, trace and span IDs, and the
event-time bucket. Retrying therefore overwrites the same edge or leaf. Each trace
also has a small expiring tombstone in the hidden
`.opensearch-otlp-service-map-state` index. A late span reopens the trace; the task
upserts corrected edges and deterministically deletes a leaf that is no longer a
leaf, without storing emitted document IDs. A per-task guard prevents overlapping
runs.

Disabling service-map creation removes the persistent tasks. Changes to task
configuration replace them sequentially with updated parameters.

## Current limits

- The partition count is fixed at 16.
- Prefix searches fan out across the trace index because spans are not routed by
  `traceId`.
- Existing assignments are not proactively rebalanced when a node joins.
- One task holds one discovery page and the affected traces in memory while
  correlating them.
