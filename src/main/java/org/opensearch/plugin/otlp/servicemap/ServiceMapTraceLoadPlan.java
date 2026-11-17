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

import org.opensearch.action.fieldcaps.FieldCapabilities;
import org.opensearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects doc values or filtered source for fields needed while loading traces. */
final class ServiceMapTraceLoadPlan {

    static final List<String> SCALAR_FIELDS = List.of(
        "traceId", "spanId", "parentSpanId", "serviceName",
        "kind", "name", "endTime", "indexedAt");
    static final Set<String> SPAN_ATTRIBUTE_KEYS = Set.of(
        "cloud.platform", "aws.api_gateway.stage", "aws.ecs.launchtype",
        "cloud.resource_id", "aws.lambda.invoked_arn", "cloud.provider", "faas.name");
    static final Set<String> RESOURCE_ATTRIBUTE_KEYS = Set.of(
        "cloud.platform", "aws.api_gateway.stage", "aws.ecs.launchtype",
        "cloud.resource_id", "aws.lambda.invoked_arn", "cloud.provider", "faas.name",
        "deployment.environment.name", "deployment.environment");

    private static final Set<String> DATE_FIELDS = Set.of("endTime", "indexedAt");
    private static final String DATE_FORMAT = "strict_date_optional_time_nanos";

    private final List<String> docValueFields;
    private final List<String> sourceFields;
    private final List<String> fallbackFields;

    private ServiceMapTraceLoadPlan(
            Collection<String> docValueFields,
            Collection<String> sourceFields,
            Collection<String> fallbackFields) {
        this.docValueFields = List.copyOf(docValueFields);
        this.sourceFields = List.copyOf(sourceFields);
        this.fallbackFields = List.copyOf(fallbackFields);
    }

    static ServiceMapTraceLoadPlan resolve(
            FieldCapabilitiesResponse capabilities, List<String> groupByAttributes) {
        LinkedHashSet<String> docValues = new LinkedHashSet<>();
        LinkedHashSet<String> source = new LinkedHashSet<>();
        LinkedHashSet<String> fallback = new LinkedHashSet<>();

        for (String field : SCALAR_FIELDS) {
            if (access(capabilities, field) == FieldAccess.DOC_VALUES) {
                docValues.add(field);
            } else {
                source.add(field);
                fallback.add(field);
            }
        }

        List<String> spanAttributeFields = prefixed("attributes.", SPAN_ATTRIBUTE_KEYS);
        if (spanAttributeFields.stream().anyMatch(
                field -> access(capabilities, field) == FieldAccess.SOURCE)) {
            source.add("attributes");
            fallback.addAll(spanAttributeFields.stream()
                .filter(field -> access(capabilities, field) == FieldAccess.SOURCE).toList());
        } else {
            spanAttributeFields.stream()
                .filter(field -> access(capabilities, field) != FieldAccess.IGNORED)
                .forEach(docValues::add);
        }

        LinkedHashSet<String> resourceKeys = new LinkedHashSet<>(RESOURCE_ATTRIBUTE_KEYS);
        resourceKeys.addAll(groupByAttributes);
        List<String> resourceAttributeFields = prefixed("resource.attributes.", resourceKeys);
        boolean resourceSourceRequired = resourceAttributeFields.stream().anyMatch(field -> {
            FieldAccess fieldAccess = access(capabilities, field);
            String key = field.substring("resource.attributes.".length());
            return fieldAccess == FieldAccess.SOURCE
                || (groupByAttributes.contains(key)
                    && (fieldAccess == FieldAccess.UNMAPPED
                        || fieldAccess == FieldAccess.PARTIALLY_UNMAPPED));
        });
        if (resourceSourceRequired) {
            source.add("resource.attributes");
            resourceAttributeFields.stream().filter(field -> {
                FieldAccess fieldAccess = access(capabilities, field);
                String key = field.substring("resource.attributes.".length());
                return fieldAccess == FieldAccess.SOURCE
                    || (groupByAttributes.contains(key)
                        && (fieldAccess == FieldAccess.UNMAPPED
                            || fieldAccess == FieldAccess.PARTIALLY_UNMAPPED));
            }).forEach(fallback::add);
        } else {
            resourceAttributeFields.stream()
                .filter(field -> access(capabilities, field) != FieldAccess.IGNORED)
                .forEach(docValues::add);
        }

        return new ServiceMapTraceLoadPlan(docValues, source, fallback);
    }

    static ServiceMapTraceLoadPlan sourceFallback() {
        List<String> source = new ArrayList<>(SCALAR_FIELDS);
        source.add("attributes");
        source.add("resource.attributes");
        return new ServiceMapTraceLoadPlan(List.of(), source, source);
    }

    static String[] requestedFields(List<String> groupByAttributes) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(SCALAR_FIELDS);
        fields.addAll(prefixed("attributes.", SPAN_ATTRIBUTE_KEYS));
        LinkedHashSet<String> resourceKeys = new LinkedHashSet<>(RESOURCE_ATTRIBUTE_KEYS);
        resourceKeys.addAll(groupByAttributes);
        fields.addAll(prefixed("resource.attributes.", resourceKeys));
        return fields.toArray(String[]::new);
    }

    void apply(SearchSourceBuilder source) {
        if (sourceFields.isEmpty()) {
            source.fetchSource(false);
        } else {
            source.fetchSource(sourceFields.toArray(String[]::new), null);
        }
        for (String field : docValueFields) {
            if (DATE_FIELDS.contains(field)) {
                source.docValueField(field, DATE_FORMAT);
            } else {
                source.docValueField(field);
            }
        }
    }

    List<String> docValueFields() {
        return docValueFields;
    }

    List<String> sourceFields() {
        return sourceFields;
    }

    List<String> fallbackFields() {
        return fallbackFields;
    }

    private static List<String> prefixed(String prefix, Collection<String> fields) {
        return fields.stream().map(field -> prefix + field).sorted().toList();
    }

    private static FieldAccess access(FieldCapabilitiesResponse response, String field) {
        Map<String, FieldCapabilities> capabilities = response.getField(field);
        if (capabilities == null || capabilities.isEmpty()) {
            return FieldAccess.UNMAPPED;
        }
        boolean docValueMapped = false;
        boolean unmapped = false;
        boolean ignored = false;
        for (FieldCapabilities capability : capabilities.values()) {
            if ("unmapped".equals(capability.getType())) {
                unmapped = true;
                continue;
            }
            if (!capability.isAggregatable()) {
                if ("object".equals(capability.getType())
                        || "nested".equals(capability.getType())
                        || "flat_object".equals(capability.getType())) {
                    ignored = true;
                    continue;
                }
                return FieldAccess.SOURCE;
            }
            docValueMapped = true;
        }
        if (docValueMapped) {
            return unmapped ? FieldAccess.PARTIALLY_UNMAPPED : FieldAccess.DOC_VALUES;
        }
        return ignored ? FieldAccess.IGNORED : FieldAccess.UNMAPPED;
    }

    private enum FieldAccess {
        DOC_VALUES,
        SOURCE,
        UNMAPPED,
        PARTIALLY_UNMAPPED,
        IGNORED
    }
}
