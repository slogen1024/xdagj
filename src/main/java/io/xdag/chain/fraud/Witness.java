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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.p2p.ProofBodies;

/**
 * Witness chain payload. E1 carries the commitment opening. E3 carries one outbox-map proof.
 * E2 version 1 carries the pre-state rows, the call, and (when {@code k > 0}) the previous opening.
 * {@link #e2()} is the one-byte body: it does not decode and the replay fails closed.
 *
 * <pre>
 * type u8 (1 E1, 2 E2, 3 E3)
 * E1: opening ({@link ProofBodies#encodeCommitment})
 * E2: version u8=1 ‖ rowCount u32 ‖ (key 32 ‖ blobLen u32 ‖ blob)* ‖ codeLen u32 ‖ code
 *     ‖ sender 20 ‖ contract 20 ‖ value u64 ‖ gasLimit u64 ‖ gasPrice u64 ‖ selector u32
 *     ‖ argsLen u32 ‖ args ‖ timestamp u64 ‖ inputHash 32 ‖ prevOpeningLen u32 ‖ prevOpening
 * E3: src 20 ‖ srcSeq u64 LE ‖ dst 20 ‖ declaredTo u64 LE ‖ present u8 ‖ valueLen u32 LE ‖ value ‖ sibling[256]
 * </pre>
 */
public final class Witness {

    public static final int E1 = 1;
    public static final int E2 = 2;
    public static final int E3 = 3;

    public record E1Body(ProofBodies.Opened opening) {
    }

    public record Row(Bytes32 key, byte[] blob) {
        public Row {
            blob = blob.clone();
        }
    }

    public record E2Body(List<Row> rows, byte[] code, byte[] sender, byte[] contract, long value, long gasLimit,
            long gasPrice, int selector, byte[] args, long timestamp, Bytes32 inputHash, byte[] prevOpening) {
        public E2Body {
            if (sender.length != 20 || contract.length != 20) {
                throw new IllegalArgumentException("address");
            }
            rows = List.copyOf(rows);
            code = code.clone();
            sender = sender.clone();
            contract = contract.clone();
            args = args.clone();
            prevOpening = prevOpening.clone();
        }
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

    /** One byte. {@link #decodeE2} rejects it. */
    public static byte[] e2() {
        return new byte[]{E2};
    }

    public static byte[] encodeE2(E2Body body) {
        int size = 1 + 1 + 4;
        for (Row row : body.rows()) {
            size += 32 + 4 + row.blob().length;
        }
        size += 4 + body.code().length + 20 + 20 + 8 + 8 + 8 + 4 + 4 + body.args().length + 8 + 32 + 4
                + body.prevOpening().length;
        byte[] raw = new byte[size];
        raw[0] = E2;
        raw[1] = 1;
        ExtCodec.putU32(raw, 2, body.rows().size());
        int o = 6;
        for (Row row : body.rows()) {
            System.arraycopy(row.key().toArray(), 0, raw, o, 32);
            ExtCodec.putU32(raw, o + 32, row.blob().length);
            System.arraycopy(row.blob(), 0, raw, o + 36, row.blob().length);
            o += 36 + row.blob().length;
        }
        ExtCodec.putU32(raw, o, body.code().length);
        System.arraycopy(body.code(), 0, raw, o + 4, body.code().length);
        o += 4 + body.code().length;
        System.arraycopy(body.sender(), 0, raw, o, 20);
        System.arraycopy(body.contract(), 0, raw, o + 20, 20);
        ExtCodec.putU64(raw, o + 40, body.value());
        ExtCodec.putU64(raw, o + 48, body.gasLimit());
        ExtCodec.putU64(raw, o + 56, body.gasPrice());
        ExtCodec.putU32(raw, o + 64, body.selector());
        ExtCodec.putU32(raw, o + 68, body.args().length);
        System.arraycopy(body.args(), 0, raw, o + 72, body.args().length);
        o += 72 + body.args().length;
        ExtCodec.putU64(raw, o, body.timestamp());
        System.arraycopy(body.inputHash().toArray(), 0, raw, o + 8, 32);
        ExtCodec.putU32(raw, o + 40, body.prevOpening().length);
        System.arraycopy(body.prevOpening(), 0, raw, o + 44, body.prevOpening().length);
        return raw;
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

    public static E2Body decodeE2(byte[] raw) {
        if (raw == null || raw.length < 6 || raw[0] != E2 || raw[1] != 1) {
            return null;
        }
        int rows = (int) ExtCodec.u32(raw, 2);
        if (rows < 0) {
            return null;
        }
        int o = 6;
        List<Row> list = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            if (o + 36 > raw.length) {
                return null;
            }
            Bytes32 key = Bytes32.wrap(Arrays.copyOfRange(raw, o, o + 32));
            int blobLen = (int) ExtCodec.u32(raw, o + 32);
            if (blobLen < 0 || o + 36 + blobLen > raw.length) {
                return null;
            }
            list.add(new Row(key, Arrays.copyOfRange(raw, o + 36, o + 36 + blobLen)));
            o += 36 + blobLen;
        }
        if (o + 4 > raw.length) {
            return null;
        }
        int codeLen = (int) ExtCodec.u32(raw, o);
        if (codeLen < 0 || o + 4 + codeLen + 20 + 20 + 8 + 8 + 8 + 4 + 4 > raw.length) {
            return null;
        }
        byte[] code = Arrays.copyOfRange(raw, o + 4, o + 4 + codeLen);
        o += 4 + codeLen;
        byte[] sender = Arrays.copyOfRange(raw, o, o + 20);
        byte[] contract = Arrays.copyOfRange(raw, o + 20, o + 40);
        long value = ExtCodec.u64(raw, o + 40);
        long gasLimit = ExtCodec.u64(raw, o + 48);
        long gasPrice = ExtCodec.u64(raw, o + 56);
        int selector = (int) ExtCodec.u32(raw, o + 64);
        int argsLen = (int) ExtCodec.u32(raw, o + 68);
        if (argsLen < 0 || o + 72 + argsLen + 8 + 32 + 4 > raw.length) {
            return null;
        }
        byte[] args = Arrays.copyOfRange(raw, o + 72, o + 72 + argsLen);
        o += 72 + argsLen;
        long timestamp = ExtCodec.u64(raw, o);
        Bytes32 inputHash = Bytes32.wrap(Arrays.copyOfRange(raw, o + 8, o + 40));
        int prevLen = (int) ExtCodec.u32(raw, o + 40);
        if (prevLen < 0 || o + 44 + prevLen != raw.length) {
            return null;
        }
        byte[] prev = Arrays.copyOfRange(raw, o + 44, o + 44 + prevLen);
        return new E2Body(list, code, sender, contract, value, gasLimit, gasPrice, selector, args, timestamp, inputHash,
                prev);
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
