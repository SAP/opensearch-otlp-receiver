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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.plugin.otlp.document.IdentifiedDocument;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.transport.client.Client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public class BulkIndexer {

    private static final Logger LOG = LogManager.getLogger(BulkIndexer.class);

    private final Client client;
    private final Semaphore inflightSemaphore;
    private final boolean requireAlias;
    private final BulkIndexerMetrics metrics = new BulkIndexerMetrics();

    public BulkIndexer(
            Client client,
            int maxInflight,
            boolean requireAlias,
            MetricsRegistry metricsRegistry) {
        this.client = client;
        this.inflightSemaphore = new Semaphore(maxInflight);
        this.requireAlias = requireAlias;
        metrics.initialize(metricsRegistry);
    }

    public void setMetricsRegistry(MetricsRegistry metricsRegistry) {
        metrics.initialize(metricsRegistry);
    }

    public CompletableFuture<BulkResponse> index(String index, List<? extends ToXContent> docs) {
        if (docs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!inflightSemaphore.tryAcquire()) {
            metrics.trackRejected();
            return CompletableFuture.failedFuture(
                new OpenSearchRejectedExecutionException("OTLP max_inflight_requests limit reached"));
        }
        metrics.trackInflightUp();

        BulkRequest bulkRequest = new BulkRequest();
        for (ToXContent doc : docs) {
            try {
                var builder = XContentFactory.jsonBuilder();
                doc.toXContent(builder, ToXContent.EMPTY_PARAMS);
                IndexRequest req = new IndexRequest(index).source(builder);
                if (doc instanceof IdentifiedDocument) {
                    req.id(((IdentifiedDocument) doc).documentId());
                }
                if (requireAlias) req.setRequireAlias(true);
                bulkRequest.add(req);
            } catch (java.io.IOException e) {
                inflightSemaphore.release();
                metrics.trackInflightDown();
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<BulkResponse> future = new CompletableFuture<>();
        client.bulk(bulkRequest, new ActionListener<BulkResponse>() {
            @Override
            public void onResponse(BulkResponse response) {
                inflightSemaphore.release();
                metrics.trackInflightDown();
                if (response.hasFailures()) {
                    LOG.warn("Bulk index completed with failures: {}", response.buildFailureMessage());
                }
                future.complete(response);
            }
            @Override
            public void onFailure(Exception e) {
                inflightSemaphore.release();
                metrics.trackInflightDown();
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
