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

import java.util.ArrayList;
import java.util.List;

public class CompactTraceIdSetTests extends OpenSearchTestCase {

    public void testStoresDecodedIdsAndReencodesPartitions() {
        CompactTraceIdSet ids = new CompactTraceIdSet();
        String first = "00112233445566778899aabbccddeeff";
        String second = "ffeeddccbbaa99887766554433221100";
        String third = "0123456789abcdef0123456789abcdef";

        assertEquals(CompactTraceIdSet.AddResult.ADDED, ids.add(first));
        assertEquals(CompactTraceIdSet.AddResult.ADDED, ids.add(second));
        assertEquals(CompactTraceIdSet.AddResult.ADDED, ids.add(third));
        assertEquals(CompactTraceIdSet.AddResult.DUPLICATE, ids.add(first));

        assertEquals(3, ids.size());
        assertEquals(List.of(List.of(first, second), List.of(third)), partitions(ids, 2));
    }

    public void testPartitionsCanBeTraversedIndependently() {
        CompactTraceIdSet ids = new CompactTraceIdSet();
        String first = "00112233445566778899aabbccddeeff";
        String second = "ffeeddccbbaa99887766554433221100";
        ids.add(first);
        ids.add(second);

        Iterable<List<String>> partitions = ids.partitions(1);

        List<List<String>> expected = List.of(List.of(first), List.of(second));
        assertEquals(expected, collect(partitions));
        assertEquals(expected, collect(partitions));
    }

    public void testEmptySetHasNoPartitions() {
        assertTrue(partitions(new CompactTraceIdSet(), 1).isEmpty());
    }

    public void testRejectsInvalidPartitionSize() {
        CompactTraceIdSet ids = new CompactTraceIdSet();

        assertThrows(IllegalArgumentException.class, () -> ids.partitions(0));
        assertThrows(IllegalArgumentException.class, () -> ids.partitions(-1));
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

    private static List<List<String>> partitions(CompactTraceIdSet ids, int partitionSize) {
        return collect(ids.partitions(partitionSize));
    }

    private static List<List<String>> collect(Iterable<List<String>> partitions) {
        List<List<String>> result = new ArrayList<>();
        partitions.forEach(result::add);
        return result;
    }
}
