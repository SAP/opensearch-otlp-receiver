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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.plugin.otlp.common.Cancellable;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.opensearch.plugin.otlp.servicemap.ServiceMapStateStore.Cursor;
import static org.opensearch.plugin.otlp.servicemap.ServiceMapStateStore.Scope;
import static org.opensearch.plugin.otlp.servicemap.ServiceMapStateStore.TraceTombstone;

/** Builds OpenSearch APM v2 service-map observations from recently indexed spans. */
public class ServiceMapBuilder extends AbstractLifecycleComponent {

    private static final Logger LOG = LogManager.getLogger(ServiceMapBuilder.class);
    private static final int PAGE_SIZE = 10_000;
    private static final TimeValue DISCOVERY_DELAY = TimeValue.timeValueSeconds(2);
    private static final TimeValue CURSOR_OVERLAP = TimeValue.timeValueSeconds(10);
    private static final String CLIENT_KIND = "SPAN_KIND_CLIENT";
    private static final String SERVER_KIND = "SPAN_KIND_SERVER";
    private static final String CONSUMER_KIND = "SPAN_KIND_CONSUMER";
    private static final String ALGORITHM_VERSION = "service-map-generation-v1";
    private static final ServiceEnvironmentResolver ENVIRONMENT_RESOLVER =
        new ServiceEnvironmentResolver();

    private static final String[] SOURCE_FIELDS = {
        "traceId", "spanId", "parentSpanId", "serviceName", "kind", "name",
        "startTime", "endTime", "indexedAt", "attributes", "resource.attributes"
    };

    private final Client client;
    private final ThreadPool threadPool;
    private final String tracesIndex;
    private final String serviceMapIndex;
    private final TimeValue interval;
    private final TimeValue lookbackWindow;
    private final List<String> groupByAttributes;
    private final String traceIdPrefix;
    private final Clock clock;
    private final ServiceMapStateStore stateStore;
    private final ServiceMapDocumentFactory documentFactory;
    private final String stateNamespace;

    private Scheduler.Cancellable scheduledTask;
    private final Cancellable cancellable = new Cancellable();
    private final AtomicBoolean runInProgress = new AtomicBoolean();

    public ServiceMapBuilder(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow) {
        this(client, threadPool, tracesIndex, serviceMapIndex, interval, lookbackWindow, List.of());
    }

    public ServiceMapBuilder(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes) {
        this(client, threadPool, tracesIndex, serviceMapIndex, interval, lookbackWindow,
            groupByAttributes, null, Clock.systemUTC(), new OpenSearchServiceMapStateStore(client));
    }

    public ServiceMapBuilder(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes,
            String traceIdPrefix) {
        this(client, threadPool, tracesIndex, serviceMapIndex, interval, lookbackWindow,
            groupByAttributes, traceIdPrefix, Clock.systemUTC(), new OpenSearchServiceMapStateStore(client));
    }

    ServiceMapBuilder(
            Client client,
            ThreadPool threadPool,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes,
            String traceIdPrefix,
            Clock clock,
            ServiceMapStateStore stateStore) {
        this.client = client;
        this.threadPool = threadPool;
        this.tracesIndex = tracesIndex;
        this.serviceMapIndex = serviceMapIndex;
        this.interval = interval;
        this.lookbackWindow = lookbackWindow;
        this.groupByAttributes = List.copyOf(groupByAttributes);
        this.traceIdPrefix = traceIdPrefix;
        this.clock = clock;
        this.stateStore = stateStore;
        this.documentFactory = new ServiceMapDocumentFactory(interval.millis());
        this.stateNamespace = stateNamespace(
            tracesIndex, serviceMapIndex, interval, lookbackWindow, groupByAttributes);
    }

    @Override
    protected void doStart() {
        cancellable.reset();
        scheduledTask = threadPool.scheduleWithFixedDelay(this::run, interval, ThreadPool.Names.GENERIC);
        LOG.info("ServiceMapBuilder started (interval={}, lookback={})", interval, lookbackWindow);
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
        if (cancellable.isCancelled() || !runInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            LOG.debug("ServiceMapBuilder: discovering new spans in [{}*] for trace prefix [{}]",
                tracesIndex, traceIdPrefix);
            processIncremental(now);
        } catch (Exception e) {
            if (cancellable.isCancelled()) {
                LOG.debug("ServiceMapBuilder tick interrupted by shutdown", e);
            } else {
                LOG.warn("ServiceMapBuilder tick failed", e);
            }
        } finally {
            runInProgress.set(false);
        }
    }

