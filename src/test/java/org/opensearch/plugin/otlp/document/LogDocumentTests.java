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
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

public class LogDocumentTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── timestamps (log-specific @timestamp fallback logic) ──────────────────────

    public void testTimestampUsesTimeUnixNano() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setObservedTimeUnixNano(2_000_000_000L)
            .build();
        Map<String, Object> doc = toMap(log);
        assertEquals("1970-01-01T00:00:01Z", doc.get("time"));
        assertEquals("1970-01-01T00:00:02Z", doc.get("observedTimestamp"));
        assertEquals("1970-01-01T00:00:01Z", doc.get("@timestamp"));
    }

    public void testTimestampFallsBackToObservedWhenTimeIsAbsent() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setObservedTimeUnixNano(2_000_000_000L)
            .build();
        assertEquals("1970-01-01T00:00:02Z", toMap(log).get("@timestamp"));
    }

    public void testTimestampFallsBackToNowWhenBothAbsent() throws Exception {
        Instant before = Instant.now();
        Map<String, Object> doc = toMap(LogRecord.getDefaultInstance());
        Instant after = Instant.now();

        Instant tsInstant = Instant.parse((String) doc.get("@timestamp"));
        assertFalse("@timestamp should be >= before", tsInstant.isBefore(before));
        assertFalse("@timestamp should be <= after", tsInstant.isAfter(after));
    }

    // ── log-record fields ────────────────────────────────────────────────────────

    public void testTraceAndSpanIdHexEncoded() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setTraceId(ByteString.copyFrom(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}))
            .setSpanId(ByteString.copyFrom(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}))
            .build();
        Map<String, Object> doc = toMap(log);
        assertEquals("0102030405060708090a0b0c0d0e0f10", doc.get("traceId"));
        assertEquals("0102030405060708", doc.get("spanId"));
    }

    public void testSeverityNestedObject() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
            .setSeverityText("ERROR")
            .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> severity = (Map<String, Object>) toMap(log).get("severity");
        assertNotNull(severity);
        assertEquals(17, ((Number) severity.get("number")).intValue());
        assertEquals("ERROR", severity.get("text"));
    }

    public void testBodyWritten() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setBody(AnyValue.newBuilder().setStringValue("hello world"))
            .build();
        assertEquals("hello world", toMap(log).get("body"));
    }

    public void testEventNamePresentAndAbsent() throws Exception {
        LogRecord withEvent = LogRecord.newBuilder().setEventName("user.clicked").build();
        assertEquals("user.clicked", toMap(withEvent).get("eventName"));
        assertNull(toMap(LogRecord.getDefaultInstance()).get("eventName"));
    }

    public void testAttributesPresentAndAbsent() throws Exception {
        LogRecord withAttr = LogRecord.newBuilder()
            .addAttributes(kv("env", "prod"))
            .build();
        assertNotNull(toMap(withAttr).get("attributes"));
        assertNull(toMap(LogRecord.getDefaultInstance()).get("attributes"));
    }

    // ── context fields (structure only — detail in OTLPXContentUtilsTests) ───────

    public void testInstrumentationScopePresent() throws Exception {
        InstrumentationScope scope = InstrumentationScope.newBuilder().setName("my-lib").build();
        Map<String, Object> doc = toMap(LogRecord.getDefaultInstance(),
            Resource.getDefaultInstance(), "", scope, "");
        assertNotNull(doc.get("instrumentationScope"));
    }

    public void testResourcePresentAndServiceNameAtTopLevel() throws Exception {
        Resource resource = Resource.newBuilder()
            .addAttributes(kv("service.name", "my-svc"))
            .build();
        Map<String, Object> doc = toMap(LogRecord.getDefaultInstance(),
            resource, "", InstrumentationScope.getDefaultInstance(), "");
        assertNotNull(doc.get("resource"));
        assertEquals("my-svc", doc.get("serviceName"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static Map<String, Object> toMap(LogRecord log) throws IOException {
        return toMap(log, Resource.getDefaultInstance(), "",
            InstrumentationScope.getDefaultInstance(), "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(
            LogRecord log, Resource resource, String resourceSchemaUrl,
            InstrumentationScope scope, String scopeSchemaUrl) throws IOException {
        LogDocument doc = new LogDocument(resource, resourceSchemaUrl, scope, scopeSchemaUrl, log);
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
