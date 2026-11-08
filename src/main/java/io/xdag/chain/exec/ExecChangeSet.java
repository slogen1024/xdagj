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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes32;

/**
 * One seq of {@code CHAIN_EXEC}, enough to put that seq back. State values are the pre-images
 * ({@code null} when the key was created). Outbox rows are the entry count before the seq appended.
 * {@code prevSeq} is {@code -1} when the chain had no head. The executor keeps the last 128.
 */
final class ExecChangeSet {

    record MmrCut(Bytes32 dst, int oldSize) {
    }

    final long prevSeq;
    final Bytes32 prevRoot;
    final List<Pair<Bytes32, byte[]>> state;
    final List<MmrCut> mmr;
    final List<Bytes32> sized;
    final int openings;
    final List<Bytes32> receipts;

    ExecChangeSet(long prevSeq, Bytes32 prevRoot, List<Pair<Bytes32, byte[]>> state, List<MmrCut> mmr,
            List<Bytes32> sized, int openings, List<Bytes32> receipts) {
        this.prevSeq = prevSeq;
        this.prevRoot = prevRoot;
        this.state = state;
        this.mmr = mmr;
        this.sized = sized;
        this.openings = openings;
        this.receipts = receipts;
    }

    byte[] encode() {
        int size = 1 + Long.BYTES + Bytes32.SIZE + Integer.BYTES;
        for (Pair<Bytes32, byte[]> row : state) {
            size += Bytes32.SIZE + Integer.BYTES + (row.getValue() == null ? 0 : row.getValue().length);
        }
        size += Integer.BYTES + mmr.size() * (Bytes32.SIZE + Integer.BYTES);
        size += Integer.BYTES + sized.size() * Bytes32.SIZE;
        size += Integer.BYTES + Integer.BYTES + receipts.size() * Bytes32.SIZE;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 1);
        buf.putLong(prevSeq);
        buf.put(prevRoot.toArray());
        buf.putInt(state.size());
        for (Pair<Bytes32, byte[]> row : state) {
            buf.put(row.getKey().toArray());
            byte[] value = row.getValue();
            if (value == null || value.length == 0) {
                buf.putInt(-1);
            } else {
                buf.putInt(value.length);
                buf.put(value);
            }
        }
        buf.putInt(mmr.size());
        for (MmrCut cut : mmr) {
            buf.put(cut.dst().toArray());
            buf.putInt(cut.oldSize());
        }
        buf.putInt(sized.size());
        for (Bytes32 dst : sized) {
            buf.put(dst.toArray());
        }
        buf.putInt(openings);
        buf.putInt(receipts.size());
        for (Bytes32 hash : receipts) {
            buf.put(hash.toArray());
        }
        return buf.array();
    }

    static ExecChangeSet decode(byte[] raw) {
        if (raw == null || raw.length < 1 + Long.BYTES + Bytes32.SIZE + Integer.BYTES) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        if (buf.get() != 1) {
            return null;
        }
        long prevSeq = buf.getLong();
        byte[] root = new byte[Bytes32.SIZE];
        buf.get(root);
        int nState = buf.getInt();
        if (nState < 0) {
            return null;
        }
        List<Pair<Bytes32, byte[]>> state = new ArrayList<>(nState);
        for (int i = 0; i < nState; i++) {
            if (buf.remaining() < Bytes32.SIZE + Integer.BYTES) {
                return null;
            }
            byte[] key = new byte[Bytes32.SIZE];
            buf.get(key);
            int len = buf.getInt();
            if (len == -1) {
                state.add(Pair.of(Bytes32.wrap(key), null));
            } else if (len < 0 || len > buf.remaining()) {
                return null;
            } else {
                byte[] value = new byte[len];
                buf.get(value);
                state.add(Pair.of(Bytes32.wrap(key), value));
            }
        }
        if (buf.remaining() < Integer.BYTES) {
            return null;
        }
        int nMmr = buf.getInt();
        if (nMmr < 0) {
            return null;
        }
        List<MmrCut> mmr = new ArrayList<>(nMmr);
        for (int i = 0; i < nMmr; i++) {
            if (buf.remaining() < Bytes32.SIZE + Integer.BYTES) {
                return null;
            }
            byte[] dst = new byte[Bytes32.SIZE];
            buf.get(dst);
            int old = buf.getInt();
            if (old < 0) {
                return null;
            }
            mmr.add(new MmrCut(Bytes32.wrap(dst), old));
        }
        if (buf.remaining() < Integer.BYTES) {
            return null;
        }
        int nSized = buf.getInt();
        if (nSized < 0) {
            return null;
        }
        List<Bytes32> sized = new ArrayList<>(nSized);
        for (int i = 0; i < nSized; i++) {
            if (buf.remaining() < Bytes32.SIZE) {
                return null;
            }
            byte[] dst = new byte[Bytes32.SIZE];
            buf.get(dst);
            sized.add(Bytes32.wrap(dst));
        }
        if (buf.remaining() < Integer.BYTES * 2) {
            return null;
        }
        int openings = buf.getInt();
        int nReceipts = buf.getInt();
        if (openings < 0 || nReceipts < 0) {
            return null;
        }
        List<Bytes32> receipts = new ArrayList<>(nReceipts);
        for (int i = 0; i < nReceipts; i++) {
            if (buf.remaining() < Bytes32.SIZE) {
                return null;
            }
            byte[] hash = new byte[Bytes32.SIZE];
            buf.get(hash);
            receipts.add(Bytes32.wrap(hash));
        }
        if (buf.hasRemaining()) {
            return null;
        }
        return new ExecChangeSet(prevSeq, Bytes32.wrap(root), state, mmr, sized, openings, receipts);
    }
}
