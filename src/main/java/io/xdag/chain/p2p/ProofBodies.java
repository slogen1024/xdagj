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
package io.xdag.chain.p2p;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.crypto.hash.HashUtils;

/**
 * The four {@code CHAIN_PROOF} bodies. Each one is checked against a root the requester already
 * has (the anchor's state root, outboxMap root, commitment, or events root). The peer does not
 * get to name that root.
 *
 * <ul>
 * <li>SEGMENT key {@code dst 32 ‖ from u64 ‖ to u64}. Body is the entries in {@code [from, to)}
 *     plus one MMR inclusion proof each. Leaf = {@code sha256(entry)}.
 * <li>OUTBOX_MAP key = dst chain. Body is the SMT value {@code mmrRoot 32 ‖ size u64} and the
 *     256 siblings. Absent is a legal proof that the destination has no outbox.
 * <li>COMMITMENT key {@code k u64}. Body is the opening whose sha256 is {@code C_k}.
 * <li>EVENT key {@code index u32}. Body is the event and its MMR proof. Leaf =
 *     {@code sha256(0x04 ‖ event)}.
 * </ul>
 */
public final class ProofBodies {

    private ProofBodies() {
    }

    public record Segment(Bytes32 dst, long from, long to, long mmrSize, Bytes32 mmrRoot, List<Entry> entries) {

