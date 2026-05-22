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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.Post;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;

import java.util.concurrent.CompletableFuture;

public class OTLPHttpHandler {

    private static final Logger logger = LogManager.getLogger(OTLPHttpHandler.class);

    private static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

    private final TraceTransformer traceTransformer;
    private final LogsTransformer logsTransformer;
    private final MetricsTransformer metricsTransformer;
    private final BulkIndexer bulkIndexer;
    private final String tracesIndex;
    private final String logsIndex;
    private final String metricsIndex;
    private final RetryInfoCalculator retryInfoCalculator;

    public OTLPHttpHandler(
            TraceTransformer traceTransformer,
            LogsTransformer logsTransformer,
            MetricsTransformer metricsTransformer,
            BulkIndexer bulkIndexer,
            String tracesIndex,
            String logsIndex,
            String metricsIndex,
            RetryInfoCalculator retryInfoCalculator) {
        this.traceTransformer = traceTransformer;
        this.logsTransformer = logsTransformer;
        this.metricsTransformer = metricsTransformer;
        this.bulkIndexer = bulkIndexer;
        this.tracesIndex = tracesIndex;
        this.logsIndex = logsIndex;
        this.metricsIndex = metricsIndex;
        this.retryInfoCalculator = retryInfoCalculator;
    }

    @Post("/v1/traces")
    public CompletableFuture<HttpResponse> exportTraces(HttpRequest req) {
        return req.aggregate().thenCompose(agg -> {
            try {
                ExportTraceServiceRequest request = parseOrDecode(
                    agg.content().array(), agg.contentType(),
                    ExportTraceServiceRequest.newBuilder());
                return bulkIndexer.index(tracesIndex, traceTransformer.transform(request))
                    .thenApply(r -> respond(ExportTraceServiceResponse.getDefaultInstance(), agg.contentType()))
                    .exceptionally(e -> errorResponse(e, "/v1/traces"));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(errorResponse(e, "/v1/traces"));
            }
        });
    }

    @Post("/v1/metrics")
    public CompletableFuture<HttpResponse> exportMetrics(HttpRequest req) {
        return req.aggregate().thenCompose(agg -> {
            try {
                ExportMetricsServiceRequest request = parseOrDecode(
                    agg.content().array(), agg.contentType(),
                    ExportMetricsServiceRequest.newBuilder());
                return bulkIndexer.index(metricsIndex, metricsTransformer.transform(request))
                    .thenApply(r -> respond(ExportMetricsServiceResponse.getDefaultInstance(), agg.contentType()))
                    .exceptionally(e -> errorResponse(e, "/v1/metrics"));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(errorResponse(e, "/v1/metrics"));
            }
        });
    }

    @Post("/v1/logs")
    public CompletableFuture<HttpResponse> exportLogs(HttpRequest req) {
        return req.aggregate().thenCompose(agg -> {
            try {
                ExportLogsServiceRequest request = parseOrDecode(
                    agg.content().array(), agg.contentType(),
                    ExportLogsServiceRequest.newBuilder());
                return bulkIndexer.index(logsIndex, logsTransformer.transform(request))
                    .thenApply(r -> respond(ExportLogsServiceResponse.getDefaultInstance(), agg.contentType()))
                    .exceptionally(e -> errorResponse(e, "/v1/logs"));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(errorResponse(e, "/v1/logs"));
            }
        });
    }

    private HttpResponse errorResponse(Throwable e, String path) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof OpenSearchRejectedExecutionException) {
            logger.warn("OTLP {} export rejected — server overloaded", path);
            long retrySeconds = retryInfoCalculator.createRetryInfo().getRetryDelay().getSeconds();
            long retryAfter = Math.max(1, retrySeconds);
            return HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.TOO_MANY_REQUESTS)
                    .add(HttpHeaderNames.RETRY_AFTER, String.valueOf(retryAfter))
                    .build());
        }
        logger.error("Failed to index {}", path, e);
        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message> T parseOrDecode(
            byte[] body, MediaType contentType, Message.Builder builder) throws Exception {
        if (contentType != null && contentType.is(MediaType.JSON)) {
            JsonFormat.parser().ignoringUnknownFields().merge(new String(body, java.nio.charset.StandardCharsets.UTF_8), builder);
        } else {
            builder.mergeFrom(body);
        }
        return (T) builder.build();
    }

    private static HttpResponse respond(Message response, MediaType requestContentType) {
        try {
            if (requestContentType != null && requestContentType.is(MediaType.JSON_UTF_8)) {
                String json = JsonFormat.printer().print(response);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, json);
            } else {
                byte[] bytes = response.toByteArray();
                return HttpResponse.of(HttpStatus.OK,
                    MediaType.parse(PROTOBUF_CONTENT_TYPE), bytes);
            }
        } catch (InvalidProtocolBufferException e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
