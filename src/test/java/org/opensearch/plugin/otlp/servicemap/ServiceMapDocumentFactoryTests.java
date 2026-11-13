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
package org.opensearch.plugin.otlp.servicemap;

import org.opensearch.test.OpenSearchTestCase;

import java.time.Instant;
import java.util.Map;

@SuppressWarnings("unchecked")
public class ServiceMapDocumentFactoryTests extends OpenSearchTestCase {

    private static final Instant EVENT_TIME = Instant.parse("2026-10-16T12:00:45Z");
    private static final String BUCKET = "2026-10-16T12:00:00Z";
    private final ServiceMapDocumentFactory factory = new ServiceMapDocumentFactory(60_000L);

    public void testCreatesDeterministicEdgeDocument() {
        ServiceMapSpan client = span("client", "frontend", "HTTP GET", EVENT_TIME);
        ServiceMapSpan server = span("server", "catalog", "ListItems", EVENT_TIME);

        ServiceMapDocumentFactory.GeneratedDocument document =
            factory.edge(client, server, "GET /items");

        assertTrue(document.id().matches("[0-9a-f]{64}"));
        assertEquals(BUCKET, document.source().get("timestamp"));
        assertNode(document.source(), "sourceNode", "frontend");
        assertNode(document.source(), "targetNode", "catalog");
        assertEquals("GET /items", operationName(document.source(), "sourceOperation"));
        assertEquals("ListItems", operationName(document.source(), "targetOperation"));
        assertNotNull(document.source().get("nodeConnectionHash"));
        assertNotNull(document.source().get("operationConnectionHash"));
        assertEquals(document, factory.edge(client, server, "GET /items"));
        assertEquals("trace identity does not create duplicate history entries", document.id(),
            factory.edge(
                span("trace-2", "other-client", "frontend", "HTTP GET", EVENT_TIME),
                span("trace-2", "other-server", "catalog", "ListItems", EVENT_TIME),
                "GET /items").id());
    }

    public void testLeafIdIsIndependentOfTraceAndChangesWithBucket() {
        ServiceMapSpan server = span("server", "catalog", "ListItems", EVENT_TIME);

        ServiceMapDocumentFactory.GeneratedDocument document = factory.leaf(server);

        assertTrue(document.id().matches("[0-9a-f]{64}"));
        assertEquals(document.id(), factory.leaf(
            span("trace-2", "other-server", "catalog", "ListItems", EVENT_TIME)).id());
        assertNull(document.source().get("targetNode"));
        assertNull(document.source().get("targetOperation"));
        assertNotEquals(document.id(), factory.leaf(
            span("server", "catalog", "ListItems", EVENT_TIME.plusSeconds(60))).id());
    }

    public void testUsesIndexedAtWhenEndTimeIsMissing() {
        ServiceMapSpan server = new ServiceMapSpan(
            "trace-1", "server", null, "catalog", "SPAN_KIND_SERVER", "ListItems",
            null, EVENT_TIME, "production", Map.of());

        assertEquals(BUCKET, factory.leaf(server).source().get("timestamp"));
    }

    private static ServiceMapSpan span(
            String spanId, String serviceName, String operationName, Instant endTime) {
        return span("trace-1", spanId, serviceName, operationName, endTime);
    }

    private static ServiceMapSpan span(
            String traceId, String spanId, String serviceName, String operationName, Instant endTime) {
        return new ServiceMapSpan(
            traceId, spanId, null, serviceName, "SPAN_KIND_SERVER", operationName,
            endTime, EVENT_TIME, "production", Map.of("service.version", "1.0"));
    }

    private static void assertNode(
            Map<String, Object> document, String field, String serviceName) {
        Map<String, Object> node = (Map<String, Object>) document.get(field);
        Map<String, Object> keys = (Map<String, Object>) node.get("keyAttributes");
        assertEquals(serviceName, keys.get("name"));
        assertEquals("production", keys.get("environment"));
        assertEquals(Map.of("service.version", "1.0"), node.get("groupByAttributes"));
    }

    private static String operationName(Map<String, Object> document, String field) {
        return (String) ((Map<String, Object>) document.get(field)).get("name");
    }
}
