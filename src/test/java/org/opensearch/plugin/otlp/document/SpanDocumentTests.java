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
package org.opensearch.plugin.otlp.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class SpanDocumentTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ByteString TRACE_ID = ByteString.copyFrom(new byte[]{
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    private static final ByteString SPAN_ID = ByteString.copyFrom(new byte[]{
        1, 2, 3, 4, 5, 6, 7, 8});

    // ── document ID ───────────────────────────────────────────────────────────

    public void testDocumentIdIsTraceIdSlashSpanId() {
        SpanDocument doc = new SpanDocument(
            Resource.getDefaultInstance(), "",
            InstrumentationScope.getDefaultInstance(), "",
            spanWith(b -> b.setTraceId(TRACE_ID).setSpanId(SPAN_ID)));
        assertEquals(
            "0102030405060708090a0b0c0d0e0f10/0102030405060708",
            doc.documentId());
    }

    // ── identity fields ───────────────────────────────────────────────────────

    public void testTraceIdAndSpanIdHexEncoded() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.setTraceId(TRACE_ID).setSpanId(SPAN_ID)));
        assertEquals("0102030405060708090a0b0c0d0e0f10", doc.get("traceId"));
        assertEquals("0102030405060708", doc.get("spanId"));
    }

    public void testParentSpanIdPresentAndAbsent() throws Exception {
        Map<String, Object> withParent = toMap(spanWith(b -> b.setParentSpanId(SPAN_ID)));
        assertNotNull(withParent.get("parentSpanId"));

        Map<String, Object> root = toMap(Span.getDefaultInstance());
        assertNull(root.get("parentSpanId"));
    }

    public void testTraceStatePresentAndAbsent() throws Exception {
        Map<String, Object> withState = toMap(spanWith(b -> b.setTraceState("rojo=00f067")));
        assertEquals("rojo=00f067", withState.get("traceState"));

        Map<String, Object> noState = toMap(Span.getDefaultInstance());
        assertNull(noState.get("traceState"));
    }

    // ── timing ───────────────────────────────────────────────────────────────

    public void testTimingFields() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b
            .setStartTimeUnixNano(1_000_000_000L)
            .setEndTimeUnixNano(2_000_000_000L)));
        assertEquals("1970-01-01T00:00:01Z", doc.get("startTime"));
        assertEquals("1970-01-01T00:00:02Z", doc.get("endTime"));
        assertEquals(1_000_000_000L, ((Number) doc.get("durationInNanos")).longValue());
    }

    // ── kind and status ───────────────────────────────────────────────────────

    public void testKindWritten() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.setKind(Span.SpanKind.SPAN_KIND_SERVER)));
        assertEquals("SPAN_KIND_SERVER", doc.get("kind"));
    }

    public void testStatusNestedObject() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.setStatus(
            Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) doc.get("status");
        assertNotNull(status);
        assertEquals(Status.StatusCode.STATUS_CODE_ERROR.getNumber(), ((Number) status.get("code")).intValue());
        assertEquals("boom", status.get("message"));
    }

    public void testStatusUnset() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) toMap(Span.getDefaultInstance()).get("status");
        assertEquals(0, ((Number) status.get("code")).intValue());
    }

    // ── attributes ────────────────────────────────────────────────────────────

    public void testAttributesPresentAndAbsent() throws Exception {
        Map<String, Object> withAttr = toMap(spanWith(b -> b.addAttributes(kv("http.method", "GET"))));
        assertNotNull(withAttr.get("attributes"));

        assertNull(toMap(Span.getDefaultInstance()).get("attributes"));
    }

    // ── events ────────────────────────────────────────────────────────────────

    public void testEventsArray() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.addEvents(
            Span.Event.newBuilder()
                .setName("exception")
                .setTimeUnixNano(1_000_000_000L)
                .addAttributes(kv("exception.type", "NullPointerException")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) doc.get("events");
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("exception", events.get(0).get("name"));
        assertEquals("1970-01-01T00:00:01Z", events.get(0).get("time"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evtAttrs = (Map<String, Object>) events.get(0).get("attributes");
        assertEquals("NullPointerException", evtAttrs.get("exception.type"));
    }

    public void testEventAttributesAbsentWhenEmpty() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.addEvents(
            Span.Event.newBuilder().setName("tick"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) doc.get("events");
        assertNull(events.get(0).get("attributes"));
    }

    public void testEmptyEventsArrayAlwaysPresent() throws Exception {
        @SuppressWarnings("unchecked")
        List<?> events = (List<?>) toMap(Span.getDefaultInstance()).get("events");
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    // ── links ─────────────────────────────────────────────────────────────────

    public void testLinksArray() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.addLinks(
            Span.Link.newBuilder().setTraceId(TRACE_ID).setSpanId(SPAN_ID))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) doc.get("links");
        assertNotNull(links);
        assertEquals(1, links.size());
        assertEquals("0102030405060708090a0b0c0d0e0f10", links.get(0).get("traceId"));
        assertEquals("0102030405060708", links.get(0).get("spanId"));
    }

    public void testLinkTraceStateOmittedWhenEmpty() throws Exception {
        Map<String, Object> doc = toMap(spanWith(b -> b.addLinks(
            Span.Link.newBuilder().setTraceId(TRACE_ID).setSpanId(SPAN_ID))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) doc.get("links");
        assertNull(links.get(0).get("traceState"));
    }

    // ── context fields ────────────────────────────────────────────────────────

    public void testInstrumentationScopePresent() throws Exception {
        InstrumentationScope scope = InstrumentationScope.newBuilder().setName("my-lib").build();
        Map<String, Object> doc = toMap(Span.getDefaultInstance(),
            Resource.getDefaultInstance(), "", scope, "");
        assertNotNull(doc.get("instrumentationScope"));
    }

    public void testResourceAndServiceNameAtTopLevel() throws Exception {
        Resource resource = Resource.newBuilder().addAttributes(kv("service.name", "my-svc")).build();
        Map<String, Object> doc = toMap(Span.getDefaultInstance(),
            resource, "", InstrumentationScope.getDefaultInstance(), "");
        assertNotNull(doc.get("resource"));
        assertEquals("my-svc", doc.get("serviceName"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface SpanBuilderConsumer {
        void accept(Span.Builder b);
    }

    private static Span spanWith(SpanBuilderConsumer consumer) {
        Span.Builder b = Span.newBuilder();
        consumer.accept(b);
        return b.build();
    }

    private static Map<String, Object> toMap(Span span) throws IOException {
        return toMap(span, Resource.getDefaultInstance(), "",
            InstrumentationScope.getDefaultInstance(), "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(
            Span span, Resource resource, String resourceSchemaUrl,
            InstrumentationScope scope, String scopeSchemaUrl) throws IOException {
        SpanDocument doc = new SpanDocument(resource, resourceSchemaUrl, scope, scopeSchemaUrl, span);
        var builder = XContentFactory.jsonBuilder();
        doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
