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

        assertEquals("9b784e6ea35b4422d00005ca52a143a354281dac055c0c81e5d47d042942507c",
            document.id());
        assertEquals(BUCKET, document.source().get("timestamp"));
        assertNode(document.source(), "sourceNode", "frontend");
        assertNode(document.source(), "targetNode", "catalog");
        assertEquals("GET /items", operationName(document.source(), "sourceOperation"));
        assertEquals("ListItems", operationName(document.source(), "targetOperation"));
        assertNotNull(document.source().get("nodeConnectionHash"));
        assertNotNull(document.source().get("operationConnectionHash"));
        assertEquals(document, factory.edge(client, server, "GET /items"));
    }

    public void testLeafIdMatchesGeneratedDocumentAndChangesWithBucket() {
        ServiceMapSpan server = span("server", "catalog", "ListItems", EVENT_TIME);

        ServiceMapDocumentFactory.GeneratedDocument document = factory.leaf(server);

        assertEquals("f2d30c5c131e22dffa7eca4d1c4672cdbb9ef1989fd3a9d69a7cb352709843a3",
            document.id());
        assertEquals(document.id(), factory.leafId(server));
        assertNull(document.source().get("targetNode"));
        assertNull(document.source().get("targetOperation"));
        assertNotEquals(document.id(), factory.leafId(
            span("server", "catalog", "ListItems", EVENT_TIME.plusSeconds(60))));
    }

    public void testUsesIndexedAtWhenEndTimeIsMissing() {
        ServiceMapSpan server = new ServiceMapSpan(
            "trace-1", "server", null, "catalog", "SPAN_KIND_SERVER", "ListItems",
            null, EVENT_TIME, "production", Map.of());

        assertEquals(BUCKET, factory.leaf(server).source().get("timestamp"));
    }

    private static ServiceMapSpan span(
            String spanId, String serviceName, String operationName, Instant endTime) {
        return new ServiceMapSpan(
            "trace-1", spanId, null, serviceName, "SPAN_KIND_SERVER", operationName,
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
