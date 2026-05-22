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

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Streaming XContent helpers shared across all OTLP document types.
 * Mirrors OTLPTransformUtils for the map-based path but writes directly
 * to an XContentBuilder to avoid intermediate object allocation.
 */
final class OTLPXContentUtils {

    private OTLPXContentUtils() {}

    static String toIso8601(long unixNanos) {
        return Instant.ofEpochSecond(0, unixNanos).toString();
    }

    static String hexFromBytes(ByteString bytes) {
        byte[] raw = bytes.toByteArray();
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Caps Infinity at ±Float.MAX_VALUE for JSON compatibility.
     */
    static Double toDouble(double value) {
        if (Double.isInfinite(value)) {
            return value > 0 ? (double) Float.MAX_VALUE : (double) -Float.MAX_VALUE;
        }
        return value;
    }

    /**
     * Writes a single named field whose value is an OTLP AnyValue.
     * Arrays and KVLists are written as nested JSON arrays/objects.
     */
    static void writeAnyValue(XContentBuilder builder, String fieldName, AnyValue value) throws IOException {
        switch (value.getValueCase()) {
            case STRING_VALUE:  builder.field(fieldName, value.getStringValue());  return;
            case INT_VALUE:     builder.field(fieldName, value.getIntValue());     return;
            case DOUBLE_VALUE:  builder.field(fieldName, value.getDoubleValue());  return;
            case BOOL_VALUE:    builder.field(fieldName, value.getBoolValue());    return;
            case BYTES_VALUE:   builder.field(fieldName, hexFromBytes(value.getBytesValue())); return;
            case ARRAY_VALUE:
                builder.startArray(fieldName);
                writeArrayValues(builder, value.getArrayValue());
                builder.endArray();
                return;
            case KVLIST_VALUE:
                builder.startObject(fieldName);
                writeKVListEntries(builder, value.getKvlistValue());
                builder.endObject();
                return;
            default:
                builder.nullField(fieldName);
        }
    }

    /**
     * Writes a list of KeyValue pairs as fields of the current object.
     * The caller is responsible for opening and closing the enclosing object.
     */
    static void writeAttributes(XContentBuilder builder, List<KeyValue> attributes) throws IOException {
        for (KeyValue kv : attributes) {
            writeAnyValue(builder, kv.getKey(), kv.getValue());
        }
    }

    /**
     * Writes the "resource" object. Returns the value of the "service.name" attribute if present,
     * otherwise null. The caller can then emit "serviceName" as a top-level field.
     */
    static String writeResource(XContentBuilder builder, Resource resource, String schemaUrl) throws IOException {
        String serviceName = null;
        builder.startObject("resource")
            .field("schemaUrl", schemaUrl)
            .field("droppedAttributesCount", resource.getDroppedAttributesCount());
        if (resource.getAttributesCount() > 0) {
            builder.startObject("attributes");
            for (KeyValue kv : resource.getAttributesList()) {
                writeAnyValue(builder, kv.getKey(), kv.getValue());
                if ("service.name".equals(kv.getKey())) {
                    serviceName = kv.getValue().getStringValue();
                }
            }
            builder.endObject();
        }
        builder.endObject();
        return serviceName;
    }

    /**
     * Writes the "instrumentationScope" object including its optional attributes.
     */
    static void writeInstrumentationScope(
            XContentBuilder builder, InstrumentationScope scope, String schemaUrl) throws IOException {
        builder.startObject("instrumentationScope")
            .field("name", scope.getName())
            .field("version", scope.getVersion())
            .field("schemaUrl", schemaUrl)
            .field("droppedAttributesCount", scope.getDroppedAttributesCount());
        if (scope.getAttributesCount() > 0) {
            builder.startObject("attributes");
            writeAttributes(builder, scope.getAttributesList());
            builder.endObject();
        }
        builder.endObject();
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private static void writeArrayValues(XContentBuilder builder, ArrayValue array) throws IOException {
        for (AnyValue element : array.getValuesList()) {
            switch (element.getValueCase()) {
                case STRING_VALUE: builder.value(element.getStringValue()); break;
                case INT_VALUE:    builder.value(element.getIntValue());    break;
                case DOUBLE_VALUE: builder.value(element.getDoubleValue()); break;
                case BOOL_VALUE:   builder.value(element.getBoolValue());   break;
                case BYTES_VALUE:  builder.value(hexFromBytes(element.getBytesValue())); break;
                case ARRAY_VALUE:
                    builder.startArray();
                    writeArrayValues(builder, element.getArrayValue());
                    builder.endArray();
                    break;
                case KVLIST_VALUE:
                    builder.startObject();
                    writeKVListEntries(builder, element.getKvlistValue());
                    builder.endObject();
                    break;
                default: break;
            }
        }
    }

    private static void writeKVListEntries(XContentBuilder builder, KeyValueList kvList) throws IOException {
        for (KeyValue kv : kvList.getValuesList()) {
            writeAnyValue(builder, kv.getKey(), kv.getValue());
        }
    }
}
