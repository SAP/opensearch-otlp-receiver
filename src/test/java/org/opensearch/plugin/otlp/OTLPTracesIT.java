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

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration tests for OTLP trace ingestion. Each test method
 * covers the same assertion via a different transport: HTTP/JSON, HTTP/protobuf,
 * and gRPC. Mapping assertions use an index name that matches the ss4o_traces
 * composable template pattern (otel-v1-apm-span-*) so that template-defined
 * keyword / date_nanos / long types are applied.
 */
public class OTLPTracesIT extends OTLPPluginITBase {

    // Matches the ss4o_traces template pattern "otel-v1-apm-span-*".
    private static final String INDEX = "otel-v1-apm-span-test";

    // JSON payload — uses the OTLP JSON protobuf format (bytes fields are base64).
    private static final String JSON_PAYLOAD = "{\n"
        + "  \"resourceSpans\": [{\n"
        + "    \"resource\": {\n"
        + "      \"attributes\": [{\"key\": \"service.name\","
        +          " \"value\": {\"stringValue\": \"e2e-svc\"}}]\n"
        + "    },\n"
        + "    \"scopeSpans\": [{\n"
        + "      \"scope\": {\"name\": \"e2e-lib\", \"version\": \"1.0\"},\n"
        + "      \"spans\": [{\n"
        + "        \"traceId\": \"AQIDBAUGBwgJCgsMDQ4PEA==\",\n"
        + "        \"spanId\":  \"AQIDBAUGBwg=\",\n"
        + "        \"name\": \"e2e-span\",\n"
        + "        \"kind\": \"SPAN_KIND_SERVER\",\n"
        + "        \"startTimeUnixNano\": \"1000000000\",\n"
        + "        \"endTimeUnixNano\":   \"2000000000\",\n"
        + "        \"attributes\": [{\"key\": \"http.method\","
        +          " \"value\": {\"stringValue\": \"GET\"}}],\n"
        + "        \"status\": {\"code\": \"STATUS_CODE_OK\"}\n"
        + "      }]\n"
        + "    }]\n"
        + "  }]\n"
        + "}";

    // ── transport: HTTP/JSON ──────────────────────────────────────────────────────

    public void testSpanFields_httpJson() throws Exception {
        sendJsonOTLP("/v1/traces", JSON_PAYLOAD);
        assertSpanDocument(INDEX);
    }

    // ── transport: HTTP/protobuf ──────────────────────────────────────────────────

    public void testSpanFields_httpProto() throws Exception {
        emitSpan(OtlpProtocol.HTTP_PROTOBUF);
        assertSpanDocument(INDEX);
    }

    // ── transport: gRPC ──────────────────────────────────────────────────────────

    public void testSpanFields_grpc() throws Exception {
        emitSpan(OtlpProtocol.GRPC);
        assertSpanDocument(INDEX);
    }

    // ── mapping: applied from composable template (keyword, date_nanos, long) ────

    public void testSpanMappingTypes() throws Exception {
        emitSpan(OtlpProtocol.GRPC);
        refresh(INDEX);

        GetMappingsResponse resp = client().admin().indices()
            .prepareGetMappings(INDEX).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
            ((Map<String, Object>) resp.getMappings().get(INDEX).sourceAsMap().get("properties"));

        // Fields defined in the ss4o_traces template → must use exact template types.
        assertMappingType(props, "traceId",        "keyword");
        assertMappingType(props, "spanId",         "keyword");
        assertMappingType(props, "serviceName",    "keyword");
        assertMappingType(props, "name",           "keyword");
        assertMappingType(props, "kind",           "keyword");
        assertMappingType(props, "durationInNanos","long");
        assertMappingType(props, "startTime",      "date_nanos");
        assertMappingType(props, "endTime",        "date_nanos");
    }

    // ── signal emission ───────────────────────────────────────────────────────────

    private void emitSpan(OtlpProtocol protocol) throws Exception {
        var exporter = buildSpanExporter(protocol);
        var provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .setResource(telemetryResource("e2e-svc"))
            .build();
        try {
            provider.get("e2e-lib").spanBuilder("e2e-span")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", "GET")
                .startSpan()
                .setStatus(StatusCode.OK)
                .end();
            provider.forceFlush().join(10, TimeUnit.SECONDS);
        } finally {
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    // ── shared document assertion ─────────────────────────────────────────────────

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
}
