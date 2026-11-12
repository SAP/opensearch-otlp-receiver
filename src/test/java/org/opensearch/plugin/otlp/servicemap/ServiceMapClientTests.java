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

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.plugin.otlp.common.Cancellable;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.support.AbstractClient;

import java.util.concurrent.ExecutionException;

public class ServiceMapClientTests extends OpenSearchTestCase {

    public void testCompletesFutureFromClientResponse() throws Exception {
        BulkResponse expected = new BulkResponse(new BulkItemResponse[0], 0);
        StubClient delegate = new StubClient(expected, null);
        ServiceMapClient client = new ServiceMapClient(delegate);

        assertSame(expected, client.bulk(new BulkRequest()).get());
        assertEquals(1, delegate.requestCount);
    }

    public void testCompletesFutureExceptionallyFromClientFailure() {
        RuntimeException expected = new RuntimeException("request failed");
        StubClient delegate = new StubClient(null, expected);
        ServiceMapClient client = new ServiceMapClient(delegate);

        ExecutionException failure = expectThrows(
            ExecutionException.class, () -> client.bulk(new BulkRequest()).get());

        assertSame(expected, failure.getCause());
        assertEquals(1, delegate.requestCount);
    }

    public void testDoesNotDispatchAfterCancellation() {
        StubClient delegate = new StubClient(null, null);
        ServiceMapClient client = new ServiceMapClient(delegate, () -> true);

        ExecutionException failure = expectThrows(
            ExecutionException.class, () -> client.bulk(new BulkRequest()).get());

        assertTrue(failure.getCause() instanceof Cancellable.CancelledException);
        assertEquals(0, delegate.requestCount);
    }

    private static final class StubClient extends AbstractClient {
        private final ActionResponse response;
        private final RuntimeException failure;
        private int requestCount;

        private StubClient(ActionResponse response, RuntimeException failure) {
            super(org.opensearch.common.settings.Settings.EMPTY, null);
            this.response = response;
            this.failure = failure;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(
                ActionType<Response> action, Request request, ActionListener<Response> listener) {
            requestCount++;
            if (failure != null) {
                listener.onFailure(failure);
            } else {
                listener.onResponse((Response) response);
            }
        }

        @Override
        public void close() {}
    }
}
