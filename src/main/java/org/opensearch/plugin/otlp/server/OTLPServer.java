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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.encoding.DecodingService;
import com.linecorp.armeria.server.grpc.GrpcService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;

public class OTLPServer extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(OTLPServer.class);

    private final OTLPGrpcService grpcService;
    private final OTLPHttpHandler httpHandler;
    private final String bindHost;
    private final int grpcPort;
    private final int httpPort;
    private final int maxRequestBytes;
    private final boolean compressionEnabled;
    private final RetryInfoCalculator retryInfoCalculator;

    private Server grpcServer;
    private Server httpServer;

    public OTLPServer(
            OTLPGrpcService grpcService,
            OTLPHttpHandler httpHandler,
            String bindHost,
            int grpcPort,
            int httpPort,
            int maxRequestBytes,
            boolean compressionEnabled,
            RetryInfoCalculator retryInfoCalculator) {
        this.grpcService = grpcService;
        this.httpHandler = httpHandler;
        this.bindHost = bindHost;
        this.grpcPort = grpcPort;
        this.httpPort = httpPort;
        this.maxRequestBytes = maxRequestBytes;
        this.compressionEnabled = compressionEnabled;
        this.retryInfoCalculator = retryInfoCalculator;
    }

    @Override
    protected void doStart() {
        OTLPGrpcExceptionHandler grpcExceptionHandler = new OTLPGrpcExceptionHandler(retryInfoCalculator);

        var grpcBuilder = Server.builder()
            .port(grpcPort, SessionProtocol.HTTP)
            .maxRequestLength(maxRequestBytes)
            .service(GrpcService.builder()
                .addService(grpcService.traces)
                .addService(grpcService.metrics)
                .addService(grpcService.logs)
                .exceptionHandler(grpcExceptionHandler)
                .build())
            .errorHandler((ctx, cause) -> {
                logger.error("Unhandled error in gRPC server for {}", ctx.path(), cause);
                return null;
            });
        if (compressionEnabled) {
            grpcBuilder.decorator(DecodingService.newDecorator());
        }
        grpcServer = grpcBuilder.build();
        grpcServer.start().join();
        logger.info("OTLP gRPC server started on {}:{}", bindHost, grpcPort);

        var httpBuilder = Server.builder()
            .port(httpPort, SessionProtocol.HTTP)
            .maxRequestLength(maxRequestBytes)
            .annotatedService(httpHandler)
            .service(GrpcService.builder()
                .addService(grpcService.traces)
                .addService(grpcService.metrics)
                .addService(grpcService.logs)
                .supportedSerializationFormats(
                    GrpcSerializationFormats.PROTO,
                    GrpcSerializationFormats.PROTO_WEB)
                .exceptionHandler(grpcExceptionHandler)
                .build())
            .errorHandler((ctx, cause) -> {
                logger.error("Unhandled error in HTTP handler for {}", ctx.path(), cause);
                return null;
            });
        if (compressionEnabled) {
            httpBuilder.decorator(DecodingService.newDecorator());
        }
        httpServer = httpBuilder.build();
        httpServer.start().join();
        logger.info("OTLP HTTP server started on {}:{}", bindHost, httpPort);
    }

    @Override
    protected void doStop() {
        if (httpServer != null) {
            httpServer.stop().join();
        }
        if (grpcServer != null) {
            grpcServer.stop().join();
        }
        logger.info("OTLP servers stopped");
    }

    @Override
    protected void doClose() {}
}
