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
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.transport.client.Client;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/** Stores service-map cursors in a hidden OpenSearch index. */
final class OpenSearchServiceMapStateStore implements ServiceMapStateStore {

    static final String INDEX = ".opensearch-otlp-service-map-state";

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
    public void commit(Scope scope, Cursor cursor) throws Exception {
        ensureIndex();
        IndexRequest cursorRequest = new IndexRequest(INDEX).id(cursorId(scope)).source(Map.of(
            "type", "cursor",
            "prefix", scope.prefix(),
            "indexedAt", cursor.indexedAt().toString(),
            "documentId", cursor.documentId()), XContentType.JSON);
        client.index(cursorRequest).get();
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
                    "indexedAt", Map.of("type", "date_nanos"),
                    "documentId", Map.of("type", "keyword"))));
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

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

}
