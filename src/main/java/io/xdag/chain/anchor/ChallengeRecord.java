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

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.ext.ExtCodec;

/**
 * {@code 0x0A ‖ challenge block hash}. Status: pending 0, success 1, fail 2, invalid 3.
 * Invalid is an E2 witness this slice does not re-execute: the deposit is returned and nobody
 * is slashed. {@code oldHeadSeq}/{@code oldHeadHash} are the canonical head before a successful
 * void. {@code slashed} is the bond chain (the chain id, or {@link BondVault#GLOBAL}) whose
 * amount was zeroed; 20 zero bytes when nothing was slashed.
 */
public record ChallengeRecord(int status, long index, long deposit, byte[] challenger, byte[] attester, byte[] chain,
        long seq, long oldHeadSeq, Bytes32 oldHeadHash, byte[] slashed) {

    public static final int PENDING = 0;
    public static final int SUCCESS = 1;
    public static final int FAIL = 2;
    public static final int INVALID = 3;
    public static final int BYTES = 1 + 4 + 8 + 20 + 20 + 20 + 8 + 8 + 32 + 20;

    public ChallengeRecord {
        challenger = challenger.clone();
        attester = attester.clone();
        chain = chain.clone();
        slashed = slashed.clone();
        if (slashed.length != 20) {
            throw new IllegalArgumentException("slashed");
        }
    }

    public byte[] encode() {
        byte[] raw = new byte[BYTES];
        raw[0] = (byte) status;
        ExtCodec.putU32(raw, 1, index);
        ExtCodec.putU64(raw, 5, deposit);
        System.arraycopy(challenger, 0, raw, 13, 20);
        System.arraycopy(attester, 0, raw, 33, 20);
        System.arraycopy(chain, 0, raw, 53, 20);
        ExtCodec.putU64(raw, 73, seq);
        ExtCodec.putU64(raw, 81, oldHeadSeq);
        System.arraycopy(oldHeadHash.toArray(), 0, raw, 89, 32);
        System.arraycopy(slashed, 0, raw, 121, 20);
        return raw;
    }

    public static ChallengeRecord decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        return new ChallengeRecord(raw[0] & 0xff, ExtCodec.u32(raw, 1), ExtCodec.u64(raw, 5),
                Arrays.copyOfRange(raw, 13, 33), Arrays.copyOfRange(raw, 33, 53), Arrays.copyOfRange(raw, 53, 73),
                ExtCodec.u64(raw, 73), ExtCodec.u64(raw, 81), Bytes32.wrap(Arrays.copyOfRange(raw, 89, 121)),
                Arrays.copyOfRange(raw, 121, 141));
    }
}
