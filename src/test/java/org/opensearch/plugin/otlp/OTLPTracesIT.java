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
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;

import java.util.Map;

/**
 * End-to-end integration tests for OTLP trace ingestion. Each test
 * constructs an OTLP proto request and calls the gRPC service implementation
 * directly — no network connection is needed.
 */
public class OTLPTracesIT extends OTLPPluginITBase {

    private static final ByteString TRACE_ID = ByteString.copyFrom(
        new byte[]{ 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16 });
    private static final ByteString SPAN_ID = ByteString.copyFrom(
        new byte[]{ 1,2,3,4,5,6,7,8 });

    // ── field values ─────────────────────────────────────────────────────────

    public void testSpanFields() throws Exception {
        export(traceRequest("e2e-svc", "e2e-lib", "e2e-span", Span.SpanKind.SPAN_KIND_SERVER,
            TRACE_ID, SPAN_ID, null));
        assertSpanDocument(TRACES_INDEX);
    }

    // ── mapping: template types applied ──────────────────────────────────────

    public void testSpanMappingTypes() throws Exception {
        export(traceRequest("e2e-svc", "e2e-lib", "e2e-span", Span.SpanKind.SPAN_KIND_SERVER,
            TRACE_ID, SPAN_ID, null));
        refresh(TRACES_INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(TRACES_INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(TRACES_INDEX).sourceAsMap().get("properties"));

        assertMappingType(props, "traceId",         "keyword");
        assertMappingType(props, "spanId",          "keyword");
        assertMappingType(props, "serviceName",     "keyword");
        assertMappingType(props, "name",            "keyword");
        assertMappingType(props, "kind",            "keyword");
        assertMappingType(props, "durationInNanos", "long");
        assertMappingType(props, "startTime",       "date_nanos");
        assertMappingType(props, "endTime",         "date_nanos");
    }

    // ── document assertion ────────────────────────────────────────────────────

    private void assertSpanDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        SearchHit hit = resp.getHits().getHits()[0];
        Map<String, Object> src = hit.getSourceAsMap();

        assertEquals("e2e-svc", src.get("serviceName"));
        assertEquals("e2e-span", src.get("name"));
        assertEquals("SPAN_KIND_SERVER", src.get("kind"));
        assertNotNull("traceId must be present", src.get("traceId"));
        assertNotNull("spanId must be present", src.get("spanId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) src.get("attributes");
        assertNotNull(attrs);
        assertEquals("GET", attrs.get("http.method"));

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) src.get("status");
        assertNotNull(status);
        assertEquals(1, ((Number) status.get("code")).intValue()); // STATUS_CODE_OK = 1

        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) src.get("instrumentationScope");
        assertNotNull(scope);
        assertEquals("e2e-lib", scope.get("name"));
    }

    // ── proto builder helpers ─────────────────────────────────────────────────

    static ExportTraceServiceRequest traceRequest(
            String serviceName, String scopeName, String spanName,
            Span.SpanKind kind, ByteString traceId, ByteString spanId,
            ByteString parentSpanId) {
        long nowNanos = System.currentTimeMillis() * 1_000_000L;
        Span.Builder spanBuilder = Span.newBuilder()
            .setTraceId(traceId)
            .setSpanId(spanId)
            .setName(spanName)
            .setKind(kind)
            .setStartTimeUnixNano(nowNanos - 1_000_000_000L)
            .setEndTimeUnixNano(nowNanos)
            .addAttributes(KeyValue.newBuilder()
                .setKey("http.method")
                .setValue(AnyValue.newBuilder().setStringValue("GET").build())
                .build())
            .setStatus(Status.newBuilder()
                .setCode(Status.StatusCode.STATUS_CODE_OK)
                .build());
        if (parentSpanId != null) {
            spanBuilder.setParentSpanId(parentSpanId);
        }
        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder()
                    .addAttributes(KeyValue.newBuilder()
                        .setKey("service.name")
                        .setValue(AnyValue.newBuilder().setStringValue(serviceName).build())
                        .build())
                    .build())
                .addScopeSpans(ScopeSpans.newBuilder()
                    .setScope(InstrumentationScope.newBuilder()
                        .setName(scopeName)
                        .setVersion("1.0")
                        .build())
                    .addSpans(spanBuilder.build())
                    .build())
                .build())
            .build();
    }
}
