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

import com.google.protobuf.Any;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;

public class OTLPMetricsGrpcService extends MetricsServiceGrpc.MetricsServiceImplBase {

    private static final Logger LOG = LogManager.getLogger(OTLPMetricsGrpcService.class);

    private final BulkIndexer bulkIndexer;
    private final MetricsTransformer transformer;
    private final RetryInfoCalculator retryInfoCalculator;
    private final String index;

    public OTLPMetricsGrpcService(BulkIndexer bulkIndexer, MetricsTransformer transformer,
            RetryInfoCalculator retryInfoCalculator, String index) {
        this.bulkIndexer = bulkIndexer;
        this.transformer = transformer;
        this.retryInfoCalculator = retryInfoCalculator;
        this.index = index;
    }

    @Override
    public void export(ExportMetricsServiceRequest request,
                       StreamObserver<ExportMetricsServiceResponse> responseObserver) {
        bulkIndexer.index(index, transformer.transform(request)).whenComplete((result, error) -> {
            if (error == null) {
                responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (cause instanceof OpenSearchRejectedExecutionException) {
                LOG.warn("OTLP metrics export rejected — server overloaded", cause);
                responseObserver.onError(resourceExhaustedWithRetry(cause.getMessage()));
            } else {
                LOG.error("Unhandled error in OTLP metrics gRPC handler", error);
                responseObserver.onError(Status.INTERNAL.withCause(error).asException());
            }
        });
    }

    private StatusException resourceExhaustedWithRetry(String message) {
        return StatusProto.toStatusException(com.google.rpc.Status.newBuilder()
            .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
            .setMessage(message)
            .addDetails(Any.pack(retryInfoCalculator.createRetryInfo()))
            .build());
    }
}
