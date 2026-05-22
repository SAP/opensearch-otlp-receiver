#!/usr/bin/env bash
# Send sample metrics (gauge, sum, histogram) to the OTLP HTTP endpoint.
# Usage: ./send-metrics.sh [http://localhost:4318]

set -euo pipefail
ENDPOINT="${1:-http://localhost:4318}"

NOW_NS=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time() * 1e9))")
START_NS=$((NOW_NS - 60000000000))   # window start: 60 s ago

curl -sf -X POST "${ENDPOINT}/v1/metrics" \
  -H "Content-Type: application/json" \
  -d @- <<EOF
{
  "resourceMetrics": [{
    "resource": {
      "attributes": [
        {"key": "service.name",    "value": {"stringValue": "sample-service"}},
        {"key": "host.name",       "value": {"stringValue": "localhost"}},
        {"key": "deployment.environment", "value": {"stringValue": "dev"}}
      ]
    },
    "scopeMetrics": [{
      "scope": {"name": "sample-instrumentation", "version": "1.0.0"},
      "metrics": [
        {
          "name": "system.cpu.utilization",
          "description": "CPU utilization fraction",
          "unit": "1",
          "gauge": {
            "dataPoints": [{
              "timeUnixNano": "${NOW_NS}",
              "asDouble": 0.42,
              "attributes": [
                {"key": "cpu.state", "value": {"stringValue": "user"}}
              ]
            }]
          }
        },
        {
          "name": "http.server.request.count",
          "description": "Total HTTP requests served",
          "unit": "{request}",
          "sum": {
            "dataPoints": [{
              "startTimeUnixNano": "${START_NS}",
              "timeUnixNano": "${NOW_NS}",
              "asDouble": 1024,
              "attributes": [
                {"key": "http.method",      "value": {"stringValue": "GET"}},
                {"key": "http.status_code", "value": {"intValue": 200}}
              ]
            }],
            "aggregationTemporality": 2,
            "isMonotonic": true
          }
        },
        {
          "name": "http.server.duration",
          "description": "HTTP request duration",
          "unit": "ms",
          "histogram": {
            "dataPoints": [{
              "startTimeUnixNano": "${START_NS}",
              "timeUnixNano": "${NOW_NS}",
              "count": "200",
              "sum": 18500.0,
              "min": 1.2,
              "max": 980.5,
              "bucketCounts": ["10", "80", "90", "15", "4", "1"],
              "explicitBounds": [5.0, 25.0, 100.0, 250.0, 500.0],
              "attributes": [
                {"key": "http.method", "value": {"stringValue": "GET"}}
              ]
            }],
            "aggregationTemporality": 2
          }
        }
      ]
    }]
  }]
}
EOF

echo ""
echo "Metrics sent. Verify with:"
echo "  curl -s 'http://localhost:9200/metrics-otel-v1/_search?pretty&size=3'"
