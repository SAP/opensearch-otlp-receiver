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
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.opensearch.plugin.otlp.index.ServiceMapStateStore.Cursor;
import static org.opensearch.plugin.otlp.index.ServiceMapStateStore.Scope;
import static org.opensearch.plugin.otlp.index.ServiceMapStateStore.TraceTombstone;

/** Builds OpenSearch APM v2 service-map observations from recently indexed spans. */
public class ServiceMapBuilder extends AbstractLifecycleComponent {

    private static final Logger LOG = LogManager.getLogger(ServiceMapBuilder.class);
    private static final int PAGE_SIZE = 10_000;
    private static final TimeValue DISCOVERY_DELAY = TimeValue.timeValueSeconds(2);
    private static final TimeValue CURSOR_OVERLAP = TimeValue.timeValueSeconds(10);
    private static final String DEFAULT_ENVIRONMENT = "generic:default";
    private static final String CLIENT_KIND = "SPAN_KIND_CLIENT";
    private static final String SERVER_KIND = "SPAN_KIND_SERVER";
    private static final String CONSUMER_KIND = "SPAN_KIND_CONSUMER";
    private static final String ALGORITHM_VERSION = "service-map-generation-v1";

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
            Clock clock) {
        this(client, threadPool, tracesIndex, serviceMapIndex, interval, lookbackWindow,
            groupByAttributes, null, clock, new LocalStateStore());
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
            Clock clock) {
        this(client, threadPool, tracesIndex, serviceMapIndex, interval, lookbackWindow,
            groupByAttributes, traceIdPrefix, clock, new LocalStateStore());
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
            Map<String, Map<String, SpanData>> spansByTrace = loadTraces(traceIds);
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

    private Map<String, Map<String, SpanData>> loadTraces(Set<String> traceIds) throws Exception {
        Map<String, Map<String, SpanData>> spansByTrace = new LinkedHashMap<>();
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
                SpanData span = parseSpan(hit.getSourceAsMap());
                if (span != null) {
                    spansByTrace.computeIfAbsent(span.traceId, ignored -> new LinkedHashMap<>())
                        .put(span.spanId, span);
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
            Map<String, Map<String, SpanData>> spansByTrace, Set<String> previouslyProcessed) throws Exception {
        Map<String, Map<String, Object>> documentsById = new LinkedHashMap<>();
        Set<String> deletesById = new HashSet<>();

        for (Map.Entry<String, Map<String, SpanData>> trace : spansByTrace.entrySet()) {
            Map<String, SpanData> spansById = trace.getValue();
            Map<String, List<SpanData>> childrenByParent = indexChildren(spansById.values());

            for (SpanData clientSpan : spansById.values()) {
                if (!CLIENT_KIND.equals(clientSpan.kind)) {
                    continue;
                }
                for (SpanData serverSpan : childrenByParent.getOrDefault(clientSpan.spanId, List.of())) {
                    if (!SERVER_KIND.equals(serverSpan.kind)
                            || clientSpan.serviceName == null
                            || serverSpan.serviceName == null
                            || clientSpan.serviceName.equals(serverSpan.serviceName)) {
                        continue;
                    }
                    addEdgeDocument(documentsById, clientSpan, serverSpan, spansById);
                }
            }

            for (SpanData serverSpan : spansById.values()) {
                if (!SERVER_KIND.equals(serverSpan.kind) || serverSpan.serviceName == null) {
                    continue;
                }
                if (hasClientDescendant(serverSpan, childrenByParent)) {
                    if (!previouslyProcessed.contains(trace.getKey())) {
                        continue;
                    }
                    deletesById.add(leafDocumentId(serverSpan));
                } else {
                    addLeafDocument(documentsById, serverSpan);
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

    private void addEdgeDocument(
            Map<String, Map<String, Object>> documentsById,
            SpanData clientSpan,
            SpanData serverSpan,
            Map<String, SpanData> spansById) {
        NodeIdentity sourceNode = nodeFor(clientSpan);
        NodeIdentity targetNode = nodeFor(serverSpan);
        String sourceOperation = findEntryOperation(clientSpan, spansById);
        String targetOperation = serverSpan.operationName;
        String nodeHash = String.valueOf(Objects.hash(sourceNode, targetNode));
        String operationHash = String.valueOf(Objects.hash(
            sourceNode, targetNode, sourceOperation, targetOperation));
        Instant bucket = eventTimeBucket(clientSpan);
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(bucket);

        Map<String, Object> document = document(
            sourceNode, targetNode, sourceOperation, targetOperation, nodeHash, operationHash, timestamp);
        String id = edgeDocumentId(clientSpan, serverSpan, bucket);
        documentsById.put(id, document);
    }

    private void addLeafDocument(
            Map<String, Map<String, Object>> documentsById, SpanData serverSpan) {
        NodeIdentity sourceNode = nodeFor(serverSpan);
        String sourceOperation = serverSpan.operationName;
        String nodeHash = String.valueOf(Objects.hash(sourceNode, null));
        String operationHash = String.valueOf(Objects.hash(sourceNode, null, sourceOperation, null));
        Instant bucket = eventTimeBucket(serverSpan);
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(bucket);

        Map<String, Object> document = document(
            sourceNode, null, sourceOperation, null, nodeHash, operationHash, timestamp);
        String id = leafDocumentId(serverSpan, bucket);
        documentsById.put(id, document);
    }

    private String leafDocumentId(SpanData serverSpan) {
        return leafDocumentId(serverSpan, eventTimeBucket(serverSpan));
    }

    private static String edgeDocumentId(SpanData clientSpan, SpanData serverSpan, Instant bucket) {
        return sha256Hex(String.join("\u0000", "service-map-edge-v1", clientSpan.traceId,
            clientSpan.spanId, serverSpan.spanId, bucket.toString()));
    }

    private static String leafDocumentId(SpanData serverSpan, Instant bucket) {
        return sha256Hex(String.join("\u0000", "service-map-leaf-v1", serverSpan.traceId,
            serverSpan.spanId, bucket.toString()));
    }

    private Instant eventTimeBucket(SpanData span) {
        Instant eventTime = span.endTime != null ? span.endTime
            : span.indexedAt != null ? span.indexedAt : Instant.EPOCH;
        long intervalMillis = interval.millis();
        if (intervalMillis <= 0) {
            return eventTime;
        }
        return Instant.ofEpochMilli(
            Math.floorDiv(eventTime.toEpochMilli(), intervalMillis) * intervalMillis);
    }

    private static Map<String, Object> document(
            NodeIdentity sourceNode,
            NodeIdentity targetNode,
            String sourceOperation,
            String targetOperation,
            String nodeHash,
            String operationHash,
            String timestamp) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sourceNode", sourceNode.asMap());
        document.put("targetNode", targetNode != null ? targetNode.asMap() : null);
        document.put("sourceOperation", operation(sourceOperation));
        document.put("targetOperation", targetOperation != null ? operation(targetOperation) : null);
        document.put("nodeConnectionHash", nodeHash);
        document.put("operationConnectionHash", operationHash);
        document.put("timestamp", timestamp);
        return document;
    }

    private static Map<String, Object> operation(String name) {
        return Map.of("name", name != null ? name : "", "attributes", Map.of());
    }

    private NodeIdentity nodeFor(SpanData span) {
        return new NodeIdentity("service",
            new KeyAttributes(span.environment, span.serviceName), span.groupByAttributes);
    }

    private static Map<String, List<SpanData>> indexChildren(Collection<SpanData> spans) {
        Map<String, List<SpanData>> childrenByParent = new HashMap<>();
        for (SpanData span : spans) {
            if (span.parentSpanId != null) {
                childrenByParent.computeIfAbsent(span.parentSpanId, ignored -> new ArrayList<>()).add(span);
            }
        }
        return childrenByParent;
    }

    private static boolean hasClientDescendant(
            SpanData serverSpan, Map<String, List<SpanData>> childrenByParent) {
        Set<String> visited = new HashSet<>();
        Deque<SpanData> pending = new ArrayDeque<>(
            childrenByParent.getOrDefault(serverSpan.spanId, Collections.emptyList()));
        while (!pending.isEmpty()) {
            SpanData child = pending.removeFirst();
            if (!visited.add(child.spanId) || !serverSpan.serviceName.equals(child.serviceName)) {
                continue;
            }
            if (CLIENT_KIND.equals(child.kind)) {
                return true;
            }
            pending.addAll(childrenByParent.getOrDefault(child.spanId, Collections.emptyList()));
        }
        return false;
    }

    private static String findEntryOperation(SpanData span, Map<String, SpanData> spansById) {
        SpanData current = span;
        Set<String> visited = new HashSet<>();
        while (current.parentSpanId != null && visited.add(current.spanId)) {
            SpanData parent = spansById.get(current.parentSpanId);
            if (parent == null || !Objects.equals(span.serviceName, parent.serviceName)) {
                break;
            }
            if (SERVER_KIND.equals(parent.kind) || CONSUMER_KIND.equals(parent.kind)) {
                return parent.operationName;
            }
            current = parent;
        }
        return span.operationName;
    }

    private SpanData parseSpan(Map<String, Object> source) {
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

        return new SpanData(
            traceId,
            spanId,
            stringValue(source.get("parentSpanId")),
            stringValue(source.get("serviceName")),
            stringValue(source.get("kind")),
            stringValue(source.get("name")),
            parseInstant(source.get("endTime")),
            parseInstant(source.get("indexedAt")),
            deriveEnvironment(spanAttributes, resourceAttributes),
            groups);
    }

    private Collection<TraceTombstone> tombstones(
            Map<String, Map<String, SpanData>> spansByTrace, Instant now) {
        List<TraceTombstone> tombstones = new ArrayList<>(spansByTrace.size());
        for (Map.Entry<String, Map<String, SpanData>> trace : spansByTrace.entrySet()) {
            Instant lastIndexedAt = trace.getValue().values().stream()
                .map(SpanData::indexedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(now);
            boolean rootSeen = trace.getValue().values().stream()
                .anyMatch(span -> span.parentSpanId == null);
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

    private static String deriveEnvironment(
            Map<String, Object> spanAttributes, Map<String, Object> resourceAttributes) {
        String cloudPlatform = firstString("cloud.platform", spanAttributes, resourceAttributes);
        if (cloudPlatform != null) {
            switch (cloudPlatform.toLowerCase(Locale.ROOT)) {
                case "aws_api_gateway":
                    return "api-gateway:" + valueOrDefault(
                        firstString("aws.api_gateway.stage", spanAttributes, resourceAttributes), "default");
                case "aws_ec2":
                    return "ec2:default";
                case "aws_ecs":
                    String launchType = firstString("aws.ecs.launchtype", spanAttributes, resourceAttributes);
                    if ("fargate".equalsIgnoreCase(launchType)) {
                        return "ecs-fargate:default";
                    }
                    if ("ec2".equalsIgnoreCase(launchType)) {
                        return "ecs-ec2:default";
                    }
                    return "ecs:default";
                case "aws_eks":
                    return "eks:default";
                case "aws_elastic_beanstalk":
                    return "elastic-beanstalk:default";
                case "aws_lambda":
                    return "lambda:default";
                default:
                    break;
            }
        }

        String resourceId = firstString("cloud.resource_id", spanAttributes, resourceAttributes);
        String invokedArn = firstString("aws.lambda.invoked_arn", spanAttributes, resourceAttributes);
        String cloudProvider = firstString("cloud.provider", spanAttributes, resourceAttributes);
        String functionName = firstString("faas.name", spanAttributes, resourceAttributes);
        if ((resourceId != null && resourceId.startsWith("arn:aws:lambda:"))
                || invokedArn != null
                || ("aws".equalsIgnoreCase(cloudProvider) && functionName != null)) {
            return "lambda:default";
        }

        String environment = stringValue(resourceAttributes.get("deployment.environment.name"));
        if (environment == null) {
            environment = stringValue(resourceAttributes.get("deployment.environment"));
        }
        return valueOrDefault(environment, DEFAULT_ENVIRONMENT);
    }

    @SafeVarargs
    private static String firstString(String key, Map<String, Object>... maps) {
        for (Map<String, Object> map : maps) {
            String value = stringValue(map.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
        return sha256Hex(canonical.toString());
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

    private record SpanData(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String kind,
        String operationName,
        Instant endTime,
        Instant indexedAt,
        String environment,
        Map<String, Object> groupByAttributes) {}

    private static final class LocalStateStore implements ServiceMapStateStore {
        private final Map<Scope, Cursor> cursors = new HashMap<>();
        private final Set<String> processedTraceIds = new HashSet<>();

        @Override
        public Cursor loadCursor(Scope scope) {
            return cursors.get(scope);
        }

        @Override
        public Set<String> loadProcessedTraceIds(Scope scope, Collection<String> traceIds) {
            Set<String> result = new HashSet<>();
            for (String traceId : traceIds) {
                if (processedTraceIds.contains(processedTraceId(scope, traceId))) {
                    result.add(traceId);
                }
            }
            return result;
        }

        @Override
        public void commit(Scope scope, Cursor cursor, Collection<TraceTombstone> tombstones) {
            cursors.put(scope, cursor);
            tombstones.stream()
                .map(TraceTombstone::traceId)
                .map(traceId -> processedTraceId(scope, traceId))
                .forEach(processedTraceIds::add);
        }

        @Override
        public void deleteExpired(String prefix, Instant now) {}

        private static String processedTraceId(Scope scope, String traceId) {
            return scope.namespace() + "\u0000" + traceId;
        }
    }

    private record KeyAttributes(String environment, String name) {
        @Override
        public int hashCode() {
            return Objects.hash(environment, name);
        }
    }

    private record NodeIdentity(
            String type, KeyAttributes keyAttributes, Map<String, Object> groupByAttributes) {
        private NodeIdentity {
            groupByAttributes = Map.copyOf(groupByAttributes);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", type);
            node.put("keyAttributes", Map.of(
                "name", keyAttributes.name,
                "environment", keyAttributes.environment));
            node.put("groupByAttributes", groupByAttributes);
            return node;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, keyAttributes, groupByAttributes);
        }
    }
}
