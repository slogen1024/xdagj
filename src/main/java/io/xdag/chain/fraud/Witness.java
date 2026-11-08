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

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.p2p.ProofBodies;

/**
 * Witness chain payload. E2 is recognised and then refused by the arbiter: re-execution is not in
 * this slice. E1 carries the commitment opening. E3 carries one outbox-map proof.
 *
 * <pre>
 * type u8 (1 E1, 2 E2, 3 E3)
 * E1: opening ({@link ProofBodies#encodeCommitment})
 * E3: src 20 ‖ srcSeq u64 LE ‖ dst 20 ‖ declaredTo u64 LE ‖ present u8 ‖ valueLen u32 LE ‖ value ‖ sibling[256]
 * </pre>
 */
public final class Witness {

    public static final int E1 = 1;
    public static final int E2 = 2;
    public static final int E3 = 3;

    public record E1Body(ProofBodies.Opened opening) {
    }

    public record E3Body(byte[] src, long srcSeq, byte[] dst, long declaredTo, byte[] value, Bytes32[] siblings) {
    }

    private Witness() {
    }

    public static byte[] e1(byte[] opening) {
        byte[] raw = new byte[1 + opening.length];
        raw[0] = E1;
        System.arraycopy(opening, 0, raw, 1, opening.length);
        return raw;
    }

    public static byte[] e2() {
        return new byte[]{E2};
    }

    public static byte[] e3(byte[] src, long srcSeq, byte[] dst, long declaredTo, boolean present, byte[] value,
            Bytes32[] siblings) {
        byte[] raw = new byte[1 + 20 + 8 + 20 + 8 + 1 + 4 + value.length + siblings.length * 32];
        raw[0] = E3;
        System.arraycopy(src, 0, raw, 1, 20);
        ExtCodec.putU64(raw, 21, srcSeq);
        System.arraycopy(dst, 0, raw, 29, 20);
        ExtCodec.putU64(raw, 49, declaredTo);
        raw[57] = (byte) (present ? 1 : 0);
        ExtCodec.putU32(raw, 58, value.length);
        System.arraycopy(value, 0, raw, 62, value.length);
        int o = 62 + value.length;
        for (Bytes32 sibling : siblings) {
            System.arraycopy(sibling.toArray(), 0, raw, o, 32);
            o += 32;
        }
        return raw;
    }

    public static int type(byte[] raw) {
        return raw == null || raw.length == 0 ? 0 : raw[0] & 0xff;
    }

    public static E1Body decodeE1(byte[] raw) {
        if (type(raw) != E1) {
            return null;
        }
        try {
            return new E1Body(ProofBodies.decodeCommitment(Arrays.copyOfRange(raw, 1, raw.length)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static E3Body decodeE3(byte[] raw) {
        if (type(raw) != E3 || raw.length < 62) {
            return null;
        }
        byte[] src = Arrays.copyOfRange(raw, 1, 21);
        long srcSeq = ExtCodec.u64(raw, 21);
        byte[] dst = Arrays.copyOfRange(raw, 29, 49);
        long declared = ExtCodec.u64(raw, 49);
        boolean present = raw[57] == 1;
        int valueLen = (int) ExtCodec.u32(raw, 58);
        if (valueLen < 0 || 62 + valueLen > raw.length) {
            return null;
        }
        byte[] value = Arrays.copyOfRange(raw, 62, 62 + valueLen);
        int sibBytes = raw.length - (62 + valueLen);
        if (sibBytes != SparseMerkleTree.DEPTH * 32 || !present && valueLen != 0) {
            return null;
        }
        Bytes32[] siblings = new Bytes32[SparseMerkleTree.DEPTH];
        for (int i = 0; i < siblings.length; i++) {
            siblings[i] = Bytes32.wrap(Arrays.copyOfRange(raw, 62 + valueLen + i * 32, 62 + valueLen + (i + 1) * 32));
        }
        return new E3Body(src, srcSeq, dst, declared, value, siblings);
    }
}
