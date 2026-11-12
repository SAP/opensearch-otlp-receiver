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

import org.opensearch.ExceptionsHelper;
import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.cluster.health.ClusterHealthResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.get.MultiGetItemResponse;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.get.MultiGetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.transport.client.Client;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Stores cursors and expiring trace tombstones in a hidden OpenSearch index. */
final class OpenSearchServiceMapStateStore implements ServiceMapStateStore {

    static final String INDEX = ".opensearch-otlp-service-map-state";
    private static final int CLEANUP_PAGE_SIZE = 1_000;

    private final ServiceMapClient client;
    private volatile boolean initialized;

    OpenSearchServiceMapStateStore(Client client) {
        this(new ServiceMapClient(client));
    }

    OpenSearchServiceMapStateStore(ServiceMapClient client) {
        this.client = client;
    }

    @Override
    public Cursor loadCursor(Scope scope) throws Exception {
        ensureIndex();
        GetResponse response = client.get(new GetRequest(INDEX, cursorId(scope))).get();
        if (!response.isExists()) {
            return null;
        }
        Map<String, Object> source = response.getSourceAsMap();
        String indexedAt = stringValue(source.get("indexedAt"));
        String documentId = stringValue(source.get("documentId"));
        return indexedAt == null || documentId == null
            ? null : new Cursor(Instant.parse(indexedAt), documentId);
    }

    @Override
    public Set<String> loadProcessedTraceIds(Scope scope, Collection<String> traceIds) throws Exception {
        ensureIndex();
        if (traceIds.isEmpty()) {
            return Set.of();
        }
        MultiGetRequest request = new MultiGetRequest();
        traceIds.forEach(id -> request.add(INDEX, traceId(scope, id)));
        MultiGetResponse response = client.multiGet(request).get();
        Set<String> processed = new HashSet<>();
        for (MultiGetItemResponse item : response) {
            if (item.isFailed()) {
                throw new IllegalStateException("Failed to load service-map tombstone",
                    item.getFailure().getFailure());
            }
            if (item.getResponse().isExists()) {
                String id = stringValue(item.getResponse().getSourceAsMap().get("traceId"));
                if (id != null) {
                    processed.add(id);
                }
            }
        }
        return processed;
    }

    @Override
    public void commit(Scope scope, Cursor cursor, Collection<TraceTombstone> tombstones) throws Exception {
        ensureIndex();
        BulkRequest bulk = new BulkRequest();
        for (TraceTombstone tombstone : tombstones) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "trace");
            source.put("prefix", scope.prefix());
            source.put("traceId", tombstone.traceId());
            source.put("lastProcessedIndexedAt", tombstone.lastProcessedIndexedAt().toString());
            source.put("generation", tombstone.generation());
            source.put("rootSeen", tombstone.rootSeen());
            source.put("expiresAt", tombstone.expiresAt().toString());
            bulk.add(new IndexRequest(INDEX).id(traceId(scope, tombstone.traceId()))
                .source(source, XContentType.JSON));
        }
        if (bulk.numberOfActions() > 0) {
            BulkResponse response = client.bulk(bulk).get();
            if (response.hasFailures()) {
                throw new IllegalStateException("Failed to save service-map tombstones: "
                    + response.buildFailureMessage());
            }
        }
        IndexRequest cursorRequest = new IndexRequest(INDEX).id(cursorId(scope)).source(Map.of(
            "type", "cursor",
            "prefix", scope.prefix(),
            "indexedAt", cursor.indexedAt().toString(),
            "documentId", cursor.documentId()), XContentType.JSON);
        client.index(cursorRequest).get();
    }

    @Override
    public void deleteExpired(String prefix, Instant now) throws Exception {
        ensureIndex();
        Object[] searchAfter = null;
        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(CLEANUP_PAGE_SIZE)
                .fetchSource(false)
                .query(QueryBuilders.boolQuery()
                    .filter(QueryBuilders.termQuery("type", "trace"))
                    .filter(QueryBuilders.termQuery("prefix", prefix))
                    .filter(QueryBuilders.rangeQuery("expiresAt").lte(now.toString())))
                .sort("_id", SortOrder.ASC);
            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }
            SearchHit[] hits = client.search(new SearchRequest(INDEX).source(source))
                .get().getHits().getHits();
            if (hits.length == 0) {
                return;
            }
            BulkRequest bulk = new BulkRequest();
            for (SearchHit hit : hits) {
                bulk.add(new DeleteRequest(INDEX, hit.getId()));
            }
            BulkResponse bulkResponse = client.bulk(bulk).get();
            if (bulkResponse.hasFailures()) {
                throw new IllegalStateException("Failed to delete expired service-map state: "
                    + bulkResponse.buildFailureMessage());
            }
            if (hits.length < CLEANUP_PAGE_SIZE) {
                return;
            }
            searchAfter = hits[hits.length - 1].getSortValues();
        } while (true);
    }

    private synchronized void ensureIndex() throws Exception {
        if (initialized) {
            return;
        }
        CreateIndexRequest request = new CreateIndexRequest(INDEX)
            .settings(Settings.builder()
                .put("index.hidden", true)
                .put("index.number_of_shards", 1)
                .put("index.auto_expand_replicas", "0-1"))
            .mapping(Map.of(
                "dynamic", "strict",
                "properties", Map.of(
                    "type", Map.of("type", "keyword"),
                    "prefix", Map.of("type", "keyword"),
                    "traceId", Map.of("type", "keyword"),
                    "indexedAt", Map.of("type", "date_nanos"),
                    "documentId", Map.of("type", "keyword"),
                    "lastProcessedIndexedAt", Map.of("type", "date_nanos"),
                    "generation", Map.of("type", "long"),
                    "rootSeen", Map.of("type", "boolean"),
                    "expiresAt", Map.of("type", "date_nanos"))));
        try {
            client.createIndex(request).get();
        } catch (ExecutionException e) {
            if (!(ExceptionsHelper.unwrapCause(e.getCause()) instanceof ResourceAlreadyExistsException)) {
                throw e;
            }
        }
        ClusterHealthResponse health = client.health(new ClusterHealthRequest(INDEX)
            .waitForYellowStatus()
            .waitForActiveShards(1)).get();
        if (health.isTimedOut()) {
            throw new IllegalStateException("Timed out waiting for service-map state index");
        }
        initialized = true;
    }

    private static String cursorId(Scope scope) {
        return "cursor:" + scope.namespace() + ":" + scope.prefix();
    }

    private static String traceId(Scope scope, String traceId) {
        return "trace:" + scope.namespace() + ":" + traceId;
    }

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

}
