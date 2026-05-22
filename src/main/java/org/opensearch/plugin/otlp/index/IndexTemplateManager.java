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
package org.opensearch.plugin.otlp.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.admin.indices.template.put.PutComposableIndexTemplateAction;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.metadata.ComposableIndexTemplate;
import org.opensearch.cluster.metadata.Template;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.compress.CompressedXContent;
import org.opensearch.core.action.ActionListener;
import org.opensearch.transport.client.Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class IndexTemplateManager implements ClusterStateListener {

    private static final Logger logger = LogManager.getLogger(IndexTemplateManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<TemplateSpec> TEMPLATES = List.of(
        new TemplateSpec("ss4o_traces",      "otel-v1-apm-span*",
            "templates/otel-v1-apm-span-index-standard-template.json"),
        new TemplateSpec("ss4o_logs",        "logs-otel-v1*",
            "templates/logs-otel-v1-index-standard-template.json"),
        new TemplateSpec("ss4o_metrics",     "metrics-otel-v1*",
            "templates/metrics-otel-v1-index-standard-template.json"),
        new TemplateSpec("ss4o_service_map", "otel-v1-apm-service-map*",
            "templates/otel-v1-apm-service-map-index-standard-template.json")
    );

    private final Client client;
    private volatile boolean initialized = false;

    public IndexTemplateManager(Client client, ClusterService clusterService) {
        this.client = client;
        clusterService.addListener(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (initialized || !event.localNodeClusterManager()) {
            return;
        }
        initialized = true;
        // Run template creation off the cluster-state applier thread to avoid deadlock
        Thread t = new Thread(() -> {
            for (TemplateSpec spec : TEMPLATES) {
                ensureTemplate(spec, event);
            }
        }, "otlp-template-init");
        t.setDaemon(true);
        t.start();
    }

    private void ensureTemplate(TemplateSpec spec, ClusterChangedEvent event) {
        if (event.state().metadata().templatesV2().containsKey(spec.name)) {
            return;
        }
        try {
            String json = loadResource(spec.resourcePath);
            JsonNode root = MAPPER.readTree(json);
            JsonNode mappingsNode = root.path("template").path("mappings");
            String mappingsJson = MAPPER.writeValueAsString(mappingsNode);
            ComposableIndexTemplate indexTemplate = new ComposableIndexTemplate(
                List.of(spec.indexPattern),
                new Template(null, new CompressedXContent(mappingsJson), null),
                null, null, null, null
            );
            PutComposableIndexTemplateAction.Request request =
                new PutComposableIndexTemplateAction.Request(spec.name);
            request.indexTemplate(indexTemplate);
            client.execute(PutComposableIndexTemplateAction.INSTANCE, request,
                ActionListener.wrap(
                    r -> logger.info("Created index template [{}] for pattern [{}]", spec.name, spec.indexPattern),
                    e -> logger.error("Failed to create index template [{}]", spec.name, e)
                ));
        } catch (Exception e) {
            logger.error("Failed to create index template [{}]", spec.name, e);
        }
    }

    private static String loadResource(String path) {
        try (InputStream is = IndexTemplateManager.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record TemplateSpec(String name, String indexPattern, String resourcePath) {}
}
