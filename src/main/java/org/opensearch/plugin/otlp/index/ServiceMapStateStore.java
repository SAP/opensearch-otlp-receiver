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

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Durable, small coordination state for an incremental service-map partition. */
interface ServiceMapStateStore {

    Cursor loadCursor(String prefix) throws Exception;

    Set<String> loadProcessedTraceIds(Collection<String> traceIds) throws Exception;

    void commit(String prefix, Cursor cursor, Collection<TraceTombstone> tombstones) throws Exception;

    void deleteExpired(String prefix, Instant now) throws Exception;

    record Cursor(Instant indexedAt, String documentId) {}

    record TraceTombstone(
        String traceId, Instant lastProcessedIndexedAt, long generation, boolean rootSeen, Instant expiresAt) {}
}
