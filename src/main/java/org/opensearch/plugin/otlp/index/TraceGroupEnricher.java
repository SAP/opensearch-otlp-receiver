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
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.update.UpdateRequest;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TraceGroupEnricher extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(TraceGroupEnricher.class);

    private final Client client;
    private final ThreadPool threadPool;
    private final String tracesIndex;
    private final TimeValue interval;
    private final TimeValue lookbackWindow;
    private final TimeValue traceCutoff;
    private final int batchSize;

    private Scheduler.Cancellable scheduledTask;
    private final Cancellable cancellable = new Cancellable();

    public TraceGroupEnricher(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            TimeValue traceCutoff,
            int batchSize) {
        this.client = client;
        this.threadPool = threadPool;
        this.tracesIndex = tracesIndex;
        this.interval = interval;
        this.lookbackWindow = lookbackWindow;
        this.traceCutoff = traceCutoff;
        this.batchSize = batchSize;
    }

    @Override
    protected void doStart() {
        cancellable.reset();
        scheduledTask = threadPool.scheduleWithFixedDelay(
            this::runEnrichment, interval, ThreadPool.Names.GENERIC);
        logger.info("TraceGroupEnricher started (interval={})", interval);
    }

    @Override
    protected void doStop() {
        cancellable.cancel();
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
        logger.info("TraceGroupEnricher stopped");
    }

    @Override
    protected void doClose() {}

    public void runEnrichment() {
        if (cancellable.isCancelled()) return;
        try {
            Map<String, TraceGroupData> rootSpans = findRootSpans();
            if (!rootSpans.isEmpty()) {
                propagate(rootSpans);
            }
            markAbandoned();
        } catch (Exception e) {
            if (cancellable.isCancelled()) {
                logger.debug("TraceGroupEnricher tick interrupted by shutdown", e);
            } else {
                logger.error("TraceGroupEnricher tick failed", e);
            }
        }
    }

    // Finds root spans (no parentSpanId) within the lookback window.
    private Map<String, TraceGroupData> findRootSpans() throws Exception {
        Map<String, TraceGroupData> result = new HashMap<>();
        Object[] searchAfter = null;

        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(10000)
                .fetchSource(new String[]{"traceId", "name", "endTime", "durationInNanos", "status"}, null)
                .query(QueryBuilders.boolQuery()
                    .mustNot(QueryBuilders.existsQuery("parentSpanId"))
                    .filter(QueryBuilders.rangeQuery("startTime")
                        .gte("now-" + lookbackWindow.seconds() + "s")))
                .sort("startTime", SortOrder.ASC);

            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }

            SearchResponse response = search(
                new SearchRequest(tracesIndex + "*").source(source)).get();

            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                break;
            }

            for (SearchHit hit : hits) {
                Map<String, Object> src = hit.getSourceAsMap();
                String traceId = (String) src.get("traceId");
                if (traceId == null) continue;

                String name = (String) src.get("name");
                String endTime = (String) src.get("endTime");
                Number durationInNanos = (Number) src.get("durationInNanos");
                Map<?, ?> status = (Map<?, ?>) src.get("status");
                Integer statusCode = status != null ? (Integer) status.get("code") : null;

                Map<String, Object> fields = new HashMap<>();
                if (endTime != null) fields.put("endTime", endTime);
                if (durationInNanos != null) fields.put("durationInNanos", durationInNanos.longValue());
                if (statusCode != null) fields.put("statusCode", statusCode);

                result.put(traceId, new TraceGroupData(name != null ? name : "", fields));
            }

            searchAfter = hits[hits.length - 1].getSortValues();

            if (hits.length < 10000) {
                break;
            }
        } while (true);

        logger.debug("TraceGroupEnricher: found {} root spans in lookback window", result.size());
        return result;
    }

    // Finds sibling spans without traceGroup and bulk-updates them.
    private void propagate(Map<String, TraceGroupData> rootSpans) throws Exception {
        List<String> traceIds = new ArrayList<>(rootSpans.keySet());

        for (int offset = 0; offset < traceIds.size(); offset += batchSize) {
            List<String> batch = traceIds.subList(offset, Math.min(offset + batchSize, traceIds.size()));

            BulkRequest traceGroupBulk = new BulkRequest();
            Object[] searchAfter = null;

            do {
                SearchSourceBuilder source = new SearchSourceBuilder()
                    .size(10000)
                    .fetchSource(new String[]{"traceId"}, null)
                    .query(QueryBuilders.boolQuery()
                        .mustNot(QueryBuilders.existsQuery("traceGroup"))
                        .filter(QueryBuilders.termsQuery("traceId", batch))
                        .filter(QueryBuilders.rangeQuery("startTime")
                            .gte("now-" + lookbackWindow.seconds() + "s")))
                    .sort("_id", SortOrder.ASC);

                if (searchAfter != null) {
                    source.searchAfter(searchAfter);
                }

                SearchResponse response = search(
                    new SearchRequest(tracesIndex + "*").source(source)).get();

                SearchHit[] hits = response.getHits().getHits();
                if (hits.length == 0) {
                    break;
                }

                for (SearchHit hit : hits) {
                    String traceId = (String) hit.getSourceAsMap().get("traceId");
                    TraceGroupData tgd = traceId != null ? rootSpans.get(traceId) : null;
                    if (tgd == null) continue;

                    Map<String, Object> doc = new HashMap<>();
                    doc.put("traceGroup", tgd.name);
                    doc.put("traceGroupFields", tgd.fields);
                    traceGroupBulk.add(new UpdateRequest(hit.getIndex(), hit.getId()).doc(doc, XContentType.JSON));
                }

                searchAfter = hits[hits.length - 1].getSortValues();

                if (hits.length < 10000) {
                    break;
                }
            } while (true);

            if (traceGroupBulk.numberOfActions() > 0) {
                bulkUpdate(traceGroupBulk).get();
                logger.debug("TraceGroupEnricher: propagated trace group for {} spans", traceGroupBulk.numberOfActions());
            }
        }
    }

    // Marks spans older than traceCutoff that still have no root span with a sentinel value.
    private void markAbandoned() throws Exception {
        BulkRequest bulk = new BulkRequest();
        Object[] searchAfter = null;

        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(10000)
                .fetchSource(false)
                .query(QueryBuilders.boolQuery()
                    .mustNot(QueryBuilders.existsQuery("traceGroup"))
                    .filter(QueryBuilders.rangeQuery("startTime")
                        .lt("now-" + traceCutoff.seconds() + "s")))
                .sort("_id", SortOrder.ASC);

            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }

            SearchResponse response = search(
                new SearchRequest(tracesIndex + "*").source(source)).get();

            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                break;
            }

            for (SearchHit hit : hits) {
                bulk.add(new UpdateRequest(hit.getIndex(), hit.getId())
                    .doc(Map.of("traceGroup", ""), XContentType.JSON));
            }

            searchAfter = hits[hits.length - 1].getSortValues();

            if (hits.length < 10000) {
                break;
            }
        } while (true);

        if (bulk.numberOfActions() > 0) {
            bulkUpdate(bulk).get();
            logger.debug("TraceGroupEnricher: marked {} abandoned spans with sentinel", bulk.numberOfActions());
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

    private CompletableFuture<BulkResponse> bulkUpdate(BulkRequest request) {
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

    private record TraceGroupData(String name, Map<String, Object> fields) {}
}
