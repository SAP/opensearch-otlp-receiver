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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.plugin.otlp.index.ServiceMapBuilder;
import org.opensearch.search.SearchHit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for ServiceMapBuilder against an embedded OpenSearch node.
 * run() is called directly to avoid waiting for the scheduler tick.
 */
public class ServiceMapBuilderIT extends OTLPPluginITBase {

    private static final String TRACES_INDEX = "otel-v1-apm-span-test";
    private static final String SERVICE_MAP_INDEX = "otel-v1-apm-service-map-test";

    // ── two-service trace produces two relationship docs ──────────────────────

    public void testTwoServicesProduceTwoRelationships() throws Exception {
        emitTwoServiceTrace("GET /api", "frontend-svc", "backend-svc");
        refresh(TRACES_INDEX);
        assertEquals("two spans indexed", 2, searchAll(TRACES_INDEX).getHits().getTotalHits().value());

        builder().run();
        refresh(SERVICE_MAP_INDEX);

        assertEquals("two relationship documents in service map",
            2, searchAll(SERVICE_MAP_INDEX).getHits().getTotalHits().value());

        boolean hasDestination = false, hasTarget = false;
        for (SearchHit hit : searchAll(SERVICE_MAP_INDEX).getHits().getHits()) {
            Map<String, Object> src = hit.getSourceAsMap();
            if (src.containsKey("destination")) hasDestination = true;
            if (src.containsKey("target")) hasTarget = true;
        }
        assertTrue("destination relationship doc present", hasDestination);
        assertTrue("target relationship doc present", hasTarget);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ServiceMapBuilder builder() {
        return new ServiceMapBuilder(
            client(),
            getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            TRACES_INDEX,
            SERVICE_MAP_INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES));
    }

    private void emitTwoServiceTrace(String rootSpanName, String parentService, String childService)
            throws Exception {
        var parentExporter = buildSpanExporter(OtlpProtocol.GRPC);
        var parentProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(parentExporter))
            .setResource(telemetryResource(parentService))
            .build();
        var childExporter = buildSpanExporter(OtlpProtocol.GRPC);
        var childProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(childExporter))
            .setResource(telemetryResource(childService))
            .build();
        try {
            var parentTracer = parentProvider.get("parent-lib");
            var childTracer = childProvider.get("child-lib");

            Span root = parentTracer.spanBuilder(rootSpanName)
                .setSpanKind(SpanKind.CLIENT)
                .setNoParent()
                .startSpan();
            try (var scope = root.makeCurrent()) {
                childTracer.spanBuilder("child-op")
                    .setSpanKind(SpanKind.SERVER)
                    .setParent(Context.current())
                    .startSpan()
                    .setStatus(StatusCode.OK)
                    .end();
            } finally {
                root.setStatus(StatusCode.OK);
                root.end();
            }
            parentProvider.forceFlush().join(10, TimeUnit.SECONDS);
            childProvider.forceFlush().join(10, TimeUnit.SECONDS);
        } finally {
            parentProvider.shutdown().join(10, TimeUnit.SECONDS);
            childProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }
}
