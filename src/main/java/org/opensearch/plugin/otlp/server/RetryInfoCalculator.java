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
package org.opensearch.plugin.otlp.server;

import com.google.rpc.RetryInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class RetryInfoCalculator {

    private final Duration minimumDelay;
    private final Duration maximumDelay;

    private final AtomicReference<Instant> lastTimeCalled;
    private final AtomicReference<Duration> nextDelay;

    public RetryInfoCalculator(Duration minimumDelay, Duration maximumDelay) {
        this.minimumDelay = minimumDelay;
        this.maximumDelay = maximumDelay;
        // Treat a first exception shortly after startup as normal — no backoff yet.
        this.lastTimeCalled = new AtomicReference<>(Instant.now().minus(maximumDelay));
        this.nextDelay = new AtomicReference<>(minimumDelay);
    }

    RetryInfo createRetryInfo() {
        Instant now = Instant.now();
        if (lastTimeCalled.getAndSet(now).isBefore(now.minus(nextDelay.get()))) {
            nextDelay.set(minimumDelay);
            return buildRetryInfo(minimumDelay);
        }
        Duration delay = nextDelay.getAndUpdate(d -> minDuration(maximumDelay, d.multipliedBy(2)));
        return buildRetryInfo(delay);
    }

    private static RetryInfo buildRetryInfo(Duration delay) {
        return RetryInfo.newBuilder()
            .setRetryDelay(com.google.protobuf.Duration.newBuilder()
                .setSeconds(delay.getSeconds())
                .setNanos(delay.getNano()))
            .build();
    }

    private static Duration minDuration(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
