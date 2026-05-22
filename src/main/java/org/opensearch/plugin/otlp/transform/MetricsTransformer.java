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

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import org.opensearch.plugin.otlp.document.ExponentialHistogramDataPointDocument;
import org.opensearch.plugin.otlp.document.GaugeDataPointDocument;
import org.opensearch.plugin.otlp.document.HistogramDataPointDocument;
import org.opensearch.plugin.otlp.document.MetricDocument;
import org.opensearch.plugin.otlp.document.SumDataPointDocument;
import org.opensearch.plugin.otlp.document.SummaryDataPointDocument;

import java.util.ArrayList;
import java.util.List;

public class MetricsTransformer {

    public List<MetricDocument> transform(ExportMetricsServiceRequest request) {
        List<MetricDocument> docs = new ArrayList<>();
        for (ResourceMetrics rm : request.getResourceMetricsList()) {
            for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                for (Metric metric : sm.getMetricsList()) {
                    toDocuments(docs, metric, rm, sm);
                }
            }
        }
        return docs;
    }

    private static void toDocuments(
            List<MetricDocument> docs, Metric metric, ResourceMetrics rm, ScopeMetrics sm) {
        switch (metric.getDataCase()) {
            case GAUGE:
                for (var dp : metric.getGauge().getDataPointsList()) {
                    docs.add(new GaugeDataPointDocument(
                        rm.getResource(), rm.getSchemaUrl(), sm.getScope(), sm.getSchemaUrl(), metric, dp));
                }
                break;
            case SUM:
                for (var dp : metric.getSum().getDataPointsList()) {
                    docs.add(new SumDataPointDocument(
                        rm.getResource(), rm.getSchemaUrl(), sm.getScope(), sm.getSchemaUrl(), metric, dp));
                }
                break;
            case HISTOGRAM:
                for (var dp : metric.getHistogram().getDataPointsList()) {
                    docs.add(new HistogramDataPointDocument(
                        rm.getResource(), rm.getSchemaUrl(), sm.getScope(), sm.getSchemaUrl(), metric, dp));
                }
                break;
            case EXPONENTIAL_HISTOGRAM:
                for (var dp : metric.getExponentialHistogram().getDataPointsList()) {
                    docs.add(new ExponentialHistogramDataPointDocument(
                        rm.getResource(), rm.getSchemaUrl(), sm.getScope(), sm.getSchemaUrl(), metric, dp));
                }
                break;
            case SUMMARY:
                for (var dp : metric.getSummary().getDataPointsList()) {
                    docs.add(new SummaryDataPointDocument(
                        rm.getResource(), rm.getSchemaUrl(), sm.getScope(), sm.getSchemaUrl(), metric, dp));
                }
                break;
            default:
                break;
        }
    }
}
