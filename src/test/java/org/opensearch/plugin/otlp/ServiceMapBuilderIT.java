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
import io.opentelemetry.proto.trace.v1.Span;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.plugin.otlp.index.ServiceMapBuilder;
import org.opensearch.search.SearchHit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for ServiceMapBuilder against an embedded OpenSearch node.
 * run() is called directly to avoid waiting for the scheduler tick.
 */
public class ServiceMapBuilderIT extends OTLPPluginITBase {

    private static final String SERVICE_MAP_INDEX = "otel-v2-apm-service-map-test";

    private static final ByteString TRACE_ID = ByteString.copyFrom(
        new byte[]{ 10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25 });
    private static final ByteString ROOT_SPAN_ID = ByteString.copyFrom(
        new byte[]{ 10,11,12,13,14,15,16,17 });
    private static final ByteString CHILD_SPAN_ID = ByteString.copyFrom(
        new byte[]{ 20,21,22,23,24,25,26,27 });

    // ── two-service trace produces one edge and one leaf observation ──────────

    public void testTwoServicesProduceTwoRelationships() throws Exception {
        // Root span from frontend-svc (CLIENT)
        export(OTLPTracesIT.traceRequest("frontend-svc", "parent-lib", "GET /api",
            Span.SpanKind.SPAN_KIND_CLIENT, TRACE_ID, ROOT_SPAN_ID, null));
        // Child span from backend-svc (SERVER, parent = root)
        export(OTLPTracesIT.traceRequest("backend-svc", "child-lib", "child-op",
            Span.SpanKind.SPAN_KIND_SERVER, TRACE_ID, CHILD_SPAN_ID, ROOT_SPAN_ID));
        refresh(TRACES_INDEX);
        assertEquals("two spans indexed", 2, searchAll(TRACES_INDEX).getHits().getTotalHits().value());

        builder().run();
        refresh(SERVICE_MAP_INDEX);
        assertV2Mapping();

        assertEquals("one edge and one leaf document in service map",
            2, searchAll(SERVICE_MAP_INDEX).getHits().getTotalHits().value());

        boolean hasEdge = false, hasLeaf = false;
        for (SearchHit hit : searchAll(SERVICE_MAP_INDEX).getHits().getHits()) {
            Map<String, Object> src = hit.getSourceAsMap();
            assertNotNull("timestamp is required", src.get("timestamp"));
            assertNotNull("node hash is required", src.get("nodeConnectionHash"));
            assertNotNull("operation hash is required", src.get("operationConnectionHash"));
            if (src.get("targetNode") == null) {
                hasLeaf = true;
            } else {
                hasEdge = true;
                @SuppressWarnings("unchecked")
                Map<String, Object> sourceNode = (Map<String, Object>) src.get("sourceNode");
                @SuppressWarnings("unchecked")
                Map<String, Object> targetNode = (Map<String, Object>) src.get("targetNode");
                @SuppressWarnings("unchecked")
                Map<String, Object> sourceKeys = (Map<String, Object>) sourceNode.get("keyAttributes");
                @SuppressWarnings("unchecked")
                Map<String, Object> targetKeys = (Map<String, Object>) targetNode.get("keyAttributes");
                assertEquals("frontend-svc", sourceKeys.get("name"));
                assertEquals("backend-svc", targetKeys.get("name"));
                assertEquals("generic:default", sourceKeys.get("environment"));
            }
        }
        assertTrue("edge document present", hasEdge);
        assertTrue("leaf document present", hasLeaf);
    }

    @SuppressWarnings("unchecked")
    private void assertV2Mapping() {
        GetMappingsResponse response = client().admin().indices()
            .prepareGetMappings(SERVICE_MAP_INDEX).get();
        Map<String, Object> properties = (Map<String, Object>)
            response.getMappings().get(SERVICE_MAP_INDEX).sourceAsMap().get("properties");

        assertTrue(properties.containsKey("sourceNode"));
        assertTrue(properties.containsKey("targetNode"));
        assertTrue(properties.containsKey("sourceOperation"));
        assertTrue(properties.containsKey("targetOperation"));
        assertMappingType(properties, "nodeConnectionHash", "keyword");
        assertMappingType(properties, "operationConnectionHash", "keyword");
        assertMappingType(properties, "timestamp", "date");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ServiceMapBuilder builder() {
        return new ServiceMapBuilder(
            client(),
            getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class),
            TRACES_INDEX,
            SERVICE_MAP_INDEX,
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES));
    }
}
