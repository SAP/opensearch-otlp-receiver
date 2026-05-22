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

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.TimeValue;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OTLPPluginSettings {

    private OTLPPluginSettings() {}

    public static final Setting<Boolean> ENABLED = Setting.boolSetting(
        "plugins.otlp.enabled", true, Setting.Property.NodeScope);

    public static final Setting<Integer> GRPC_PORT = Setting.intSetting(
        "plugins.otlp.port", 4317, 1, 65535, Setting.Property.NodeScope);

    public static final Setting<Integer> HTTP_PORT = Setting.intSetting(
        "plugins.otlp.http.port", 4318, 1, 65535, Setting.Property.NodeScope);

    public static final Setting<String> BIND_HOST = Setting.simpleString(
        "plugins.otlp.bind_host", "0.0.0.0", Setting.Property.NodeScope);

    public static final Setting<String> TRACES_INDEX = Setting.simpleString(
        "plugins.otlp.traces.index", "otel-v1-apm-span", Setting.Property.NodeScope);

    public static final Setting<String> LOGS_INDEX = Setting.simpleString(
        "plugins.otlp.logs.index", "logs-otel-v1", Setting.Property.NodeScope);

    public static final Setting<String> METRICS_INDEX = Setting.simpleString(
        "plugins.otlp.metrics.index", "metrics-otel-v1", Setting.Property.NodeScope);

    public static final Setting<Boolean> CREATE_TEMPLATES = Setting.boolSetting(
        "plugins.otlp.index.create_templates", true, Setting.Property.NodeScope);

    public static final Setting<Boolean> SSL_ENABLED = Setting.boolSetting(
        "plugins.otlp.ssl.enabled", false, Setting.Property.NodeScope);

    public static final Setting<String> SSL_CERTIFICATE = Setting.simpleString(
        "plugins.otlp.ssl.certificate", Setting.Property.NodeScope);

    public static final Setting<String> SSL_KEY = Setting.simpleString(
        "plugins.otlp.ssl.key", Setting.Property.NodeScope);

    public static final Setting<Boolean> ENRICHMENT_ENABLED = Setting.boolSetting(
        "plugins.otlp.traces.enrichment.enabled", true, Setting.Property.NodeScope);

    public static final Setting<TimeValue> ENRICHMENT_INTERVAL = Setting.timeSetting(
        "plugins.otlp.traces.enrichment.interval",
        new TimeValue(60, TimeUnit.SECONDS), Setting.Property.NodeScope);

    public static final Setting<TimeValue> ENRICHMENT_LOOKBACK_WINDOW = Setting.timeSetting(
        "plugins.otlp.traces.enrichment.lookback_window",
        new TimeValue(5, TimeUnit.MINUTES), Setting.Property.NodeScope);

    public static final Setting<TimeValue> ENRICHMENT_TRACE_CUTOFF = Setting.timeSetting(
        "plugins.otlp.traces.enrichment.trace_cutoff",
        new TimeValue(10, TimeUnit.MINUTES), Setting.Property.NodeScope);

    public static final Setting<Integer> ENRICHMENT_BATCH_SIZE = Setting.intSetting(
        "plugins.otlp.traces.enrichment.batch_size", 1000, 1, Setting.Property.NodeScope);

    public static final Setting<String> SERVICE_MAP_INDEX = Setting.simpleString(
        "plugins.otlp.service_map.index", "otel-v1-apm-service-map", Setting.Property.NodeScope);

    public static final Setting<TimeValue> SERVICE_MAP_INTERVAL = Setting.timeSetting(
        "plugins.otlp.service_map.interval",
        new TimeValue(60, TimeUnit.SECONDS), Setting.Property.NodeScope);

    public static final Setting<Integer> MAX_INFLIGHT_REQUESTS = Setting.intSetting(
        "plugins.otlp.max_inflight_requests", 64, 1, Setting.Property.NodeScope);

    public static final Setting<Boolean> REQUIRE_ALIAS = Setting.boolSetting(
        "plugins.otlp.index.require_alias", false, Setting.Property.NodeScope);

    public static final Setting<Integer> MAX_REQUEST_BYTES = Setting.intSetting(
        "plugins.otlp.max_request_bytes", 4 * 1024 * 1024, 1, Setting.Property.NodeScope);

    public static final Setting<Boolean> COMPRESSION_ENABLED = Setting.boolSetting(
        "plugins.otlp.compression.enabled", true, Setting.Property.NodeScope);

    public static final List<Setting<?>> ALL = List.of(
        ENABLED, GRPC_PORT, HTTP_PORT, BIND_HOST,
        TRACES_INDEX, LOGS_INDEX, METRICS_INDEX,
        CREATE_TEMPLATES, SSL_ENABLED, SSL_CERTIFICATE, SSL_KEY,
        ENRICHMENT_ENABLED, ENRICHMENT_INTERVAL, ENRICHMENT_LOOKBACK_WINDOW,
        ENRICHMENT_TRACE_CUTOFF, ENRICHMENT_BATCH_SIZE,
        SERVICE_MAP_INDEX, SERVICE_MAP_INTERVAL,
        MAX_INFLIGHT_REQUESTS, REQUIRE_ALIAS, MAX_REQUEST_BYTES, COMPRESSION_ENABLED
    );
}
