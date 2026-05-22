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
package org.opensearch.plugin.otlp.server;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.index.BulkIndexerTests;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.support.AbstractClient;

import com.carrotsearch.randomizedtesting.ThreadFilter;

import java.time.Duration;

@ThreadLeakFilters(filters = OTLPHttpHandlerTests.ArmeriaThreadFilter.class)
public class OTLPHttpHandlerTests extends OpenSearchTestCase {

    private static final ByteString TRACE_ID = ByteString.copyFrom(new byte[16]);
    private static final ByteString SPAN_ID  = ByteString.copyFrom(new byte[8]);

    // ── /v1/traces — protobuf request returns 200 with protobuf body ──────────

    public void testTracesProtoRequestReturns200Proto() throws Exception {
        OTLPHttpHandler handler = handlerWithClient(new SucceedingBulkClient());

        AggregatedHttpResponse response = handler.exportTraces(protoRequest("/v1/traces", oneSpanRequest()))
            .get().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        assertTrue("content-type is protobuf",
            response.contentType().is(MediaType.parse("application/x-protobuf")));
    }

    // ── /v1/metrics — protobuf request returns 200 ───────────────────────────

    public void testMetricsProtoRequestReturns200() throws Exception {
        OTLPHttpHandler handler = handlerWithClient(new SucceedingBulkClient());

        AggregatedHttpResponse response = handler.exportMetrics(protoRequest("/v1/metrics", oneMetricRequest()))
            .get().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
    }

    // ── /v1/logs — protobuf request returns 200 ──────────────────────────────

    public void testLogsProtoRequestReturns200() throws Exception {
        OTLPHttpHandler handler = handlerWithClient(new SucceedingBulkClient());

        AggregatedHttpResponse response = handler.exportLogs(protoRequest("/v1/logs", oneLogRequest()))
            .get().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
    }

    // ── OpenSearchRejectedExecutionException → 429 + Retry-After from calculator