        public boolean verifies() {
            if (entries.size() != to - from || from < 0 || to < from) {
                return false;
            }
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.index() != from + i || e.proof().size() != mmrSize) {
                    return false;
                }
                Bytes32 leaf = HashUtils.sha256(Bytes.wrap(e.bytes()));
                if (!e.proof().root(leaf).equals(mmrRoot)) {
                    return false;
                }
            }
            return true;
        }
    }

    public record Entry(long index, byte[] bytes, Mmr.Proof proof) {
    }

    public record Opened(Bytes32 inputHash, Bytes32 postStateRoot, Bytes32 outboxMapRoot, Bytes32 eventsRoot,
            int status, long gasUsed) {

        public Bytes32 digest() {
            return ProofBodies.commitment(inputHash, postStateRoot, outboxMapRoot, eventsRoot, status, gasUsed);
        }
    }

    public static byte[] segmentKey(Bytes32 dst, long from, long to) {
        ByteBuffer buf = ByteBuffer.allocate(ChainCodec.CHAIN_ID_BYTES + Long.BYTES + Long.BYTES);
        buf.put(dst.toArray());
        buf.putLong(from);
        buf.putLong(to);
        return buf.array();
    }

    public static byte[] commitmentKey(long k) {
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
        buf.putLong(k);
        return buf.array();
    }

    public static byte[] eventKey(int index) {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES);
        buf.putInt(index);
        return buf.array();
    }

    public static byte[] encodeSegment(Bytes32 dst, long from, long to, Mmr mmr, List<byte[]> entries) {
        if (entries.size() != to - from) {
            throw new IllegalArgumentException("segment width");
        }
        List<byte[]> proofs = new ArrayList<>();
        int total = ChainCodec.CHAIN_ID_BYTES + Long.BYTES * 3 + Bytes32.SIZE + Integer.BYTES;
        for (int i = 0; i < entries.size(); i++) {
            byte[] proof = mmr.proof(Math.toIntExact(from + i)).encode();
            proofs.add(proof);
            total += Long.BYTES + Integer.BYTES + entries.get(i).length + Integer.BYTES + proof.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(dst.toArray());
        buf.putLong(from);
        buf.putLong(to);
        buf.putLong(mmr.size());
        buf.put(mmr.root().toArray());
        buf.putInt(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            buf.putLong(from + i);
            buf.putInt(entries.get(i).length);
            buf.put(entries.get(i));
            buf.putInt(proofs.get(i).length);
            buf.put(proofs.get(i));
        }
        return buf.array();
    }

    public static Segment decodeSegment(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < ChainCodec.CHAIN_ID_BYTES + Long.BYTES * 3 + Bytes32.SIZE + Integer.BYTES) {
            throw ChainCodec.malformed("segment truncated");
        }
        Bytes32 dst = ChainCodec.chainId(buf);
        long from = buf.getLong();
        long to = buf.getLong();
        long mmrSize = buf.getLong();
        byte[] root = new byte[Bytes32.SIZE];
        buf.get(root);
        int n = buf.getInt();
        if (from < 0 || to < from || n != to - from || n > 10_000) {
            throw ChainCodec.malformed("segment range");
        }
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (buf.remaining() < Long.BYTES + Integer.BYTES) {
                throw ChainCodec.malformed("segment truncated");
            }
            long index = buf.getLong();
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw ChainCodec.malformed("segment entry");
            }
            byte[] bytes = new byte[len];
            buf.get(bytes);
            if (buf.remaining() < Integer.BYTES) {
                throw ChainCodec.malformed("segment truncated");
            }
            int proofLen = buf.getInt();
            if (proofLen < 0 || proofLen > buf.remaining()) {
                throw ChainCodec.malformed("segment proof");
            }
            byte[] raw = new byte[proofLen];
            buf.get(raw);
            entries.add(new Entry(index, bytes, Mmr.Proof.decode(ByteBuffer.wrap(raw))));
        }
        ChainCodec.exhaust(buf);
        return new Segment(dst, from, to, mmrSize, Bytes32.wrap(root), entries);
    }

    public static byte[] encodeOutbox(SparseMerkleTree.Proof proof) {
        ByteBuffer buf = ByteBuffer.allocate(1 + Integer.BYTES + proof.value().length
                + SparseMerkleTree.DEPTH * Bytes32.SIZE);
        buf.put(proof.present() ? (byte) 1 : (byte) 0);
        buf.putInt(proof.value().length);
        buf.put(proof.value());
        for (Bytes32 sibling : proof.siblings()) {
            buf.put(sibling.toArray());
        }
        return buf.array();
    }

    public static SparseMerkleTree.Proof decodeOutbox(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < 1 + Integer.BYTES) {
            throw ChainCodec.malformed("outbox proof truncated");
        }
        int present = buf.get() & 0xff;
        if (present > 1) {
            throw ChainCodec.malformed("outbox proof flag");
        }
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining() - SparseMerkleTree.DEPTH * Bytes32.SIZE) {
            throw ChainCodec.malformed("outbox proof value");
        }
        byte[] value = new byte[len];
        buf.get(value);
        Bytes32[] siblings = new Bytes32[SparseMerkleTree.DEPTH];
        for (int i = 0; i < siblings.length; i++) {
            if (buf.remaining() < Bytes32.SIZE) {
                throw ChainCodec.malformed("outbox proof truncated");
            }
            byte[] raw = new byte[Bytes32.SIZE];
            buf.get(raw);
            siblings[i] = Bytes32.wrap(raw);
        }
        ChainCodec.exhaust(buf);
        return SparseMerkleTree.proof(present == 1, value, siblings);
    }

    public static Bytes32 commitment(Bytes32 inputHash, Bytes32 postStateRoot, Bytes32 outboxMapRoot,
            Bytes32 eventsRoot, int status, long gasUsed) {
        ByteBuffer buf = ByteBuffer.allocate(Bytes32.SIZE * 4 + 1 + Long.BYTES);
        buf.put(inputHash.toArray());
        buf.put(postStateRoot.toArray());
        buf.put(outboxMapRoot.toArray());
        buf.put(eventsRoot.toArray());
        buf.put((byte) status);
        buf.putLong(gasUsed);
        return HashUtils.sha256(Bytes.wrap(buf.array()));
    }

    public static byte[] encodeCommitment(Opened opened) {
        ByteBuffer buf = ByteBuffer.allocate(Bytes32.SIZE * 4 + 1 + Long.BYTES);
        buf.put(opened.inputHash().toArray());
        buf.put(opened.postStateRoot().toArray());
        buf.put(opened.outboxMapRoot().toArray());
        buf.put(opened.eventsRoot().toArray());
        buf.put((byte) opened.status());
        buf.putLong(opened.gasUsed());
        return buf.array();
    }

    public static Opened decodeCommitment(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() != Bytes32.SIZE * 4 + 1 + Long.BYTES) {
            throw ChainCodec.malformed("commitment opening");
        }
        return new Opened(read32(buf), read32(buf), read32(buf), read32(buf), buf.get() & 0xff, buf.getLong());
    }

    public static byte[] encodeEvent(int index, Mmr mmr, byte[] event) {
        byte[] proof = mmr.proof(index).encode();
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES * 2 + Integer.BYTES + event.length + Integer.BYTES
                + proof.length);
        buf.putInt(index);
        buf.putInt(mmr.size());
        buf.putInt(event.length);
        buf.put(event);
        buf.putInt(proof.length);
        buf.put(proof);
        return buf.array();
    }

    public record Event(int index, int count, byte[] bytes, Mmr.Proof proof) {

        public Bytes32 leaf() {
            ByteBuffer buf = ByteBuffer.allocate(1 + bytes.length);
            buf.put((byte) 0x04);
            buf.put(bytes);
            return HashUtils.sha256(Bytes.wrap(buf.array()));
        }

        public boolean verifies(Bytes32 eventsRoot) {
            return proof.size() == count && proof.index() == index && proof.root(leaf()).equals(eventsRoot);
        }
    }

    public static Event decodeEvent(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < Integer.BYTES * 3) {
            throw ChainCodec.malformed("event truncated");
        }
        int index = buf.getInt();
        int count = buf.getInt();
        int len = buf.getInt();
        if (index < 0 || count <= index || len < 0 || len > buf.remaining()) {
            throw ChainCodec.malformed("event range");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        if (buf.remaining() < Integer.BYTES) {
            throw ChainCodec.malformed("event truncated");
        }
        int proofLen = buf.getInt();
        if (proofLen < 0 || proofLen != buf.remaining()) {
            throw ChainCodec.malformed("event proof");
        }
        byte[] raw = new byte[proofLen];
        buf.get(raw);
        return new Event(index, count, bytes, Mmr.Proof.decode(ByteBuffer.wrap(raw)));
    }

    private static Bytes32 read32(ByteBuffer buf) {
        byte[] raw = new byte[Bytes32.SIZE];
        buf.get(raw);
        return Bytes32.wrap(raw);
    }
}
