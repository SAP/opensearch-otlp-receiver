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
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.plugin.otlp.common.Cancellable;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.internal.InternalSearchResponse;
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

    public void testSearchDisablesPartialResults() throws Exception {
        SearchResponse expected = searchResponse(false, false);
        StubClient delegate = new StubClient(expected, null);
        ServiceMapClient client = new ServiceMapClient(delegate);

        assertSame(expected, client.search(new SearchRequest("traces")).get());
        assertEquals(Boolean.FALSE,
            ((SearchRequest) delegate.lastRequest).allowPartialSearchResults());
    }

    public void testSearchRejectsTimedOutResponse() {
        StubClient delegate = new StubClient(searchResponse(true, false), null);
        ServiceMapClient client = new ServiceMapClient(delegate);

        ExecutionException failure = expectThrows(
            ExecutionException.class, () -> client.search(new SearchRequest("traces")).get());

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("Service-map search timed out", failure.getCause().getMessage());
    }

    public void testSearchRejectsFailedShards() {
        StubClient delegate = new StubClient(searchResponse(false, true), null);
        ServiceMapClient client = new ServiceMapClient(delegate);

        ExecutionException failure = expectThrows(
            ExecutionException.class, () -> client.search(new SearchRequest("traces")).get());

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(failure.getCause().getMessage(),
            failure.getCause().getMessage().contains("failedShards=1"));
    }

    private static SearchResponse searchResponse(boolean timedOut, boolean failedShard) {
        SearchHits searchHits = new SearchHits(new SearchHit[0], null, 1.0f);
        InternalSearchResponse internal = new InternalSearchResponse(
            searchHits, null, null, null, timedOut, false, 1);
        ShardSearchFailure[] failures = failedShard
            ? new ShardSearchFailure[]{
                new ShardSearchFailure(new IllegalStateException("shard unavailable"))
            }
            : ShardSearchFailure.EMPTY_ARRAY;
        return new SearchResponse(
            internal, null, 1, failedShard ? 0 : 1, 0, 0,
            failures, SearchResponse.Clusters.EMPTY);
    }

    private static final class StubClient extends AbstractClient {
        private final ActionResponse response;
        private final RuntimeException failure;
        private int requestCount;
        private ActionRequest lastRequest;

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
            lastRequest = request;
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
