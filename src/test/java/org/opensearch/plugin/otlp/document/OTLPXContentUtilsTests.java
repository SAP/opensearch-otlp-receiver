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
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class OTLPXContentUtilsTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── writeAnyValue: scalar types ──────────────────────────────────────────────

    public void testWriteStringValue() throws Exception {
        assertEquals("hello", writeValue(AnyValue.newBuilder().setStringValue("hello").build()));
    }

    public void testWriteIntValue() throws Exception {
        assertEquals(42L, ((Number) writeValue(AnyValue.newBuilder().setIntValue(42).build())).longValue());
    }

    public void testWriteDoubleValue() throws Exception {
        assertEquals(0.5, ((Number) writeValue(AnyValue.newBuilder().setDoubleValue(0.5).build())).doubleValue(), 0.0001);
    }

    public void testWriteBoolValue() throws Exception {
        assertEquals(Boolean.TRUE, writeValue(AnyValue.newBuilder().setBoolValue(true).build()));
    }

    public void testWriteBytesValueHexEncoded() throws Exception {
        AnyValue v = AnyValue.newBuilder()
            .setBytesValue(ByteString.copyFrom(new byte[]{(byte) 0xde, (byte) 0xad}))
            .build();
        assertEquals("dead", writeValue(v));
    }

    public void testWriteNullForUnsetValue() throws Exception {
        assertNull(writeValue(AnyValue.getDefaultInstance()));
    }

    // ── writeAnyValue: array ─────────────────────────────────────────────────────

    public void testWriteArrayOfStrings() throws Exception {
        AnyValue v = AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setStringValue("a"))
            .addValues(AnyValue.newBuilder().setStringValue("b")))
            .build();
        assertEquals(List.of("a", "b"), writeValue(v));
    }

    public void testWriteNestedArray() throws Exception {
        AnyValue inner = AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setIntValue(1)))
            .build();
        AnyValue outer = AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
            .addValues(inner))
            .build();
        @SuppressWarnings("unchecked")
        List<List<Object>> result = (List<List<Object>>) writeValue(outer);
        assertEquals(1, result.size());
        assertEquals(1L, ((Number) result.get(0).get(0)).longValue());
    }

    // ── writeAnyValue: KVList ────────────────────────────────────────────────────

    public void testWriteKvListAsObject() throws Exception {
        AnyValue v = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
            .addValues(kv("region", "eu"))
            .addValues(kv("zone", "a")))
            .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) writeValue(v);
        assertEquals("eu", result.get("region"));
        assertEquals("a", result.get("zone"));
    }

    public void testWriteNestedKvList() throws Exception {
        AnyValue inner = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
            .addValues(kv("k", "v")))
            .build();
        AnyValue outer = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
            .addValues(KeyValue.newBuilder().setKey("nested").setValue(inner)))
            .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) writeValue(outer);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("nested");
        assertEquals("v", nested.get("k"));
    }

    // ── writeAttributes ──────────────────────────────────────────────────────────

    public void testWriteAttributes() throws Exception {
        List<KeyValue> attrs = List.of(kv("log.file", "app.log"), kv("env", "prod"));
        Map<String, Object> result = writeAttributes(attrs);
        assertEquals("app.log", result.get("log.file"));
        assertEquals("prod", result.get("env"));
    }

    public void testWriteEmptyAttributesProducesEmptyObject() throws Exception {
        assertTrue(writeAttributes(List.of()).isEmpty());
    }

    // ── writeResource ────────────────────────────────────────────────────────────

    public void testWriteResourceSchemaUrlAndAttributes() throws Exception {
        Resource resource = Resource.newBuilder()
            .addAttributes(kv("host.name", "prod-1"))
            .build();
        Map<String, Object> doc = writeResource(resource, "schema://res");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) doc.get("resource");
        assertEquals("schema://res", res.get("schemaUrl"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) res.get("attributes");
        assertEquals("prod-1", attrs.get("host.name"));
    }

    public void testWriteResourceReturnsServiceName() throws Exception {
        Resource resource = Resource.newBuilder()
            .addAttributes(kv("service.name", "my-svc"))
            .addAttributes(kv("other", "value"))
            .build();
        Map<String, Object> doc = writeResource(resource, "");
        assertEquals("my-svc", doc.get("serviceName"));
    }

    public void testWriteResourceReturnsNullServiceNameWhenAbsent() throws Exception {
        Resource resource = Resource.newBuilder()
            .addAttributes(kv("host.name", "h1"))
            .build();
        Map<String, Object> doc = writeResource(resource, "");
        assertNull(doc.get("serviceName"));
    }

    public void testWriteResourceNoAttributesField() throws Exception {
        Map<String, Object> doc = writeResource(Resource.getDefaultInstance(), "");
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) doc.get("resource");
        assertNull(res.get("attributes"));
    }

    // ── writeInstrumentationScope ────────────────────────────────────────────────

    public void testWriteInstrumentationScopeFields() throws Exception {
        InstrumentationScope scope = InstrumentationScope.newBuilder()
            .setName("my-lib")
            .setVersion("1.2.3")
            .build();
        Map<String, Object> doc = writeScope(scope, "schema://scope");
        @SuppressWarnings("unchecked")
        Map<String, Object> is = (Map<String, Object>) doc.get("instrumentationScope");
        assertEquals("my-lib", is.get("name"));
        assertEquals("1.2.3", is.get("version"));
        assertEquals("schema://scope", is.get("schemaUrl"));
    }

    public void testWriteInstrumentationScopeAttributes() throws Exception {
        InstrumentationScope scope = InstrumentationScope.newBuilder()
            .setName("lib")
            .addAttributes(kv("lib.version", "2"))
            .build();
        Map<String, Object> doc = writeScope(scope, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> is = (Map<String, Object>) doc.get("instrumentationScope");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) is.get("attributes");
        assertEquals("2", attrs.get("lib.version"));
    }

    public void testWriteInstrumentationScopeNoAttributesField() throws Exception {
        Map<String, Object> doc = writeScope(InstrumentationScope.getDefaultInstance(), "");
        @SuppressWarnings("unchecked")
        Map<String, Object> is = (Map<String, Object>) doc.get("instrumentationScope");
        assertNull(is.get("attributes"));
    }

    // ── toIso8601 ─────────────────────────────────────────────────────────────────

    public void testToIso8601Zero() {
        assertEquals("1970-01-01T00:00:00Z", OTLPXContentUtils.toIso8601(0L));
    }

    public void testToIso8601WholeSecond() {
        assertEquals("1970-01-01T00:00:01Z", OTLPXContentUtils.toIso8601(1_000_000_000L));
    }

    public void testToIso8601SubSecond() {
        assertEquals("1970-01-01T00:00:00.500Z", OTLPXContentUtils.toIso8601(500_000_000L));
    }

    public void testToIso8601SubSecondNanos() {
        assertEquals("1970-01-01T00:00:00.500000001Z", OTLPXContentUtils.toIso8601(500_000_001L));
    }

    // ── hexFromBytes ──────────────────────────────────────────────────────────────

    public void testHexFromBytes() {
        ByteString bs = ByteString.copyFrom(new byte[]{0x0a, (byte) 0xbc, (byte) 0xff});
        assertEquals("0abcff", OTLPXContentUtils.hexFromBytes(bs));
    }

    public void testHexFromBytesEmpty() {
        assertEquals("", OTLPXContentUtils.hexFromBytes(ByteString.EMPTY));
    }

    // ── toDouble ──────────────────────────────────────────────────────────────────

    public void testToDoubleNormal() {
        assertEquals(Double.valueOf(1.5), OTLPXContentUtils.toDouble(1.5));
    }

    public void testToDoublePositiveInfinity() {
        assertEquals(Double.valueOf((double) Float.MAX_VALUE), OTLPXContentUtils.toDouble(Double.POSITIVE_INFINITY));
    }

    public void testToDoubleNegativeInfinity() {
        assertEquals(Double.valueOf((double) -Float.MAX_VALUE), OTLPXContentUtils.toDouble(Double.NEGATIVE_INFINITY));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────
    /** Writes a single AnyValue field named "v" and returns the parsed result. */
    @SuppressWarnings("unchecked")
    private static Object writeValue(AnyValue value) throws IOException {
        var builder = XContentFactory.jsonBuilder().startObject();
        OTLPXContentUtils.writeAnyValue(builder, "v", value);
        builder.endObject();
        return MAPPER.readValue(builder.toString(), Map.class).get("v");
    }

    /** Writes attributes into an object and returns the parsed map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> writeAttributes(List<KeyValue> attrs) throws IOException {
        var builder = XContentFactory.jsonBuilder().startObject();
        OTLPXContentUtils.writeAttributes(builder, attrs);
        builder.endObject();
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    /**
     * Writes resource into an outer object (to capture any top-level fields like serviceName)
     * and returns the full parsed map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> writeResource(Resource resource, String schemaUrl) throws IOException {
        var builder = XContentFactory.jsonBuilder().startObject();
        String serviceName = OTLPXContentUtils.writeResource(builder, resource, schemaUrl);
        if (serviceName != null) {
            builder.field("serviceName", serviceName);
        }
        builder.endObject();
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    /** Writes instrumentationScope into an outer object and returns the full parsed map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> writeScope(InstrumentationScope scope, String schemaUrl) throws IOException {
        var builder = XContentFactory.jsonBuilder().startObject();
        OTLPXContentUtils.writeInstrumentationScope(builder, scope, schemaUrl);
        builder.endObject();
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
