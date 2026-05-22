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
package org.opensearch.plugin.otlp.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MetricDocumentTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Gauge ─────────────────────────────────────────────────────────────────

    public void testGaugeDoubleValue() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L).setAsDouble(0.42).build();
        Metric metric = gaugeMetric("cpu.usage", "CPU", "1", dp);
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals("cpu.usage", doc.get("name"));
        assertEquals("Gauge", doc.get("kind"));
        assertEquals("CPU", doc.get("description"));
        assertEquals("1", doc.get("unit"));
        assertEquals(0.42, doc.get("value@double"));
        assertEquals(0.42, doc.get("value"));
        assertNull(doc.get("value@int"));
        assertEquals("1970-01-01T00:00:01Z", doc.get("time"));
        assertEquals("1970-01-01T00:00:01Z", doc.get("@timestamp"));
    }

    public void testGaugeIntValue() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L).setAsInt(100L).build();
        Metric metric = gaugeMetric("requests", "", "", dp);
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals(100, ((Number) doc.get("value@int")).longValue());
        assertEquals(100, ((Number) doc.get("value")).longValue());
        assertNull(doc.get("value@double"));
    }

    public void testDescriptionAndUnitOmittedWhenEmpty() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder().setTimeUnixNano(0).setAsDouble(0).build();
        Metric metric = gaugeMetric("m", "", "", dp);
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertNull(doc.get("description"));
        assertNull(doc.get("unit"));
    }

    // ── Sum ───────────────────────────────────────────────────────────────────

    public void testSumFields() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L).setAsDouble(42.0).build();
        Metric metric = Metric.newBuilder()
            .setName("http.requests")
            .setSum(Sum.newBuilder()
                .setIsMonotonic(true)
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(dp))
            .build();
        Map<String, Object> doc = toMap(new SumDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals("Sum", doc.get("kind"));
        assertEquals(42.0, doc.get("value@double"));
        assertEquals(42.0, doc.get("value"));
        assertEquals(true, doc.get("monotonic"));
        assertEquals("AGGREGATION_TEMPORALITY_CUMULATIVE", doc.get("aggregationTemporality"));
    }

    // ── Histogram ─────────────────────────────────────────────────────────────

    public void testHistogramFields() throws Exception {
        HistogramDataPoint dp = HistogramDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setCount(10).setSum(100.0).setMin(1.0).setMax(20.0)
            .addBucketCounts(2).addBucketCounts(8)
            .addExplicitBounds(10.0)
            .build();
        Metric metric = histogramMetric("latency", dp, AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA);
        Map<String, Object> doc = toMap(new HistogramDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals("Histogram", doc.get("kind"));
        assertEquals(10, ((Number) doc.get("count")).longValue());
        assertEquals(100.0, doc.get("sum"));
        assertEquals(1.0, doc.get("min"));
        assertEquals(20.0, doc.get("max"));
        assertEquals(2, doc.get("bucketCount"));
        assertEquals(1, doc.get("explicitBoundsCount"));
        assertEquals("AGGREGATION_TEMPORALITY_DELTA", doc.get("aggregationTemporality"));
        assertNotNull(doc.get("bucketCountsList"));
        assertNotNull(doc.get("explicitBoundsList"));
        assertNull(doc.get("value"));
    }

    public void testHistogramMinMaxOmittedWhenAbsent() throws Exception {
        HistogramDataPoint dp = HistogramDataPoint.newBuilder().setTimeUnixNano(0).setCount(0).setSum(0).build();
        Metric metric = histogramMetric("h", dp, AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA);
        Map<String, Object> doc = toMap(new HistogramDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertNull(doc.get("min"));
        assertNull(doc.get("max"));
    }

    // ── ExponentialHistogram ──────────────────────────────────────────────────

    public void testExponentialHistogramFields() throws Exception {
        ExponentialHistogramDataPoint dp = ExponentialHistogramDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setCount(5).setSum(50.0).setScale(2).setZeroCount(1)
            .setPositive(ExponentialHistogramDataPoint.Buckets.newBuilder()
                .setOffset(3).addBucketCounts(1).addBucketCounts(2))
            .setNegative(ExponentialHistogramDataPoint.Buckets.newBuilder()
                .setOffset(-1).addBucketCounts(0))
            .build();
        Metric metric = Metric.newBuilder()
            .setName("exp.hist")
            .setExponentialHistogram(io.opentelemetry.proto.metrics.v1.ExponentialHistogram.newBuilder()
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(dp))
            .build();
        Map<String, Object> doc = toMap(new ExponentialHistogramDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals("ExponentialHistogram", doc.get("kind"));
        assertEquals(5, ((Number) doc.get("count")).longValue());
        assertEquals(2, doc.get("scale"));
        assertEquals(1, ((Number) doc.get("zeroCount")).longValue());
        assertEquals(3, doc.get("positiveOffset"));
        assertEquals(-1, doc.get("negativeOffset"));
        assertNotNull(doc.get("positiveBuckets"));
        assertNotNull(doc.get("negativeBuckets"));
        assertEquals("AGGREGATION_TEMPORALITY_CUMULATIVE", doc.get("aggregationTemporality"));
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    public void testSummaryFields() throws Exception {
        SummaryDataPoint dp = SummaryDataPoint.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setCount(100).setSum(5000.0)
            .addQuantileValues(SummaryDataPoint.ValueAtQuantile.newBuilder()
                .setQuantile(0.99).setValue(120.0))
            .build();
        Metric metric = Metric.newBuilder()
            .setName("resp.size")
            .setSummary(Summary.newBuilder().addDataPoints(dp))
            .build();
        Map<String, Object> doc = toMap(new SummaryDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "", metric, dp));

        assertEquals("Summary", doc.get("kind"));
        assertEquals(100, ((Number) doc.get("count")).longValue());
        assertEquals(5000.0, doc.get("sum"));
        assertEquals(1, doc.get("quantileValuesCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quantiles = (List<Map<String, Object>>) doc.get("quantiles");
        assertNotNull(quantiles);
        assertEquals(1, quantiles.size());
        assertEquals(0.99, quantiles.get(0).get("quantile"));
        assertEquals(120.0, quantiles.get(0).get("value"));
        assertNull(doc.get("exemplar"));
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    public void testAttributesPresentAndAbsent() throws Exception {
        NumberDataPoint with = NumberDataPoint.newBuilder()
            .setTimeUnixNano(0).setAsDouble(1.0).addAttributes(kv("http.method", "GET")).build();
        assertNotNull(toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", with), with)).get("attributes"));

        NumberDataPoint without = NumberDataPoint.newBuilder().setTimeUnixNano(0).setAsDouble(1.0).build();
        assertNull(toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", without), without)).get("attributes"));
    }

    // ── Exemplars ─────────────────────────────────────────────────────────────

    public void testExemplarDoubleValue() throws Exception {
        ByteString traceId = ByteString.copyFrom(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16});
        ByteString spanId  = ByteString.copyFrom(new byte[]{1,2,3,4,5,6,7,8});
        NumberDataPoint dp = NumberDataPoint.newBuilder()
            .setTimeUnixNano(0).setAsDouble(1.0)
            .addExemplars(Exemplar.newBuilder()
                .setTimeUnixNano(1_000_000_000L).setTraceId(traceId).setSpanId(spanId).setAsDouble(0.5))
            .build();
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", dp), dp));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exemplars = (List<Map<String, Object>>) doc.get("exemplar");
        assertNotNull(exemplars);
        assertEquals(1, exemplars.size());
        Map<String, Object> ex = exemplars.get(0);
        assertEquals("1970-01-01T00:00:01Z", ex.get("time"));
        assertEquals("0102030405060708090a0b0c0d0e0f10", ex.get("traceId"));
        assertEquals("0102030405060708", ex.get("spanId"));
        assertEquals(0.5, ex.get("value@double"));
        assertEquals(0.5, ex.get("value"));
        assertNull(ex.get("value@int"));
    }

    public void testExemplarIntValue() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
            .setTimeUnixNano(0).setAsDouble(1.0)
            .addExemplars(Exemplar.newBuilder().setTimeUnixNano(0).setAsInt(7L))
            .build();
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", dp), dp));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exemplars = (List<Map<String, Object>>) doc.get("exemplar");
        Map<String, Object> ex = exemplars.get(0);
        assertEquals(7, ((Number) ex.get("value@int")).longValue());
        assertEquals(7, ((Number) ex.get("value")).longValue());
        assertNull(ex.get("value@double"));
    }

    public void testNoExemplarArrayWhenEmpty() throws Exception {
        NumberDataPoint dp = NumberDataPoint.newBuilder().setTimeUnixNano(0).setAsDouble(1.0).build();
        assertNull(toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", dp), dp)).get("exemplar"));
    }

    // ── Context fields ────────────────────────────────────────────────────────

    public void testResourceAndServiceName() throws Exception {
        Resource resource = Resource.newBuilder().addAttributes(kv("service.name", "my-svc")).build();
        NumberDataPoint dp = NumberDataPoint.newBuilder().setTimeUnixNano(0).setAsDouble(0).build();
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            resource, "", InstrumentationScope.getDefaultInstance(), "",
            gaugeMetric("m", "", "", dp), dp));

        assertNotNull(doc.get("resource"));
        assertEquals("my-svc", doc.get("serviceName"));
    }

    public void testInstrumentationScopePresent() throws Exception {
        InstrumentationScope scope = InstrumentationScope.newBuilder().setName("my-lib").build();
        NumberDataPoint dp = NumberDataPoint.newBuilder().setTimeUnixNano(0).setAsDouble(0).build();
        Map<String, Object> doc = toMap(new GaugeDataPointDocument(
            Resource.getDefaultInstance(), "", scope, "",
            gaugeMetric("m", "", "", dp), dp));

        assertNotNull(doc.get("instrumentationScope"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(MetricDocument doc) throws IOException {
        var builder = XContentFactory.jsonBuilder();
        doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private static Metric gaugeMetric(String name, String description, String unit, NumberDataPoint dp) {
        return Metric.newBuilder()
            .setName(name).setDescription(description).setUnit(unit)
            .setGauge(Gauge.newBuilder().addDataPoints(dp))
            .build();
    }

    private static Metric histogramMetric(String name, HistogramDataPoint dp, AggregationTemporality temporality) {
        return Metric.newBuilder()
            .setName(name)
            .setHistogram(io.opentelemetry.proto.metrics.v1.Histogram.newBuilder()
                .setAggregationTemporality(temporality).addDataPoints(dp))
            .build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
