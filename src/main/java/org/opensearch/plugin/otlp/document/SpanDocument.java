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
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class SpanDocument implements IdentifiedDocument {

    private final Resource resource;
    private final String resourceSchemaUrl;
    private final InstrumentationScope instrumentationScope;
    private final String instrumentationScopeSchemaUrl;
    private final Span span;

    public SpanDocument(Resource resource, String resourceSchemaUrl,
                        InstrumentationScope instrumentationScope, String instrumentationScopeSchemaUrl,
                        Span span) {
        this.resource = resource;
        this.resourceSchemaUrl = resourceSchemaUrl;
        this.instrumentationScope = instrumentationScope;
        this.instrumentationScopeSchemaUrl = instrumentationScopeSchemaUrl;
        this.span = span;
    }

    @Override
    public String documentId() {
        return OTLPXContentUtils.hexFromBytes(span.getTraceId())
            + "/" + OTLPXContentUtils.hexFromBytes(span.getSpanId());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject()
            .field("traceId", OTLPXContentUtils.hexFromBytes(span.getTraceId()))
            .field("spanId", OTLPXContentUtils.hexFromBytes(span.getSpanId()));
        if (!span.getParentSpanId().isEmpty()) {
            builder.field("parentSpanId", OTLPXContentUtils.hexFromBytes(span.getParentSpanId()));
        }
        if (!span.getTraceState().isEmpty()) {
            builder.field("traceState", span.getTraceState());
        }
        builder.field("name", span.getName())
            .field("kind", span.getKind().name())
            .field("startTime", OTLPXContentUtils.toIso8601(span.getStartTimeUnixNano()))
            .field("endTime", OTLPXContentUtils.toIso8601(span.getEndTimeUnixNano()))
            .field("durationInNanos", span.getEndTimeUnixNano() - span.getStartTimeUnixNano())
            .field("flags", span.getFlags())
            .startObject("status")
                .field("code", span.getStatus().getCodeValue())
                .field("message", span.getStatus().getMessage())
            .endObject()
            .field("droppedAttributesCount", span.getDroppedAttributesCount());
        if (span.getAttributesCount() > 0) {
            builder.startObject("attributes");
            OTLPXContentUtils.writeAttributes(builder, span.getAttributesList());
            builder.endObject();
        }
        writeEvents(builder, span.getEventsList());
        builder.field("droppedEventsCount", span.getDroppedEventsCount());
        writeLinks(builder, span.getLinksList());
        builder.field("droppedLinksCount", span.getDroppedLinksCount());
        OTLPXContentUtils.writeInstrumentationScope(builder, instrumentationScope, instrumentationScopeSchemaUrl);
        String serviceName = OTLPXContentUtils.writeResource(builder, resource, resourceSchemaUrl);
        if (serviceName != null) {
            builder.field("serviceName", serviceName);
        }
        return builder.endObject();
    }

    private static void writeEvents(XContentBuilder builder, List<Span.Event> events) throws IOException {
        builder.startArray("events");
        for (Span.Event event : events) {
            builder.startObject()
                .field("time", OTLPXContentUtils.toIso8601(event.getTimeUnixNano()))
                .field("name", event.getName())
                .field("droppedAttributesCount", event.getDroppedAttributesCount());
            if (event.getAttributesCount() > 0) {
                builder.startObject("attributes");
                OTLPXContentUtils.writeAttributes(builder, event.getAttributesList());
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endArray();
    }

    private static void writeLinks(XContentBuilder builder, List<Span.Link> links) throws IOException {        builder.startArray("links");
        for (Span.Link link : links) {
            builder.startObject()
                .field("traceId", OTLPXContentUtils.hexFromBytes(link.getTraceId()))
                .field("spanId", OTLPXContentUtils.hexFromBytes(link.getSpanId()));
            if (!link.getTraceState().isEmpty()) {
                builder.field("traceState", link.getTraceState());
            }
            builder.field("flags", link.getFlags())
                .field("droppedAttributesCount", link.getDroppedAttributesCount());
            if (link.getAttributesCount() > 0) {
                builder.startObject("attributes");
                OTLPXContentUtils.writeAttributes(builder, link.getAttributesList());
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endArray();
    }
}
