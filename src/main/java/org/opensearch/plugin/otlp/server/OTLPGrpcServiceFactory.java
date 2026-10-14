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
package org.opensearch.plugin.otlp.server;

import io.grpc.BindableService;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugin.otlp.OTLPPluginSettings;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.grpc.spi.GrpcServiceFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class OTLPGrpcServiceFactory implements GrpcServiceFactory {

    // OpenSearch discovers this class via ServiceLoader (META-INF/services), so the plugin
    // cannot hold a direct reference to the instance.  Store it here so that
    // OTLPPlugin.createComponents() can inject the MetricsRegistry after build() returns.
    private static volatile OTLPGrpcServiceFactory INSTANCE;

    private Client client;
    private Settings settings;
    private BulkIndexer bulkIndexer;
    private final AtomicBoolean built = new AtomicBoolean(false);

    public OTLPGrpcServiceFactory() {
        INSTANCE = this;
    }

    public static OTLPGrpcServiceFactory getInstance() {
        return INSTANCE;
    }

    public void setMetricsRegistry(MetricsRegistry metricsRegistry) {
        if (bulkIndexer != null) {
            bulkIndexer.setMetricsRegistry(metricsRegistry);
        }
    }

    @Override
    public String plugin() {
        return "opensearch-otlp-receiver";
    }

    @Override
    public GrpcServiceFactory initClient(Client client) {
        this.client = client;
        return this;
    }

    @Override
    public GrpcServiceFactory initSettings(Settings settings) {
        this.settings = settings;
        return this;
    }

    @Override
    public List<BindableService> build() {
        if (!OTLPPluginSettings.ENABLED.get(settings)) {
            return List.of();
        }
        if (!built.compareAndSet(false, true)) {
            throw new IllegalStateException("OTLPGrpcServiceFactory.build() called more than once");
        }
        // MetricsRegistry is not available through the GrpcServiceFactory SPI —
        // it arrives later via OTLPPlugin.createComponents().  OTLPPlugin calls
        // OTLPGrpcServiceFactory.getInstance().setMetricsRegistry() at that point.
        bulkIndexer = new BulkIndexer(
            client,
            OTLPPluginSettings.MAX_INFLIGHT_REQUESTS.get(settings),
            OTLPPluginSettings.REQUIRE_ALIAS.get(settings),
            null);
        RetryInfoCalculator retryInfoCalculator = new RetryInfoCalculator(
            Duration.ofMillis(100), Duration.ofSeconds(2));
        return List.of(
            new OTLPTraceGrpcService(bulkIndexer, new TraceTransformer(), retryInfoCalculator,
                OTLPPluginSettings.TRACES_INDEX.get(settings)),
            new OTLPMetricsGrpcService(bulkIndexer, new MetricsTransformer(), retryInfoCalculator,
                OTLPPluginSettings.METRICS_INDEX.get(settings)),
            new OTLPLogsGrpcService(bulkIndexer, new LogsTransformer(), retryInfoCalculator,
                OTLPPluginSettings.LOGS_INDEX.get(settings))
        );
    }
}
