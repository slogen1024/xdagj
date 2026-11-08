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

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ExtResult;
import io.xdag.config.Config;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.ECKeyPair;

/**
 * Picks the first commitment the local execution does not share with an anchor and builds
 * the CHALLENGE block. The witness bytes are the caller's (E1 when the input hash differs,
 * E2 when the input matches and the post-state does not). Filing the block is the caller's
 * import; this class does not slash.
 */
public final class Challenger {

    private Challenger() {
    }

    /** {@code -1} when the lists are equal. A length mismatch returns the first missing index. */
    public static int diverge(List<Bytes32> local, List<Bytes32> claimed) {
        int n = Math.min(local.size(), claimed.size());
        for (int i = 0; i < n; i++) {
            if (!local.get(i).equals(claimed.get(i))) {
                return i;
            }
        }
        return local.size() == claimed.size() ? -1 : n;
    }

    public static ExtResult<ChainBlockBuilder.Built> file(Config config, long timestamp, ECKeyPair who, UInt64 nonce,
            XAmount headerFee, long k, long deposit, Bytes32 anchor, byte[] witness) {
        return ChainBlockBuilder.challenge(config, timestamp, who, nonce, headerFee, k, deposit, anchor,
                Bytes.wrap(witness));
    }
}
