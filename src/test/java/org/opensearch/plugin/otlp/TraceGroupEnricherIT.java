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

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.Span;
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

    private static final ByteString TRACE_ID = ByteString.copyFrom(
        new byte[]{ 20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35 });
    private static final ByteString ROOT_SPAN_ID = ByteString.copyFrom(
        new byte[]{ 20,21,22,23,24,25,26,27 });
    private static final ByteString CHILD_SPAN_ID = ByteString.copyFrom(
        new byte[]{ 30,31,32,33,34,35,36,37 });

    // ── propagation: root and child span get traceGroup + traceGroupFields ────

    public void testEnrichmentPopulatesTraceGroupOnAllSpans() throws Exception {
        emitTrace("GET /orders");
        refresh(TRACES_INDEX);
        assertEquals("both spans indexed", 2, searchAll(TRACES_INDEX).getHits().getTotalHits().value());

        enricher().runEnrichment();
        refresh(TRACES_INDEX);

        for (SearchHit hit : searchAll(TRACES_INDEX).getHits().getHits()) {
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
        refresh(TRACES_INDEX);
        enricher().runEnrichment();
        refresh(TRACES_INDEX);

        SearchHit rootHit = null, childHit = null;
        for (SearchHit hit : searchAll(TRACES_INDEX).getHits().getHits()) {
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
        client().index(new IndexRequest(TRACES_INDEX)
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
        refresh(TRACES_INDEX);

        TraceGroupEnricher enricher = new TraceGroupEnricher(
            client(), getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            TRACES_INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            new TimeValue(1, TimeUnit.SECONDS),
            1000);

        enricher.runEnrichment();
        refresh(TRACES_INDEX);

        SearchHit orphan = searchAll(TRACES_INDEX).getHits().getHits()[0];
        assertEquals("orphaned span gets empty sentinel traceGroup",
            "", orphan.getSourceAsMap().get("traceGroup"));
    }

    // ── idempotency: second run does not corrupt already-enriched spans ───────

    public void testEnrichmentIsIdempotent() throws Exception {
        emitTrace("GET /items");
        refresh(TRACES_INDEX);
        TraceGroupEnricher enricher = enricher();
        enricher.runEnrichment();
        enricher.runEnrichment();
        refresh(TRACES_INDEX);

        for (SearchHit hit : searchAll(TRACES_INDEX).getHits().getHits()) {
            assertEquals("GET /items", hit.getSourceAsMap().get("traceGroup"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TraceGroupEnricher enricher() {
        return new TraceGroupEnricher(
            client(),
            getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            TRACES_INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            new TimeValue(10, TimeUnit.MINUTES),
            1000);
    }

    private void emitTrace(String rootSpanName) throws Exception {
        // Root span (SERVER, no parent)
        export(OTLPTracesIT.traceRequest("enricher-svc", "enricher-lib", rootSpanName,
            Span.SpanKind.SPAN_KIND_SERVER, TRACE_ID, ROOT_SPAN_ID, null));
        // Child span (CLIENT, parent = root)
        export(OTLPTracesIT.traceRequest("enricher-svc", "enricher-lib", "child-op",
            Span.SpanKind.SPAN_KIND_CLIENT, TRACE_ID, CHILD_SPAN_ID, ROOT_SPAN_ID));
    }
}
