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
package org.opensearch.plugin.otlp.index;

import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.OpenSearchTestCase;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unchecked")
public class ServiceMapBuilderTests extends OpenSearchTestCase {

    private static final Instant FIRST_TICK = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant SECOND_TICK = Instant.parse("2026-10-16T12:01:00Z");
    private static final Instant EVENT_BUCKET = Instant.parse("2026-10-16T11:59:00Z");

    public void testCrossServiceClientServerPairEmitsEdgeAndLeaf() {
        Map<String, Object> clientSpan = span(
            "trace-1", "client", null, "frontend", "SPAN_KIND_CLIENT", "POST /checkout");
        Map<String, Object> serverSpan = span(
            "trace-1", "server", "client", "checkout", "SPAN_KIND_SERVER", "Checkout");
        TraceGroupEnricherTests.StubClient client = stub(List.of(clientSpan, serverSpan));

        builder(client, FIRST_TICK, List.of()).run();

        BulkRequest bulk = onlyBulk(client);
        assertEquals("one edge and one leaf observation", 2, bulk.numberOfActions());

        Map<String, Object> edge = documents(bulk).stream()
            .filter(document -> document.get("targetNode") != null)
            .findFirst().orElseThrow();
        assertNode(edge, "sourceNode", "frontend", "generic:default");
        assertNode(edge, "targetNode", "checkout", "generic:default");
        assertEquals("POST /checkout", ((Map<String, Object>) edge.get("sourceOperation")).get("name"));
        assertEquals("Checkout", ((Map<String, Object>) edge.get("targetOperation")).get("name"));
        assertEquals(EVENT_BUCKET.toString(), edge.get("timestamp"));
        assertNotNull(edge.get("nodeConnectionHash"));
        assertNotNull(edge.get("operationConnectionHash"));

        Map<String, Object> leaf = documents(bulk).stream()
            .filter(document -> document.get("targetNode") == null)
            .findFirst().orElseThrow();
        assertNode(leaf, "sourceNode", "checkout", "generic:default");
        assertNull(leaf.get("targetOperation"));
    }

    public void testServerWithoutRootOrTraceGroupEmitsLeaf() {
        TraceGroupEnricherTests.StubClient client = stub(List.of(
            span("trace-orphan", "server", "missing", "worker", "SPAN_KIND_SERVER", "consume")));

        builder(client, FIRST_TICK, List.of()).run();

        Map<String, Object> leaf = documents(onlyBulk(client)).get(0);
        assertNode(leaf, "sourceNode", "worker", "generic:default");
        assertNull(leaf.get("targetNode"));
        assertFalse("v2 documents do not contain trace groups", leaf.containsKey("traceGroupName"));
    }

    public void testSameServiceCallDoesNotEmitAnEdge() {
        Map<String, Object> clientSpan = span(
            "trace-same", "client", null, "orders", "SPAN_KIND_CLIENT", "internal call");
        Map<String, Object> serverSpan = span(
            "trace-same", "server", "client", "orders", "SPAN_KIND_SERVER", "internal handler");
        TraceGroupEnricherTests.StubClient client = stub(List.of(clientSpan, serverSpan));

        builder(client, FIRST_TICK, List.of()).run();

        List<Map<String, Object>> documents = documents(onlyBulk(client));
        assertEquals(1, documents.size());
        assertNull(documents.get(0).get("targetNode"));
    }

