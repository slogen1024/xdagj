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
package io.xdag.chain.asset;

import org.apache.tuweni.bytes.Bytes;

/**
 * Builds the outbox bodies a CLAIM later proves. {@code ChainExecutor} appends the returned bytes
 * to that destination's MMR and stores {@code mmrRoot ‖ size} in the outbox map. There is no second
 * encoder.
 */
public final class WithdrawOutbox {

    private static final byte[] L1 = new byte[20];

    private WithdrawOutbox() {
    }

    /** {@code withdraw}: destination is L1 (20 zero bytes), {@code recipient} sits in the contract field. */
    public static byte[] withdraw(Bytes src, byte[] sender, byte[] recipient, long amount, long height, long index) {
        return new OutboxEntry(addr(src), L1, index, sender, recipient, 0, amount, height, new byte[0]).encode();
    }

    /** {@code send}: destination is {@code dst}, the callee is {@code contract}. */
    public static byte[] send(Bytes src, byte[] dst, byte[] sender, byte[] contract, int selector, byte[] args,
            long value, long height, long index) {
        return new OutboxEntry(addr(src), dst, index, sender, contract, selector, value, height, args).encode();
    }

    private static byte[] addr(Bytes src) {
        return src == null ? null : src.toArray();
    }
}
