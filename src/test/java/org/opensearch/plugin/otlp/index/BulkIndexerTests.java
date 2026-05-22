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

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.Histogram;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.metrics.tags.Tags;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.support.AbstractClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public class BulkIndexerTests extends OpenSearchTestCase {

    // ── semaphore rejects when limit is exhausted ─────────────────────────────

    public void testRejectWhenSemaphoreFull() throws Exception {
        StubMetricsRegistry metrics = new StubMetricsRegistry();
        // Semaphore of 1, never completes the first request so it stays acquired
        HangingClient client = new HangingClient();
        BulkIndexer indexer = new BulkIndexer(client, 1, false, metrics);

        // First call acquires the semaphore
        indexer.index("idx", List.of(new MapDocument("k", "v")));

        // Second call should be rejected immediately
        CompletableFuture<BulkResponse> rejected = indexer.index("idx", List.of(new MapDocument("k", "v")));
        assertTrue("rejected future must be complete", rejected.isDone());

        ExecutionException ex = expectThrows(ExecutionException.class, rejected::get);
        assertTrue("cause is rejection", ex.getCause() instanceof OpenSearchRejectedExecutionException);
        assertEquals("rejection counter incremented", 1, metrics.rejected.count);
    }

    // ── semaphore released on success ─────────────────────────────────────────

    public void testSemaphoreReleasedOnSuccess() throws Exception {
        StubMetricsRegistry metrics = new StubMetricsRegistry();
        SucceedingClient client = new SucceedingClient(false);
        BulkIndexer indexer = new BulkIndexer(client, 1, false, metrics);

        indexer.index("idx", List.of(new MapDocument("k", "v"))).get();

        // Semaphore must be free — a second request should succeed
        CompletableFuture<BulkResponse> second = indexer.index("idx", List.of(new MapDocument("k", "v")));
        assertFalse("second call not rejected", second.isCompletedExceptionally());
        assertEquals("no rejections", 0, metrics.rejected.count);
    }

    // ── semaphore released on bulk failure ────────────────────────────────────

    public void testSemaphoreReleasedOnFailure() throws Exception {
        StubMetricsRegistry metrics = new StubMetricsRegistry();
        FailingClient client = new FailingClient();
        BulkIndexer indexer = new BulkIndexer(client, 1, false, metrics);

        CompletableFuture<BulkResponse> first = indexer.index("idx", List.of(new MapDocument("k", "v")));
        expectThrows(ExecutionException.class, first::get);

        // Semaphore must be free again
        CompletableFuture<BulkResponse> second = indexer.index("idx", List.of(new MapDocument("k", "v")));
        expectThrows(ExecutionException.class, second::get);
        assertEquals("no rejections — both failed via onFailure not semaphore", 0, metrics.rejected.count);
    }

    // ── requireAlias=true sets flag on each IndexRequest ─────────────────────

    public void testRequireAliasSetOnRequest() throws Exception {
        StubMetricsRegistry metrics = new StubMetricsRegistry();
        CapturingClient client = new CapturingClient();
        BulkIndexer indexer = new BulkIndexer(client, 64, true, metrics);

        indexer.index("idx", List.of(new MapDocument("k", "v"))).get();

        assertFalse("at least one IndexRequest captured", client.captured.isEmpty());
        for (IndexRequest req : client.captured) {
            assertTrue("requireAlias set", req.isRequireAlias());
        }
    }

    // ── requireAlias=false leaves flag unset ─────────────────────────────────

    public void testRequireAliasNotSetWhenDisabled() throws Exception {
        StubMetricsRegistry metrics = new StubMetricsRegistry();
        CapturingClient client = new CapturingClient();
        BulkIndexer indexer = new BulkIndexer(client, 64, false, metrics);

        indexer.index("idx", List.of(new MapDocument("k", "v"))).get();

        for (IndexRequest req : client.captured) {
            assertFalse("requireAlias not set", req.isRequireAlias());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Simple ToXContent document backed by a single key-value pair. */
    private record MapDocument(String key, String value)
            implements org.opensearch.core.xcontent.ToXContent {
        @Override
        public org.opensearch.core.xcontent.XContentBuilder toXContent(
                org.opensearch.core.xcontent.XContentBuilder builder,
                Params params) throws java.io.IOException {
            builder.startObject();
            builder.field(key, value);
            builder.endObject();
            return builder;
        }
    }

    public static class CountingCounter implements Counter {
        volatile int count = 0;
        @Override public void add(double v) { count += (int) v; }
        @Override public void add(double v, Tags t) { add(v); }
    }

    public static class StubMetricsRegistry implements MetricsRegistry {
        final CountingCounter rejected = new CountingCounter();
        final CountingCounter inflight = new CountingCounter();
        private int callCount = 0;

        @Override
        public Counter createCounter(String name, String description, String unit) {
            return callCount++ == 0 ? rejected : new CountingCounter();
        }

        @Override
        public Counter createUpDownCounter(String name, String description, String unit) {
            return inflight;
        }

        @Override
        public Histogram createHistogram(String name, String description, String unit) {
            return new Histogram() {
                @Override public void record(double v) {}
                @Override public void record(double v, Tags t) {}
            };
        }

        @Override
        public java.io.Closeable createGauge(String name, String description, String unit,
                Supplier<Double> value, Tags tags) {
            return () -> {};
        }

        @Override
        public java.io.Closeable createGauge(String name, String description, String unit,
                Supplier<org.opensearch.telemetry.metrics.TaggedMeasurement> value) {
            return () -> {};
        }

        @Override
        public void close() {}
    }

    /** Client that never completes the bulk request (simulates a slow cluster). */
    private static class HangingClient extends AbstractClient {
        HangingClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            // intentionally do nothing — future never completes
        }
        @Override public void close() {}
    }

    /** Client that immediately completes every bulk with success or item-level failures. */
    static class SucceedingClient extends AbstractClient {
        private final boolean withFailures;
        SucceedingClient(boolean withFailures) {
            super(org.opensearch.common.settings.Settings.EMPTY, null);
            this.withFailures = withFailures;
        }
        @Override
        @SuppressWarnings("unchecked")
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            if (withFailures) {
                BulkItemResponse.Failure failure = new BulkItemResponse.Failure(
                    "idx", "1", new RuntimeException("mapping error"));
                BulkItemResponse item = new BulkItemResponse(0, null, failure);
                listener.onResponse((Res) new BulkResponse(new BulkItemResponse[]{item}, 0));
            } else {
                listener.onResponse((Res) new BulkResponse(new BulkItemResponse[0], 0));
            }
        }
        @Override public void close() {}
    }

    /** Client that immediately fails every bulk with an exception. */
    private static class FailingClient extends AbstractClient {
        FailingClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            listener.onFailure(new RuntimeException("cluster unavailable"));
        }
        @Override public void close() {}
    }

    /** Client that captures all IndexRequests from bulk operations. */
    private static class CapturingClient extends AbstractClient {
        final List<IndexRequest> captured = new ArrayList<>();
        CapturingClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        @SuppressWarnings("unchecked")
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            if (request instanceof org.opensearch.action.bulk.BulkRequest br) {
                br.requests().forEach(r -> { if (r instanceof IndexRequest ir) captured.add(ir); });
                listener.onResponse((Res) new BulkResponse(new BulkItemResponse[0], 0));
            }
        }
        @Override public void close() {}
    }
}
