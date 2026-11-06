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
import java.time.Clock;
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

/** Builds OpenSearch APM v2 service-map observations from recently indexed spans. */
public class ServiceMapBuilder extends AbstractLifecycleComponent {

    private static final Logger LOG = LogManager.getLogger(ServiceMapBuilder.class);
    private static final int PAGE_SIZE = 10_000;
    private static final String DEFAULT_ENVIRONMENT = "generic:default";
    private static final String CLIENT_KIND = "SPAN_KIND_CLIENT";
    private static final String SERVER_KIND = "SPAN_KIND_SERVER";
    private static final String CONSUMER_KIND = "SPAN_KIND_CONSUMER";

    private static final String[] SOURCE_FIELDS = {
        "traceId", "spanId", "parentSpanId", "serviceName", "kind", "name",
        "startTime", "attributes", "resource.attributes"
    };

    private final Client client;
    private final ThreadPool threadPool;
    private final String tracesIndex;
    private final String serviceMapIndex;
    private final TimeValue interval;
    private final TimeValue lookbackWindow;
    private final List<String> groupByAttributes;
    private final Clock clock;

    private Scheduler.Cancellable scheduledTask;
    private final Cancellable cancellable = new Cancellable();

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
            groupByAttributes, Clock.systemUTC());
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
        this.client = client;
        this.threadPool = threadPool;
        this.tracesIndex = tracesIndex;
        this.serviceMapIndex = serviceMapIndex;
        this.interval = interval;
        this.lookbackWindow = lookbackWindow;
        this.groupByAttributes = List.copyOf(groupByAttributes);
        this.clock = clock;
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
        if (cancellable.isCancelled()) {
            return;
        }
        try {
            Instant observationTime = clock.instant();
            LOG.debug("ServiceMapBuilder: scanning [{}*] over lookback {}", tracesIndex, lookbackWindow);
            buildServiceMap(loadRecentSpans(), observationTime);
        } catch (Exception e) {
            if (cancellable.isCancelled()) {
                LOG.debug("ServiceMapBuilder tick interrupted by shutdown", e);
            } else {
                LOG.warn("ServiceMapBuilder tick failed", e);
            }
        }
    }

    private Map<String, Map<String, SpanData>> loadRecentSpans() throws Exception {
        Map<String, Map<String, SpanData>> spansByTrace = new LinkedHashMap<>();
        Object[] searchAfter = null;

        do {
            SearchSourceBuilder source = new SearchSourceBuilder()
                .size(PAGE_SIZE)
                .fetchSource(SOURCE_FIELDS, null)
                .query(QueryBuilders.rangeQuery("startTime")
                    .gte("now-" + lookbackWindow.seconds() + "s"))
                .sort("startTime", SortOrder.ASC)
                .sort("_id", SortOrder.ASC);
            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }

            SearchResponse response = search(new SearchRequest(tracesIndex + "*").source(source)).get();
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                break;
            }

            for (SearchHit hit : hits) {
                SpanData span = parseSpan(hit.getSourceAsMap());
                if (span == null) {
                    continue;
                }
                spansByTrace.computeIfAbsent(span.traceId, ignored -> new LinkedHashMap<>())
                    .put(span.spanId, span);
            }

            searchAfter = hits[hits.length - 1].getSortValues();
            if (hits.length < PAGE_SIZE) {
                break;
            }
        } while (true);

        return spansByTrace;
    }

    private void buildServiceMap(Map<String, Map<String, SpanData>> spansByTrace, Instant observationTime)
            throws Exception {
        Map<String, Map<String, Object>> documentsById = new LinkedHashMap<>();

        for (Map<String, SpanData> spansById : spansByTrace.values()) {
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
                    addEdgeDocument(documentsById, clientSpan, serverSpan, spansById, observationTime);
                }
            }

            for (SpanData serverSpan : spansById.values()) {
                if (SERVER_KIND.equals(serverSpan.kind)
                        && serverSpan.serviceName != null
                        && !hasClientDescendant(serverSpan, childrenByParent)) {
                    addLeafDocument(documentsById, serverSpan, observationTime);
                }
            }
        }

        if (documentsById.isEmpty()) {
            return;
        }

        BulkRequest bulk = new BulkRequest();
        for (Map.Entry<String, Map<String, Object>> entry : documentsById.entrySet()) {
            bulk.add(new IndexRequest(serviceMapIndex).id(entry.getKey())
                .source(entry.getValue(), XContentType.JSON));
        }
        BulkResponse response = bulkIndex(bulk).get();
        if (response.hasFailures()) {
            throw new IllegalStateException("Failed to index service-map observations: "
                + response.buildFailureMessage());
        }
        LOG.debug("ServiceMapBuilder: indexed {} v2 observations", bulk.numberOfActions());
    }

    private void addEdgeDocument(
            Map<String, Map<String, Object>> documentsById,
            SpanData clientSpan,
            SpanData serverSpan,
            Map<String, SpanData> spansById,
            Instant observationTime) {
        NodeIdentity sourceNode = nodeFor(clientSpan);
        NodeIdentity targetNode = nodeFor(serverSpan);
        String sourceOperation = findEntryOperation(clientSpan, spansById);
        String targetOperation = serverSpan.operationName;
        String nodeHash = String.valueOf(Objects.hash(sourceNode, targetNode));
        String operationHash = String.valueOf(Objects.hash(
            sourceNode, targetNode, sourceOperation, targetOperation));
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(observationTime);

        Map<String, Object> document = document(
            sourceNode, targetNode, sourceOperation, targetOperation, nodeHash, operationHash, timestamp);
        String id = sha256Hex(String.join("\u0000", nodeHash, operationHash, timestamp,
            clientSpan.traceId, clientSpan.spanId, serverSpan.spanId));
        documentsById.put(id, document);
    }

    private void addLeafDocument(
            Map<String, Map<String, Object>> documentsById, SpanData serverSpan, Instant observationTime) {
        NodeIdentity sourceNode = nodeFor(serverSpan);
        String sourceOperation = serverSpan.operationName;
        String nodeHash = String.valueOf(Objects.hash(sourceNode, null));
        String operationHash = String.valueOf(Objects.hash(sourceNode, null, sourceOperation, null));
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(observationTime);

        Map<String, Object> document = document(
            sourceNode, null, sourceOperation, null, nodeHash, operationHash, timestamp);
        String id = sha256Hex(String.join("\u0000", nodeHash, operationHash, timestamp,
            serverSpan.traceId, serverSpan.spanId));
        documentsById.put(id, document);
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
            deriveEnvironment(spanAttributes, resourceAttributes),
            groups);
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
        String environment,
        Map<String, Object> groupByAttributes) {}

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
