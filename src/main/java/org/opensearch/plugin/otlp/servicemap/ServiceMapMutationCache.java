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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Consolidates service-map mutations and suppresses unchanged overlap replacements. */
final class ServiceMapMutationCache {

    private final Map<String, CachedMutation> cache = new HashMap<>();

    Run beginRun(Instant now, long retentionMillis) {
        Instant cutoff = now.minusMillis(retentionMillis);
        cache.values().removeIf(mutation -> mutation.lastSeen().isBefore(cutoff));
        return new Run(now);
    }

    final class Run {
        private final Instant now;
        private final Map<String, Map<String, Object>> documentsById = new LinkedHashMap<>();
        private final Map<String, byte[]> fingerprintsById = new HashMap<>();
        private final Set<String> deletesById = new LinkedHashSet<>();

        private Run(Instant now) {
            this.now = now;
        }

        void index(ServiceMapDocumentFactory.GeneratedDocument document) {
            byte[] fingerprint = ServiceMapHashing.sha256(document.source().toString());
            CachedMutation cached = touch(document.id());
            if (cached != null && !cached.deleted() && Arrays.equals(fingerprint, cached.fingerprint())) {
                remove(document.id());
                return;
            }
            deletesById.remove(document.id());
            documentsById.put(document.id(), document.source());
            fingerprintsById.put(document.id(), fingerprint);
        }

        void delete(String id) {
            CachedMutation cached = touch(id);
            if (cached != null && cached.deleted()) {
                remove(id);
                return;
            }
            documentsById.remove(id);
            fingerprintsById.remove(id);
            deletesById.add(id);
        }

        Map<String, Map<String, Object>> documents() {
            return Collections.unmodifiableMap(documentsById);
        }

        Set<String> deletes() {
            return Collections.unmodifiableSet(deletesById);
        }

        boolean isEmpty() {
            return documentsById.isEmpty() && deletesById.isEmpty();
        }

        int size() {
            return documentsById.size() + deletesById.size();
        }

        void commit() {
            documentsById.forEach((id, source) -> cache.put(
                id, new CachedMutation(fingerprintsById.get(id), false, now)));
            deletesById.forEach(id -> cache.put(id, new CachedMutation(null, true, now)));
        }

        private CachedMutation touch(String id) {
            CachedMutation cached = cache.get(id);
            if (cached != null) {
                cache.put(id, new CachedMutation(cached.fingerprint(), cached.deleted(), now));
            }
            return cached;
        }

        private void remove(String id) {
            documentsById.remove(id);
            fingerprintsById.remove(id);
            deletesById.remove(id);
        }
    }

    private record CachedMutation(byte[] fingerprint, boolean deleted, Instant lastSeen) {}
}
