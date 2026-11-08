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

import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ExtCodec;

/**
 * The 0x06 value. The first {@link AnchorRecord#BYTES} are the SP2 claim prefix. The tail is what
 * finality, void and arbitration read. {@code anchorHash} and {@code parent} are link addresses
 * after {@code Address.parse} (8 leading zero bytes).
 */
public record AnchorBody(AnchorRecord.Status status, Bytes32 anchorHash, Bytes32 outboxMapRoot, Bytes32 stateRoot,
        long prevSeq, long inputCount, long segmentCount, long confirmHeight, Bytes32 parent, byte[] attester,
        Bytes32 commitmentHead) {

    public static final int BYTES = AnchorRecord.BYTES + 32 + 8 + 4 + 4 + 8 + 32 + 20 + 32;

    public AnchorBody {
        attester = attester.clone();
        if (attester.length != 20) {
            throw new IllegalArgumentException("attester");
        }
    }

    public byte[] encode() {
        byte[] raw = new byte[BYTES];
        System.arraycopy(new AnchorRecord(status, anchorHash, outboxMapRoot).encode(), 0, raw, 0, AnchorRecord.BYTES);
        int o = AnchorRecord.BYTES;
        System.arraycopy(stateRoot.toArray(), 0, raw, o, 32);
        o += 32;
        ExtCodec.putU64(raw, o, prevSeq);
        o += 8;
        ExtCodec.putU32(raw, o, inputCount);
        o += 4;
        ExtCodec.putU32(raw, o, segmentCount);
        o += 4;
        ExtCodec.putU64(raw, o, confirmHeight);
        o += 8;
        System.arraycopy(parent.toArray(), 0, raw, o, 32);
        o += 32;
        System.arraycopy(attester, 0, raw, o, 20);
        o += 20;
        System.arraycopy(commitmentHead.toArray(), 0, raw, o, 32);
        return raw;
    }

    public static AnchorBody decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        AnchorRecord prefix = AnchorRecord.decode(raw);
        if (prefix == null) {
            return null;
        }
        int o = AnchorRecord.BYTES;
        Bytes32 state = Bytes32.wrap(Arrays.copyOfRange(raw, o, o + 32));
        o += 32;
        long prev = ExtCodec.u64(raw, o);
        o += 8;
        long inputs = ExtCodec.u32(raw, o);
        o += 4;
        long segments = ExtCodec.u32(raw, o);
        o += 4;
        long confirm = ExtCodec.u64(raw, o);
        o += 8;
        Bytes32 parent = Bytes32.wrap(Arrays.copyOfRange(raw, o, o + 32));
        o += 32;
        byte[] attester = Arrays.copyOfRange(raw, o, o + 20);
        o += 20;
        Bytes32 commitment = Bytes32.wrap(Arrays.copyOfRange(raw, o, o + 32));
        return new AnchorBody(prefix.status(), prefix.anchorHash(), prefix.outboxMapRoot(), state, prev, inputs,
                segments, confirm, parent, attester, commitment);
    }

    public AnchorBody withStatus(AnchorRecord.Status status) {
        return new AnchorBody(status, anchorHash, outboxMapRoot, stateRoot, prevSeq, inputCount, segmentCount,
                confirmHeight, parent, attester, commitmentHead);
    }

    /** Same truncation as {@code Block.getHashLow}: 8 zero bytes then {@code hash[8..32]}. */
    public static Bytes32 hashLow(Bytes32 hash) {
        byte[] raw = new byte[32];
        System.arraycopy(hash.toArray(), 8, raw, 8, 24);
        return Bytes32.wrap(raw);
    }
}