    private void processIncremental(Instant now) throws Exception {
        String prefix = traceIdPrefix == null ? "all" : traceIdPrefix;
        Scope scope = new Scope(stateNamespace, prefix);
        Cursor cursor = stateStore.loadCursor(scope);
        Instant lowerBound = cursor == null
            ? now.minusMillis(lookbackWindow.millis())
            : cursor.indexedAt().minusMillis(Math.min(CURSOR_OVERLAP.millis(), lookbackWindow.millis()));
        Instant upperBound = now.minusMillis(DISCOVERY_DELAY.millis());
        Object[] searchAfter = null;

        do {
            QueryBuilder query = QueryBuilders.rangeQuery("indexedAt")
                .gte(lowerBound.toString())
                .lte(upperBound.toString());
            if (traceIdPrefix != null) {
                query = QueryBuilders.boolQuery()
                    .filter(query)
                    .filter(QueryBuilders.prefixQuery("traceId", traceIdPrefix));
            }

            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(PAGE_SIZE)
                .fetchSource(new String[]{"traceId", "indexedAt"}, null)
                .query(query)
                .sort("indexedAt", SortOrder.ASC)
                .sort("_id", SortOrder.ASC);
            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }

            SearchResponse response = search(new SearchRequest(tracesIndex + "*").source(source)).get();
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                break;
            }

            Set<String> traceIds = new HashSet<>();
            for (SearchHit hit : hits) {
                String traceId = stringValue(hit.getSourceAsMap().get("traceId"));
                if (traceId != null) {
                    traceIds.add(traceId);
                }
            }
            Map<String, Map<String, ServiceMapSpan>> spansByTrace = loadTraces(traceIds);
            Set<String> previouslyProcessed = stateStore.loadProcessedTraceIds(scope, traceIds);
            reconcile(spansByTrace, previouslyProcessed);

            SearchHit last = hits[hits.length - 1];
            Instant lastIndexedAt = parseInstant(last.getSourceAsMap().get("indexedAt"));
            if (lastIndexedAt == null) {
                throw new IllegalStateException("Discovered span has no indexedAt value");
            }
            cursor = maxCursor(cursor, new Cursor(lastIndexedAt, last.getId()));
            stateStore.commit(scope, cursor, tombstones(spansByTrace, now));

