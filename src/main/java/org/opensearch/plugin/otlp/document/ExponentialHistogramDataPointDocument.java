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
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.resource.v1.Resource;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class ExponentialHistogramDataPointDocument extends MetricDocument {

    private final ExponentialHistogramDataPoint dp;

    public ExponentialHistogramDataPointDocument(
            Resource resource, String resourceSchemaUrl,
            InstrumentationScope scope, String scopeSchemaUrl,
            Metric metric, ExponentialHistogramDataPoint dp) {
        super(resource, resourceSchemaUrl, scope, scopeSchemaUrl, metric, "ExponentialHistogram");
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
        builder.field("scale", dp.getScale());
        builder.field("zeroCount", dp.getZeroCount());
        builder.field("positiveOffset", dp.getPositive().getOffset());
        builder.field("positiveBuckets", dp.getPositive().getBucketCountsList());
        builder.field("negativeOffset", dp.getNegative().getOffset());
        builder.field("negativeBuckets", dp.getNegative().getBucketCountsList());
        builder.field("aggregationTemporality",
            metric.getExponentialHistogram().getAggregationTemporality().name());
    }
}
