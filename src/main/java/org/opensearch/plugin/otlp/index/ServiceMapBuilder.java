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
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ServiceMapBuilder extends AbstractLifecycleComponent {

    private static final Logger LOG = LogManager.getLogger(ServiceMapBuilder.class);

    private final Client client;
    private final ThreadPool threadPool;
    private final String tracesIndex;
    private final String serviceMapIndex;
    private final TimeValue interval;
    private final TimeValue lookbackWindow;

    private Scheduler.Cancellable scheduledTask;
    private final Cancellable cancellable = new Cancellable();
    // Tracks the start of the previous tick so each run only processes newly-arrived spans.
    // Initialised to now - lookbackWindow on first run so the first tick catches all recent spans.
    private volatile Instant lastRunTime = null;

    public ServiceMapBuilder(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow) {
        this.client = client;
        this.threadPool = threadPool;
        this.tracesIndex = tracesIndex;
        this.serviceMapIndex = serviceMapIndex;
        this.interval = interval;
        this.lookbackWindow = lookbackWindow;
    }

    @Override
    protected void doStart() {
        cancellable.reset();
        scheduledTask = threadPool.scheduleWithFixedDelay(
            this::run, interval, ThreadPool.Names.GENERIC);
        LOG.info("ServiceMapBuilder started (interval={})", interval);
    }

    @Override
    protected void doStop() {
        cancellable.cancel();
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
        LOG.info("ServiceMapBuilder stopped");
    }

    @Override
    protected void doClose() {}

    public void run() {
        if (cancellable.isCancelled()) return;
        try {
            Instant tickStart = Instant.now();
            // On the first tick scan the full lookback window; afterwards only the new interval.
            Instant since = lastRunTime != null ? lastRunTime : tickStart.minusSeconds(lookbackWindow.seconds());
            LOG.info("ServiceMapBuilder: starting tick (tracesIndex={}, since={})", tracesIndex, since);
            buildServiceMap(since);
            lastRunTime = tickStart;
        } catch (Exception e) {
            if (cancellable.isCancelled()) {
                LOG.debug("ServiceMapBuilder tick interrupted by shutdown", e);
            } else {
                LOG.warn("ServiceMapBuilder tick failed", e);
            }
        }
    }

    private void buildServiceMap(Instant since) throws Exception {
        Map<String, SpanData> childById = new LinkedHashMap<>();
        List<String> parentSpanIds = new ArrayList<>();

        // Page through all child spans since the last tick.
        Object[] childSearchAfter = null;
        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(10000)
                .fetchSource(new String[]{"traceId", "spanId", "parentSpanId", "serviceName", "kind", "name", "traceGroup"}, null)
                .query(QueryBuilders.boolQuery()
                    .filter(QueryBuilders.existsQuery("parentSpanId"))
                    .filter(QueryBuilders.rangeQuery("startTime")
                        .gte(since.toEpochMilli())))
                .sort("spanId", SortOrder.ASC);

            if (childSearchAfter != null) {
                source.searchAfter(childSearchAfter);
            }

            SearchResponse childResponse = search(
                new SearchRequest(tracesIndex + "*").source(source)).get();

            SearchHit[] childHits = childResponse.getHits().getHits();
            LOG.trace("ServiceMapBuilder: child span page on [{}*] returned {} hits", tracesIndex, childHits.length);
            if (childHits.length == 0) {
                break;
            }

            for (SearchHit hit : childHits) {
                Map<String, Object> src = hit.getSourceAsMap();
                String spanId = (String) src.get("spanId");
                String parentSpanId = (String) src.get("parentSpanId");
                String traceId = (String) src.get("traceId");
                if (spanId == null || parentSpanId == null || traceId == null) {
                    LOG.debug("ServiceMapBuilder: skipping hit — missing spanId={} parentSpanId={} traceId={}",
                        spanId, parentSpanId, traceId);
                    continue;
                }
                childById.put(spanId, new SpanData(
                    traceId, spanId, parentSpanId,
                    (String) src.get("serviceName"),
                    (String) src.get("kind"),
                    (String) src.get("name"),
                    (String) src.get("traceGroup")
                ));
                parentSpanIds.add(parentSpanId);
            }

            childSearchAfter = childHits[childHits.length - 1].getSortValues();

            if (childHits.length < 10000) {
                break;
            }
        } while (true);

        LOG.trace("ServiceMapBuilder: {} valid child spans, looking up {} parent spanIds", childById.size(), parentSpanIds.size());
        if (parentSpanIds.isEmpty()) {
            return;
        }

        // Page through all parent spans matching the collected parent IDs.
        Map<String, SpanData> parentById = new LinkedHashMap<>();
        Object[] parentSearchAfter = null;
        do {
            SearchSourceBuilder parentSource = new SearchSourceBuilder()
                .size(10000)
                .fetchSource(new String[]{"spanId", "serviceName", "kind", "traceGroup"}, null)
                .query(QueryBuilders.termsQuery("spanId", parentSpanIds))
                .sort("spanId", SortOrder.ASC);

            if (parentSearchAfter != null) {
                parentSource.searchAfter(parentSearchAfter);
            }

            SearchResponse parentResponse = search(
                new SearchRequest(tracesIndex + "*").source(parentSource)).get();

            SearchHit[] parentHits = parentResponse.getHits().getHits();
            LOG.trace("ServiceMapBuilder: parent span page returned {} hits", parentHits.length);
            if (parentHits.length == 0) {
                break;
            }

            for (SearchHit hit : parentHits) {
                Map<String, Object> src = hit.getSourceAsMap();
                String spanId = (String) src.get("spanId");
                if (spanId == null) continue;
                parentById.put(spanId, new SpanData(
                    null, spanId, null,
                    (String) src.get("serviceName"),
                    (String) src.get("kind"),
                    null,
                    (String) src.get("traceGroup")
                ));
            }

            parentSearchAfter = parentHits[parentHits.length - 1].getSortValues();

            if (parentHits.length < 10000) {
                break;
            }
        } while (true);

        LOG.trace("ServiceMapBuilder: matched {}/{} parent spanIds", parentById.size(), parentSpanIds.size());

        BulkRequest bulk = new BulkRequest();
        for (SpanData child : childById.values()) {
            SpanData parent = parentById.get(child.parentSpanId);
            if (parent == null) {
                LOG.debug("ServiceMapBuilder: no parent found for child spanId={} parentSpanId={} serviceName={}",
                    child.spanId, child.parentSpanId, child.serviceName);
                continue;
            }
            if (parent.serviceName == null || child.serviceName == null) {
                LOG.debug("ServiceMapBuilder: skipping edge — missing serviceName: parent={} child={}",
                    parent.serviceName, child.serviceName);
                continue;
            }
            if (parent.serviceName.equals(child.serviceName)) {
                LOG.trace("ServiceMapBuilder: skipping same-service edge: {}", child.serviceName);
                continue;
            }

            LOG.trace("ServiceMapBuilder: cross-service edge {} -> {} (op={})",
                parent.serviceName, child.serviceName, child.operationName);

            String traceGroupName = parent.traceGroupName != null ? parent.traceGroupName
                : child.traceGroupName != null ? child.traceGroupName : "";

            addServiceMapDoc(bulk,
                parent.serviceName, parent.kind,
                child.serviceName, child.operationName,
                null, null,
                traceGroupName);

            addServiceMapDoc(bulk,
                child.serviceName, child.kind,
                null, null,
                child.serviceName, child.operationName,
                traceGroupName);
        }

        if (bulk.numberOfActions() > 0) {
            bulkIndex(bulk).get();
            LOG.trace("ServiceMapBuilder: indexed {} service map relationships", () -> bulk.numberOfActions());
        }
    }

    private void addServiceMapDoc(BulkRequest bulk,
            String serviceName, String kind,
            String destDomain, String destResource,
            String targetDomain, String targetResource,
            String traceGroupName) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("serviceName", serviceName);
        doc.put("kind", kind);
        if (destDomain != null) {
            doc.put("destination", Map.of("domain", destDomain, "resource", destResource != null ? destResource : ""));
        }
        if (targetDomain != null) {
            doc.put("target", Map.of("domain", targetDomain, "resource", targetResource != null ? targetResource : ""));
        }
        doc.put("traceGroupName", traceGroupName);

        String hashId = md5Hex(serviceName + kind
            + (destDomain != null ? destDomain : "") + (destResource != null ? destResource : "")
            + (targetDomain != null ? targetDomain : "") + (targetResource != null ? targetResource : ""));
        doc.put("hashId", hashId);

        bulk.add(new IndexRequest(serviceMapIndex).id(hashId).source(doc, XContentType.JSON));
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private CompletableFuture<SearchResponse> search(SearchRequest request) {
        if (cancellable.isCancelled()) {
            return CompletableFuture.failedFuture(new Cancellable.CancelledException());
        }
        CompletableFuture<SearchResponse> future = new CompletableFuture<>();
        client.search(request, new ActionListener<SearchResponse>() {
            @Override public void onResponse(SearchResponse r) { future.complete(r); }
            @Override public void onFailure(Exception e) { future.completeExceptionally(e); }
        });
        return future;
    }

    private CompletableFuture<BulkResponse> bulkIndex(BulkRequest request) {
        if (cancellable.isCancelled()) {
            return CompletableFuture.failedFuture(new Cancellable.CancelledException());
        }
        CompletableFuture<BulkResponse> future = new CompletableFuture<>();
        client.bulk(request, new ActionListener<BulkResponse>() {
            @Override public void onResponse(BulkResponse r) { future.complete(r); }
            @Override public void onFailure(Exception e) { future.completeExceptionally(e); }
        });
        return future;
    }

    private record SpanData(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String kind,
        String operationName,
        String traceGroupName) {}
}
