/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to this file be
 * licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.opensearch.plugin.otlp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration tests for OTLP metrics ingestion. Covers HTTP/JSON,
 * HTTP/protobuf, and gRPC transports. Mapping assertions target fields
 * defined in the ss4o_metrics composable template (metrics-otel-v1-*).
 */
public class OTLPMetricsIT extends OTLPPluginITBase {

    // Matches the ss4o_metrics template pattern "metrics-otel-v1-*".
    private static final String INDEX = "metrics-otel-v1-test";

    // ── JSON payloads for HTTP/JSON transport ─────────────────────────────────────

    private static final String GAUGE_JSON = "{\n"
        + "  \"resourceMetrics\": [{\n"
        + "    \"resource\": {\"attributes\": [{\"key\": \"service.name\","
        +      " \"value\": {\"stringValue\": \"metric-svc\"}}]},\n"
        + "    \"scopeMetrics\": [{\n"
        + "      \"scope\": {\"name\": \"metric-lib\"},\n"
        + "      \"metrics\": [{\n"
        + "        \"name\": \"cpu.usage\", \"description\": \"CPU usage fraction\","
        +          " \"unit\": \"1\",\n"
        + "        \"gauge\": {\"dataPoints\": [{\n"
        + "          \"startTimeUnixNano\": \"1000000000\","
        +            " \"timeUnixNano\": \"2000000000\",\n"
        + "          \"asDouble\": 0.75,\n"
        + "          \"attributes\": [{\"key\": \"host\","
        +            " \"value\": {\"stringValue\": \"node-1\"}}]\n"
        + "        }]}\n"
        + "      }]\n"
        + "    }]\n"
        + "  }]\n"
        + "}";

    private static final String SUM_JSON = "{\n"
        + "  \"resourceMetrics\": [{\n"
        + "    \"resource\": {\"attributes\": [{\"key\": \"service.name\","
        +      " \"value\": {\"stringValue\": \"metric-svc\"}}]},\n"
        + "    \"scopeMetrics\": [{\n"
        + "      \"scope\": {\"name\": \"metric-lib\"},\n"
        + "      \"metrics\": [{\n"
        + "        \"name\": \"req.count\", \"description\": \"Request counter\","
        +          " \"unit\": \"1\",\n"
        + "        \"sum\": {\n"
        + "          \"isMonotonic\": true,\n"
        + "          \"aggregationTemporality\": \"AGGREGATION_TEMPORALITY_CUMULATIVE\",\n"
        + "          \"dataPoints\": [{\n"
        + "            \"startTimeUnixNano\": \"1000000000\","
        +              " \"timeUnixNano\": \"2000000000\",\n"
        + "            \"asInt\": \"42\"\n"
        + "          }]\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }]\n"
        + "}";

    private static final String HISTOGRAM_JSON = "{\n"
        + "  \"resourceMetrics\": [{\n"
        + "    \"resource\": {\"attributes\": [{\"key\": \"service.name\","
        +      " \"value\": {\"stringValue\": \"metric-svc\"}}]},\n"
        + "    \"scopeMetrics\": [{\n"
        + "      \"scope\": {\"name\": \"metric-lib\"},\n"
        + "      \"metrics\": [{\n"
        + "        \"name\": \"latency\", \"description\": \"Request latency\","
        +          " \"unit\": \"ms\",\n"
        + "        \"histogram\": {\n"
        + "          \"aggregationTemporality\": \"AGGREGATION_TEMPORALITY_CUMULATIVE\",\n"
        + "          \"dataPoints\": [{\n"
        + "            \"startTimeUnixNano\": \"1000000000\","
        +              " \"timeUnixNano\": \"2000000000\",\n"
        + "            \"count\": \"2\", \"sum\": 550.0,\n"
        + "            \"bucketCounts\": [\"0\", \"1\", \"1\", \"0\"],\n"
        + "            \"explicitBounds\": [10.0, 100.0, 1000.0]\n"
        + "          }]\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }]\n"
        + "}";

    // ── Gauge tests ───────────────────────────────────────────────────────────────

    public void testGauge_httpJson() throws Exception {
        sendJsonOTLP("/v1/metrics", GAUGE_JSON);
        assertGaugeDocument(INDEX);
    }

    public void testGauge_httpProto() throws Exception {
        emitGauge(OtlpProtocol.HTTP_PROTOBUF);
        assertGaugeDocument(INDEX);
    }

    public void testGauge_grpc() throws Exception {
        emitGauge(OtlpProtocol.GRPC);
        assertGaugeDocument(INDEX);
    }

    // ── Counter (Sum) tests ───────────────────────────────────────────────────────

