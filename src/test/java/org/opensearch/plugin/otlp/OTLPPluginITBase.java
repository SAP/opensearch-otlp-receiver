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

import com.carrotsearch.randomizedtesting.ThreadFilter;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.server.OTLPLogsGrpcService;
import org.opensearch.plugin.otlp.server.OTLPMetricsGrpcService;
import org.opensearch.plugin.otlp.server.OTLPTraceGrpcService;
import org.opensearch.plugin.otlp.server.RetryInfoCalculator;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ThreadLeakFilters(filters = OTLPPluginITBase.KnownThreadFilter.class)
public abstract class OTLPPluginITBase extends OpenSearchSingleNodeTestCase {

    static final String TRACES_INDEX  = "otel-v1-apm-span-test";
    static final String LOGS_INDEX    = "logs-otel-v1-test";
    static final String METRICS_INDEX = "metrics-otel-v1-test";

    protected OTLPTraceGrpcService   traceService;
    protected OTLPMetricsGrpcService metricsService;
    protected OTLPLogsGrpcService    logsService;

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(OTLPPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
            .put(super.nodeSettings())
            .put("plugins.otlp.index.create_templates", true)
            .put("plugins.otlp.traces.index",  TRACES_INDEX)
            .put("plugins.otlp.logs.index",    LOGS_INDEX)
            .put("plugins.otlp.metrics.index", METRICS_INDEX)
            .build();
    }

    @Override
    protected boolean resetNodeAfterTest() {
        return false;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Wire service instances directly against the embedded OpenSearch client.
        // This exercises the transformer + indexer pipeline without a gRPC network connection.
        BulkIndexer indexer = new BulkIndexer(client(), 64, false, null);
        RetryInfoCalculator retryCalc = new RetryInfoCalculator(Duration.ofMillis(100), Duration.ofSeconds(2));
        traceService   = new OTLPTraceGrpcService  (indexer, new TraceTransformer(),   retryCalc, TRACES_INDEX);
        metricsService = new OTLPMetricsGrpcService(indexer, new MetricsTransformer(), retryCalc, METRICS_INDEX);
        logsService    = new OTLPLogsGrpcService   (indexer, new LogsTransformer(),    retryCalc, LOGS_INDEX);

        assertBusy(() -> {
            var templates = getInstanceFromNode(org.opensearch.cluster.service.ClusterService.class)
                .state().metadata().templatesV2();
            assertTrue("ss4o_traces template not yet installed",  templates.containsKey("ss4o_traces"));
            assertTrue("ss4o_logs template not yet installed",    templates.containsKey("ss4o_logs"));
            assertTrue("ss4o_metrics template not yet installed", templates.containsKey("ss4o_metrics"));
        }, 30, TimeUnit.SECONDS);
    }

    @Override
    public void tearDown() throws Exception {
        deleteTestIndices();
        super.tearDown();
    }

    private void deleteTestIndices() {
        for (String index : new String[]{ TRACES_INDEX, LOGS_INDEX, METRICS_INDEX,
                "otel-v1-apm-service-map-test" }) {
            try {
                client().admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
            } catch (Exception ignored) {}
        }
    }

    // ── gRPC export helpers ───────────────────────────────────────────────────

    protected void export(ExportTraceServiceRequest request) throws Exception {
        SyncObserver<ExportTraceServiceResponse> obs = new SyncObserver<>();
        traceService.export(request, obs);
        obs.await();
    }

    protected void export(ExportMetricsServiceRequest request) throws Exception {
        SyncObserver<ExportMetricsServiceResponse> obs = new SyncObserver<>();
        metricsService.export(request, obs);
        obs.await();
    }

    protected void export(ExportLogsServiceRequest request) throws Exception {
        SyncObserver<ExportLogsServiceResponse> obs = new SyncObserver<>();
        logsService.export(request, obs);
        obs.await();
    }

    // ── OpenSearch helpers ────────────────────────────────────────────────────

    protected void refresh(String index) {
        client().admin().indices().refresh(new RefreshRequest(index)).actionGet();
    }

    protected SearchResponse searchAll(String index) {
        return client().prepareSearch(index).setSize(100).get();
    }

    protected void assertMappingType(Map<String, Object> props, String field, String expectedType) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldMapping = (Map<String, Object>) props.get(field);
        assertNotNull("Mapping for '" + field + "' is missing", fieldMapping);
        assertEquals("Wrong type for '" + field + "'", expectedType, fieldMapping.get("type"));
    }

    // ── Synchronous StreamObserver ────────────────────────────────────────────

    public static class SyncObserver<T> implements StreamObserver<T> {
        private volatile Throwable error;
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override public void onNext(T value) {}
        @Override public void onCompleted() { latch.countDown(); }
        @Override public void onError(Throwable t) { error = t; latch.countDown(); }

        public void await() throws Exception {
            assertTrue("gRPC export timed out after 10 s", latch.await(10, TimeUnit.SECONDS));
            if (error != null) {
                throw new RuntimeException("gRPC export failed: " + error.getMessage(), error);
            }
        }
    }

    // ── Thread leak suppression ───────────────────────────────────────────────

    public static final class KnownThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            String name = t.getName();
            return name.startsWith("ForkJoinPool.commonPool-")
                || name.startsWith("startstop-");
        }
    }
}
