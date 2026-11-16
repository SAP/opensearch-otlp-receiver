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

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Stores canonical OTLP trace IDs in their compact 16-byte representation. */
final class CompactTraceIdSet {

    enum AddResult {
        ADDED,
        DUPLICATE,
        INVALID
    }

    private static final int HEX_LENGTH = 32;
    private static final int BYTE_LENGTH = 16;
    private static final HexFormat HEX = HexFormat.of();

    private final BytesRefHash ids = new BytesRefHash();
    private final byte[] decoded = new byte[BYTE_LENGTH];
    private final BytesRef decodedRef = new BytesRef(decoded);

    /** Adds a canonical hexadecimal trace ID and reports whether it was new, duplicate, or invalid. */
    AddResult add(String traceId) {
        if (traceId == null || traceId.length() != HEX_LENGTH) {
            return AddResult.INVALID;
        }
        boolean nonZero = false;
        for (int byteIndex = 0; byteIndex < BYTE_LENGTH; byteIndex++) {
            int charIndex = byteIndex * 2;
            int high = decodeNibble(traceId.charAt(charIndex));
            int low = decodeNibble(traceId.charAt(charIndex + 1));
            if (high < 0 || low < 0) {
                return AddResult.INVALID;
            }
            decoded[byteIndex] = (byte) ((high << 4) | low);
            nonZero |= decoded[byteIndex] != 0;
        }
        if (!nonZero) {
            return AddResult.INVALID;
        }
        return ids.add(decodedRef) >= 0 ? AddResult.ADDED : AddResult.DUPLICATE;
    }

    private static int decodeNibble(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        return -1;
    }

    int size() {
        return ids.size();
    }

    /** Re-encodes one bounded partition for use in a keyword terms query. */
    List<String> partition(int offset, int limit) {
        if (offset < 0 || limit < 1 || offset > ids.size()) {
            throw new IllegalArgumentException(
                "Invalid trace-ID partition: offset=" + offset + ", limit=" + limit);
        }
        int end = Math.min(ids.size(), offset + limit);
        List<String> result = new ArrayList<>(end - offset);
        BytesRef value = new BytesRef();
        for (int id = offset; id < end; id++) {
            ids.get(id, value);
            result.add(HEX.formatHex(value.bytes, value.offset, value.offset + value.length));
        }
        return result;
    }
}
