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

import java.util.concurrent.atomic.AtomicBoolean;

class Cancellable {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    void reset() {
        cancelled.set(false);
    }

    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    static final class CancelledException extends Exception {
        CancelledException() { super(null, null, true, false); }
    }
}
