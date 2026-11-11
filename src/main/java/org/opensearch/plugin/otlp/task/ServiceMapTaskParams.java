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
        List<String> groups = new ArrayList<>();
        Object rawGroups = values.get("group_by_attributes");
        if (rawGroups instanceof List<?> list) {
            for (Object value : list) {
                groups.add(Objects.toString(value));
            }
        }
        return new ServiceMapTaskParams(
            Objects.toString(values.get("trace_id_prefix")),
            new ServiceMapGenerationConfig(
                Objects.toString(values.get("traces_index")),
                Objects.toString(values.get("service_map_index")),
                ((Number) values.get("interval_millis")).longValue(),
                ((Number) values.get("lookback_millis")).longValue(),
                groups));
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
