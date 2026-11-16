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

import java.util.List;

public class CompactTraceIdSetTests extends OpenSearchTestCase {

    public void testStoresDecodedIdsAndReencodesPartitions() {
        CompactTraceIdSet ids = new CompactTraceIdSet();
        String first = "00112233445566778899aabbccddeeff";
        String second = "ffeeddccbbaa99887766554433221100";

        assertEquals(CompactTraceIdSet.AddResult.ADDED, ids.add(first));
        assertEquals(CompactTraceIdSet.AddResult.ADDED, ids.add(second));
        assertEquals(CompactTraceIdSet.AddResult.DUPLICATE, ids.add(first));

        assertEquals(2, ids.size());
        assertEquals(List.of(first), ids.partition(0, 1));
        assertEquals(List.of(second), ids.partition(1, 1));
    }

    public void testRejectsInvalidIds() {
        CompactTraceIdSet ids = new CompactTraceIdSet();

        assertEquals(CompactTraceIdSet.AddResult.INVALID, ids.add(null));
        assertEquals(CompactTraceIdSet.AddResult.INVALID, ids.add("too-short"));
        assertEquals(CompactTraceIdSet.AddResult.INVALID,
            ids.add("00112233445566778899aabbccddeefg"));
        assertEquals(CompactTraceIdSet.AddResult.INVALID,
            ids.add("00112233445566778899AABBCCDDEEFF"));
        assertEquals(CompactTraceIdSet.AddResult.INVALID,
            ids.add("00000000000000000000000000000000"));
        assertEquals(0, ids.size());
    }
}
