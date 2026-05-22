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
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.time.Instant;

public class LogDocument implements ToXContent {

    private final Resource resource;
    private final String resourceSchemaUrl;
    private final InstrumentationScope instrumentationScope;
    private final String instrumentationScopeSchemaUrl;
    private final LogRecord logRecord;

    public LogDocument(Resource resource, String resourceSchemaUrl, InstrumentationScope instrumentationScope, String instrumentationScopeSchemaUrl, LogRecord logRecord) {
        this.resource = resource;
        this.resourceSchemaUrl = resourceSchemaUrl;
        this.instrumentationScope = instrumentationScope;
        this.instrumentationScopeSchemaUrl = instrumentationScopeSchemaUrl;
        this.logRecord = logRecord;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject()
            .field("time", OTLPXContentUtils.toIso8601(logRecord.getTimeUnixNano()))
            .field("observedTimestamp", OTLPXContentUtils.toIso8601(logRecord.getObservedTimeUnixNano()))
            .field("@timestamp", ensureTimestamp(logRecord))
            .field("traceId", OTLPXContentUtils.hexFromBytes(logRecord.getTraceId()))
            .field("spanId", OTLPXContentUtils.hexFromBytes(logRecord.getSpanId()))
            .field("flags", logRecord.getFlags())
            .startObject("severity")
                .field("number", logRecord.getSeverityNumberValue())
                .field("text", logRecord.getSeverityText())
            .endObject()
            .field("droppedAttributesCount", logRecord.getDroppedAttributesCount());
        if (!logRecord.getEventName().isEmpty()) {
            builder.field("eventName", logRecord.getEventName());
        }
        OTLPXContentUtils.writeAnyValue(builder, "body", logRecord.getBody());
        if (logRecord.getAttributesCount() > 0) {
            builder.startObject("attributes");
            OTLPXContentUtils.writeAttributes(builder, logRecord.getAttributesList());
            builder.endObject();
        }
        OTLPXContentUtils.writeInstrumentationScope(builder, instrumentationScope, instrumentationScopeSchemaUrl);
        String serviceName = OTLPXContentUtils.writeResource(builder, resource, resourceSchemaUrl);
        if (serviceName != null) {
            builder.field("serviceName", serviceName);
        }
        return builder.endObject();
    }

    private String ensureTimestamp(LogRecord logRecord) {
        if (logRecord.getTimeUnixNano() != 0) {
            return OTLPXContentUtils.toIso8601(logRecord.getTimeUnixNano());
        } else if (logRecord.getObservedTimeUnixNano() != 0) {
            return OTLPXContentUtils.toIso8601(logRecord.getObservedTimeUnixNano());
        } else {
            return Instant.now().toString();
        }
    }
}

