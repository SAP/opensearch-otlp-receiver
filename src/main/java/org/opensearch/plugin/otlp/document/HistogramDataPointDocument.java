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
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class HistogramDataPointDocument extends MetricDocument {

    private final HistogramDataPoint dp;

    public HistogramDataPointDocument(
            Resource resource, String resourceSchemaUrl,
            InstrumentationScope scope, String scopeSchemaUrl,
            Metric metric, HistogramDataPoint dp) {
        super(resource, resourceSchemaUrl, scope, scopeSchemaUrl, metric, "Histogram");
        this.dp = dp;
    }

    @Override protected long startTimeNanos()  { return dp.getStartTimeUnixNano(); }
    @Override protected long timeNanos()        { return dp.getTimeUnixNano(); }
    @Override protected List<KeyValue>  dataPointAttributes() { return dp.getAttributesList(); }
    @Override protected List<Exemplar>  dataPointExemplars()  { return dp.getExemplarsList(); }
    @Override protected void writeValue(XContentBuilder builder) {}

    @Override
    protected void writeKindSpecificFields(XContentBuilder builder) throws IOException {
        builder.field("count", dp.getCount());
        builder.field("sum", dp.getSum());
        if (dp.hasMin()) builder.field("min", dp.getMin());
        if (dp.hasMax()) builder.field("max", dp.getMax());
        builder.field("bucketCountsList", dp.getBucketCountsList());
        builder.field("bucketCount", dp.getBucketCountsCount());
        builder.field("explicitBoundsList", dp.getExplicitBoundsList());
        builder.field("explicitBoundsCount", dp.getExplicitBoundsCount());
        builder.field("aggregationTemporality",
            metric.getHistogram().getAggregationTemporality().name());
    }
}
