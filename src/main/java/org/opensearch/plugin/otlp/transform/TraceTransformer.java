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
package org.opensearch.plugin.otlp.transform;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.opensearch.plugin.otlp.document.SpanDocument;

import java.util.ArrayList;
import java.util.List;

public class TraceTransformer {

    public List<SpanDocument> transform(ExportTraceServiceRequest request) {
        List<SpanDocument> docs = new ArrayList<>();
        for (ResourceSpans rs : request.getResourceSpansList()) {
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                for (Span span : ss.getSpansList()) {
                    docs.add(new SpanDocument(
                        rs.getResource(), rs.getSchemaUrl(),
                        ss.getScope(), ss.getSchemaUrl(),
                        span));
                }
            }
        }
        return docs;
    }
}
