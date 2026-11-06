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
package org.opensearch.plugin.otlp.index;

import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.MetricsRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the OpenSearch telemetry counters for {@link BulkIndexer} and provides
 * unconditional track methods so callers need no null checks.
 *
 * <p>{@link #initialize} may be called at most once and is safe to call from any
 * thread. {@code compareAndSet} ensures exactly one caller wins the race; the
 * {@code volatile} fields guarantee the written counter references are visible to
 * all threads that subsequently call the track methods.
 */
class BulkIndexerMetrics {

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile Counter rejectedCounter;
    private volatile Counter inflightCounter;

    void initialize(MetricsRegistry registry) {
        if (registry != null && initialized.compareAndSet(false, true)) {
            rejectedCounter = registry.createCounter(
                "otlp.rejected_requests", "Requests rejected due to max_inflight_requests limit", "1");
            inflightCounter = registry.createUpDownCounter(
                "otlp.inflight_requests", "Current number of in-flight bulk index requests", "1");
        }
    }

    void trackRejected() {
        Counter c = rejectedCounter;
        if (c != null) c.add(1.0);
    }

    void trackInflightUp() {
        Counter c = inflightCounter;
        if (c != null) c.add(1.0);
    }

    void trackInflightDown() {
        Counter c = inflightCounter;
        if (c != null) c.add(-1.0);
    }
}