    public void testLateParentIsCorrelatedOnOverlappingLookback() {
        Map<String, Object> clientSpan = span(
            "trace-late", "client", null, "frontend", "SPAN_KIND_CLIENT", "GET /late");
        Map<String, Object> serverSpan = span(
            "trace-late", "server", "client", "backend", "SPAN_KIND_SERVER", "late handler");
        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(serverSpan), List.of(serverSpan),
            List.of(clientSpan, serverSpan), List.of(clientSpan, serverSpan)));
        ServiceMapBuilder builder = builder(client, FIRST_TICK, List.of());

        builder.run();
        builder.run();

        assertEquals(2, client.bulkRequests.size());
        assertFalse(documents(client.bulkRequests.get(0)).stream()
            .anyMatch(document -> document.get("targetNode") != null));
        assertTrue(documents(client.bulkRequests.get(1)).stream()
            .anyMatch(document -> document.get("targetNode") != null));
        assertTrue("discovery is driven by the ingestion timestamp",
            client.searchRequests.get(0).source().query().toString().contains("indexedAt"));
    }

    public void testRelationshipIdsAreIndependentOfProcessingTime() {
        List<Map<String, Object>> spans = List.of(
            span("trace-history", "client", null, "frontend", "SPAN_KIND_CLIENT", "GET /items"),
            span("trace-history", "server", "client", "catalog", "SPAN_KIND_SERVER", "ListItems"));
        TraceGroupEnricherTests.StubClient firstClient = stub(spans);
        TraceGroupEnricherTests.StubClient secondClient = stub(spans);

        builder(firstClient, FIRST_TICK, List.of()).run();
        builder(secondClient, SECOND_TICK, List.of()).run();

        IndexRequest first = edgeRequest(onlyBulk(firstClient));
        IndexRequest second = edgeRequest(onlyBulk(secondClient));
        Map<String, Object> firstSource = first.sourceAsMap();
        Map<String, Object> secondSource = second.sourceAsMap();
        assertEquals(firstSource.get("nodeConnectionHash"), secondSource.get("nodeConnectionHash"));
        assertEquals(firstSource.get("operationConnectionHash"), secondSource.get("operationConnectionHash"));
        assertEquals("the same relationship overwrites on retry", first.id(), second.id());
        assertEquals(firstSource.get("timestamp"), secondSource.get("timestamp"));
    }

    public void testRetriesWithinIntervalUseStableTimestampAndIds() {
        List<Map<String, Object>> spans = List.of(
            span("a0000000000000000000000000000000", "client", null,
                "frontend", "SPAN_KIND_CLIENT", "GET /items"),
            span("a0000000000000000000000000000000", "server", "client",
                "catalog", "SPAN_KIND_SERVER", "ListItems"));
        TraceGroupEnricherTests.StubClient firstClient = stub(spans);
        TraceGroupEnricherTests.StubClient retryClient = stub(spans);

        builder(firstClient, FIRST_TICK.plusSeconds(5), List.of()).run();
        builder(retryClient, FIRST_TICK.plusSeconds(55), List.of()).run();

        IndexRequest first = edgeRequest(onlyBulk(firstClient));
        IndexRequest retry = edgeRequest(onlyBulk(retryClient));
        assertEquals(EVENT_BUCKET.toString(), first.sourceAsMap().get("timestamp"));
        assertEquals(first.sourceAsMap().get("timestamp"), retry.sourceAsMap().get("timestamp"));
        assertEquals("retry in one interval overwrites the same observation", first.id(), retry.id());
    }

    public void testTraceIdPrefixIsAppliedToSearch() {
        TraceGroupEnricherTests.StubClient client = stub(List.of(
            span("a0000000000000000000000000000000", "server", null,
                "catalog", "SPAN_KIND_SERVER", "ListItems")));

        builder(client, FIRST_TICK, List.of(), "a").run();

        String query = client.searchRequests.get(0).source().query().toString();
        assertTrue(query, query.contains("prefix"));
        assertTrue(query, query.contains("traceId"));
        assertTrue(query, query.contains("\"value\" : \"a\""));
    }

    public void testEnvironmentAndConfiguredGroupingAttributesAreEmitted() {
        Map<String, Object> serverSpan = span(
            "trace-env", "server", null, "payments", "SPAN_KIND_SERVER", "charge");
        serverSpan.put("resource", Map.of("attributes", Map.of(
            "deployment.environment.name", "production",
            "service.version", "2.4.0",
            "k8s.cluster.name", "eu-central")));
        TraceGroupEnricherTests.StubClient client = stub(List.of(serverSpan));

        builder(client, FIRST_TICK, List.of("service.version", "k8s.cluster.name")).run();

        Map<String, Object> sourceNode = (Map<String, Object>)
            documents(onlyBulk(client)).get(0).get("sourceNode");
        Map<String, Object> keyAttributes = (Map<String, Object>) sourceNode.get("keyAttributes");
        assertEquals("production", keyAttributes.get("environment"));
        assertEquals(Map.of("service.version", "2.4.0", "k8s.cluster.name", "eu-central"),
            sourceNode.get("groupByAttributes"));
    }

    public void testEntryServerOperationBecomesEdgeSourceOperation() {
        Map<String, Object> entry = span(
            "trace-entry", "entry", null, "frontend", "SPAN_KIND_SERVER", "POST /orders");
        Map<String, Object> clientCall = span(
            "trace-entry", "client", "entry", "frontend", "SPAN_KIND_CLIENT", "HTTP GET");
        Map<String, Object> downstream = span(
            "trace-entry", "downstream", "client", "inventory", "SPAN_KIND_SERVER", "Reserve");
        TraceGroupEnricherTests.StubClient client = stub(List.of(entry, clientCall, downstream));

        builder(client, FIRST_TICK, List.of()).run();

        Map<String, Object> edge = edgeRequest(onlyBulk(client)).sourceAsMap();
        assertEquals("POST /orders", ((Map<String, Object>) edge.get("sourceOperation")).get("name"));
    }

    public void testLateClientDescendantDeletesPreviouslyEmittedLeaf() {
        Map<String, Object> server = span(
            "trace-leaf", "server", null, "checkout", "SPAN_KIND_SERVER", "Checkout");
        Map<String, Object> internal = span(
            "trace-leaf", "internal", "server", "checkout", "SPAN_KIND_INTERNAL", "work");
        Map<String, Object> lateClient = span(
            "trace-leaf", "client", "internal", "checkout", "SPAN_KIND_CLIENT", "HTTP GET");
        lateClient.put("indexedAt", FIRST_TICK.plusSeconds(20).toString());
        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(server, internal), List.of(server, internal),
            List.of(lateClient), List.of(server, internal, lateClient)));
        ServiceMapBuilder builder = builder(client, FIRST_TICK.plusSeconds(30), List.of());

        builder.run();
        String leafId = ((IndexRequest) client.bulkRequests.get(0).requests().get(0)).id();
        builder.run();

        BulkRequest correction = client.bulkRequests.get(1);
        DeleteRequest delete = correction.requests().stream()
            .filter(DeleteRequest.class::isInstance)
            .map(DeleteRequest.class::cast)
            .findFirst().orElseThrow();
        assertEquals("the deterministic leaf ID can be derived without metadata", leafId, delete.id());
    }

    public void testLateEntrySpanOverwritesEdgeWithCorrectedSourceOperation() {
        Map<String, Object> clientCall = span(
            "trace-operation", "client", "entry", "frontend",
            "SPAN_KIND_CLIENT", "HTTP GET");
        Map<String, Object> downstream = span(
            "trace-operation", "downstream", "client", "inventory",
            "SPAN_KIND_SERVER", "Reserve");
        Map<String, Object> lateEntry = span(
            "trace-operation", "entry", null, "frontend",
            "SPAN_KIND_SERVER", "POST /orders");
        lateEntry.put("indexedAt", FIRST_TICK.plusSeconds(20).toString());
        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(clientCall, downstream), List.of(clientCall, downstream),
            List.of(lateEntry), List.of(lateEntry, clientCall, downstream)));
        ServiceMapBuilder builder = builder(client, FIRST_TICK.plusSeconds(30), List.of());

        builder.run();
        IndexRequest original = edgeRequest(client.bulkRequests.get(0));
        builder.run();
        IndexRequest corrected = edgeRequest(client.bulkRequests.get(1));

        assertEquals(original.id(), corrected.id());
        assertEquals("HTTP GET",
            ((Map<String, Object>) original.sourceAsMap().get("sourceOperation")).get("name"));
        assertEquals("POST /orders",
            ((Map<String, Object>) corrected.sourceAsMap().get("sourceOperation")).get("name"));
    }

    private static TraceGroupEnricherTests.StubClient stub(List<Map<String, Object>> spans) {
        return new TraceGroupEnricherTests.StubClient(List.of(spans, spans));
    }

    private static ServiceMapBuilder builder(
            TraceGroupEnricherTests.StubClient client, Instant time, List<String> groupByAttributes) {
        return builder(client, time, groupByAttributes, null);
    }

    private static ServiceMapBuilder builder(
            TraceGroupEnricherTests.StubClient client,
            Instant time,
            List<String> groupByAttributes,
            String traceIdPrefix) {
        return new ServiceMapBuilder(
            client,
            null,
            "otel-v1-apm-span",
            "otel-v2-apm-service-map",
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            groupByAttributes,
            traceIdPrefix,
            Clock.fixed(time, ZoneOffset.UTC));
    }

    private static Map<String, Object> span(
            String traceId,
            String spanId,
            String parentSpanId,
            String serviceName,
            String kind,
            String name) {
        Map<String, Object> span = new HashMap<>();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentSpanId != null) {
            span.put("parentSpanId", parentSpanId);
        }
        span.put("serviceName", serviceName);
        span.put("kind", kind);
        span.put("name", name);
        span.put("endTime", FIRST_TICK.minusSeconds(30).toString());
        span.put("indexedAt", FIRST_TICK.minusSeconds(10).toString());
        return span;
    }

    private static BulkRequest onlyBulk(TraceGroupEnricherTests.StubClient client) {
        assertEquals("expected one bulk request", 1, client.bulkRequests.size());
        return client.bulkRequests.get(0);
    }

    private static List<Map<String, Object>> documents(BulkRequest bulk) {
        return bulk.requests().stream()
            .filter(IndexRequest.class::isInstance)
            .map(request -> ((IndexRequest) request).sourceAsMap())
            .toList();
    }

    private static IndexRequest edgeRequest(BulkRequest bulk) {
        return bulk.requests().stream()
            .map(request -> (IndexRequest) request)
            .filter(request -> request.sourceAsMap().get("targetNode") != null)
            .findFirst().orElseThrow();
    }

    private static void assertNode(
            Map<String, Object> document, String field, String name, String environment) {
        Map<String, Object> node = (Map<String, Object>) document.get(field);
        assertEquals("service", node.get("type"));
        Map<String, Object> keys = (Map<String, Object>) node.get("keyAttributes");
        assertEquals(name, keys.get("name"));
        assertEquals(environment, keys.get("environment"));
    }
}
