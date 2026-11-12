#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
PROJECT_NAME="opensearch-otlp-e2e-$$"
SETUP_LOG=""

is_true() {
  case "$1" in
    1|true|TRUE|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

quiet_output() {
  if [[ -n ${E2E_QUIET+x} ]]; then
    is_true "${E2E_QUIET}"
  else
    is_true "${CI:-}" || [[ ! -t 1 ]]
  fi
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

compose() {
  docker compose --project-name "${PROJECT_NAME}" --file "${COMPOSE_FILE}" "$@"
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM

  if (( exit_code != 0 )); then
    echo "End-to-end test failed; container logs follow." >&2
    if [[ -n "${SETUP_LOG}" && -s "${SETUP_LOG}" ]]; then
      echo "Docker Compose startup output:" >&2
      cat "${SETUP_LOG}" >&2
    fi
    compose logs --no-color >&2 || true
  fi

  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [[ -n "${SETUP_LOG}" ]]; then
    rm -f -- "${SETUP_LOG}"
  fi
  exit "${exit_code}"
}

wait_for_url() {
  local url=$1
  local description=$2
  local deadline=$((SECONDS + 90))

  until curl --silent --fail --output /dev/null "${url}"; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${description} at ${url}" >&2
      return 1
    fi
    sleep 2
  done
}

require_command docker
require_command curl
require_command python3

: "${E2E_PLUGIN_ZIP:?E2E_PLUGIN_ZIP must point to the packaged plugin ZIP}"
: "${E2E_OPENSEARCH_IMAGE:?E2E_OPENSEARCH_IMAGE must name the OpenSearch image}"

docker info >/dev/null
docker compose version >/dev/null

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Starting the end-to-end test environment"
if quiet_output; then
  SETUP_LOG=$(mktemp "${TMPDIR:-/tmp}/opensearch-otlp-e2e.XXXXXX")
  compose up --detach --wait --wait-timeout 240 >"${SETUP_LOG}" 2>&1
else
  compose up --detach --wait --wait-timeout 240
fi

OPENSEARCH_ADDRESS=$(compose port opensearch 9200)
OTLP_HTTP_ADDRESS=$(compose port otelcol 4318)
COLLECTOR_HEALTH_ADDRESS=$(compose port otelcol 13133)

OPENSEARCH_URL="http://${OPENSEARCH_ADDRESS}"
OTLP_HTTP_URL="http://${OTLP_HTTP_ADDRESS}"

wait_for_url "http://${COLLECTOR_HEALTH_ADDRESS}/" "OpenTelemetry Collector"

echo "Sending trace, metric, and log fixtures"
python3 "${SCRIPT_DIR}/send.py" "${OTLP_HTTP_URL}"

echo "Verifying indexed telemetry and generated service map"
python3 "${SCRIPT_DIR}/verify.py" "${OPENSEARCH_URL}"

echo "End-to-end test passed"
