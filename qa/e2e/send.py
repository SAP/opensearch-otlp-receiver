#!/usr/bin/env python3

import json
import secrets
import sys
import time
import urllib.error
import urllib.request


def string_value(key, value):
    return {"key": key, "value": {"stringValue": value}}


def int_value(key, value):
    return {"key": key, "value": {"intValue": value}}


def resource(service_name, include_version=False):
    attributes = [string_value("service.name", service_name)]
    if include_version:
        attributes.append(string_value("service.version", "1.0.0"))
    attributes.append(string_value("deployment.environment", "dev"))
    return {"attributes": attributes}


def scope():
    return {"name": "sample-instrumentation", "version": "1.0.0"}


def trace_fixture():
    now_ns = time.time_ns()
    end_ns = now_ns + 5_000_000
    trace_id = secrets.token_hex(16)
    client_span_id = secrets.token_hex(8)
    server_span_id = secrets.token_hex(8)

    return {
        "resourceSpans": [
            {
                "resource": resource("frontend-service", include_version=True),
                "scopeSpans": [{
                    "scope": scope(),
                    "spans": [{
                        "traceId": trace_id,
                        "spanId": client_span_id,
                        "name": "GET /api/orders",
                        "kind": 3,
                        "startTimeUnixNano": str(now_ns),
                        "endTimeUnixNano": str(end_ns),
                        "attributes": [
                            string_value("http.method", "GET"),
                            string_value("http.target", "/api/orders"),
                            int_value("http.status_code", 200),
                        ],
                        "status": {"code": 1, "message": "OK"},
                    }],
                }],
            },
            {
                "resource": resource("backend-service", include_version=True),
                "scopeSpans": [{
                    "scope": scope(),
                    "spans": [{
                        "traceId": trace_id,
                        "spanId": server_span_id,
                        "parentSpanId": client_span_id,
                        "name": "query-orders",
                        "kind": 2,
                        "startTimeUnixNano": str(now_ns),
                        "endTimeUnixNano": str(end_ns),
                        "attributes": [
                            string_value("db.system", "postgresql"),
                            string_value("db.name", "shop"),
                            string_value(
                                "db.statement",
                                "SELECT * FROM orders WHERE user_id = ?",
                            ),
                        ],
                        "status": {"code": 1},
                    }],
                }],
            },
        ]
    }


def metrics_fixture():
    now_ns = time.time_ns()
    start_ns = now_ns - 60_000_000_000
    return {
        "resourceMetrics": [{
            "resource": {
                "attributes": [
                    string_value("service.name", "sample-service"),
                    string_value("host.name", "localhost"),
                    string_value("deployment.environment", "dev"),
                ]
            },
            "scopeMetrics": [{
                "scope": scope(),
                "metrics": [
                    {
                        "name": "system.cpu.utilization",
                        "description": "CPU utilization fraction",
                        "unit": "1",
                        "gauge": {
                            "dataPoints": [{
                                "timeUnixNano": str(now_ns),
                                "asDouble": 0.42,
                                "attributes": [string_value("cpu.state", "user")],
                            }]
                        },
                    },
                    {
                        "name": "http.server.request.count",
                        "description": "Total HTTP requests served",
                        "unit": "{request}",
                        "sum": {
                            "dataPoints": [{
                                "startTimeUnixNano": str(start_ns),
                                "timeUnixNano": str(now_ns),
                                "asDouble": 1024,
                                "attributes": [
                                    string_value("http.method", "GET"),
                                    int_value("http.status_code", 200),
                                ],
                            }],
                            "aggregationTemporality": 2,
                            "isMonotonic": True,
                        },
                    },
                    {
                        "name": "http.server.duration",
                        "description": "HTTP request duration",
                        "unit": "ms",
                        "histogram": {
                            "dataPoints": [{
                                "startTimeUnixNano": str(start_ns),
                                "timeUnixNano": str(now_ns),
                                "count": "200",
                                "sum": 18500.0,
                                "min": 1.2,
                                "max": 980.5,
                                "bucketCounts": ["10", "80", "90", "15", "4", "1"],
                                "explicitBounds": [5.0, 25.0, 100.0, 250.0, 500.0],
                                "attributes": [string_value("http.method", "GET")],
                            }],
                            "aggregationTemporality": 2,
                        },
                    },
                ],
            }],
        }]
    }


def logs_fixture():
    now_ns = time.time_ns()
    return {
        "resourceLogs": [{
            "resource": resource("sample-service", include_version=True),
            "scopeLogs": [{
                "scope": scope(),
                "logRecords": [
                    {
                        "timeUnixNano": str(now_ns),
                        "observedTimeUnixNano": str(now_ns),
                        "severityNumber": 9,
                        "severityText": "INFO",
                        "body": {"stringValue": "Order 42 placed successfully"},
                        "traceId": secrets.token_hex(16),
                        "spanId": secrets.token_hex(8),
                        "attributes": [
                            int_value("order.id", 42),
                            string_value("customer.tier", "gold"),
                            string_value("http.method", "POST"),
                            int_value("http.status_code", 201),
                        ],
                    },
                    {
                        "timeUnixNano": str(now_ns),
                        "observedTimeUnixNano": str(now_ns),
                        "severityNumber": 17,
                        "severityText": "ERROR",
                        "body": {"stringValue": "Payment gateway timeout for order 43"},
                        "attributes": [
                            int_value("order.id", 43),
                            string_value("error.type", "TimeoutException"),
                            int_value("http.status_code", 504),
                        ],
                    },
                ],
            }],
        }]
    }


def post_json(base_url, path, payload):
    request = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
    except urllib.error.HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OTLP request to {path} failed: {error.code} {details}") from error


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: send.py OTLP_HTTP_URL")

    base_url = sys.argv[1].rstrip("/")
    post_json(base_url, "/v1/traces", trace_fixture())
    post_json(base_url, "/v1/metrics", metrics_fixture())
    post_json(base_url, "/v1/logs", logs_fixture())
    print("Trace, metric, and log fixtures sent")


if __name__ == "__main__":
    main()
