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
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.plugin.otlp.document.LogDocument;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Map;

public class LogsTransformerTests extends OpenSearchTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LogsTransformer transformer = new LogsTransformer();

    public void testEmptyRequestProducesNoDocs() {
        assertTrue(transformer.transform(ExportLogsServiceRequest.getDefaultInstance()).isEmpty());
    }

    public void testBasicLogFields() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setObservedTimeUnixNano(2_000_000_000L)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
            .setSeverityText("ERROR")
            .setBody(AnyValue.newBuilder().setStringValue("something went wrong"))
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(log, "my-svc")).get(0));

        assertEquals("1970-01-01T00:00:01Z", doc.get("time"));
        assertEquals("1970-01-01T00:00:02Z", doc.get("observedTimestamp"));
        @SuppressWarnings("unchecked")
        Map<String, Object> severity = (Map<String, Object>) doc.get("severity");
        assertEquals(SeverityNumber.SEVERITY_NUMBER_ERROR.getNumber(), severity.get("number"));
        assertEquals("ERROR", severity.get("text"));
        assertEquals("something went wrong", doc.get("body"));
        assertEquals("my-svc", doc.get("serviceName"));
    }

    public void testTraceContextPreserved() throws Exception {
        ByteString traceId = ByteString.copyFrom(new byte[16]);
        ByteString spanId  = ByteString.copyFrom(new byte[8]);
        LogRecord log = LogRecord.newBuilder()
            .setTraceId(traceId)
            .setSpanId(spanId)
            .build();

        Map<String, Object> doc = toMap(transformer.transform(requestWith(log, "svc")).get(0));
        assertEquals("00000000000000000000000000000000", doc.get("traceId"));
        assertEquals("0000000000000000", doc.get("spanId"));
    }

    public void testAttributesNested_NoDedot() throws Exception {
        LogRecord log = LogRecord.newBuilder()
            .addAttributes(kv("log.file.name", "app.log"))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(requestWith(log, "svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) doc.get("attributes");
        assertEquals("app.log", attrs.get("log.file.name"));
        assertNull(attrs.get("log@file@name"));
    }

    public void testResourceIsNestedObject() throws Exception {
        Map<String, Object> doc = toMap(transformer.transform(requestWith(LogRecord.getDefaultInstance(), "svc")).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) doc.get("resource");
        assertNotNull(resource);
        assertTrue(resource.containsKey("attributes"));
    }

    public void testInstrumentationScopeIsNestedObject() throws Exception {
        ExportLogsServiceRequest req = ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(ResourceLogs.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", "svc")))
                .addScopeLogs(ScopeLogs.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName("my-lib").setVersion("2.0"))
                    .addLogRecords(LogRecord.getDefaultInstance())))
            .build();
        Map<String, Object> doc = toMap(transformer.transform(req).get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) doc.get("instrumentationScope");
        assertEquals("my-lib", scope.get("name"));
        assertEquals("2.0", scope.get("version"));
    }

    public void testMultipleLogsAcrossScopes() {
        ExportLogsServiceRequest req = ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(ResourceLogs.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", "svc")))
                .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.getDefaultInstance()))
                .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.getDefaultInstance())))
            .build();
        assertEquals(2, transformer.transform(req).size());
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(LogDocument doc) throws IOException {
        var builder = XContentFactory.jsonBuilder();
        doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return MAPPER.readValue(builder.toString(), Map.class);
    }

    private ExportLogsServiceRequest requestWith(LogRecord log, String serviceName) {
        return ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(ResourceLogs.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("service.name", serviceName)))
                .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(log)))
            .build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }
}
