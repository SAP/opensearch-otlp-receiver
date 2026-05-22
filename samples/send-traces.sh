#!/usr/bin/env bash
# Send a sample trace (two spans across two services) to the OTLP HTTP endpoint.
# The root span (frontend-service) calls the child span (backend-service),
# which produces a cross-service edge in the service map after enrichment runs.
# Usage: ./send-traces.sh [http://localhost:4318]

set -euo pipefail
ENDPOINT="${1:-http://localhost:4318}"

NOW_NS=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time() * 1e9))")
END_NS=$((NOW_NS + 5000000))   # +5 ms

TRACE_ID="$(dd if=/dev/urandom bs=16 count=1 2>/dev/null | base64 | tr -d '=\n')"
ROOT_SPAN_ID="$(dd if=/dev/urandom bs=8  count=1 2>/dev/null | base64 | tr -d '=\n')"
CHILD_SPAN_ID="$(dd if=/dev/urandom bs=8  count=1 2>/dev/null | base64 | tr -d '=\n')"

curl -sf -X POST "${ENDPOINT}/v1/traces" \
  -H "Content-Type: application/json" \
  -d @- <<EOF
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name",    "value": {"stringValue": "frontend-service"}},
          {"key": "service.version", "value": {"stringValue": "1.0.0"}},
          {"key": "deployment.environment", "value": {"stringValue": "dev"}}
        ]
      },
      "scopeSpans": [{
        "scope": {"name": "sample-instrumentation", "version": "1.0.0"},
        "spans": [
          {
            "traceId": "${TRACE_ID}",
            "spanId": "${ROOT_SPAN_ID}",
            "name": "GET /api/orders",
            "kind": 3,
            "startTimeUnixNano": "${NOW_NS}",
            "endTimeUnixNano": "${END_NS}",
            "attributes": [
              {"key": "http.method",      "value": {"stringValue": "GET"}},
              {"key": "http.target",      "value": {"stringValue": "/api/orders"}},
              {"key": "http.status_code", "value": {"intValue": 200}}
            ],
            "status": {"code": 1, "message": "OK"}
          }
        ]
      }]
    },
    {
      "resource": {
        "attributes": [
          {"key": "service.name",    "value": {"stringValue": "backend-service"}},
          {"key": "service.version", "value": {"stringValue": "1.0.0"}},
          {"key": "deployment.environment", "value": {"stringValue": "dev"}}
        ]
      },
      "scopeSpans": [{
        "scope": {"name": "sample-instrumentation", "version": "1.0.0"},
        "spans": [
          {
            "traceId": "${TRACE_ID}",
            "spanId": "${CHILD_SPAN_ID}",
            "parentSpanId": "${ROOT_SPAN_ID}",
            "name": "query-orders",
            "kind": 2,
            "startTimeUnixNano": "${NOW_NS}",
            "endTimeUnixNano": "${END_NS}",
            "attributes": [
              {"key": "db.system",    "value": {"stringValue": "postgresql"}},
              {"key": "db.name",      "value": {"stringValue": "shop"}},
              {"key": "db.statement", "value": {"stringValue": "SELECT * FROM orders WHERE user_id = ?"}}
            ],
            "status": {"code": 1}
          }
        ]
      }]
    }
  ]
}
EOF

echo ""
echo "Traces sent (frontend-service → backend-service)."
echo "After the enrichment interval (~60 s) verify with:"
echo "  curl -s 'http://localhost:9200/otel-v1-apm-span/_search?pretty&size=2'"
echo "  curl -s 'http://localhost:9200/otel-v1-apm-service-map/_search?pretty'"
