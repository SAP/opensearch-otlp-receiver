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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Consolidates service-map observations and suppresses unchanged overlap replacements. */
final class ServiceMapMutationCache {

    private final Map<String, Instant> cache = new HashMap<>();

    Run beginRun(Instant now, long retentionMillis) {
        Instant cutoff = now.minusMillis(retentionMillis);
        cache.values().removeIf(lastSeen -> lastSeen.isBefore(cutoff));
        return new Run(now);
    }

    final class Run {
        private final Instant now;
        private final Map<String, Map<String, Object>> documentsById = new HashMap<>();

        private Run(Instant now) {
            this.now = now;
        }

        void index(ServiceMapDocumentFactory.GeneratedDocument document) {
            if (cache.containsKey(document.id())) {
                cache.put(document.id(), now);
                remove(document.id());
                return;
            }
            documentsById.put(document.id(), document.source());
        }

        Map<String, Map<String, Object>> documents() {
            return Collections.unmodifiableMap(documentsById);
        }

        boolean isEmpty() {
            return documentsById.isEmpty();
        }

        int size() {
            return documentsById.size();
        }

        void commit() {
            documentsById.keySet().forEach(id -> cache.put(id, now));
        }

        private void remove(String id) {
            documentsById.remove(id);
        }
    }
}
