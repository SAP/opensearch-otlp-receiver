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

import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;

/**
 * Hosts all three OTLP gRPC signal services as separate ImplBase subclasses so that
 * Armeria's GrpcService can register each one independently via addService().
 */
public class OTLPGrpcService {

    public final TraceServiceGrpc.TraceServiceImplBase traces;
    public final MetricsServiceGrpc.MetricsServiceImplBase metrics;
    public final LogsServiceGrpc.LogsServiceImplBase logs;

    public OTLPGrpcService(
            TraceTransformer traceTransformer,
            LogsTransformer logsTransformer,
            MetricsTransformer metricsTransformer,
            BulkIndexer bulkIndexer,
            String tracesIndex,
            String logsIndex,
            String metricsIndex) {

        this.traces = new TraceServiceGrpc.TraceServiceImplBase() {
            @Override
            public void export(ExportTraceServiceRequest request,
                               StreamObserver<ExportTraceServiceResponse> responseObserver) {
                bulkIndexer.index(tracesIndex, traceTransformer.transform(request)).whenComplete((r, e) -> {
                    if (e != null) {
                        responseObserver.onError(e);
                    } else {
                        responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                });
            }
        };

        this.metrics = new MetricsServiceGrpc.MetricsServiceImplBase() {
            @Override
            public void export(ExportMetricsServiceRequest request,
                               StreamObserver<ExportMetricsServiceResponse> responseObserver) {
                bulkIndexer.index(metricsIndex, metricsTransformer.transform(request)).whenComplete((r, e) -> {
                    if (e != null) {
                        responseObserver.onError(e);
                    } else {
                        responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                });
            }
        };

        this.logs = new LogsServiceGrpc.LogsServiceImplBase() {
            @Override
            public void export(ExportLogsServiceRequest request,
                               StreamObserver<ExportLogsServiceResponse> responseObserver) {
                bulkIndexer.index(logsIndex, logsTransformer.transform(request)).whenComplete((r, e) -> {
                    if (e != null) {
                        responseObserver.onError(e);
                    } else {
                        responseObserver.onNext(ExportLogsServiceResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                });
            }
        };
    }
}
