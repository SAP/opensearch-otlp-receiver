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

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.support.AbstractClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;

@SuppressWarnings("unchecked")

public class TraceGroupEnricherTests extends OpenSearchTestCase {

    // ── empty result — only sentinel search issued, no updates ───────────────

    public void testRunEnrichmentNoRootSpans() {
        // Searches: root spans (empty) → sentinel (empty)
        StubClient client = new StubClient(List.of(
            Collections.emptyList(),  // root span search → empty
            Collections.emptyList()   // sentinel search → empty
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        assertEquals("two searches: root spans + sentinel", 2, client.searchRequests.size());
        assertEquals("no bulk updates when nothing to do", 0, client.bulkRequests.size());
    }

    // ── root span fields are parsed and propagated ────────────────────────────

    public void testRunEnrichmentPropagatesRootSpanFields() {
        Map<String, Object> rootSrc = new HashMap<>();
        rootSrc.put("traceId", "aabbcc");
        rootSrc.put("name", "/checkout");
        rootSrc.put("endTime", "2024-01-01T00:00:01Z");
        rootSrc.put("durationInNanos", 1_000_000L);
        rootSrc.put("status", Map.of("code", 0));

        Map<String, Object> childSrc = new HashMap<>();
        childSrc.put("traceId", "aabbcc");

        StubClient client = new StubClient(List.of(
            List.of(rootSrc),           // root span search
            List.of(childSrc),          // propagation sibling search
            Collections.emptyList()     // sentinel search
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        assertEquals("one bulk update for propagation", 1, client.bulkRequests.size());
        BulkRequest bulk = client.bulkRequests.get(0);
        assertEquals("one UpdateRequest for the child span", 1, bulk.numberOfActions());

        UpdateRequest update = (UpdateRequest) bulk.requests().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) update.doc().sourceAsMap();
        assertEquals("/checkout", doc.get("traceGroup"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) doc.get("traceGroupFields");
        assertNotNull("traceGroupFields present", fields);
        assertEquals("2024-01-01T00:00:01Z", fields.get("endTime"));
        assertEquals(1_000_000L, ((Number) fields.get("durationInNanos")).longValue());
        assertEquals(0, ((Number) fields.get("statusCode")).intValue());
    }

    // ── sentinel bulk update issued for abandoned spans ───────────────────────

    public void testRunEnrichmentMarksSentinelForAbandonedSpans() {
        Map<String, Object> abandoned = new HashMap<>();
        abandoned.put("traceId", "deadbeef");

        StubClient client = new StubClient(List.of(
            Collections.emptyList(),  // root span search → empty (no root spans)
            List.of(abandoned)        // sentinel search → one old span
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        assertEquals("one bulk for sentinel", 1, client.bulkRequests.size());
        UpdateRequest update = (UpdateRequest) client.bulkRequests.get(0).requests().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) update.doc().sourceAsMap();
        assertEquals("sentinel value is empty string", "", doc.get("traceGroup"));
    }

    // ── batching: two propagate bulk requests when root spans exceed batchSize ─

    public void testRunEnrichmentBatchesPropagation() {
        List<Map<String, Object>> roots = new ArrayList<>();
        List<Map<String, Object>> siblings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> root = new HashMap<>();
            root.put("traceId", "trace" + i);
            root.put("name", "op" + i);
            roots.add(root);

            Map<String, Object> sibling = new HashMap<>();
            sibling.put("traceId", "trace" + i);
            siblings.add(sibling);
        }

        // batchSize=2: two propagation searches, each returning siblings
        StubClient client = new StubClient(List.of(
            roots,                                     // root span search → 3 roots
            List.of(siblings.get(0), siblings.get(1)), // first batch siblings
            List.of(siblings.get(2)),                  // second batch siblings
            Collections.emptyList()                    // sentinel search → empty
        ));
        TraceGroupEnricher enricher = enricher(client, 2);

        enricher.runEnrichment();

        assertEquals("two bulk requests for two propagation batches", 2, client.bulkRequests.size());
    }

    // ── hits with no traceId are skipped in propagation ──────────────────────

    public void testRunEnrichmentSkipsHitsWithNullTraceId() {
        Map<String, Object> rootSrc = new HashMap<>();
        rootSrc.put("traceId", "aabbcc");
        rootSrc.put("name", "op");

        Map<String, Object> noTraceId = new HashMap<>();
        noTraceId.put("name", "something");

        StubClient client = new StubClient(List.of(
            List.of(rootSrc),           // root span search
            List.of(noTraceId),         // propagation search returns a hit with no traceId
            Collections.emptyList()     // sentinel search
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        assertEquals("no bulk updates when sibling has no traceId", 0, client.bulkRequests.size());
    }

    // ── propagate pages through multiple searchAfter calls ────────────────────

    public void testPropagatePagesThroughMultipleBatches() {
        Map<String, Object> rootSrc = new HashMap<>();
        rootSrc.put("traceId", "trace1");
        rootSrc.put("name", "op");

        // Build exactly 10000 child hits (triggers pagination) then 1 more
        List<Map<String, Object>> firstPage = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            Map<String, Object> child = new HashMap<>();
            child.put("traceId", "trace1");
            firstPage.add(child);
        }
        Map<String, Object> lastChild = new HashMap<>();
        lastChild.put("traceId", "trace1");

        StubClient client = new StubClient(List.of(
            List.of(rootSrc),          // root span search
            firstPage,                  // first page of propagation search (10000 hits)
            List.of(lastChild),         // second page of propagation search (1 hit)
            Collections.emptyList()     // sentinel search
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        // Both pages produced UpdateRequests — two bulk requests (one per page boundary)
        assertEquals("four searches total: root + 2 propagation pages + sentinel", 4, client.searchRequests.size());
        assertNotNull("second search had searchAfter set",
            client.searchRequests.get(2).source().searchAfter());
        assertTrue("updates produced for all child spans",
            client.bulkRequests.stream().mapToInt(b -> b.numberOfActions()).sum() > 0);
    }

    // ── markAbandoned pages through multiple searchAfter calls ────────────────

    public void testMarkAbandonedPagesThroughMultipleBatches() {
        List<Map<String, Object>> firstPage = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            firstPage.add(new HashMap<>());
        }
        Map<String, Object> last = new HashMap<>();

        StubClient client = new StubClient(List.of(
            Collections.emptyList(),   // root span search → empty
            firstPage,                  // first page of sentinel search (10000 hits)
            List.of(last)               // second page of sentinel search (1 hit)
        ));
        TraceGroupEnricher enricher = enricher(client);

        enricher.runEnrichment();

        assertEquals("three searches: root + 2 sentinel pages", 3, client.searchRequests.size());
        assertNotNull("second sentinel search had searchAfter set",
            client.searchRequests.get(2).source().searchAfter());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static TraceGroupEnricher enricher(StubClient client) {
        return enricher(client, 1000);
    }

    private static TraceGroupEnricher enricher(StubClient client, int batchSize) {
        return new TraceGroupEnricher(
            client, null,
            "otel-v1-apm-span",
            new TimeValue(60, TimeUnit.SECONDS),
            new TimeValue(5, TimeUnit.MINUTES),
            new TimeValue(10, TimeUnit.MINUTES),
            batchSize);
    }

    // ── minimal Client stub ───────────────────────────────────────────────────

    static class StubClient extends AbstractClient {

        final List<SearchRequest> searchRequests = new ArrayList<>();
        final List<BulkRequest> bulkRequests = new ArrayList<>();

        private final List<List<Map<String, Object>>> searchResponses;
        private int searchIndex = 0;

        StubClient(List<List<Map<String, Object>>> searchResponses) {
            super(org.opensearch.common.settings.Settings.EMPTY, null);
            this.searchResponses = searchResponses;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <Request extends ActionRequest, Response extends ActionResponse>
        void doExecute(ActionType<Response> action, Request request, ActionListener<Response> listener) {
            if (request instanceof SearchRequest sr) {
                searchRequests.add(sr);
                List<Map<String, Object>> sources = searchIndex < searchResponses.size()
                    ? searchResponses.get(searchIndex++) : Collections.emptyList();
                try {
                    listener.onResponse((Response) buildSearchResponse(sources));
                } catch (IOException e) {
                    listener.onFailure(e);
                }
            } else if (request instanceof BulkRequest br) {
                bulkRequests.add(br);
                listener.onResponse((Response) new BulkResponse(new BulkItemResponse[0], 0));
            } else {
                listener.onFailure(new UnsupportedOperationException("Unexpected: " + request.getClass()));
            }
        }

        @Override
        public void close() {}

        static SearchResponse buildSearchResponse(List<Map<String, Object>> sources) throws IOException {
            SearchHit[] hits = new SearchHit[sources.size()];
            for (int i = 0; i < sources.size(); i++) {
                var builder = jsonBuilder();
                builder.map(sources.get(i));
                SearchHit hit = new SearchHit(i, "id" + i, null, null);
                hit.sourceRef(new BytesArray(builder.toString()));
                // Set sort values so searchAfter can be used by callers
                hit.sortValues(new Object[]{"id" + i}, new DocValueFormat[]{DocValueFormat.RAW});
                hits[i] = hit;
            }
            SearchHits searchHits = new SearchHits(hits, null, 1.0f);
            InternalSearchResponse internal = new InternalSearchResponse(
                searchHits, null, null, null, false, false, 1);
            return new SearchResponse(internal, null, 1, 1, 0, 0,
                ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
        }
    }
}
