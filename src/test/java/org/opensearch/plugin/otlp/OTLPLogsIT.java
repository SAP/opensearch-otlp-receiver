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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration tests for OTLP log ingestion. Covers HTTP/JSON,
 * HTTP/protobuf, and gRPC transports. Mapping assertions target fields
 * defined in the ss4o_logs composable template (logs-otel-v1-*).
 */
public class OTLPLogsIT extends OTLPPluginITBase {

    // Matches the ss4o_logs template pattern "logs-otel-v1-*".
    private static final String INDEX = "logs-otel-v1-test";

    // JSON payload for the HTTP/JSON transport variant.
    private static final String JSON_PAYLOAD = "{\n"
        + "  \"resourceLogs\": [{\n"
        + "    \"resource\": {\n"
        + "      \"attributes\": [{\"key\": \"service.name\","
        +          " \"value\": {\"stringValue\": \"log-svc\"}}]\n"
        + "    },\n"
        + "    \"scopeLogs\": [{\n"
        + "      \"scope\": {\"name\": \"log-lib\"},\n"
        + "      \"logRecords\": [{\n"
        + "        \"timeUnixNano\":         \"1000000000\",\n"
        + "        \"observedTimeUnixNano\": \"1100000000\",\n"
        + "        \"severityNumber\": \"SEVERITY_NUMBER_ERROR\",\n"
        + "        \"severityText\": \"ERROR\",\n"
        + "        \"body\": {\"stringValue\": \"test log message\"},\n"
        + "        \"traceId\": \"AQIDBAUGBwgJCgsMDQ4PEA==\",\n"
        + "        \"spanId\":  \"AQIDBAUGBwg=\",\n"
        + "        \"attributes\": [{\"key\": \"log.source\","
        +          " \"value\": {\"stringValue\": \"app\"}}]\n"
        + "      }]\n"
        + "    }]\n"
        + "  }]\n"
        + "}";

    // ── transport: HTTP/JSON ──────────────────────────────────────────────────────

    public void testLogFields_httpJson() throws Exception {
        sendJsonOTLP("/v1/logs", JSON_PAYLOAD);
        assertLogDocument(INDEX);
    }

    // ── transport: HTTP/protobuf ──────────────────────────────────────────────────

    public void testLogFields_httpProto() throws Exception {
        emitLog(OtlpProtocol.HTTP_PROTOBUF);
        assertLogDocument(INDEX);
    }

    // ── transport: gRPC ──────────────────────────────────────────────────────────

    public void testLogFields_grpc() throws Exception {
        emitLog(OtlpProtocol.GRPC);
        assertLogDocument(INDEX);
    }

    // ── mapping: fields defined in the ss4o_logs template ────────────────────────

    public void testLogMappingTypes() throws Exception {
        emitLog(OtlpProtocol.GRPC);
        refresh(INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(INDEX).sourceAsMap().get("properties"));

        // Fields explicitly typed in the ss4o_logs composable template.
        assertMappingType(props, "traceId", "keyword");
        assertMappingType(props, "spanId",  "keyword");
        assertMappingType(props, "time",    "date_nanos");
        assertMappingType(props, "flags",   "long");
    }

    // ── signal emission ───────────────────────────────────────────────────────────

    private void emitLog(OtlpProtocol protocol) throws Exception {
        var exporter = buildLogExporter(protocol);
        var provider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .setResource(telemetryResource("log-svc"))
            .build();
        try {
            provider.get("log-lib").logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setSeverityText("ERROR")
                .setBody("test log message")
                .setAttribute(AttributeKey.stringKey("log.source"), "app")
                .emit();
            provider.forceFlush().join(10, TimeUnit.SECONDS);
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    // ── shared document assertion ─────────────────────────────────────────────────

    private void assertLogDocument(String index) {
        refresh(index);
        SearchResponse resp = searchAll(index);
        assertEquals(1, resp.getHits().getTotalHits().value());

        SearchHit hit = resp.getHits().getHits()[0];
        Map<String, Object> src = hit.getSourceAsMap();

        assertEquals("log-svc", src.get("serviceName"));
        assertEquals("test log message", src.get("body"));
        assertEquals("ERROR", ((Map<?, ?>) src.get("severity")).get("text"));
        // SEVERITY_NUMBER_ERROR = 17
        assertEquals(17, ((Number) ((Map<?, ?>) src.get("severity")).get("number")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) src.get("attributes");
        assertNotNull(attrs);
        assertEquals("app", attrs.get("log.source"));
    }
}
