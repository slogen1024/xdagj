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
package io.xdag.chain.fraud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Memoises a challenge verdict by the challenge block hash. Not consulted from {@code tryToConnect};
 * {@link io.xdag.chain.anchor.HeightTriggers} is the only caller.
 */
public final class VerdictCache {

    private final Map<Bytes32, Arbiter.Verdict> seen = new LinkedHashMap<>();

    public Arbiter.Verdict get(Bytes32 challenge, Supplier<Arbiter.Verdict> compute) {
        Arbiter.Verdict have = seen.get(challenge);
        if (have != null) {
            return have;
        }
        Arbiter.Verdict verdict = compute.get();
        seen.put(challenge, verdict);
        return verdict;
    }

    public int size() {
        return seen.size();
    }
}
