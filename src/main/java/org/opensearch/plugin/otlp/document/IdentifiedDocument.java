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
package org.opensearch.plugin.otlp.document;

import org.opensearch.core.xcontent.ToXContent;

/**
 * A document that carries a pre-determined index ID.
 * BulkIndexer uses this to set IndexRequest.id() instead of letting
 * OpenSearch generate a random one.
 */
public interface IdentifiedDocument extends ToXContent {
    String documentId();
}
