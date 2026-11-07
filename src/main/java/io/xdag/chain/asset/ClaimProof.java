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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.ext.ExtCodec;

/**
 * CLAIM proof-chain payload, version 1. Lengths are little-endian. The 40-byte outbox value is the
 * SMT leaf as stored: {@code mmrRoot 32 ‖ size u64 BE}. The MMR proof is {@link Mmr.Proof#encode()}
 * (big-endian) and the entry is the raw {@link OutboxEntry} body.
 *
 * <pre>
 * version u8 = 1 ‖ dst 20 ‖ present u8 = 1 ‖ valueLen u32 ‖ value ‖ sibling[256] ‖ mmr proof ‖ entryLen u32 ‖ entry
 * </pre>
 */
public final class ClaimProof {

    public static final int VERSION = 1;

    public record Body(byte[] dst, byte[] value, Bytes32[] siblings, Mmr.Proof mmr, byte[] entry) {
    }

    private ClaimProof() {
    }

    public static byte[] encode(byte[] dst, SparseMerkleTree.Proof smt, Mmr.Proof mmr, byte[] entry) {
        if (dst == null || dst.length != 20 || smt == null || !smt.present() || mmr == null || entry == null) {
            throw new IllegalArgumentException("claim proof");
        }
        if (smt.value().length != OutboxMap.VALUE_BYTES) {
            throw new IllegalArgumentException("outbox value");
        }
        byte[] mmrBytes = mmr.encode();
        byte[] raw = new byte[1 + 20 + 1 + Integer.BYTES + OutboxMap.VALUE_BYTES
                + SparseMerkleTree.DEPTH * Bytes32.SIZE + mmrBytes.length + Integer.BYTES + entry.length];
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.put((byte) VERSION);
        buf.put(dst);
        buf.put((byte) 1);
        byte[] len = new byte[4];
        ExtCodec.putU32(len, 0, OutboxMap.VALUE_BYTES);
        buf.put(len);
        buf.put(smt.value());
        for (Bytes32 sibling : smt.siblings()) {
            buf.put(sibling.toArray());
        }
        buf.put(mmrBytes);
        ExtCodec.putU32(len, 0, entry.length);
        buf.put(len);
        buf.put(entry);
        return raw;
    }

    public static Body decode(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        try {
            if (buf.remaining() < 1 + 20 + 1 + Integer.BYTES || (buf.get() & 0xff) != VERSION) {
                return null;
            }
            byte[] dst = new byte[20];
            buf.get(dst);
            if ((buf.get() & 0xff) != 1) {
                return null;
            }
            byte[] len = new byte[4];
            buf.get(len);
            int valueLen = (int) ExtCodec.u32(len, 0);
            if (valueLen != OutboxMap.VALUE_BYTES || buf.remaining() < valueLen) {
                return null;
            }
            byte[] value = new byte[valueLen];
            buf.get(value);
            if (buf.remaining() < SparseMerkleTree.DEPTH * Bytes32.SIZE) {
                return null;
            }
            Bytes32[] siblings = new Bytes32[SparseMerkleTree.DEPTH];
            byte[] one = new byte[Bytes32.SIZE];
            for (int i = 0; i < siblings.length; i++) {
                buf.get(one);
                siblings[i] = Bytes32.wrap(one.clone());
            }
            Mmr.Proof mmr = Mmr.Proof.decode(buf);
            if (buf.remaining() < Integer.BYTES) {
                return null;
            }
            buf.get(len);
            int entryLen = (int) ExtCodec.u32(len, 0);
            if (entryLen < OutboxEntry.HEAD || entryLen != buf.remaining()) {
                return null;
            }
            byte[] entry = new byte[entryLen];
            buf.get(entry);
            return new Body(dst, value, siblings, mmr, entry);
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            return null;
        }
    }
}
