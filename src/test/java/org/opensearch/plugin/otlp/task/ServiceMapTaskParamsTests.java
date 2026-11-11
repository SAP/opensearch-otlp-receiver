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
package org.opensearch.plugin.otlp.task;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opensearch.core.xcontent.ToXContent.EMPTY_PARAMS;

public class ServiceMapTaskParamsTests extends OpenSearchTestCase {

    public void testTransportRoundTrip() throws Exception {
        ServiceMapTaskParams expected = params();

        try (BytesStreamOutput output = new BytesStreamOutput()) {
            expected.writeTo(output);
            try (StreamInput input = output.bytes().streamInput()) {
                assertEquals(expected, new ServiceMapTaskParams(input));
            }
        }
    }

    public void testXContentRoundTrip() throws Exception {
        ServiceMapTaskParams expected = params();
        String json;
        try (var builder = XContentFactory.jsonBuilder()) {
            expected.toXContent(builder, EMPTY_PARAMS);
            json = builder.toString();
        }

        try (XContentParser parser = JsonXContent.jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, json)) {
            assertEquals(expected, ServiceMapTaskParams.fromXContent(parser));
        }
    }

    public void testMalformedXContentCanBeLoadedForReplacement() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("trace_id_prefix", "a");
        values.put("traces_index", "otel-v1-apm-span");
        values.put("service_map_index", "otel-v2-apm-service-map");
        values.put("interval_millis", 60_000L);
        values.put("lookback_millis", 300_000L);
        values.put("group_by_attributes", List.of("service.version"));

        for (String field : List.copyOf(values.keySet())) {
            Map<String, Object> malformed = new HashMap<>(values);
            malformed.remove(field);
            ServiceMapTaskParams parsed = parse(malformed);
            assertFalse("missing " + field + " must produce replaceable parameters", parsed.isValid());

            try (BytesStreamOutput output = new BytesStreamOutput()) {
                parsed.writeTo(output);
                try (StreamInput input = output.bytes().streamInput()) {
                    assertFalse("invalid marker must survive transport",
                        new ServiceMapTaskParams(input).isValid());
                }
            }
        }
    }

    public void testStableTaskIdsUsePrefix() {
        assertEquals("otlp-service-map-prefix-0", ServiceMapTaskCoordinator.taskId("0"));
        assertEquals("otlp-service-map-prefix-f", ServiceMapTaskCoordinator.taskId("f"));
    }

    private static ServiceMapTaskParams params() {
        return new ServiceMapTaskParams(
            "a",
            new ServiceMapGenerationConfig(
                "otel-v1-apm-span",
                "otel-v2-apm-service-map",
                60_000L,
                300_000L,
                List.of("service.version", "k8s.cluster.name")));
    }

    private static ServiceMapTaskParams parse(Map<String, Object> values) throws Exception {
        String json;
        try (var builder = XContentFactory.jsonBuilder()) {
            builder.map(values);
            json = builder.toString();
        }
        try (XContentParser parser = JsonXContent.jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, json)) {
            return ServiceMapTaskParams.fromXContent(parser);
        }
    }
}
