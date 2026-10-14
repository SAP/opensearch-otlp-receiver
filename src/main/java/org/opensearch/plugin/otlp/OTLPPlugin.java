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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugin.otlp.server.OTLPGrpcServiceFactory;
import org.opensearch.plugin.otlp.index.IndexTemplateManager;
import org.opensearch.plugin.otlp.index.ServiceMapBuilder;
import org.opensearch.plugin.otlp.index.TraceGroupEnricher;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.TelemetryAwarePlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

public class OTLPPlugin extends Plugin implements TelemetryAwarePlugin {

    private static final Logger LOG = LogManager.getLogger(OTLPPlugin.class);

    private final Settings settings;

    public OTLPPlugin(Settings settings) {
        this.settings = settings;
        LOG.info("OpenSearch OTLP Receiver plugin {} starting", readVersion());
    }

    private static String readVersion() {
        try (InputStream in = OTLPPlugin.class.getResourceAsStream("/plugin-info.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {}
        return "unknown";
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

        // Inject the MetricsRegistry into the BulkIndexer that OTLPGrpcServiceFactory
        // created in build() — which runs before createComponents() and therefore
        // cannot receive MetricsRegistry through the GrpcServiceFactory SPI.
        OTLPGrpcServiceFactory grpcFactory = OTLPGrpcServiceFactory.getInstance();
        if (grpcFactory != null) {
            grpcFactory.setMetricsRegistry(metricsRegistry);
        }

        List<Object> components = new ArrayList<>();

        if (OTLPPluginSettings.CREATE_TEMPLATES.get(settings)) {
            components.add(new IndexTemplateManager(client, clusterService));
        }

        if (OTLPPluginSettings.ENRICHMENT_ENABLED.get(settings)) {
            String tracesIndex = OTLPPluginSettings.TRACES_INDEX.get(settings);
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
