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
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceMapTraceLoadPlanTests extends OpenSearchTestCase {

    public void testUnmappedBuiltInAttributesDoNotRequireSource() {
        Map<String, Map<String, FieldCapabilities>> fields = new HashMap<>();
        for (String field : ServiceMapTraceLoadPlan.SCALAR_FIELDS) {
            fields.put(field, capability(field, "keyword", true));
        }

        ServiceMapTraceLoadPlan plan = ServiceMapTraceLoadPlan.resolve(response(fields), List.of());

        assertTrue(plan.sourceFields().isEmpty());
        assertTrue(plan.docValueFields().containsAll(ServiceMapTraceLoadPlan.SCALAR_FIELDS));
        assertTrue(plan.docValueFields().contains("attributes.cloud.platform"));
    }

    public void testUnmappedConfiguredGroupByAttributeRequiresSource() {
        Map<String, Map<String, FieldCapabilities>> fields = mappedFields(List.of("service.version"));
        fields.remove("resource.attributes.service.version");

        ServiceMapTraceLoadPlan plan = ServiceMapTraceLoadPlan.resolve(
            response(fields), List.of("service.version"));

        assertEquals(List.of("resource.attributes"), plan.sourceFields());
        assertEquals(List.of("resource.attributes.service.version"), plan.fallbackFields());
    }

    public void testPartiallyUnmappedConfiguredGroupByAttributeRequiresSource() {
        Map<String, Map<String, FieldCapabilities>> fields = mappedFields(List.of("service.version"));
        fields.put("resource.attributes.service.version", Map.of(
            "keyword", fieldCapability("resource.attributes.service.version", "keyword", true),
            "unmapped", fieldCapability("resource.attributes.service.version", "unmapped", false)));

        ServiceMapTraceLoadPlan plan = ServiceMapTraceLoadPlan.resolve(
            response(fields), List.of("service.version"));

        assertEquals(List.of("resource.attributes"), plan.sourceFields());
    }

    public void testObjectAlternativeIsNotRequestedOrLoadedFromSource() {
        Map<String, Map<String, FieldCapabilities>> fields = mappedFields(List.of());
        fields.put("resource.attributes.deployment.environment",
            capability("resource.attributes.deployment.environment", "object", false));

        ServiceMapTraceLoadPlan plan = ServiceMapTraceLoadPlan.resolve(response(fields), List.of());

        assertTrue(plan.sourceFields().isEmpty());
        assertFalse(plan.docValueFields().contains("resource.attributes.deployment.environment"));
        assertTrue(plan.docValueFields().contains("resource.attributes.deployment.environment.name"));
    }

    private static Map<String, Map<String, FieldCapabilities>> mappedFields(
            List<String> groupByAttributes) {
        Map<String, Map<String, FieldCapabilities>> fields = new HashMap<>();
        Arrays.stream(ServiceMapTraceLoadPlan.requestedFields(groupByAttributes))
            .forEach(field -> fields.put(field, capability(field, "keyword", true)));
        return fields;
    }

    private static Map<String, FieldCapabilities> capability(
            String field, String type, boolean aggregatable) {
        return Map.of(type, fieldCapability(field, type, aggregatable));
    }

    private static FieldCapabilities fieldCapability(
            String field, String type, boolean aggregatable) {
        return new FieldCapabilities(
            field, type, true, aggregatable, null, null, null, Map.of());
    }

    private static FieldCapabilitiesResponse response(
            Map<String, Map<String, FieldCapabilities>> fields) {
        return new FieldCapabilitiesResponse(new String[]{"otel-v1-apm-span-test"}, fields);
    }
}
