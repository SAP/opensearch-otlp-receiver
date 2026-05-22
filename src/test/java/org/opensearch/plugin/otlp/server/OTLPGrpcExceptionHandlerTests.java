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

import com.linecorp.armeria.common.RequestContext;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.test.OpenSearchTestCase;

import java.lang.reflect.Proxy;
import java.time.Duration;

public class OTLPGrpcExceptionHandlerTests extends OpenSearchTestCase {

    static final Duration FIXED_DELAY = Duration.ofSeconds(5);

    private final OTLPGrpcExceptionHandler handler =
        new OTLPGrpcExceptionHandler(new FixedRetryInfoCalculator(FIXED_DELAY));

    private final Metadata metadata = new Metadata();

    // ── rejection maps to RESOURCE_EXHAUSTED with RetryInfo ───────────────────

    public void testRejectedExecutionMapsToResourceExhausted() {
        OpenSearchRejectedExecutionException rejection =
            new OpenSearchRejectedExecutionException("queue full");

        com.google.rpc.Status status = handler.applyStatusProto(stubCtx("/v1/traces"), rejection, metadata);

        assertNotNull("status must not be null", status);
        assertEquals("code is RESOURCE_EXHAUSTED",
            Status.Code.RESOURCE_EXHAUSTED.value(), status.getCode());
        assertTrue("message contains rejection text", status.getMessage().contains("queue full"));
        assertFalse("RetryInfo detail attached", status.getDetailsList().isEmpty());
        assertEquals("RetryInfo delay comes from calculator",
            FIXED_DELAY.getSeconds(),
            unpackRetryDelay(status));
    }

    // ── cause unwrapping: wrapped rejection still maps to RESOURCE_EXHAUSTED ──

    public void testWrappedRejectionCauseUnwrapped() {
        RuntimeException wrapper =
            new RuntimeException("wrapped", new OpenSearchRejectedExecutionException("overloaded"));

        com.google.rpc.Status status = handler.applyStatusProto(stubCtx("/v1/metrics"), wrapper, metadata);

        assertNotNull(status);
        assertEquals(Status.Code.RESOURCE_EXHAUSTED.value(), status.getCode());
    }

    // ── generic exception maps to INTERNAL ────────────────────────────────────

    public void testGenericExceptionMapsToInternal() {
        RuntimeException error = new RuntimeException("unexpected failure");

        com.google.rpc.Status status = handler.applyStatusProto(stubCtx("/v1/logs"), error, metadata);

        assertNotNull(status);
        assertEquals("code is INTERNAL", Status.Code.INTERNAL.value(), status.getCode());
        assertTrue("message contains exception text", status.getMessage().contains("unexpected failure"));
        assertTrue("no RetryInfo for internal errors", status.getDetailsList().isEmpty());
    }

    // ── null message on generic exception falls back to code name ─────────────

    public void testNullMessageFallsBackToCodeName() {
        // An exception with null message
        RuntimeException noMessage = new RuntimeException((String) null);

        com.google.rpc.Status status = handler.applyStatusProto(stubCtx("/v1/traces"), noMessage, metadata);

        assertNotNull(status);
        assertEquals(Status.Code.INTERNAL.value(), status.getCode());
        assertEquals("INTERNAL", status.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal RequestContext proxy that just provides a path for logging. */
    private static RequestContext stubCtx(String path) {
        return (RequestContext) Proxy.newProxyInstance(
            RequestContext.class.getClassLoader(),
            new Class<?>[]{ RequestContext.class },
            (proxy, method, args) -> {
                if ("path".equals(method.getName())) return path;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }

    private static long unpackRetryDelay(com.google.rpc.Status status) {
        return status.getDetailsList().stream()
            .filter(a -> a.is(RetryInfo.class))
            .findFirst()
            .map(a -> {
                try { return a.unpack(RetryInfo.class).getRetryDelay().getSeconds(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow(() -> new AssertionError("no RetryInfo in status details"));
    }

    /** Always returns a RetryInfo with a fixed delay — isolates handler logic from backoff math. */
    static class FixedRetryInfoCalculator extends RetryInfoCalculator {
        private final Duration fixedDelay;

        FixedRetryInfoCalculator(Duration fixedDelay) {
            super(fixedDelay, fixedDelay);
            this.fixedDelay = fixedDelay;
        }

        @Override
        RetryInfo createRetryInfo() {
            return RetryInfo.newBuilder()
                .setRetryDelay(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(fixedDelay.getSeconds())
                    .setNanos(fixedDelay.getNano()))
                .build();
        }
    }
}
