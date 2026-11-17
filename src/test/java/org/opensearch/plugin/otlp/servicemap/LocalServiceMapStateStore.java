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

import java.util.HashMap;
import java.util.Map;

/** In-memory service-map state used by unit tests. */
final class LocalServiceMapStateStore implements ServiceMapStateStore {

    private final Map<Scope, Cursor> cursors = new HashMap<>();

    @Override
    public Cursor loadCursor(Scope scope) {
        return cursors.get(scope);
    }

    @Override
    public void commit(Scope scope, Cursor cursor) {
        cursors.put(scope, cursor);
    }
}