    public void testSum_httpJson() throws Exception {
        sendJsonOTLP("/v1/metrics", SUM_JSON);
        assertSumDocument(INDEX);
    }

    public void testSum_httpProto() throws Exception {
        emitCounter(OtlpProtocol.HTTP_PROTOBUF);
        assertSumDocument(INDEX);
    }

    public void testSum_grpc() throws Exception {
        emitCounter(OtlpProtocol.GRPC);
        assertSumDocument(INDEX);
    }

    // ── Histogram tests ───────────────────────────────────────────────────────────

    public void testHistogram_httpJson() throws Exception {
        sendJsonOTLP("/v1/metrics", HISTOGRAM_JSON);
        assertHistogramDocument(INDEX);
    }

    public void testHistogram_httpProto() throws Exception {
        emitHistogram(OtlpProtocol.HTTP_PROTOBUF);
        assertHistogramDocument(INDEX);
    }

    public void testHistogram_grpc() throws Exception {
        emitHistogram(OtlpProtocol.GRPC);
        assertHistogramDocument(INDEX);
    }

    // ── Mapping: fields from the ss4o_metrics composable template ────────────────

    public void testMetricMappingTypes() throws Exception {
        emitGauge(OtlpProtocol.GRPC);
        refresh(INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(INDEX).sourceAsMap().get("properties"));

        // Fields defined in the ss4o_metrics template.
        assertMappingType(props, "name",      "keyword");
        assertMappingType(props, "kind",      "keyword");
        assertMappingType(props, "unit",      "keyword");
        assertMappingType(props, "value",     "double");
        assertMappingType(props, "startTime", "date_nanos");
        assertMappingType(props, "time",      "date_nanos");
    }

    // ── signal emission ───────────────────────────────────────────────────────────

    private void emitGauge(OtlpProtocol protocol) throws Exception {
        var exporter = buildMetricExporter(protocol);
        var reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofHours(1))
            .build();
        var provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .setResource(telemetryResource("metric-svc"))
            .build();
        try {
            provider.get("metric-lib")
                .gaugeBuilder("cpu.usage")
                .setDescription("CPU usage fraction")
                .setUnit("1")
                .buildWithCallback(m -> m.record(0.75, Attributes.of(AttributeKey.stringKey("host"), "node-1")));
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    private void emitCounter(OtlpProtocol protocol) throws Exception {
        var exporter = buildMetricExporter(protocol);
        var reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofHours(1))
            .build();
        var provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .setResource(telemetryResource("metric-svc"))
            .build();
        try {
            LongCounter counter = provider.get("metric-lib")
                .counterBuilder("req.count")
                .setDescription("Request counter")
                .setUnit("1")
                .build();
            counter.add(42);
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    private void emitHistogram(OtlpProtocol protocol) throws Exception {
        var exporter = buildMetricExporter(protocol);
        var reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofHours(1))
            .build();
        var provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .setResource(telemetryResource("metric-svc"))
            .build();
        try {
            DoubleHistogram histogram = provider.get("metric-lib")
                .histogramBuilder("latency")
                .setDescription("Request latency")
                .setUnit("ms")
                .build();
            histogram.record(50.0);
            histogram.record(500.0);
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    // ── shared document assertions ────────────────────────────────────────────────

    private void assertGaugeDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        Map<String, Object> src = resp.getHits().getHits()[0].getSourceAsMap();
        assertEquals("cpu.usage",  src.get("name"));
        assertEquals("Gauge",      src.get("kind"));
        assertEquals(0.75, (Double) src.get("value@double"), 1e-9);
        assertEquals("metric-svc", src.get("serviceName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) src.get("attributes");
        assertNotNull(attrs);
        assertEquals("node-1", attrs.get("host"));
    }

    private void assertSumDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        Map<String, Object> src = resp.getHits().getHits()[0].getSourceAsMap();
        assertEquals("req.count", src.get("name"));
        assertEquals("Sum",       src.get("kind"));
        assertEquals(42, ((Number) src.get("value@int")).longValue());
        assertEquals(42, ((Number) src.get("value")).longValue());
        assertEquals(Boolean.TRUE, src.get("monotonic"));
        assertEquals("AGGREGATION_TEMPORALITY_CUMULATIVE", src.get("aggregationTemporality"));
    }

    private void assertHistogramDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        Map<String, Object> src = resp.getHits().getHits()[0].getSourceAsMap();
        assertEquals("latency",   src.get("name"));
        assertEquals("Histogram", src.get("kind"));
        assertEquals(2L, ((Number) src.get("count")).longValue());
        assertEquals(550.0, (Double) src.get("sum"), 1e-9);
        assertEquals("AGGREGATION_TEMPORALITY_CUMULATIVE", src.get("aggregationTemporality"));
    }
}
