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
package io.xdag.chain.exec;

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.l1.ChainIds;
import io.xdag.crypto.hash.HashUtils;

/**
 * The system contract {@code 0x00..00}. Its user keys are {@code kind || addr20} for a balance and
 * for the in-flight sum, and a single byte for the fee pool. The SMT key is
 * {@code sha256(contract || userKey)}, the same rule as every other contract.
 */
public final class ChainLedger {

    public static final Bytes SYSTEM = ChainIds.ZERO_ADDRESS;
    public static final int MAX_VALUE_BYTES = 64 * 1024;
    public static final int MAX_USER_KEY = 1024;

    static final byte BALANCE = 0x01;
    static final byte FEE = 0x02;
    static final byte INFLIGHT = 0x03;

    private ChainLedger() {
    }

    public static Bytes32 smtKey(Bytes contract, byte[] userKey) {
        return HashUtils.sha256(Bytes.concatenate(contract, Bytes.wrap(userKey)));
    }

    public static Bytes slot(Bytes contract, byte[] userKey) {
        return Bytes.concatenate(contract, Bytes.wrap(userKey));
    }

    static byte[] balanceKey(byte[] address) {
        byte[] key = new byte[1 + 20];
        key[0] = BALANCE;
        System.arraycopy(address20(address), 0, key, 1, 20);
        return key;
    }

    static byte[] feeKey() {
        return new byte[] {FEE};
    }

    static byte[] inflightKey(byte[] address) {
        byte[] key = new byte[1 + 20];
        key[0] = INFLIGHT;
        System.arraycopy(address20(address), 0, key, 1, 20);
        return key;
    }

    public static byte[] address20(byte[] raw) {
        byte[] out = new byte[20];
        if (raw != null) {
            System.arraycopy(raw, 0, out, 0, Math.min(20, raw.length));
        }
        return out;
    }

    static long decode(byte[] raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw.length != Long.BYTES) {
            throw new IllegalStateException("balance width");
        }
        return ByteBuffer.wrap(raw).getLong();
    }

    static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("balance");
        }
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
