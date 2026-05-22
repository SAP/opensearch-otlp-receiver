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
package org.opensearch.plugin.otlp.document;

import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public abstract class MetricDocument implements ToXContent {

    protected final Resource resource;
    protected final String resourceSchemaUrl;
    protected final InstrumentationScope instrumentationScope;
    protected final String instrumentationScopeSchemaUrl;
    protected final Metric metric;
    protected final String kind;

    protected MetricDocument(
            Resource resource, String resourceSchemaUrl,
            InstrumentationScope instrumentationScope, String instrumentationScopeSchemaUrl,
            Metric metric, String kind) {
        this.resource = resource;
        this.resourceSchemaUrl = resourceSchemaUrl;
        this.instrumentationScope = instrumentationScope;
        this.instrumentationScopeSchemaUrl = instrumentationScopeSchemaUrl;
        this.metric = metric;
        this.kind = kind;
    }

    @Override
    public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        long startNanos = startTimeNanos();
        long timeNanos  = timeNanos();

        builder.startObject()
            .field("name", metric.getName())
            .field("kind", kind)
            .field("startTime", OTLPXContentUtils.toIso8601(startNanos))
            .field("time", OTLPXContentUtils.toIso8601(timeNanos))
            .field("@timestamp", OTLPXContentUtils.toIso8601(timeNanos));
        if (!metric.getDescription().isEmpty()) {
            builder.field("description", metric.getDescription());
        }
        if (!metric.getUnit().isEmpty()) {
            builder.field("unit", metric.getUnit());
        }
        writeValue(builder);
        writeKindSpecificFields(builder);

        List<KeyValue> attributes = dataPointAttributes();
        if (!attributes.isEmpty()) {
            builder.startObject("attributes");
            OTLPXContentUtils.writeAttributes(builder, attributes);
            builder.endObject();
        }

        List<Exemplar> exemplars = dataPointExemplars();
        if (!exemplars.isEmpty()) {
            writeExemplars(builder, exemplars);
        }

        OTLPXContentUtils.writeInstrumentationScope(builder, instrumentationScope, instrumentationScopeSchemaUrl);
        String serviceName = OTLPXContentUtils.writeResource(builder, resource, resourceSchemaUrl);
        if (serviceName != null) {
            builder.field("serviceName", serviceName);
        }
        return builder.endObject();
    }

    protected abstract long startTimeNanos();
    protected abstract long timeNanos();
    protected abstract void writeValue(XContentBuilder builder) throws IOException;
    protected abstract void writeKindSpecificFields(XContentBuilder builder) throws IOException;
    protected abstract List<KeyValue> dataPointAttributes();

    protected List<Exemplar> dataPointExemplars() {
        return List.of();
    }

    protected final void writeNumberValue(XContentBuilder builder, NumberDataPoint dp) throws IOException {
        switch (dp.getValueCase()) {
            case AS_INT:
                builder.field("value@int", dp.getAsInt());
                builder.field("value", dp.getAsInt());
                break;
            case AS_DOUBLE:
                double d = OTLPXContentUtils.toDouble(dp.getAsDouble());
                builder.field("value@double", d);
                builder.field("value", d);
                break;
            default:
                break;
        }
    }

    protected final void writeExemplars(XContentBuilder builder, List<Exemplar> exemplars) throws IOException {
        builder.startArray("exemplar");
        for (Exemplar ex : exemplars) {
            builder.startObject()
                .field("time", OTLPXContentUtils.toIso8601(ex.getTimeUnixNano()))
                .field("traceId", OTLPXContentUtils.hexFromBytes(ex.getTraceId()))
                .field("spanId", OTLPXContentUtils.hexFromBytes(ex.getSpanId()));
            switch (ex.getValueCase()) {
                case AS_INT:
                    builder.field("value@int", ex.getAsInt());
                    builder.field("value", ex.getAsInt());
                    break;
                case AS_DOUBLE:
                    double d = OTLPXContentUtils.toDouble(ex.getAsDouble());
                    builder.field("value@double", d);
                    builder.field("value", d);
                    break;
                default:
                    break;
            }
            if (ex.getFilteredAttributesCount() > 0) {
                builder.startObject("attributes");
                OTLPXContentUtils.writeAttributes(builder, ex.getFilteredAttributesList());
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endArray();
    }
}
