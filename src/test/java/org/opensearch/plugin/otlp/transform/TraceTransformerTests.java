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
package org.opensearch.plugin.otlp.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.plugin.otlp.document.SpanDocument;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class TraceTransformerTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TraceTransformer transformer = new TraceTransformer();

    private static final ByteString TRACE_ID = ByteString.copyFrom(new byte[16]);
    private static final ByteString SPAN_ID  = ByteString.copyFrom(new byte[8]);

    public void testEmptyRequestProducesNoDocs() {
        assertTrue(transformer.transform(ExportTraceServiceRequest.getDefaultInstance()).isEmpty());
    }

    public void testBasicSpanFields() throws Exception {
        Span span = Span.newBuilder()
            .setTraceId(TRACE_ID)
            .setSpanId(SPAN_ID)
            .setName("my-span")
            .setKind(Span.SpanKind.SPAN_KIND_SERVER)
            .setStartTimeUnixNano(1_000_000_000L)
            .setEndTimeUnixNano(2_000_000_000L)
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(span, "my-svc")).get(0));

        assertEquals("00000000000000000000000000000000", doc.get("traceId"));
        assertEquals("0000000000000000", doc.get("spanId"));
        assertEquals("my-span", doc.get("name"));
        assertEquals("SPAN_KIND_SERVER", doc.get("kind"));
        assertEquals("my-svc", doc.get("serviceName"));
        assertEquals("1970-01-01T00:00:01Z", doc.get("startTime"));
        assertEquals("1970-01-01T00:00:02Z", doc.get("endTime"));
        assertEquals(1_000_000_000L, ((Number) doc.get("durationInNanos")).longValue());
    }

    public void testStatusIsNestedObject() throws Exception {
        Span span = Span.newBuilder(minimalSpan())
            .setStatus(Status.newBuilder()
                .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                .setMessage("boom"))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(span, "svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) doc.get("status");
        assertNotNull(status);
        assertEquals(Status.StatusCode.STATUS_CODE_ERROR.getNumber(), ((Number) status.get("code")).intValue());
        assertEquals("boom", status.get("message"));
    }

    public void testResourceIsNestedObject() throws Exception {
        Map<String, Object> doc = toMap(transformer.transform(requestWith(minimalSpan(), "my-svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) doc.get("resource");
        assertNotNull(resource);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) resource.get("attributes");
        assertEquals("my-svc", attrs.get("service.name"));
    }

    public void testAttributesNested_NoDedot() throws Exception {
        Span span = Span.newBuilder(minimalSpan())
            .addAttributes(kv("http.method", "GET"))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(requestWith(span, "svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) doc.get("attributes");
        // dots are preserved, no @ substitution        assertEquals("GET", attrs.get("http.method"));
        assertNull(attrs.get("http@method"));
    }

    public void testInstrumentationScopeIsNestedObject() throws Exception {
        ExportTraceServiceRequest req = ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", "svc")))
                .addScopeSpans(ScopeSpans.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName("my-lib").setVersion("1.0"))
                    .addSpans(minimalSpan())))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(req).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) doc.get("instrumentationScope");
        assertEquals("my-lib", scope.get("name"));
        assertEquals("1.0", scope.get("version"));
    }

    public void testEventsAndLinks() throws Exception {
        Span span = Span.newBuilder(minimalSpan())
            .addEvents(Span.Event.newBuilder().setName("evt").setTimeUnixNano(1_000_000_000L))
            .addLinks(Span.Link.newBuilder().setTraceId(TRACE_ID).setSpanId(SPAN_ID))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(requestWith(span, "svc")).get(0));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) doc.get("events");
        assertEquals(1, events.size());
        assertEquals("evt", events.get(0).get("name"));
        assertEquals("1970-01-01T00:00:01Z", events.get(0).get("time"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) doc.get("links");
        assertEquals(1, links.size());
        assertEquals("00000000000000000000000000000000", links.get(0).get("traceId"));
    }

    public void testMultipleSpansAcrossScopes() {
        ExportTraceServiceRequest req = ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", "svc")))
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(minimalSpan()))
                .addScopeSpans(ScopeSpans.newBuilder()
                    .addSpans(Span.newBuilder(minimalSpan()).setName("span-2"))))
            .build();
        assertEquals(2, transformer.transform(req).size());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(SpanDocument doc) throws IOException {
        var builder = XContentFactory.jsonBuilder();
        doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private Span minimalSpan() {
        return Span.newBuilder().setTraceId(TRACE_ID).setSpanId(SPAN_ID).setName("span").build();
    }

    private ExportTraceServiceRequest requestWith(Span span, String serviceName) {
        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", serviceName)))
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
            .build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
