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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory service-map state used by unit tests. */
final class LocalServiceMapStateStore implements ServiceMapStateStore {

    private final Map<Scope, Cursor> cursors = new HashMap<>();
    private final Set<String> processedTraceIds = new HashSet<>();

    @Override
    public Cursor loadCursor(Scope scope) {
        return cursors.get(scope);
    }

    @Override
    public Set<String> loadProcessedTraceIds(Scope scope, Collection<String> traceIds) {
        Set<String> result = new HashSet<>();
        for (String traceId : traceIds) {
            if (processedTraceIds.contains(processedTraceId(scope, traceId))) {
                result.add(traceId);
            }
        }
        return result;
    }

    @Override
    public void commit(Scope scope, Cursor cursor, Collection<TraceTombstone> tombstones) {
        cursors.put(scope, cursor);
        tombstones.stream()
            .map(TraceTombstone::traceId)
            .map(traceId -> processedTraceId(scope, traceId))
            .forEach(processedTraceIds::add);
    }

    @Override
    public void deleteExpired(String prefix, Instant now) {}

    private static String processedTraceId(Scope scope, String traceId) {
        return scope.namespace() + "\u0000" + traceId;
    }
}
