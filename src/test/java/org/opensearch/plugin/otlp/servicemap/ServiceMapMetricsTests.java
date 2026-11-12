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
package org.opensearch.plugin.otlp.servicemap;

import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.Histogram;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.metrics.TaggedMeasurement;
import org.opensearch.telemetry.metrics.tags.Tags;
import org.opensearch.test.OpenSearchTestCase;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ServiceMapMetricsTests extends OpenSearchTestCase {

    public void testRecordsServiceMapRunAndWorkloadMetrics() {
        RecordingMetricsRegistry registry = new RecordingMetricsRegistry();
        ServiceMapMetrics metrics = new ServiceMapMetrics();
        metrics.initialize(registry);

        metrics.recordRun(1.25, ServiceMapMetrics.RunOutcome.SUCCESS);
        metrics.recordProcessed(3, 12, 2.5);
        metrics.recordFailure(ServiceMapMetrics.ExecutionPhase.TRACE_LOAD);
        metrics.recordSkippedRun();

        assertEquals(1.0, registry.counter(ServiceMapMetrics.RUNS).total, 0.0);
        assertEquals("success", registry.counter(ServiceMapMetrics.RUNS).samples.get(0).tags().get("outcome"));
        assertEquals(3.0, registry.counter(ServiceMapMetrics.TRACES_PROCESSED).total, 0.0);
        assertEquals(12.0, registry.counter(ServiceMapMetrics.SPANS_PROCESSED).total, 0.0);
        assertEquals(1.0, registry.counter(ServiceMapMetrics.FAILURES).total, 0.0);
        assertEquals("trace_load",
            registry.counter(ServiceMapMetrics.FAILURES).samples.get(0).tags().get("phase"));
        assertEquals(1.0, registry.counter(ServiceMapMetrics.RUNS_SKIPPED).total, 0.0);
        assertEquals(1.25, registry.histogram(ServiceMapMetrics.RUN_DURATION).samples.get(0).value(), 0.0);
        assertEquals("success",
            registry.histogram(ServiceMapMetrics.RUN_DURATION).samples.get(0).tags().get("outcome"));
        assertEquals(2.5, registry.histogram(ServiceMapMetrics.PROCESSING_LAG).samples.get(0).value(), 0.0);
    }

    public void testDoesNotRecordLagWithoutProcessedTraces() {
        RecordingMetricsRegistry registry = new RecordingMetricsRegistry();
        ServiceMapMetrics metrics = new ServiceMapMetrics();
        metrics.initialize(registry);

        metrics.recordProcessed(0, 0, 10.0);

        assertTrue(registry.histogram(ServiceMapMetrics.PROCESSING_LAG).samples.isEmpty());
    }

    public void testInitializesOnlyOnce() {
        RecordingMetricsRegistry first = new RecordingMetricsRegistry();
        RecordingMetricsRegistry second = new RecordingMetricsRegistry();
        ServiceMapMetrics metrics = new ServiceMapMetrics();

        metrics.initialize(first);
        metrics.initialize(second);

        assertFalse(first.counters.isEmpty());
        assertTrue(second.counters.isEmpty());
        assertTrue(second.histograms.isEmpty());
    }

    static final class RecordingMetricsRegistry implements MetricsRegistry {
        private final Map<String, RecordingCounter> counters = new HashMap<>();
        private final Map<String, RecordingHistogram> histograms = new HashMap<>();

        @Override
        public Counter createCounter(String name, String description, String unit) {
            RecordingCounter counter = new RecordingCounter();
            counters.put(name, counter);
            return counter;
        }

        @Override
        public Counter createUpDownCounter(String name, String description, String unit) {
            return createCounter(name, description, unit);
        }

        @Override
        public Histogram createHistogram(String name, String description, String unit) {
            RecordingHistogram histogram = new RecordingHistogram();
            histograms.put(name, histogram);
            return histogram;
        }

        @Override
        public Closeable createGauge(
                String name, String description, String unit, Supplier<Double> value, Tags tags) {
            return () -> {};
        }

        @Override
        public Closeable createGauge(
                String name, String description, String unit, Supplier<TaggedMeasurement> value) {
            return () -> {};
        }

        @Override
        public void close() {}

        RecordingCounter counter(String name) {
            return counters.get(name);
        }

        RecordingHistogram histogram(String name) {
            return histograms.get(name);
        }
    }

    static final class RecordingCounter implements Counter {
        final List<Sample> samples = new ArrayList<>();
        double total;

        @Override
        public void add(double value) {
            add(value, Tags.EMPTY);
        }

        @Override
        public void add(double value, Tags tags) {
            total += value;
            samples.add(new Sample(value, tags.getTagsMap()));
        }
    }

    static final class RecordingHistogram implements Histogram {
        final List<Sample> samples = new ArrayList<>();

        @Override
        public void record(double value) {
            record(value, Tags.EMPTY);
        }

        @Override
        public void record(double value, Tags tags) {
            samples.add(new Sample(value, tags.getTagsMap()));
        }
    }

    record Sample(double value, Map<String, ?> tags) {}
}
