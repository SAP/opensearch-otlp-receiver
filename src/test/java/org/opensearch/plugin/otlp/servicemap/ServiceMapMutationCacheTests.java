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

import org.opensearch.test.OpenSearchTestCase;

import java.time.Instant;
import java.util.Map;

public class ServiceMapMutationCacheTests extends OpenSearchTestCase {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    public void testUnchangedDocumentIsSuppressedAfterCommit() {
        ServiceMapMutationCache cache = new ServiceMapMutationCache();
        ServiceMapDocumentFactory.GeneratedDocument document = document("one");

        ServiceMapMutationCache.Run first = cache.beginRun(NOW, 10_000);
        first.index(document);
        assertEquals(1, first.size());
        first.commit();

        ServiceMapMutationCache.Run overlap = cache.beginRun(NOW.plusSeconds(1), 10_000);
        overlap.index(document);
        assertTrue(overlap.isEmpty());
    }

    public void testChangedDocumentAndStateTransitionsAreRetained() {
        ServiceMapMutationCache cache = new ServiceMapMutationCache();
        ServiceMapMutationCache.Run first = cache.beginRun(NOW, 10_000);
        first.index(document("one"));
        first.commit();

        ServiceMapMutationCache.Run changed = cache.beginRun(NOW.plusSeconds(1), 10_000);
        changed.index(document("two"));
        assertEquals(Map.of("value", "two"), changed.documents().get("id"));
        changed.delete("id");
        assertTrue(changed.documents().isEmpty());
        assertEquals(1, changed.deletes().size());
    }

    public void testExpiredFingerprintIsSafelyRewritten() {
        ServiceMapMutationCache cache = new ServiceMapMutationCache();
        ServiceMapDocumentFactory.GeneratedDocument document = document("one");
        ServiceMapMutationCache.Run first = cache.beginRun(NOW, 1_000);
        first.index(document);
        first.commit();

        ServiceMapMutationCache.Run expired = cache.beginRun(NOW.plusSeconds(2), 1_000);
        expired.index(document);

        assertEquals(1, expired.size());
    }

    private static ServiceMapDocumentFactory.GeneratedDocument document(String value) {
        return new ServiceMapDocumentFactory.GeneratedDocument("id", Map.of("value", value));
    }
}
