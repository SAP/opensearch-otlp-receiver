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
import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ServiceMapBuilderTests extends OpenSearchTestCase {

    // ── cross-service edge emits 2 relationship docs ──────────────────────────

    public void testCrossServiceEdgeEmitsTwoDocs() {
        Map<String, Object> parentSpan = new HashMap<>();
        parentSpan.put("spanId", "span-root");
        parentSpan.put("serviceName", "frontend");
        parentSpan.put("kind", "SPAN_KIND_CLIENT");
        parentSpan.put("traceGroup", "checkout");

        Map<String, Object> childSpan = new HashMap<>();
        childSpan.put("traceId", "trace1");
        childSpan.put("spanId", "span-child");
        childSpan.put("parentSpanId", "span-root");
        childSpan.put("serviceName", "backend");
        childSpan.put("kind", "SPAN_KIND_SERVER");
        childSpan.put("name", "process");
        childSpan.put("traceGroup", "checkout");

        // run() issues: child search → parent search
        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(childSpan),   // child spans search
            List.of(parentSpan)   // parent spans search
        ));
        ServiceMapBuilder builder = builder(client);

        builder.run();

        assertEquals("one bulk request for service map", 1, client.bulkRequests.size());
        BulkRequest bulk = client.bulkRequests.get(0);
        assertEquals("two IndexRequests for cross-service edge", 2, bulk.numberOfActions());

        boolean hasDestination = bulk.requests().stream()
            .filter(r -> r instanceof IndexRequest)
            .map(r -> (IndexRequest) r)
            .anyMatch(r -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) r.sourceAsMap();
                return "frontend".equals(src.get("serviceName")) && src.containsKey("destination");
            });
        boolean hasTarget = bulk.requests().stream()
            .filter(r -> r instanceof IndexRequest)
            .map(r -> (IndexRequest) r)
            .anyMatch(r -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) r.sourceAsMap();
                return "backend".equals(src.get("serviceName")) && src.containsKey("target");
            });
        assertTrue("destination doc present", hasDestination);
        assertTrue("target doc present", hasTarget);
    }

    // ── same-service spans emit no relationship docs ──────────────────────────

    public void testSameServiceEmitsNoDocs() {
        Map<String, Object> parentSpan = new HashMap<>();
        parentSpan.put("spanId", "span-root");
        parentSpan.put("serviceName", "my-service");
        parentSpan.put("kind", "SPAN_KIND_SERVER");

        Map<String, Object> childSpan = new HashMap<>();
        childSpan.put("traceId", "trace2");
        childSpan.put("spanId", "span-child");
        childSpan.put("parentSpanId", "span-root");
        childSpan.put("serviceName", "my-service");
        childSpan.put("kind", "SPAN_KIND_INTERNAL");
        childSpan.put("name", "internal-op");

        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(childSpan),   // child spans search
            List.of(parentSpan)   // parent spans search
        ));
        ServiceMapBuilder builder = builder(client);

        builder.run();

        assertEquals("no bulk requests when same service", 0, client.bulkRequests.size());
    }

    // ── child whose parent is not found emits no doc ──────────────────────────

    public void testOrphanSpanEmitsNoDocs() {
        Map<String, Object> childSpan = new HashMap<>();
        childSpan.put("traceId", "trace3");
        childSpan.put("spanId", "span-child");
        childSpan.put("parentSpanId", "span-missing");
        childSpan.put("serviceName", "svc-b");
        childSpan.put("kind", "SPAN_KIND_SERVER");
        childSpan.put("name", "op");

        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            List.of(childSpan),        // child spans search
            Collections.emptyList()    // parent spans search → parent not found
        ));
        ServiceMapBuilder builder = builder(client);

        builder.run();

        assertEquals("no bulk requests when parent not found", 0, client.bulkRequests.size());
    }

    // ── child search pages through multiple searchAfter calls ─────────────────

    public void testChildSearchPagesThroughMultipleBatches() {
        // Build 10000 child spans (first page) then 1 more (second page)
        List<Map<String, Object>> firstPage = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            Map<String, Object> child = new HashMap<>();
            child.put("traceId", "trace" + i);
            child.put("spanId", "child-" + i);
            child.put("parentSpanId", "parent-" + i);
            child.put("serviceName", "svc-b");
            child.put("kind", "SPAN_KIND_SERVER");
            child.put("name", "op");
            firstPage.add(child);
        }
        Map<String, Object> lastChild = new HashMap<>();
        lastChild.put("traceId", "trace-last");
        lastChild.put("spanId", "child-last");
        lastChild.put("parentSpanId", "parent-last");
        lastChild.put("serviceName", "svc-b");
        lastChild.put("kind", "SPAN_KIND_SERVER");
        lastChild.put("name", "op");

        // Two child searches, then one parent search (empty — no edges produced)
        TraceGroupEnricherTests.StubClient client = new TraceGroupEnricherTests.StubClient(List.of(
            firstPage,                  // first page of child search (10000 hits)
            List.of(lastChild),         // second page of child search (1 hit)
            Collections.emptyList()     // parent search → empty
        ));
        ServiceMapBuilder builder = builder(client);

        builder.run();

        assertEquals("three searches: 2 child pages + 1 parent", 3, client.searchRequests.size());
        assertNotNull("second child search had searchAfter set",
            client.searchRequests.get(1).source().searchAfter());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ServiceMapBuilder builder(TraceGroupEnricherTests.StubClient client) {
        return new ServiceMapBuilder(
            client, null,
            "otel-v1-apm-span",
            "otel-v1-apm-service-map",
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES));
    }
}
