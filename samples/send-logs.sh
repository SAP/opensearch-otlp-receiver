#!/usr/bin/env bash
# Send sample log records (INFO + ERROR) to the OTLP HTTP endpoint.
# Usage: ./send-logs.sh [http://localhost:4318]

set -euo pipefail
ENDPOINT="${1:-http://localhost:4318}"

NOW_NS=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time() * 1e9))")
TRACE_ID="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
SPAN_ID="$(od -An -tx1 -N8  /dev/urandom | tr -d ' \n')"

curl -sf -X POST "${ENDPOINT}/v1/logs" \
  -H "Content-Type: application/json" \
  -d @- <<EOF
{
  "resourceLogs": [{
    "resource": {
      "attributes": [
        {"key": "service.name",    "value": {"stringValue": "sample-service"}},
        {"key": "service.version", "value": {"stringValue": "1.0.0"}},
        {"key": "deployment.environment", "value": {"stringValue": "dev"}}
      ]
    },
    "scopeLogs": [{
      "scope": {"name": "sample-instrumentation", "version": "1.0.0"},
      "logRecords": [
        {
          "timeUnixNano": "${NOW_NS}",
          "observedTimeUnixNano": "${NOW_NS}",
          "severityNumber": 9,
          "severityText": "INFO",
          "body": {"stringValue": "Order 42 placed successfully"},
          "traceId": "${TRACE_ID}",
          "spanId": "${SPAN_ID}",
          "attributes": [
            {"key": "order.id",       "value": {"intValue": 42}},
            {"key": "customer.tier",  "value": {"stringValue": "gold"}},
            {"key": "http.method",    "value": {"stringValue": "POST"}},
            {"key": "http.status_code", "value": {"intValue": 201}}
          ]
        },
        {
          "timeUnixNano": "${NOW_NS}",
          "observedTimeUnixNano": "${NOW_NS}",
          "severityNumber": 17,
          "severityText": "ERROR",
          "body": {"stringValue": "Payment gateway timeout for order 43"},
          "attributes": [
            {"key": "order.id",        "value": {"intValue": 43}},
            {"key": "error.type",      "value": {"stringValue": "TimeoutException"}},
            {"key": "http.status_code","value": {"intValue": 504}}
          ]
        }
      ]
    }]
  }]
}
EOF

echo ""
echo "Logs sent. Verify with:"
echo "  curl -s 'http://localhost:9200/logs-otel-v1/_search?pretty&size=2'"
