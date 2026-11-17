#!/usr/bin/env python3

import json
import sys
import time
import urllib.error
import urllib.request


EXPECTED_COUNTS = {
    "otel-v1-apm-span": 2,
    "otel-v2-apm-service-map": 2,
    "metrics-otel-v1": 3,
    "logs-otel-v1": 2,
}


def request_json(base_url, path, method="GET"):
    request = urllib.request.Request(f"{base_url}{path}", method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def wait_for_documents(base_url):
    deadline = time.monotonic() + 90
    last_counts = {}

    while time.monotonic() < deadline:
        try:
            request_json(base_url, "/_refresh", method="POST")
            last_counts = {
                index: request_json(base_url, f"/{index}/_count")["count"]
                for index in EXPECTED_COUNTS
            }
            if last_counts == EXPECTED_COUNTS:
                return
        except (KeyError, urllib.error.HTTPError, urllib.error.URLError):
            pass
        time.sleep(2)

    raise AssertionError(
        f"Timed out waiting for documents: expected {EXPECTED_COUNTS}, got {last_counts}"
    )


def sources(base_url, index):
    response = request_json(base_url, f"/{index}/_search?size=100")
    return [hit["_source"] for hit in response["hits"]["hits"]]


def verify_plugin_and_templates(base_url):
    plugins = request_json(base_url, "/_cat/plugins?format=json")
    components = {plugin["component"] for plugin in plugins}
    assert "opensearch-otlp-receiver" in components, (
        f"Plugin is not installed; found components: {sorted(components)}"
    )

    for template in (
        "ss4o_traces",
        "ss4o_metrics",
        "ss4o_logs",
        "ss4o_service_map",
    ):
        response = request_json(base_url, f"/_index_template/{template}")
        assert response.get("index_templates"), f"Template {template} is missing"


def verify_spans(base_url):
    documents = sources(base_url, "otel-v1-apm-span")
    assert {document["name"] for document in documents} == {
        "GET /api/orders",
        "query-orders",
    }
    assert {document["serviceName"] for document in documents} == {
        "frontend-service",
        "backend-service",
    }
    assert len({document["traceId"] for document in documents}) == 1
    assert all(len(document["traceId"]) == 32 for document in documents)
    assert all(len(document["spanId"]) == 16 for document in documents)
    assert len([document for document in documents if document.get("parentSpanId")]) == 1


def verify_metrics(base_url):
    documents = sources(base_url, "metrics-otel-v1")
    assert {document["name"] for document in documents} == {
        "system.cpu.utilization",
        "http.server.request.count",
        "http.server.duration",
    }
    assert {document["kind"] for document in documents} == {
        "Gauge",
        "Sum",
        "Histogram",
    }
    assert all(document["serviceName"] == "sample-service" for document in documents)
    assert all("@timestamp" in document for document in documents)


def node_name(document, field):
    node = document[field]
    assert node["type"] == "service"
    assert node["keyAttributes"]["environment"] == "dev"
    return node["keyAttributes"]["name"]


def verify_service_map(base_url):
    documents = sources(base_url, "otel-v2-apm-service-map")
    edges = [document for document in documents if document.get("targetNode")]
    leaves = [document for document in documents if not document.get("targetNode")]
    assert len(edges) == 1, f"Expected one service-map edge, got {edges}"
    assert len(leaves) == 1, f"Expected one service-map leaf, got {leaves}"

    edge = edges[0]
    assert node_name(edge, "sourceNode") == "frontend-service"
    assert node_name(edge, "targetNode") == "backend-service"
    assert edge["sourceOperation"]["name"] == "GET /api/orders"
    assert edge["targetOperation"]["name"] == "query-orders"

    leaf = leaves[0]
    assert node_name(leaf, "sourceNode") == "backend-service"
    assert leaf["sourceOperation"]["name"] == "query-orders"
    assert leaf.get("targetOperation") is None

    for document in documents:
        assert document.get("timestamp")
        assert document.get("nodeConnectionHash")
        assert document.get("operationConnectionHash")


def verify_logs(base_url):
    documents = sources(base_url, "logs-otel-v1")
    assert {document["body"] for document in documents} == {
        "Order 42 placed successfully",
        "Payment gateway timeout for order 43",
    }
    assert {document["severity"]["text"] for document in documents} == {
        "INFO",
        "ERROR",
    }
    assert all(document["serviceName"] == "sample-service" for document in documents)
    assert len([document for document in documents if document.get("traceId")]) == 1
    assert all("@timestamp" in document for document in documents)


def verify_mappings(base_url):
    response = request_json(base_url, "/otel-v1-apm-span/_mapping")
    properties = response["otel-v1-apm-span"]["mappings"]["properties"]
    assert properties["traceId"]["type"] == "keyword"
    assert properties["startTime"]["type"] == "date_nanos"
    assert properties["indexedAt"]["type"] == "date_nanos"

    response = request_json(base_url, "/otel-v2-apm-service-map/_mapping")
    properties = response["otel-v2-apm-service-map"]["mappings"]["properties"]
    assert properties["timestamp"]["type"] == "date"
    assert properties["nodeConnectionHash"]["type"] == "keyword"
    assert properties["operationConnectionHash"]["type"] == "keyword"


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify.py OPENSEARCH_URL")

    base_url = sys.argv[1].rstrip("/")
    wait_for_documents(base_url)
    verify_plugin_and_templates(base_url)
    verify_spans(base_url)
    verify_service_map(base_url)
    verify_metrics(base_url)
    verify_logs(base_url)
    verify_mappings(base_url)


if __name__ == "__main__":
    main()
