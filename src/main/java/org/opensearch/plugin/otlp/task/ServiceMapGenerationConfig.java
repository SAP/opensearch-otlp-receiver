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
package org.opensearch.plugin.otlp.task;

import java.util.List;
import java.util.Objects;

/** Immutable service-map generation settings shared by all trace-prefix tasks. */
public record ServiceMapGenerationConfig(
        String tracesIndex,
        String serviceMapIndex,
        long intervalMillis,
        long lookbackMillis,
        List<String> groupByAttributes) {

    public ServiceMapGenerationConfig {
        Objects.requireNonNull(tracesIndex);
        Objects.requireNonNull(serviceMapIndex);
        groupByAttributes = List.copyOf(groupByAttributes);
    }
}
