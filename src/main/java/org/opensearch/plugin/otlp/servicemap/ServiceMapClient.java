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

import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.cluster.health.ClusterHealthResponse;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.get.MultiGetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.plugin.otlp.common.Cancellable;
import org.opensearch.transport.client.Client;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Future-based, cancellation-aware access to OpenSearch operations used by service-map generation. */
final class ServiceMapClient {

    private final Client client;
    private final BooleanSupplier cancelled;

    ServiceMapClient(Client client) {
        this(client, () -> false);
    }

    ServiceMapClient(Client client, BooleanSupplier cancelled) {
        this.client = client;
        this.cancelled = cancelled;
    }

    CompletableFuture<SearchResponse> search(SearchRequest request) {
        return execute(listener -> client.search(request, listener));
    }

    CompletableFuture<BulkResponse> bulk(BulkRequest request) {
        return execute(listener -> client.bulk(request, listener));
    }

    CompletableFuture<GetResponse> get(GetRequest request) {
        return execute(listener -> client.get(request, listener));
    }

    CompletableFuture<MultiGetResponse> multiGet(MultiGetRequest request) {
        return execute(listener -> client.multiGet(request, listener));
    }

    CompletableFuture<IndexResponse> index(IndexRequest request) {
        return execute(listener -> client.index(request, listener));
    }

    CompletableFuture<CreateIndexResponse> createIndex(CreateIndexRequest request) {
        return execute(listener -> client.admin().indices().create(request, listener));
    }

    CompletableFuture<ClusterHealthResponse> health(ClusterHealthRequest request) {
        return execute(listener -> client.admin().cluster().health(request, listener));
    }

    private <T> CompletableFuture<T> execute(Consumer<ActionListener<T>> operation) {
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new Cancellable.CancelledException());
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            operation.accept(ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