            searchAfter = hits[hits.length - 1].getSortValues();
            if (hits.length < PAGE_SIZE) {
                break;
            }
        } while (true);
        stateStore.deleteExpired(prefix, now);
    }

    private static Cursor maxCursor(Cursor current, Cursor candidate) {
        if (current == null) {
            return candidate;
        }
        int timeComparison = candidate.indexedAt().compareTo(current.indexedAt());
        if (timeComparison > 0
                || (timeComparison == 0 && candidate.documentId().compareTo(current.documentId()) > 0)) {
            return candidate;
        }
        return current;
    }

    private Map<String, Map<String, ServiceMapSpan>> loadTraces(Set<String> traceIds) throws Exception {
        Map<String, Map<String, ServiceMapSpan>> spansByTrace = new LinkedHashMap<>();
        if (traceIds.isEmpty()) {
            return spansByTrace;
        }
        Object[] searchAfter = null;
        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(PAGE_SIZE)
                .fetchSource(SOURCE_FIELDS, null)
                .query(QueryBuilders.termsQuery("traceId", traceIds))
                .sort("_index", SortOrder.ASC)
                .sort("_id", SortOrder.ASC);
            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }
            SearchHit[] hits = search(new SearchRequest(tracesIndex + "*").source(source))
                .get().getHits().getHits();
            for (SearchHit hit : hits) {
                ServiceMapSpan span = parseSpan(hit.getSourceAsMap());
                if (span != null) {
                    spansByTrace.computeIfAbsent(span.traceId(), ignored -> new LinkedHashMap<>())
                        .put(span.spanId(), span);
                }
            }
            if (hits.length < PAGE_SIZE) {
                break;
            }
            searchAfter = hits[hits.length - 1].getSortValues();
        } while (true);
        return spansByTrace;
    }

    private void reconcile(
            Map<String, Map<String, ServiceMapSpan>> spansByTrace, Set<String> previouslyProcessed) throws Exception {
        Map<String, Map<String, Object>> documentsById = new LinkedHashMap<>();
        Set<String> deletesById = new HashSet<>();

        for (Map.Entry<String, Map<String, ServiceMapSpan>> trace : spansByTrace.entrySet()) {
            Map<String, ServiceMapSpan> spansById = trace.getValue();
            Map<String, List<ServiceMapSpan>> childrenByParent = indexChildren(spansById.values());

            for (ServiceMapSpan clientSpan : spansById.values()) {
                if (!CLIENT_KIND.equals(clientSpan.kind())) {
                    continue;
                }
                for (ServiceMapSpan serverSpan : childrenByParent.getOrDefault(clientSpan.spanId(), List.of())) {
                    if (!SERVER_KIND.equals(serverSpan.kind())
                            || clientSpan.serviceName() == null
                            || serverSpan.serviceName() == null
                            || clientSpan.serviceName().equals(serverSpan.serviceName())) {
                        continue;
                    }
                    ServiceMapDocumentFactory.GeneratedDocument document = documentFactory.edge(
                        clientSpan, serverSpan, findEntryOperation(clientSpan, spansById));
                    documentsById.put(document.id(), document.source());
                }
            }

            for (ServiceMapSpan serverSpan : spansById.values()) {
                if (!SERVER_KIND.equals(serverSpan.kind()) || serverSpan.serviceName() == null) {
                    continue;
                }
                if (hasClientDescendant(serverSpan, childrenByParent)) {
                    if (!previouslyProcessed.contains(trace.getKey())) {
                        continue;
                    }
                    deletesById.add(documentFactory.leafId(serverSpan));
                } else {
                    ServiceMapDocumentFactory.GeneratedDocument document = documentFactory.leaf(serverSpan);
                    documentsById.put(document.id(), document.source());
                }
            }
        }

        if (documentsById.isEmpty() && deletesById.isEmpty()) {
            return;
        }

        BulkRequest bulk = new BulkRequest();
        for (Map.Entry<String, Map<String, Object>> entry : documentsById.entrySet()) {
            bulk.add(new IndexRequest(serviceMapIndex).id(entry.getKey())
                .source(entry.getValue(), XContentType.JSON));
        }
        for (String id : deletesById) {
            bulk.add(new DeleteRequest(serviceMapIndex, id));
        }
        BulkResponse response = bulkIndex(bulk).get();
        if (response.hasFailures()) {
            throw new IllegalStateException("Failed to index service-map observations: "
                + response.buildFailureMessage());
        }
        LOG.debug("ServiceMapBuilder: reconciled {} v2 observations", bulk.numberOfActions());
    }

    private static Map<String, List<ServiceMapSpan>> indexChildren(Collection<ServiceMapSpan> spans) {
        Map<String, List<ServiceMapSpan>> childrenByParent = new HashMap<>();
        for (ServiceMapSpan span : spans) {
            if (span.parentSpanId() != null) {
                childrenByParent.computeIfAbsent(span.parentSpanId(), ignored -> new ArrayList<>()).add(span);
            }
        }
        return childrenByParent;
    }

    private static boolean hasClientDescendant(
            ServiceMapSpan serverSpan, Map<String, List<ServiceMapSpan>> childrenByParent) {
        Set<String> visited = new HashSet<>();
        Deque<ServiceMapSpan> pending = new ArrayDeque<>(
            childrenByParent.getOrDefault(serverSpan.spanId(), Collections.emptyList()));
        while (!pending.isEmpty()) {
            ServiceMapSpan child = pending.removeFirst();
            if (!visited.add(child.spanId()) || !serverSpan.serviceName().equals(child.serviceName())) {
                continue;
            }
            if (CLIENT_KIND.equals(child.kind())) {
                return true;
            }
            pending.addAll(childrenByParent.getOrDefault(child.spanId(), Collections.emptyList()));
        }
        return false;
    }

    private static String findEntryOperation(ServiceMapSpan span, Map<String, ServiceMapSpan> spansById) {
        ServiceMapSpan current = span;
        Set<String> visited = new HashSet<>();
        while (current.parentSpanId() != null && visited.add(current.spanId())) {
            ServiceMapSpan parent = spansById.get(current.parentSpanId());
            if (parent == null || !Objects.equals(span.serviceName(), parent.serviceName())) {
                break;
            }
            if (SERVER_KIND.equals(parent.kind()) || CONSUMER_KIND.equals(parent.kind())) {
                return parent.operationName();
            }
            current = parent;
        }
        return span.operationName();
    }

    private ServiceMapSpan parseSpan(Map<String, Object> source) {
        String traceId = stringValue(source.get("traceId"));
        String spanId = stringValue(source.get("spanId"));
        if (traceId == null || spanId == null) {
            LOG.debug("Skipping span without traceId/spanId");
            return null;
        }

        Map<String, Object> spanAttributes = objectMap(source.get("attributes"));
        Map<String, Object> resource = objectMap(source.get("resource"));
        Map<String, Object> resourceAttributes = objectMap(resource.get("attributes"));
        Map<String, Object> groups = new LinkedHashMap<>();
        for (String attribute : groupByAttributes) {
            Object value = resourceAttributes.get(attribute);
            if (isGroupValue(value)) {
                groups.put(attribute, value);
            }
        }

        return new ServiceMapSpan(
            traceId,
            spanId,
            stringValue(source.get("parentSpanId")),
            stringValue(source.get("serviceName")),
            stringValue(source.get("kind")),
            stringValue(source.get("name")),
            parseInstant(source.get("endTime")),
            parseInstant(source.get("indexedAt")),
            ENVIRONMENT_RESOLVER.resolve(spanAttributes, resourceAttributes),
            groups);
    }

    private Collection<TraceTombstone> tombstones(
            Map<String, Map<String, ServiceMapSpan>> spansByTrace, Instant now) {
        List<TraceTombstone> tombstones = new ArrayList<>(spansByTrace.size());
        for (Map.Entry<String, Map<String, ServiceMapSpan>> trace : spansByTrace.entrySet()) {
            Instant lastIndexedAt = trace.getValue().values().stream()
                .map(span -> span.indexedAt())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(now);
            boolean rootSeen = trace.getValue().values().stream()
                .anyMatch(span -> span.parentSpanId() == null);
            tombstones.add(new TraceTombstone(
                trace.getKey(),
                lastIndexedAt,
                now.toEpochMilli(),
                rootSeen,
                lastIndexedAt.plusMillis(lookbackWindow.millis())));
        }
        return tombstones;
    }

    private static boolean isGroupValue(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Instant parseInstant(Object value) {
        if (value instanceof String string) {
            try {
                return Instant.parse(string);
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        return null;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, entryValue);
            }
        });
        return result;
    }

    static String stateNamespace(
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes) {
        List<String> components = new ArrayList<>();
        components.add(ALGORITHM_VERSION);
        components.add(tracesIndex);
        components.add(serviceMapIndex);
        components.add(Long.toString(interval.millis()));
        components.add(Long.toString(lookbackWindow.millis()));
        groupByAttributes.stream().distinct().sorted().forEach(components::add);

        StringBuilder canonical = new StringBuilder();
        for (String component : components) {
            canonical.append(component.length()).append(':').append(component);
        }
        return ServiceMapHashing.sha256Hex(canonical.toString());
    }

    private CompletableFuture<SearchResponse> search(SearchRequest request) {
        if (cancellable.isCancelled()) {
            return CompletableFuture.failedFuture(new Cancellable.CancelledException());
        }
        CompletableFuture<SearchResponse> future = new CompletableFuture<>();
        client.search(request, new ActionListener<>() {
            @Override
            public void onResponse(SearchResponse response) {
                future.complete(response);
            }

            @Override
            public void onFailure(Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private CompletableFuture<BulkResponse> bulkIndex(BulkRequest request) {
        if (cancellable.isCancelled()) {
            return CompletableFuture.failedFuture(new Cancellable.CancelledException());
        }
        CompletableFuture<BulkResponse> future = new CompletableFuture<>();
        client.bulk(request, new ActionListener<>() {
            @Override
            public void onResponse(BulkResponse response) {
                future.complete(response);
            }

            @Override
            public void onFailure(Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

}
