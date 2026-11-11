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
package org.opensearch.plugin.otlp.servicemap;

import java.time.Instant;
import java.util.Map;

/** Span data needed for service-map graph analysis and document generation. */
record ServiceMapSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String kind,
        String operationName,
        Instant endTime,
        Instant indexedAt,
        String environment,
        Map<String, Object> groupByAttributes) {

    ServiceMapSpan {
        groupByAttributes = Map.copyOf(groupByAttributes);
    }
}
