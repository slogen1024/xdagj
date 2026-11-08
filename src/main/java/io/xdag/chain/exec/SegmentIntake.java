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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.p2p.Delivery;
import io.xdag.chain.p2p.ProofBodies;

/**
 * Installs a fetched outbox segment into the exec store so a destination that does not execute
 * the source can still expand messages. The segment's MMR root has to be the value the source
 * anchor committed under this destination in {@code outboxMap}. A gap before {@code from} stays
 * HOLD. A body that does not prove that root is REJECT and writes nothing.
 */
public final class SegmentIntake {

    private SegmentIntake() {
    }

    public static Delivery.Status install(ChainExecStore exec, byte[] src, byte[] dst, long srcSeq, long prevSeq,
            Bytes32 outboxMapRoot, byte[] outboxProof, byte[] segmentProof) {
        if (exec == null || outboxProof == null || segmentProof == null || outboxMapRoot == null) {
            return Delivery.Status.HOLD;
        }
        SparseMerkleTree.Proof map;
        ProofBodies.Segment segment;
        try {
            map = ProofBodies.decodeOutbox(outboxProof);
            segment = ProofBodies.decodeSegment(segmentProof);
        } catch (IllegalArgumentException e) {
            return Delivery.Status.REJECT;
        }
        Bytes32 key = ChainExecutor.chain32(Bytes.wrap(dst));
        if (!map.verifies(key, outboxMapRoot) || map.value().length != OutboxMap.VALUE_BYTES || !segment.verifies()) {
            return Delivery.Status.REJECT;
        }
        ByteBuffer value = ByteBuffer.wrap(map.value());
        byte[] rootBytes = new byte[Bytes32.SIZE];
        value.get(rootBytes);
        Bytes32 mmrRoot = Bytes32.wrap(rootBytes);
        long size = value.getLong();
        if (!segment.mmrRoot().equals(mmrRoot) || !segment.dst().equals(key) || segment.to() != size
                || segment.to() > Integer.MAX_VALUE || segment.from() > Integer.MAX_VALUE) {
            return Delivery.Status.REJECT;
        }
        int from = (int) segment.from();
        int to = (int) segment.to();
        Bytes32 srcId = ChainExecutor.chain32(Bytes.wrap(src));
        int havePrev = exec.outboxSize(srcId, key, prevSeq);
        int haveTo = exec.outboxSize(srcId, key, srcSeq);
        if ((havePrev >= 0 && havePrev != from) || (haveTo >= 0 && haveTo != to)) {
            return Delivery.Status.REJECT;
        }
        byte[] blob = exec.getMmr(srcId, key);
        List<byte[]> have = blob == null ? List.of() : ChainExecutor.decodeMmr(blob);
        if (have.size() >= to) {
            writeSizes(exec, srcId, key, prevSeq, srcSeq, from, to);
            return Delivery.Status.READY;
        }
        if (have.size() != from) {
            return Delivery.Status.HOLD;
        }
        List<byte[]> merged = new ArrayList<>(have);
        for (ProofBodies.Entry entry : segment.entries()) {
            merged.add(entry.bytes());
        }
        exec.putMmr(srcId, key, ChainExecutor.encodeMmr(merged));
        writeSizes(exec, srcId, key, prevSeq, srcSeq, from, to);
        return Delivery.Status.READY;
    }

    private static void writeSizes(ChainExecStore exec, Bytes32 src, Bytes32 dst, long prevSeq, long srcSeq,
            int from, int to) {
        if (prevSeq >= 0) {
            exec.putOutboxSize(src, dst, prevSeq, from);
        }
        if (srcSeq >= 0) {
            exec.putOutboxSize(src, dst, srcSeq, to);
        }
    }
}
