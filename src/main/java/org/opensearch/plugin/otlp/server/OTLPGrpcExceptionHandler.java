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
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GoogleGrpcExceptionHandlerFunction;
import io.grpc.Metadata;
import io.grpc.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;

import java.time.Duration;

class OTLPGrpcExceptionHandler implements GoogleGrpcExceptionHandlerFunction {

    private static final Logger LOG = LogManager.getLogger(OTLPGrpcExceptionHandler.class);

    private final RetryInfoCalculator retryInfoCalculator;

    OTLPGrpcExceptionHandler(RetryInfoCalculator retryInfoCalculator) {
        this.retryInfoCalculator = retryInfoCalculator;
    }

    @Override
    public com.google.rpc.@Nullable Status applyStatusProto(RequestContext ctx, Throwable throwable, Metadata metadata) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof OpenSearchRejectedExecutionException) {
            LOG.warn("OTLP {} export rejected — server overloaded", ctx.path());
            return com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
                .setMessage(cause.getMessage())
                .addDetails(Any.pack(retryInfoCalculator.createRetryInfo()))
                .build();
        }
        LOG.error("Unhandled error in gRPC handler for {}", ctx.path(), throwable);
        return com.google.rpc.Status.newBuilder()
            .setCode(Status.Code.INTERNAL.value())
            .setMessage(throwable.getMessage() != null ? throwable.getMessage() : Status.Code.INTERNAL.name())
            .build();
    }
}
