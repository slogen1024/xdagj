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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.CommitmentChain;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.InputRecord;

/**
 * Where input {@code k} sits in an anchor's commitment. Segments whose delivery height is outside
 * {@code (prevSeq, seq]} are a prefix and are not calls. Inside the range each height is rewards,
 * then messages delivered at that height, then calls.
 */
final class InputIndex {

    /** A resolved call. {@code hash} is null when the {@code 0x0C} row is missing; the height still stands. */
    record Call(Bytes32 hash, long height) {
    }

    private InputIndex() {
    }

    static Call locate(ChainL1Store store, byte[] chain, long prevSeq, long seq, CommitmentChain.Body commitment,
            long k) {
        long outside = 0L;
        for (CommitmentChain.Segment segment : commitment.segments()) {
            long span = span(segment);
            if (span < 0L) {
                return null;
            }
            if (!inRange(deliver(store, chain, segment), prevSeq, seq)) {
                try {
                    outside = Math.addExact(outside, span);
                } catch (ArithmeticException e) {
                    return null;
                }
            }
        }
        if (Long.compareUnsigned(k, outside) < 0) {
            return null;
        }
        long left = k - outside;
        long h = prevSeq;
        Bytes id = Bytes.wrap(chain);
        while (Long.compareUnsigned(h, seq) < 0) {
            if (h == Long.MAX_VALUE) {
                return null;
            }
            h++;
            int rewards = RewardInputs.at(store, chain, h).size();
            if (Long.compareUnsigned(left, rewards) < 0) {
                return null;
            }
            left -= rewards;
            long messages = messagesAt(store, chain, commitment, h);
            if (messages < 0L) {
                return null;
            }
            if (Long.compareUnsigned(left, messages) < 0) {
                return null;
            }
            left -= messages;
            long n = store.getCallCount(id, h);
            if (Long.compareUnsigned(left, n) < 0) {
                InputRecord input = store.getInput(id, h, left);
                return new Call(input == null ? null : input.blockHash(), h);
            }
            left -= n;
        }
        return null;
    }

    private static long messagesAt(ChainL1Store store, byte[] chain, CommitmentChain.Body commitment, long height) {
        long n = 0L;
        for (CommitmentChain.Segment segment : commitment.segments()) {
            long span = span(segment);
            if (span < 0L) {
                return -1L;
            }
            if (deliver(store, chain, segment) != height) {
                continue;
            }
            try {
                n = Math.addExact(n, span);
            } catch (ArithmeticException e) {
                return -1L;
            }
        }
        return n;
    }

    private static long span(CommitmentChain.Segment segment) {
        if (Long.compareUnsigned(segment.to(), segment.from()) < 0) {
            return -1L;
        }
        return segment.to() - segment.from();
    }

    /** Delivery height, or {@link Long#MIN_VALUE} when the source anchor is not on L1. */
    private static long deliver(ChainL1Store store, byte[] dest, CommitmentChain.Segment segment) {
        ChainRecord record = store.getChain(Bytes.wrap(dest));
        long delay = record == null ? 0L : record.deliveryDelayD();
        AnchorBody src = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(segment.src()), segment.srcSeq()));
        if (src == null) {
            return Long.MIN_VALUE;
        }
        long ready = src.confirmHeight() + delay;
        if (Long.compareUnsigned(ready, src.confirmHeight()) < 0) {
            return Long.MIN_VALUE;
        }
        return ready;
    }

    private static boolean inRange(long deliver, long prevSeq, long seq) {
        return Long.compareUnsigned(deliver, prevSeq) > 0 && Long.compareUnsigned(deliver, seq) <= 0;
    }
}
