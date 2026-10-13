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

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;

import java.util.Map;

/**
 * End-to-end integration tests for OTLP metrics ingestion. Constructs OTLP
 * proto requests and calls the service directly — no network connection needed.
 */
public class OTLPMetricsIT extends OTLPPluginITBase {

    // ── Gauge tests ───────────────────────────────────────────────────────────

    public void testGauge() throws Exception {
        export(gaugeRequest("metric-svc", "metric-lib"));
        assertGaugeDocument(METRICS_INDEX);
    }

    // ── Counter (Sum) tests ───────────────────────────────────────────────────

    public void testSum() throws Exception {
        export(sumRequest("metric-svc", "metric-lib"));
        assertSumDocument(METRICS_INDEX);
    }

    // ── Histogram tests ───────────────────────────────────────────────────────

    public void testHistogram() throws Exception {
        export(histogramRequest("metric-svc", "metric-lib"));
        assertHistogramDocument(METRICS_INDEX);
    }

    // ── Mapping tests ─────────────────────────────────────────────────────────

    public void testMetricMappingTypes() throws Exception {
        export(gaugeRequest("metric-svc", "metric-lib"));
        refresh(METRICS_INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(METRICS_INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(METRICS_INDEX).sourceAsMap().get("properties"));

        assertMappingType(props, "name",      "keyword");
        assertMappingType(props, "kind",      "keyword");
        assertMappingType(props, "unit",      "keyword");
        assertMappingType(props, "value",     "double");
        assertMappingType(props, "startTime", "date_nanos");
        assertMappingType(props, "time",      "date_nanos");
    }

    // ── document assertions ───────────────────────────────────────────────────

    private void assertGaugeDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        Map<String, Object> src = resp.getHits().getHits()[0].getSourceAsMap();
        assertEquals("cpu.usage",  src.get("name"));
        assertEquals("Gauge",      src.get("kind"));
        assertEquals(0.75, (Double) src.get("value"), 1e-9);
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

    // ── proto builder helpers ─────────────────────────────────────────────────

    private static Resource resource(String serviceName) {
        return Resource.newBuilder()
            .addAttributes(KeyValue.newBuilder()
                .setKey("service.name")
                .setValue(AnyValue.newBuilder().setStringValue(serviceName).build())
                .build())
            .build();
    }

    static ExportMetricsServiceRequest gaugeRequest(String serviceName, String scopeName) {
        return ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource(serviceName))
                .addScopeMetrics(ScopeMetrics.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName(scopeName).build())
                    .addMetrics(Metric.newBuilder()
                        .setName("cpu.usage")
                        .setDescription("CPU usage fraction")
                        .setUnit("1")
                        .setGauge(Gauge.newBuilder()
                            .addDataPoints(NumberDataPoint.newBuilder()
                                .setStartTimeUnixNano(1_000_000_000L)
                                .setTimeUnixNano(2_000_000_000L)
                                .setAsDouble(0.75)
                                .addAttributes(KeyValue.newBuilder()
                                    .setKey("host")
                                    .setValue(AnyValue.newBuilder()
                                        .setStringValue("node-1").build())
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }

    static ExportMetricsServiceRequest sumRequest(String serviceName, String scopeName) {
        return ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource(serviceName))
                .addScopeMetrics(ScopeMetrics.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName(scopeName).build())
                    .addMetrics(Metric.newBuilder()
                        .setName("req.count")
                        .setDescription("Request counter")
                        .setUnit("1")
                        .setSum(Sum.newBuilder()
                            .setIsMonotonic(true)
                            .setAggregationTemporality(
                                AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                            .addDataPoints(NumberDataPoint.newBuilder()
                                .setStartTimeUnixNano(1_000_000_000L)
                                .setTimeUnixNano(2_000_000_000L)
                                .setAsInt(42L)
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }

    static ExportMetricsServiceRequest histogramRequest(String serviceName, String scopeName) {
        return ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource(serviceName))
                .addScopeMetrics(ScopeMetrics.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName(scopeName).build())
                    .addMetrics(Metric.newBuilder()
                        .setName("latency")
                        .setDescription("Request latency")
                        .setUnit("ms")
                        .setHistogram(Histogram.newBuilder()
                            .setAggregationTemporality(
                                AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                            .addDataPoints(HistogramDataPoint.newBuilder()
                                .setStartTimeUnixNano(1_000_000_000L)
                                .setTimeUnixNano(2_000_000_000L)
                                .setCount(2)
                                .setSum(550.0)
                                .addBucketCounts(0).addBucketCounts(1)
                                .addBucketCounts(1).addBucketCounts(0)
                                .addExplicitBounds(10.0).addExplicitBounds(100.0)
                                .addExplicitBounds(1000.0)
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }
}
