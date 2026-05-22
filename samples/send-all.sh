#!/usr/bin/env bash
# Sends one batch of traces, metrics, and logs to verify all three signals.
# Usage: ./send-all.sh [http://localhost:4318]

set -euo pipefail
ENDPOINT="${1:-http://localhost:4318}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Sending traces ==="
"${DIR}/send-traces.sh"  "${ENDPOINT}"

echo ""
echo "=== Sending metrics ==="
"${DIR}/send-metrics.sh" "${ENDPOINT}"

echo ""
echo "=== Sending logs ==="
"${DIR}/send-logs.sh"    "${ENDPOINT}"

echo ""
echo "=== Index document counts ==="
for idx in otel-v1-apm-span metrics-otel-v1 logs-otel-v1; do
  count=$(curl -sf "http://localhost:9200/${idx}/_count" | python3 -c "import sys,json; print(json.load(sys.stdin).get('count','?'))" 2>/dev/null || echo "?")
  printf "  %-30s %s docs\n" "${idx}" "${count}"
done
