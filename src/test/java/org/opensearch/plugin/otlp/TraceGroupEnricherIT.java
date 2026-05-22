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
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.plugin.otlp.index.TraceGroupEnricher;
import org.opensearch.search.SearchHit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for TraceGroupEnricher against an embedded OpenSearch node.
 * runEnrichment() is called directly to avoid waiting for the scheduler tick.
 */
public class TraceGroupEnricherIT extends OTLPPluginITBase {

    private static final String INDEX = "otel-v1-apm-span-test";

    // ── propagation: root and child span get traceGroup + traceGroupFields ────

    public void testEnrichmentPopulatesTraceGroupOnAllSpans() throws Exception {
        emitTrace("GET /orders");
        refresh(INDEX);
        assertEquals("both spans indexed", 2, searchAll(INDEX).getHits().getTotalHits().value());

        enricher().runEnrichment();
        refresh(INDEX);

        for (SearchHit hit : searchAll(INDEX).getHits().getHits()) {
            Map<String, Object> src = hit.getSourceAsMap();
            assertEquals("traceGroup must be root span name",
                "GET /orders", src.get("traceGroup"));

            @SuppressWarnings("unchecked")
            Map<String, Object> tgf = (Map<String, Object>) src.get("traceGroupFields");
            assertNotNull("traceGroupFields must be present on span " + src.get("spanId"), tgf);
            assertNotNull("traceGroupFields.endTime", tgf.get("endTime"));
            assertNotNull("traceGroupFields.durationInNanos", tgf.get("durationInNanos"));
            assertNotNull("traceGroupFields.statusCode", tgf.get("statusCode"));
        }
    }

    // ── traceGroupFields come from root, not from individual spans ────────────

    public void testEnrichmentUsesRootSpanFieldsNotChildFields() throws Exception {
        emitTrace("POST /checkout");
        refresh(INDEX);
        enricher().runEnrichment();
        refresh(INDEX);

        SearchHit rootHit = null, childHit = null;
        for (SearchHit hit : searchAll(INDEX).getHits().getHits()) {
            Map<String, Object> src = hit.getSourceAsMap();
            if (src.containsKey("parentSpanId")) {
                childHit = hit;
            } else {
                rootHit = hit;
            }
        }
        assertNotNull("root span found", rootHit);
        assertNotNull("child span found", childHit);

        @SuppressWarnings("unchecked")
        Map<String, Object> rootTgf = (Map<String, Object>) rootHit.getSourceAsMap().get("traceGroupFields");
        @SuppressWarnings("unchecked")
        Map<String, Object> childTgf = (Map<String, Object>) childHit.getSourceAsMap().get("traceGroupFields");

        assertNotNull("root has traceGroupFields", rootTgf);
        assertNotNull("child has traceGroupFields", childTgf);
        assertEquals("child.endTime matches root", rootTgf.get("endTime"), childTgf.get("endTime"));
        assertEquals("child.durationInNanos matches root",
            rootTgf.get("durationInNanos"), childTgf.get("durationInNanos"));
        assertEquals("child.statusCode matches root", rootTgf.get("statusCode"), childTgf.get("statusCode"));
    }

    // ── cut-off: spans older than traceCutoff with no root get sentinel ───────

    public void testEnrichmentMarksSentinelForOrphanedSpans() throws Exception {
        client().index(new IndexRequest(INDEX)
            .id("orphantrace/orphanspan")
            .source(Map.of(
                "traceId", "deadbeefdeadbeef00000000deadbeef",
                "spanId", "orphanspan00000000",
                "parentSpanId", "missingroot0000",
                "name", "orphan-op",
                "startTime", "2000-01-01T00:00:00Z",
                "endTime", "2000-01-01T00:00:01Z",
                "durationInNanos", 1_000_000_000L
            ), XContentType.JSON)
        ).actionGet();
        refresh(INDEX);

        // Use a very short cutoff (1s) so the year-2000 span falls past it
        TraceGroupEnricher enricher = new TraceGroupEnricher(
            client(), getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            new TimeValue(1, TimeUnit.SECONDS),
            1000);

        enricher.runEnrichment();
        refresh(INDEX);

        SearchHit orphan = searchAll(INDEX).getHits().getHits()[0];
        assertEquals("orphaned span gets empty sentinel traceGroup",
            "", orphan.getSourceAsMap().get("traceGroup"));
    }

    // ── idempotency: second run does not corrupt already-enriched spans ───────

    public void testEnrichmentIsIdempotent() throws Exception {
        emitTrace("GET /items");
        refresh(INDEX);
        TraceGroupEnricher enricher = enricher();
        enricher.runEnrichment();
        enricher.runEnrichment();
        refresh(INDEX);

        for (SearchHit hit : searchAll(INDEX).getHits().getHits()) {
            assertEquals("GET /items", hit.getSourceAsMap().get("traceGroup"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TraceGroupEnricher enricher() {
        return new TraceGroupEnricher(
            client(),
            getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            new TimeValue(10, TimeUnit.MINUTES),
            1000);
    }

    /**
     * Emits a root span with the given name and a child span linked to it,
     * both sharing the same traceId generated by the OTel SDK.
     */
    private void emitTrace(String rootSpanName) throws Exception {
        var exporter = buildSpanExporter(OtlpProtocol.GRPC);
        var provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .setResource(telemetryResource("enricher-svc"))
            .build();
        try {
            var tracer = provider.get("enricher-lib");
            Span root = tracer.spanBuilder(rootSpanName)
                .setSpanKind(SpanKind.SERVER)
                .setNoParent()
                .startSpan();
            try (var scope = root.makeCurrent()) {
                tracer.spanBuilder("child-op")
                    .setSpanKind(SpanKind.CLIENT)
                    .setParent(Context.current())
                    .startSpan()
                    .setStatus(StatusCode.OK)
                    .end();
            } finally {
                root.setStatus(StatusCode.OK);
                root.end();
            }
            provider.forceFlush().join(10, TimeUnit.SECONDS);
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }
}
