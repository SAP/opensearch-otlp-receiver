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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.persistent.PersistentTaskParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable configuration for one trace-id-prefix service-map task. */
public final class ServiceMapTaskParams implements PersistentTaskParams {

    public static final String NAME = "otlp_service_map";
    private static final Logger LOG = LogManager.getLogger(ServiceMapTaskParams.class);
    private static final String INVALID_VALUE = "";

    private final String traceIdPrefix;
    private final ServiceMapGenerationConfig config;

    public ServiceMapTaskParams(String traceIdPrefix, ServiceMapGenerationConfig config) {
        this.traceIdPrefix = Objects.requireNonNull(traceIdPrefix);
        this.config = Objects.requireNonNull(config);
    }

    public ServiceMapTaskParams(StreamInput in) throws IOException {
        this(in.readString(), new ServiceMapGenerationConfig(
            in.readString(), in.readString(), in.readVLong(), in.readVLong(), in.readStringList()));
    }

    public String traceIdPrefix() {
        return traceIdPrefix;
    }

    public ServiceMapGenerationConfig config() {
        return config;
    }

    boolean isValid() {
        return traceIdPrefix.matches("[0-9a-f]+")
            && !config.tracesIndex().isBlank()
            && !config.serviceMapIndex().isBlank()
            && config.intervalMillis() >= 0
            && config.lookbackMillis() >= 0
            && config.groupByAttributes().stream().noneMatch(String::isBlank);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(traceIdPrefix);
        out.writeString(config.tracesIndex());
        out.writeString(config.serviceMapIndex());
        out.writeVLong(config.intervalMillis());
        out.writeVLong(config.lookbackMillis());
        out.writeStringCollection(config.groupByAttributes());
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public Version getMinimalSupportedVersion() {
        return Version.V_3_9_0;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("trace_id_prefix", traceIdPrefix);
        builder.field("traces_index", config.tracesIndex());
        builder.field("service_map_index", config.serviceMapIndex());
        builder.field("interval_millis", config.intervalMillis());
        builder.field("lookback_millis", config.lookbackMillis());
        builder.field("group_by_attributes", config.groupByAttributes());
        builder.endObject();
        return builder;
    }

    public static ServiceMapTaskParams fromXContent(XContentParser parser) throws IOException {
        Map<String, Object> values = parser.map();
        try {
            return new ServiceMapTaskParams(
                requiredString(values, "trace_id_prefix"),
                new ServiceMapGenerationConfig(
                    requiredString(values, "traces_index"),
                    requiredString(values, "service_map_index"),
                    requiredLong(values, "interval_millis"),
                    requiredLong(values, "lookback_millis"),
                    requiredStringList(values, "group_by_attributes")));
        } catch (IllegalArgumentException e) {
            LOG.warn("Loaded malformed service-map task parameters; the task will be replaced: {}",
                e.getMessage());
            return invalid();
        }
    }

    private static String requiredString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw invalidField(field);
    }

    private static long requiredLong(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value instanceof Number number) {
            long result = number.longValue();
            if (result >= 0 && number.doubleValue() == result) {
                return result;
            }
        }
        throw invalidField(field);
    }

    private static List<String> requiredStringList(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof List<?> list)) {
            throw invalidField(field);
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String string) || string.isBlank()) {
                throw invalidField(field);
            }
            result.add(string);
        }
        return result;
    }

    private static IllegalArgumentException invalidField(String field) {
        return new IllegalArgumentException("missing or invalid field [" + field + "]");
    }

    private static ServiceMapTaskParams invalid() {
        return new ServiceMapTaskParams(INVALID_VALUE,
            new ServiceMapGenerationConfig(INVALID_VALUE, INVALID_VALUE, 0, 0, List.of()));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServiceMapTaskParams that)) {
            return false;
        }
        return traceIdPrefix.equals(that.traceIdPrefix) && config.equals(that.config);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceIdPrefix, config);
    }
}
