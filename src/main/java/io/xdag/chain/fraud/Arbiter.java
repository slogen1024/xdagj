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

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.CommitmentChain;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.InputRecord;
import io.xdag.crypto.hash.HashUtils;

/**
 * E1 compares the opened input hash with the 0x0C block hash. E2 re-executes that call on the
 * interpreter. E3 checks one outbox size. A message slot or a reward slot has no call hash, so an
 * E1 or E2 against it is {@link Verdict#INVALID}. A malformed E2 witness is {@link Verdict#FAIL}.
 */
public final class Arbiter {

    public enum Verdict {
        SUCCESS, FAIL, INVALID
    }

    private Arbiter() {
    }

    public static Verdict judge(ChainL1Store store, byte[] chain, long seq, AnchorBody anchor,
            CommitmentChain.Body commitment, long k, byte[] witness, ChunkChain.RawBlockLookup lookup, int maxChunks) {
        if (anchor == null || commitment == null || k < 0 || k >= commitment.commitments().size()) {
            return Verdict.INVALID;
        }
        int type = Witness.type(witness);
        if (type == Witness.E2) {
            return E2Replay.replay(store, chain, seq, anchor, commitment, k, witness, lookup, maxChunks);
        }
        if (type == Witness.E1) {
            return e1(store, chain, seq, anchor, commitment, k, witness);
        }
        if (type == Witness.E3) {
            return e3(store, chain, commitment, witness);
        }
        return Verdict.INVALID;
    }

    private static Verdict e1(ChainL1Store store, byte[] chain, long seq, AnchorBody anchor,
            CommitmentChain.Body commitment, long k, byte[] witness) {
        Witness.E1Body body = Witness.decodeE1(witness);
        if (body == null) {
            return Verdict.FAIL;
        }
        byte[] opening = new byte[witness.length - 1];
        System.arraycopy(witness, 1, opening, 0, opening.length);
        if (!HashUtils.sha256(Bytes.wrap(opening)).equals(commitment.commitments().get((int) k))) {
            return Verdict.FAIL;
        }
        Bytes32 expected = expectedCall(store, chain, anchor.prevSeq(), seq, commitment, k);
        if (expected == null) {
            return Verdict.INVALID;
        }
        return expected.equals(body.opening().inputHash()) ? Verdict.FAIL : Verdict.SUCCESS;
    }

    private static Verdict e3(ChainL1Store store, byte[] anchorChain, CommitmentChain.Body commitment, byte[] witness) {
        Witness.E3Body body = Witness.decodeE3(witness);
        if (body == null || !java.util.Arrays.equals(body.dst(), anchorChain)) {
            return Verdict.FAIL;
        }
        CommitmentChain.Segment segment = null;
        for (CommitmentChain.Segment s : commitment.segments()) {
            if (java.util.Arrays.equals(s.src(), body.src()) && s.srcSeq() == body.srcSeq()) {
                segment = s;
                break;
            }
        }
        if (segment == null || segment.to() != body.declaredTo()) {
            return Verdict.FAIL;
        }
        byte[] raw = store.getAnchorRaw(Bytes.wrap(body.src()), body.srcSeq());
        AnchorBody src = AnchorBody.decode(raw);
        if (src == null) {
            return Verdict.FAIL;
        }
        SparseMerkleTree.Proof proof = SparseMerkleTree.proof(body.value().length != 0, body.value(), body.siblings());
        if (!proof.verifies(ChainExecutor.chain32(body.dst()), src.outboxMapRoot())) {
            return Verdict.FAIL;
        }
        long proven = 0L;
        if (body.value().length == OutboxMap.VALUE_BYTES) {
            proven = ByteBuffer.wrap(body.value()).getLong(32);
        } else if (body.value().length != 0) {
            return Verdict.FAIL;
        }
        return proven == segment.to() ? Verdict.FAIL : Verdict.SUCCESS;
    }

    static Bytes32 expectedCall(ChainL1Store store, byte[] chain, long prevSeq, long seq,
            CommitmentChain.Body commitment, long k) {
        long messages = 0L;
        for (CommitmentChain.Segment s : commitment.segments()) {
            if (Long.compareUnsigned(s.to(), s.from()) < 0) {
                return null;
            }
            messages += s.to() - s.from();
        }
        if (Long.compareUnsigned(k, messages) < 0) {
            return null;
        }
        long left = k - messages;
        long h = prevSeq;
        Bytes id = Bytes.wrap(chain);
        while (Long.compareUnsigned(h, seq) < 0) {
            if (h == Long.MAX_VALUE) {
                return null;
            }
            h++;
            int rewards = RewardInputs.at(store, chain, h).size();
            if (Long.compareUnsigned(left, rewards) < 0) {
                return null;
            }
            left -= rewards;
            long n = store.getCallCount(id, h);
            if (Long.compareUnsigned(left, n) < 0) {
                InputRecord input = store.getInput(id, h, left);
                return input == null ? null : input.blockHash();
            }
            left -= n;
        }
        return null;
    }
}
