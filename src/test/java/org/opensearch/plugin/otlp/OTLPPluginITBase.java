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
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ThreadLeakFilters(filters = OTLPPluginITBase.KnownThreadFilter.class)
public abstract class OTLPPluginITBase extends OpenSearchSingleNodeTestCase {

    // Ephemeral ports allocated once for the whole JVM run to avoid port conflicts
    // between test classes that run sequentially in the same JVM.
    static final int OTLP_HTTP_PORT = allocateFreePort();
    static final int OTLP_GRPC_PORT = allocateFreePort();

    protected enum OtlpProtocol { GRPC, HTTP_PROTOBUF, HTTP_JSON }

    // Shared HTTP client for the hand-rolled HTTP/JSON transport.
    // The SDK does not expose http/json via a public builder API in 1.47.0;
    // gRPC and HTTP/proto use the OTel SDK exporters.
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static int allocateFreePort() {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Cannot allocate ephemeral port", e);
        }
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(OTLPPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
            .put(super.nodeSettings())
            .put("plugins.otlp.http.port", OTLP_HTTP_PORT)
            .put("plugins.otlp.port", OTLP_GRPC_PORT)
            .put("plugins.otlp.index.create_templates", true)
            .put("plugins.otlp.traces.index",  "otel-v1-apm-span-test")
            .put("plugins.otlp.logs.index",    "logs-otel-v1-test")
            .put("plugins.otlp.metrics.index", "metrics-otel-v1-test")
            .build();
    }

    // The node is shared across all test methods in a class; only the test index
    // is deleted between tests so each method starts with a clean slate.
    @Override
    protected boolean resetNodeAfterTest() {
        return false;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Wait for the async IndexTemplateManager to install all three composable templates.
        // This only blocks on the first test in each class; subsequent tests find them instantly.
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
        String[] indices = {"otel-v1-apm-span-test", "logs-otel-v1-test", "metrics-otel-v1-test"};
        for (String index : indices) {
            try {
                client().admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
            } catch (Exception ignored) {
                // index may not exist if the test did not create it
            }
        }
    }

    // ── OTel SDK exporter builders (gRPC and HTTP/protobuf only) ─────────────────
    // HTTP/JSON is handled by sendJsonOTLP below — the OTel SDK 1.47.0 does not
    // expose http/json via a public builder API.

    protected SpanExporter buildSpanExporter(OtlpProtocol protocol) {
        String httpEndpoint = "http://localhost:" + OTLP_HTTP_PORT;
        String grpcEndpoint = "http://localhost:" + OTLP_GRPC_PORT;
        switch (protocol) {
            case GRPC:
                return OtlpGrpcSpanExporter.builder().setEndpoint(grpcEndpoint).build();
            case HTTP_PROTOBUF:
                return OtlpHttpSpanExporter.builder().setEndpoint(httpEndpoint + "/v1/traces").build();
            default:
                throw new IllegalArgumentException("Use sendJsonOTLP for HTTP_JSON");
        }
    }

    protected MetricExporter buildMetricExporter(OtlpProtocol protocol) {
        String httpEndpoint = "http://localhost:" + OTLP_HTTP_PORT;
        String grpcEndpoint = "http://localhost:" + OTLP_GRPC_PORT;
        switch (protocol) {
            case GRPC:
                return OtlpGrpcMetricExporter.builder().setEndpoint(grpcEndpoint).build();
            case HTTP_PROTOBUF:
                return OtlpHttpMetricExporter.builder().setEndpoint(httpEndpoint + "/v1/metrics").build();
            default:
                throw new IllegalArgumentException("Use sendJsonOTLP for HTTP_JSON");
        }
    }

    protected LogRecordExporter buildLogExporter(OtlpProtocol protocol) {
        String httpEndpoint = "http://localhost:" + OTLP_HTTP_PORT;
        String grpcEndpoint = "http://localhost:" + OTLP_GRPC_PORT;
        switch (protocol) {
            case GRPC:
                return OtlpGrpcLogRecordExporter.builder().setEndpoint(grpcEndpoint).build();
            case HTTP_PROTOBUF:
                return OtlpHttpLogRecordExporter.builder().setEndpoint(httpEndpoint + "/v1/logs").build();
            default:
                throw new IllegalArgumentException("Use sendJsonOTLP for HTTP_JSON");
        }
    }

    protected static Resource telemetryResource(String serviceName) {
        return Resource.builder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
    }

    // ── HTTP/JSON transport (hand-rolled, SDK does not expose this protocol) ──────

    protected void sendJsonOTLP(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + OTLP_HTTP_PORT + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals("HTTP/JSON 200 expected for " + path + ", body: " + resp.body(),
            200, resp.statusCode());
    }

    // ── OpenSearch helpers ────────────────────────────────────────────────────────

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

    // ── Thread leak suppression ──────────────────────────────────────────────────

    // Suppresses Armeria's NIO worker threads, OkHttp/gRPC background threads,
    // JDK HttpClient I/O threads, and the JDK's common ForkJoinPool workers,
    // all of which are long-lived background infrastructure that outlive individual test suites.
    public static final class KnownThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            String name = t.getName();
            return name.startsWith("armeria-")
                || name.startsWith("OkHttp")
                || name.equals("Okio Watchdog")
                || name.startsWith("grpc-")
                || name.startsWith("HttpClient-")
                || name.startsWith("ForkJoinPool.commonPool-")
                || name.startsWith("startstop-");
        }
    }
}
