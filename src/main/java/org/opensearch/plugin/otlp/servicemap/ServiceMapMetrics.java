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
import org.opensearch.telemetry.metrics.tags.Tags;

import java.util.concurrent.atomic.AtomicBoolean;

/** OpenSearch telemetry instruments for service-map generation. */
public final class ServiceMapMetrics {

    static final String RUN_DURATION = "otlp.service_map.run.duration";
    static final String RUNS = "otlp.service_map.runs";
    static final String TRACES_PROCESSED = "otlp.service_map.traces.processed";
    static final String SPANS_PROCESSED = "otlp.service_map.spans.processed";
    static final String PROCESSING_LAG = "otlp.service_map.span.processing_lag";
    static final String FAILURES = "otlp.service_map.failures";
    static final String RUNS_SKIPPED = "otlp.service_map.runs.skipped";

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile Histogram runDuration;
    private volatile Counter runs;
    private volatile Counter tracesProcessed;
    private volatile Counter spansProcessed;
    private volatile Histogram processingLag;
    private volatile Counter failures;
    private volatile Counter runsSkipped;

    /** Initializes the instruments once; calls remain no-ops when telemetry is unavailable. */
    public void initialize(MetricsRegistry registry) {
        if (registry != null && initialized.compareAndSet(false, true)) {
            runDuration = registry.createHistogram(
                RUN_DURATION, "Service-map generation run duration", "s");
            runs = registry.createCounter(
                RUNS, "Service-map generation runs by outcome", "1");
            tracesProcessed = registry.createCounter(
                TRACES_PROCESSED, "Traces evaluated by successful service-map runs", "1");
            spansProcessed = registry.createCounter(
                SPANS_PROCESSED, "Spans evaluated by successful service-map runs", "1");
            processingLag = registry.createHistogram(
                PROCESSING_LAG, "Delay between span indexing and successful service-map processing", "s");
            failures = registry.createCounter(
                FAILURES, "Service-map generation failures by phase", "1");
            runsSkipped = registry.createCounter(
                RUNS_SKIPPED, "Service-map generation runs skipped because another run is active", "1");
        }
    }

    void recordRun(double durationSeconds, RunOutcome outcome) {
        Histogram duration = runDuration;
        if (duration != null) {
            duration.record(durationSeconds, outcome.tags);
        }
        Counter runCounter = runs;
        if (runCounter != null) {
            runCounter.add(1.0, outcome.tags);
        }
    }

    void recordProcessed(long traceCount, long spanCount, double lagSeconds) {
        Counter traceCounter = tracesProcessed;
        if (traceCounter != null && traceCount > 0) {
            traceCounter.add(traceCount);
        }
        Counter spanCounter = spansProcessed;
        if (spanCounter != null && spanCount > 0) {
            spanCounter.add(spanCount);
        }
        Histogram lag = processingLag;
        if (lag != null && traceCount > 0) {
            lag.record(lagSeconds);
        }
    }

    void recordFailure(ExecutionPhase phase) {
        Counter failureCounter = failures;
        if (failureCounter != null) {
            failureCounter.add(1.0, phase.tags);
        }
    }

    void recordSkippedRun() {
        Counter skippedCounter = runsSkipped;
        if (skippedCounter != null) {
            skippedCounter.add(1.0);
        }
    }

    enum RunOutcome {
        SUCCESS("success"),
        FAILURE("failure"),
        CANCELLED("cancelled");

        private final Tags tags;

        RunOutcome(String value) {
            tags = Tags.of("outcome", value);
        }
    }

    enum ExecutionPhase {
        STATE_LOAD("state_load"),
        DISCOVERY("discovery"),
        TRACE_LOAD("trace_load"),
        SERVICE_MAP_WRITE("service_map_write"),
        STATE_COMMIT("state_commit"),
        CLEANUP("cleanup");

        private final Tags tags;

        ExecutionPhase(String value) {
            tags = Tags.of("phase", value);
        }
    }
}
