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
package org.opensearch.plugin.otlp.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.plugin.otlp.document.MetricDocument;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MetricsTransformerTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MetricsTransformer transformer = new MetricsTransformer();

    public void testEmptyRequestProducesNoDocs() {
        assertTrue(transformer.transform(ExportMetricsServiceRequest.getDefaultInstance()).isEmpty());
    }

    public void testGauge_DoubleValue() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("cpu.usage")
            .setDescription("CPU usage")
            .setUnit("1")
            .setGauge(Gauge.newBuilder().addDataPoints(NumberDataPoint.newBuilder()
                .setTimeUnixNano(1_000_000_000L)
                .setAsDouble(0.42)))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals("cpu.usage", doc.get("name"));
        assertEquals("Gauge", doc.get("kind"));
        assertEquals(0.42, doc.get("value@double"));
        assertEquals(0.42, doc.get("value"));
        assertEquals("1970-01-01T00:00:01Z", doc.get("time"));
    }

    public void testGauge_IntValue() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("requests")
            .setGauge(Gauge.newBuilder().addDataPoints(NumberDataPoint.newBuilder()
                .setTimeUnixNano(1_000_000_000L)
                .setAsInt(100)))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals(100, ((Number) doc.get("value@int")).longValue());
        assertEquals(100, ((Number) doc.get("value")).longValue());
        assertNull(doc.get("value@double"));
    }

    public void testSum() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("http.requests")
            .setSum(Sum.newBuilder()
                .setIsMonotonic(true)
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(NumberDataPoint.newBuilder()
                    .setTimeUnixNano(1_000_000_000L)
                    .setAsDouble(42.0)))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals("Sum", doc.get("kind"));
        assertEquals(42.0, doc.get("value@double"));
        assertEquals(42.0, doc.get("value"));
        assertEquals(true, doc.get("monotonic"));
        assertEquals("AGGREGATION_TEMPORALITY_CUMULATIVE", doc.get("aggregationTemporality"));
    }

    public void testHistogram() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("response.time")
            .setHistogram(Histogram.newBuilder()
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
                .addDataPoints(HistogramDataPoint.newBuilder()
                    .setTimeUnixNano(1_000_000_000L)
                    .setCount(10)
                    .setSum(100.0)
                    .setMin(1.0)
                    .setMax(20.0)
                    .addBucketCounts(2).addBucketCounts(8)
                    .addExplicitBounds(10.0)))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals("Histogram", doc.get("kind"));
        assertEquals(10, ((Number) doc.get("count")).longValue());
        assertEquals(100.0, doc.get("sum"));
        assertEquals(1.0, doc.get("min"));
        assertEquals(20.0, doc.get("max"));
        assertEquals(2, doc.get("bucketCount"));
        assertEquals("AGGREGATION_TEMPORALITY_DELTA", doc.get("aggregationTemporality"));
    }

    public void testExponentialHistogram() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("latency")
            .setExponentialHistogram(ExponentialHistogram.newBuilder()
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(ExponentialHistogramDataPoint.newBuilder()
                    .setTimeUnixNano(1_000_000_000L)
                    .setCount(5)
                    .setScale(2)
                    .setZeroCount(1)))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals("ExponentialHistogram", doc.get("kind"));
        assertEquals(5, ((Number) doc.get("count")).longValue());
        assertEquals(2, doc.get("scale"));
        assertEquals(1, ((Number) doc.get("zeroCount")).longValue());
    }

    public void testSummary() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("response.size")
            .setSummary(Summary.newBuilder()
                .addDataPoints(SummaryDataPoint.newBuilder()
                    .setTimeUnixNano(1_000_000_000L)
                    .setCount(100)
                    .setSum(5000.0)
                    .addQuantileValues(SummaryDataPoint.ValueAtQuantile.newBuilder()
                        .setQuantile(0.99).setValue(120.0))))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertEquals("Summary", doc.get("kind"));
        assertEquals(100, ((Number) doc.get("count")).longValue());
        assertEquals(5000.0, doc.get("sum"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quantiles = (List<Map<String, Object>>) doc.get("quantiles");
        assertEquals(1, quantiles.size());
        assertEquals(0.99, quantiles.get(0).get("quantile"));
        assertEquals(120.0, quantiles.get(0).get("value"));
    }

    public void testAttributesNested_NoDedot() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("m")
            .setGauge(Gauge.newBuilder().addDataPoints(NumberDataPoint.newBuilder()
                .setTimeUnixNano(1_000_000_000L)
                .addAttributes(kv("http.method", "GET"))))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) doc.get("attributes");
        assertEquals("GET", attrs.get("http.method"));
        assertNull(attrs.get("http@method"));
    }

    public void testResourceAndScopeAreNested() throws Exception {
        Metric metric = Metric.newBuilder()
            .setName("m")
            .setGauge(Gauge.newBuilder().addDataPoints(NumberDataPoint.newBuilder()
                .setTimeUnixNano(0)))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(requestWith(metric, "svc")).get(0));
        assertNotNull(doc.get("resource"));
        assertNotNull(doc.get("instrumentationScope"));
    }

    public void testUnknownDataCaseProducesNoDocs() {
        Metric metric = Metric.newBuilder().setName("empty").build();
        assertTrue(transformer.transform(requestWith(metric, "svc")).isEmpty());
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(MetricDocument doc) throws IOException {
        var builder = XContentFactory.jsonBuilder();
        doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private ExportMetricsServiceRequest requestWith(Metric metric, String serviceName) {
        return ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", serviceName)))
                .addScopeMetrics(ScopeMetrics.newBuilder().addMetrics(metric)))
            .build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
