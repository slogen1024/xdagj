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
package io.xdag.chain.anchor;

import io.xdag.chain.ext.ExtCodec;

/**
 * {@code 0x04 ‖ attester20 ‖ chain20} value. {@code unbondAmount == 0} means no pending unbond.
 * {@code unbondHeight} is the main height that filed the request; the payout runs at
 * {@code unbondHeight + W}. Amounts are non-negative nano values.
 *
 * <pre>
 * amount u64 LE ‖ unbondHeight u64 LE ‖ unbondAmount u64 LE
 * </pre>
 */
public record BondAccount(long amount, long unbondHeight, long unbondAmount) {

    public static final int BYTES = 24;

    public byte[] encode() {
        byte[] raw = new byte[BYTES];
        ExtCodec.putU64(raw, 0, amount);
        ExtCodec.putU64(raw, 8, unbondHeight);
        ExtCodec.putU64(raw, 16, unbondAmount);
        return raw;
    }

    public static BondAccount decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        return new BondAccount(ExtCodec.u64(raw, 0), ExtCodec.u64(raw, 8), ExtCodec.u64(raw, 16));
    }

    public boolean pending() {
        return unbondAmount != 0;
    }
}
