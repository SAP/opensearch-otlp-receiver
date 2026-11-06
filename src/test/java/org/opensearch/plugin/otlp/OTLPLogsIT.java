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
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;

import java.util.Map;

/**
 * End-to-end integration tests for OTLP log ingestion. Constructs OTLP proto
 * requests and calls the service implementation directly — no network needed.
 */
public class OTLPLogsIT extends OTLPPluginITBase {

    private static final ByteString TRACE_ID = ByteString.copyFrom(
        new byte[]{ 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16 });
    private static final ByteString SPAN_ID = ByteString.copyFrom(
        new byte[]{ 1,2,3,4,5,6,7,8 });

    // ── field values ─────────────────────────────────────────────────────────

    public void testLogFields() throws Exception {
        export(logRequest("log-svc", "log-lib"));
        assertLogDocument(LOGS_INDEX);
    }

    // ── mapping: template types applied ──────────────────────────────────────

    public void testLogMappingTypes() throws Exception {
        export(logRequest("log-svc", "log-lib"));
        refresh(LOGS_INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(LOGS_INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(LOGS_INDEX).sourceAsMap().get("properties"));

        assertMappingType(props, "traceId", "keyword");
        assertMappingType(props, "spanId",  "keyword");
        assertMappingType(props, "time",    "date_nanos");
        assertMappingType(props, "flags",   "long");
    }

    // ── document assertion ────────────────────────────────────────────────────

    private void assertLogDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        SearchHit hit = resp.getHits().getHits()[0];
        Map<String, Object> src = hit.getSourceAsMap();

        assertEquals("log-svc", src.get("serviceName"));
        assertEquals("test log message", src.get("body"));
        assertEquals("ERROR", ((Map<?, ?>) src.get("severity")).get("text"));
        assertEquals(17, ((Number) ((Map<?, ?>) src.get("severity")).get("number")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) src.get("attributes");
        assertNotNull(attrs);
        assertEquals("app", attrs.get("log.source"));
    }

    // ── proto builder helpers ─────────────────────────────────────────────────

    static ExportLogsServiceRequest logRequest(String serviceName, String scopeName) {
        return ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(ResourceLogs.newBuilder()
                .setResource(Resource.newBuilder()
                    .addAttributes(KeyValue.newBuilder()
                        .setKey("service.name")
                        .setValue(AnyValue.newBuilder().setStringValue(serviceName).build())
                        .build())
                    .build())
                .addScopeLogs(ScopeLogs.newBuilder()
                    .setScope(InstrumentationScope.newBuilder()
                        .setName(scopeName)
                        .build())
                    .addLogRecords(LogRecord.newBuilder()
                        .setTimeUnixNano(1_000_000_000L)
                        .setObservedTimeUnixNano(1_100_000_000L)
                        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
                        .setSeverityText("ERROR")
                        .setBody(AnyValue.newBuilder().setStringValue("test log message").build())
                        .setTraceId(TRACE_ID)
                        .setSpanId(SPAN_ID)
                        .addAttributes(KeyValue.newBuilder()
                            .setKey("log.source")
                            .setValue(AnyValue.newBuilder().setStringValue("app").build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }
}
