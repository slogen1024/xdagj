/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.net;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Per-ref exponential backoff for the periodic EVM blob re-request (G2-T2). Given the set of refs a
 * peer's handler still lacks and a monotonic tick counter (one tick per {@code EVM_BLOB_RETRY_SECONDS}
 * scheduler fire), {@link #selectDue} returns the subset to re-request now and advances each due ref's
 * backoff. Refs no longer missing are pruned (so a ref that returns is fresh again). The interval
 * doubles per attempt up to {@link #MAX_INTERVAL_TICKS}; attempts are NEVER capped -- a committed-
 * include height genuinely needs its blob, so we keep retrying at the max interval, never give up.
 *
 * <p>Not thread-safe: a handler owns one instance touched only by its single-threaded retry task.
 */
final class BlobRetryBackoff {

    static final int MAX_INTERVAL_TICKS = 16;

    private final Map<Bytes32, Integer> nextEligibleTick = new HashMap<>();
    private final Map<Bytes32, Integer> attempts = new HashMap<>();

    /**
     * The subset of {@code missing} due for re-request at {@code tick}. Advances the backoff of each
     * returned ref and prunes any tracked ref not in {@code missing}.
     */
    Set<Bytes32> selectDue(Set<Bytes32> missing, int tick) {
        nextEligibleTick.keySet().retainAll(missing);
        attempts.keySet().retainAll(missing);
        Set<Bytes32> due = new HashSet<>();
        for (Bytes32 ref : missing) {
            int eligible = nextEligibleTick.getOrDefault(ref, Integer.MIN_VALUE);
            if (tick >= eligible) {
                due.add(ref);
                int a = attempts.getOrDefault(ref, 0);
                int interval = Math.min(1 << Math.min(a, 20), MAX_INTERVAL_TICKS);
                nextEligibleTick.put(ref, tick + interval);
                attempts.put(ref, a + 1);
            }
        }
        return due;
    }
}
