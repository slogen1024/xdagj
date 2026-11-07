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

import java.util.Arrays;

import io.xdag.chain.ext.ExtCodec;

/**
 * CHAIN_L1 {@code 0x10 ‖ blockHash(32)} value, included in the snapshot hash. One record per claim
 * block. Unwind reads this and does not decode the block again.
 *
 * <pre>
 * from20 ‖ to20 ‖ amount u64 LE ‖ src20 ‖ dst20 ‖ index u64 LE
 * </pre>
 *
 * {@code from} is the source vault, {@code to} is the recipient, {@code dst} is the outbox
 * destination (20 zero bytes for an L1 withdraw).
 */
public final class SettlementJournal {

    public static final int BYTES = 20 + 20 + Long.BYTES + 20 + 20 + Long.BYTES;

    public record Record(byte[] from, byte[] to, long amount, byte[] src, byte[] dst, long index) {
    }

    private SettlementJournal() {
    }

    public static byte[] encode(byte[] from, byte[] to, long amount, byte[] src, byte[] dst, long index) {
        byte[] raw = new byte[BYTES];
        System.arraycopy(from, 0, raw, 0, 20);
        System.arraycopy(to, 0, raw, 20, 20);
        ExtCodec.putU64(raw, 40, amount);
        System.arraycopy(src, 0, raw, 48, 20);
        System.arraycopy(dst, 0, raw, 68, 20);
        ExtCodec.putU64(raw, 88, index);
        return raw;
    }

    public static Record decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        return new Record(Arrays.copyOfRange(raw, 0, 20), Arrays.copyOfRange(raw, 20, 40), ExtCodec.u64(raw, 40),
                Arrays.copyOfRange(raw, 48, 68), Arrays.copyOfRange(raw, 68, 88), ExtCodec.u64(raw, 88));
    }
}
