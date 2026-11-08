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

import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.p2p.ChainPull;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.chain.p2p.ProofKind;

/**
 * Builds the {@code 0x1E} the destination sends before {@code h_deliver}. The outbox-map proof is
 * checked against the anchor root here only to learn {@code to}. {@link SegmentIntake} checks it
 * again before anything is written. A missing reply is null, which leaves the chain unstepped.
 */
public final class WireProofs {

    private WireProofs() {
    }

    public static SegmentProofs.Bodies fetch(ChainPull pull, ChainExecStore store, byte[] src, long srcSeq,
            byte[] dst, Bytes32 outboxMapRoot) {
        if (pull == null) {
            return null;
        }
        Bytes32 srcId = ChainExecutor.chain32(Bytes.wrap(src));
        Bytes32 dstId = ChainExecutor.chain32(Bytes.wrap(dst));
        byte[] outbox = pull.proof(srcId, srcSeq, ProofKind.OUTBOX_MAP, dstId.toArray());
        if (outbox == null) {
            return null;
        }
        SparseMerkleTree.Proof map;
        try {
            map = ProofBodies.decodeOutbox(outbox);
        } catch (IllegalArgumentException e) {
            return new SegmentProofs.Bodies(outbox, new byte[0]);
        }
        if (!map.verifies(dstId, outboxMapRoot) || !map.present() || map.value().length != OutboxMap.VALUE_BYTES) {
            return new SegmentProofs.Bodies(outbox, new byte[0]);
        }
        ByteBuffer value = ByteBuffer.wrap(map.value());
        value.position(Bytes32.SIZE);
        long size = value.getLong();
        int from = ChainExecutor.mmrCount(store.getMmr(srcId, dstId));
        if (size == 0 || size == from) {
            return new SegmentProofs.Bodies(outbox, new byte[0]);
        }
        if (size < from || size > Integer.MAX_VALUE) {
            return new SegmentProofs.Bodies(outbox, new byte[0]);
        }
        byte[] segment = pull.proof(srcId, srcSeq, ProofKind.SEGMENT,
                ProofBodies.segmentKey(dstId, from, size));
        if (segment == null) {
            return null;
        }
        return new SegmentProofs.Bodies(outbox, segment);
    }
}
