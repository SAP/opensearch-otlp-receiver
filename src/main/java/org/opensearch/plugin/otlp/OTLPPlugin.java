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
package org.opensearch.plugin.otlp;

import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugin.otlp.index.BulkIndexer;
import org.opensearch.plugin.otlp.index.IndexTemplateManager;
import org.opensearch.plugin.otlp.index.ServiceMapBuilder;
import org.opensearch.plugin.otlp.index.TraceGroupEnricher;
import org.opensearch.plugin.otlp.server.OTLPGrpcService;
import org.opensearch.plugin.otlp.server.OTLPHttpHandler;
import org.opensearch.plugin.otlp.server.OTLPServer;
import org.opensearch.plugin.otlp.server.RetryInfoCalculator;
import org.opensearch.plugin.otlp.transform.LogsTransformer;
import org.opensearch.plugin.otlp.transform.MetricsTransformer;
import org.opensearch.plugin.otlp.transform.TraceTransformer;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.TelemetryAwarePlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class OTLPPlugin extends Plugin implements TelemetryAwarePlugin {

    private final Settings settings;

    public OTLPPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Collection<Object> createComponents(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier,
            Tracer tracer,
            MetricsRegistry metricsRegistry) {

        if (!OTLPPluginSettings.ENABLED.get(settings)) {
            return List.of();
        }

        String tracesIndex  = OTLPPluginSettings.TRACES_INDEX.get(settings);
        String logsIndex    = OTLPPluginSettings.LOGS_INDEX.get(settings);
        String metricsIndex = OTLPPluginSettings.METRICS_INDEX.get(settings);

        TraceTransformer traceTransformer      = new TraceTransformer();
        LogsTransformer logsTransformer        = new LogsTransformer();
        MetricsTransformer metricsTransformer  = new MetricsTransformer();
        BulkIndexer bulkIndexer = new BulkIndexer(
            client,
            OTLPPluginSettings.MAX_INFLIGHT_REQUESTS.get(settings),
            OTLPPluginSettings.REQUIRE_ALIAS.get(settings),
            metricsRegistry);

        OTLPGrpcService grpcService = new OTLPGrpcService(
            traceTransformer, logsTransformer, metricsTransformer,
            bulkIndexer, tracesIndex, logsIndex, metricsIndex);

        RetryInfoCalculator retryInfoCalculator = new RetryInfoCalculator(
            Duration.ofMillis(100), Duration.ofSeconds(2));

        OTLPHttpHandler httpHandler = new OTLPHttpHandler(
            traceTransformer, logsTransformer, metricsTransformer,
            bulkIndexer, tracesIndex, logsIndex, metricsIndex,
            retryInfoCalculator);

        OTLPServer server = new OTLPServer(
            grpcService, httpHandler,
            OTLPPluginSettings.BIND_HOST.get(settings),
            OTLPPluginSettings.GRPC_PORT.get(settings),
            OTLPPluginSettings.HTTP_PORT.get(settings),
            OTLPPluginSettings.MAX_REQUEST_BYTES.get(settings),
            OTLPPluginSettings.COMPRESSION_ENABLED.get(settings),
            retryInfoCalculator);

        List<Object> components = new ArrayList<>();
        components.add(server);
        components.add(bulkIndexer);

        if (OTLPPluginSettings.CREATE_TEMPLATES.get(settings)) {
            components.add(new IndexTemplateManager(client, clusterService));
        }

        if (OTLPPluginSettings.ENRICHMENT_ENABLED.get(settings)) {
            components.add(new TraceGroupEnricher(
                client,
                threadPool,
                tracesIndex,
                OTLPPluginSettings.ENRICHMENT_INTERVAL.get(settings),
                OTLPPluginSettings.ENRICHMENT_LOOKBACK_WINDOW.get(settings),
                OTLPPluginSettings.ENRICHMENT_TRACE_CUTOFF.get(settings),
                OTLPPluginSettings.ENRICHMENT_BATCH_SIZE.get(settings)));
            components.add(new ServiceMapBuilder(
                client,
                threadPool,
                tracesIndex,
                OTLPPluginSettings.SERVICE_MAP_INDEX.get(settings),
                OTLPPluginSettings.SERVICE_MAP_INTERVAL.get(settings),
                OTLPPluginSettings.ENRICHMENT_LOOKBACK_WINDOW.get(settings)));
        }

        return components;
    }

    @Override
    public List<Setting<?>> getSettings() {
        return OTLPPluginSettings.ALL;
    }
}