    public void testRejectionReturns429WithRetryAfterFromCalculator() throws Exception {
        OTLPGrpcExceptionHandlerTests.FixedRetryInfoCalculator fixedCalc =
            new OTLPGrpcExceptionHandlerTests.FixedRetryInfoCalculator(Duration.ofSeconds(7));
        OTLPHttpHandler handler = handlerWithClientAndCalc(new RejectingBulkClient(), fixedCalc);

        AggregatedHttpResponse response = handler.exportTraces(protoRequest("/v1/traces", oneSpanRequest()))
            .get().aggregate().join();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.status());
        assertEquals("Retry-After reflects calculator delay", "7",
            response.headers().get("Retry-After"));
    }

    // ── Retry-After increases on consecutive rejections (backoff progression) ──

    public void testRetryAfterIncreasesOnConsecutiveRejections() throws Exception {
        RetryInfoCalculator sharedCalc = new RetryInfoCalculator(
            Duration.ofSeconds(1), Duration.ofSeconds(30));
        OTLPHttpHandler handler = handlerWithClientAndCalc(new RejectingBulkClient(), sharedCalc);

        AggregatedHttpResponse first = handler.exportTraces(protoRequest("/v1/traces", oneSpanRequest()))
            .get().aggregate().join();
        AggregatedHttpResponse second = handler.exportTraces(protoRequest("/v1/traces", oneSpanRequest()))
            .get().aggregate().join();

        int firstRetry  = Integer.parseInt(first.headers().get("Retry-After"));
        int secondRetry = Integer.parseInt(second.headers().get("Retry-After"));
        assertTrue("second Retry-After >= first (backoff increasing)", secondRetry >= firstRetry);
    }

    // ── generic exception → 500 ───────────────────────────────────────────────

    public void testGenericErrorReturns500() throws Exception {
        OTLPHttpHandler handler = handlerWithClient(new FailingBulkClient());

        AggregatedHttpResponse response = handler.exportTraces(protoRequest("/v1/traces", oneSpanRequest()))
            .get().aggregate().join();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.status());
    }

    // ── JSON content-type round-trip ──────────────────────────────────────────

    public void testJsonRequestReturns200Json() throws Exception {
        OTLPHttpHandler handler = handlerWithClient(new SucceedingBulkClient());

        HttpRequest req = HttpRequest.of(
            RequestHeaders.builder(HttpMethod.POST, "/v1/traces")
                .contentType(MediaType.JSON_UTF_8)
                .build(),
            HttpData.ofUtf8("{}"));

        AggregatedHttpResponse response = handler.exportTraces(req).get().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        assertTrue("content-type is JSON", response.contentType().is(MediaType.JSON_UTF_8));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static OTLPHttpHandler handlerWithClient(AbstractClient client) {
        return handlerWithClientAndCalc(client,
            new RetryInfoCalculator(Duration.ofMillis(100), Duration.ofSeconds(2)));
    }

    private static OTLPHttpHandler handlerWithClientAndCalc(
            AbstractClient client, RetryInfoCalculator retryInfoCalculator) {
        BulkIndexerTests.StubMetricsRegistry metrics = new BulkIndexerTests.StubMetricsRegistry();
        BulkIndexer bulkIndexer = new BulkIndexer(client, 64, false, metrics);
        return new OTLPHttpHandler(
            new TraceTransformer(),
            new LogsTransformer(),
            new MetricsTransformer(),
            bulkIndexer,
            "traces-idx", "logs-idx", "metrics-idx",
            retryInfoCalculator);
    }

    private static HttpRequest protoRequest(String path, byte[] body) {
        return HttpRequest.of(
            RequestHeaders.builder(HttpMethod.POST, path)
                .contentType(MediaType.parse("application/x-protobuf"))
                .build(),
            HttpData.wrap(body));
    }

    private static byte[] oneSpanRequest() {
        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.getDefaultInstance())
                .addScopeSpans(ScopeSpans.newBuilder()
                    .setScope(InstrumentationScope.getDefaultInstance())
                    .addSpans(Span.newBuilder()
                        .setTraceId(TRACE_ID)
                        .setSpanId(SPAN_ID)
                        .setName("test-span")
                        .build())))
            .build().toByteArray();
    }

    private static byte[] oneLogRequest() {
        return ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(ResourceLogs.newBuilder()
                .setResource(Resource.getDefaultInstance())
                .addScopeLogs(ScopeLogs.newBuilder()
                    .setScope(InstrumentationScope.getDefaultInstance())
                    .addLogRecords(LogRecord.newBuilder()
                        .setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
                            .setStringValue("log line"))
                        .build())))
            .build().toByteArray();
    }

    private static byte[] oneMetricRequest() {
        return ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(Resource.getDefaultInstance())
                .addScopeMetrics(ScopeMetrics.newBuilder()
                    .setScope(InstrumentationScope.getDefaultInstance())
                    .addMetrics(Metric.newBuilder()
                        .setName("test.counter")
                        .setSum(Sum.newBuilder()
                            .addDataPoints(NumberDataPoint.newBuilder()
                                .setAsInt(1L)
                                .build())
                            .build())
                        .build())))
            .build().toByteArray();
    }

    /** Client that immediately completes every bulk with no failures. */
    private static class SucceedingBulkClient extends AbstractClient {
        SucceedingBulkClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        @SuppressWarnings("unchecked")
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            listener.onResponse((Res) new BulkResponse(new BulkItemResponse[0], 0));
        }
        @Override public void close() {}
    }

    /** Client whose bulk always fails with OpenSearchRejectedExecutionException. */
    private static class RejectingBulkClient extends AbstractClient {
        RejectingBulkClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            listener.onFailure(new OpenSearchRejectedExecutionException("overloaded"));
        }
        @Override public void close() {}
    }

    /** Client whose bulk always fails with a generic exception. */
    private static class FailingBulkClient extends AbstractClient {
        FailingBulkClient() { super(org.opensearch.common.settings.Settings.EMPTY, null); }
        @Override
        protected <Req extends ActionRequest, Res extends ActionResponse>
        void doExecute(ActionType<Res> action, Req request, ActionListener<Res> listener) {
            listener.onFailure(new RuntimeException("disk full"));
        }
        @Override public void close() {}
    }

    public static final class ArmeriaThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            String name = t.getName();
            return name.startsWith("armeria-") || name.startsWith("globalEventExecutor");
        }
    }
}
