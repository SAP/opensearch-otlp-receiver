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
import org.opensearch.test.OpenSearchTestCase;

import java.time.Duration;

public class RetryInfoCalculatorTests extends OpenSearchTestCase {

    // ── first call always returns minimum delay ────────────────────────────────

    public void testFirstCallReturnsMinimumDelay() {
        RetryInfoCalculator calc = new RetryInfoCalculator(Duration.ofMillis(100), Duration.ofSeconds(2));

        RetryInfo info = calc.createRetryInfo();

        assertEquals("first call delay is minimum", 0, info.getRetryDelay().getSeconds());
        assertEquals("first call delay nanos", 100_000_000, info.getRetryDelay().getNanos());
    }

    // ── consecutive calls within the current delay window double the delay ─────

    public void testConsecutiveCallsDoubleDelay() {
        RetryInfoCalculator calc = new RetryInfoCalculator(Duration.ofMillis(100), Duration.ofSeconds(10));

        RetryInfo first = calc.createRetryInfo();
        RetryInfo second = calc.createRetryInfo();  // called immediately — within the 100 ms window
        RetryInfo third = calc.createRetryInfo();   // within the 200 ms window

        long firstNanos = toNanos(first);
        long secondNanos = toNanos(second);
        long thirdNanos = toNanos(third);

        assertTrue("second delay >= first delay", secondNanos >= firstNanos);
        assertTrue("third delay >= second delay", thirdNanos >= secondNanos);
        assertTrue("delay is growing (at least one doubling occurred)", thirdNanos > firstNanos);
    }

    // ── delay is capped at maximum ─────────────────────────────────────────────

    public void testDelayCappedAtMaximum() {
        Duration max = Duration.ofMillis(500);
        RetryInfoCalculator calc = new RetryInfoCalculator(Duration.ofMillis(100), max);

        // Drive many consecutive calls to push past the cap
        RetryInfo last = null;
        for (int i = 0; i < 10; i++) {
            last = calc.createRetryInfo();
        }

        long lastNanos = toNanos(last);
        assertTrue("delay never exceeds maximum", lastNanos <= max.toNanos());
    }

    // ── long idle period resets delay to minimum ───────────────────────────────

    public void testIdleResetsDelayToMinimum() throws InterruptedException {
        Duration min = Duration.ofMillis(50);
        // Use a very short max so the backoff ceiling is low and we can reach it quickly
        RetryInfoCalculator calc = new RetryInfoCalculator(min, Duration.ofMillis(200));

        // Push delay up with consecutive calls
        for (int i = 0; i < 5; i++) {
            calc.createRetryInfo();
        }

        // Wait longer than the current maximum delay to simulate a quiet period
        Thread.sleep(250);

        RetryInfo afterIdle = calc.createRetryInfo();

        assertEquals("delay resets to minimum after idle", min.getNano(), toNanos(afterIdle));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static long toNanos(RetryInfo info) {
        return info.getRetryDelay().getSeconds() * 1_000_000_000L + info.getRetryDelay().getNanos();
    }
}
