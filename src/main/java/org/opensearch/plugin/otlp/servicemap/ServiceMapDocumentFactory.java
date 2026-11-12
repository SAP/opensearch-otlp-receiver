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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Creates storage-agnostic APM service-map documents and deterministic IDs. */
final class ServiceMapDocumentFactory {

    private final long intervalMillis;

    ServiceMapDocumentFactory(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    GeneratedDocument edge(
            ServiceMapSpan clientSpan, ServiceMapSpan serverSpan, String sourceOperation) {
        NodeIdentity sourceNode = nodeFor(clientSpan);
        NodeIdentity targetNode = nodeFor(serverSpan);
        String targetOperation = serverSpan.operationName();
        String nodeHash = String.valueOf(Objects.hash(sourceNode, targetNode));
        String operationHash = String.valueOf(Objects.hash(
            sourceNode, targetNode, sourceOperation, targetOperation));
        Instant bucket = eventTimeBucket(clientSpan);
        return new GeneratedDocument(
            ServiceMapHashing.sha256Hex(String.join("\u0000", "service-map-edge-v1",
                clientSpan.traceId(), clientSpan.spanId(), serverSpan.spanId(), bucket.toString())),
            document(sourceNode, targetNode, sourceOperation, targetOperation,
                nodeHash, operationHash, DateTimeFormatter.ISO_INSTANT.format(bucket)));
    }

    GeneratedDocument leaf(ServiceMapSpan serverSpan) {
        NodeIdentity sourceNode = nodeFor(serverSpan);
        String sourceOperation = serverSpan.operationName();
        String nodeHash = String.valueOf(Objects.hash(sourceNode, null));
        String operationHash = String.valueOf(Objects.hash(
            sourceNode, null, sourceOperation, null));
        Instant bucket = eventTimeBucket(serverSpan);
        return new GeneratedDocument(
            leafId(serverSpan, bucket),
            document(sourceNode, null, sourceOperation, null, nodeHash, operationHash,
                DateTimeFormatter.ISO_INSTANT.format(bucket)));
    }

    String leafId(ServiceMapSpan serverSpan) {
        return leafId(serverSpan, eventTimeBucket(serverSpan));
    }

    private static String leafId(ServiceMapSpan serverSpan, Instant bucket) {
        return ServiceMapHashing.sha256Hex(String.join("\u0000", "service-map-leaf-v1",
            serverSpan.traceId(), serverSpan.spanId(), bucket.toString()));
    }

    private Instant eventTimeBucket(ServiceMapSpan span) {
        Instant eventTime = span.endTime() != null ? span.endTime()
            : span.indexedAt() != null ? span.indexedAt() : Instant.EPOCH;
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

    private static NodeIdentity nodeFor(ServiceMapSpan span) {
        return new NodeIdentity("service",
            new KeyAttributes(span.environment(), span.serviceName()), span.groupByAttributes());
    }

    record GeneratedDocument(String id, Map<String, Object> source) {}

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
                "environment", keyAttributes.environment()));
            node.put("groupByAttributes", groupByAttributes);
            return node;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, keyAttributes, groupByAttributes);
        }
    }
}
