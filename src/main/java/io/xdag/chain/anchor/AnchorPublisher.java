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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ExtError;
import io.xdag.chain.ext.ExtResult;
import io.xdag.config.Config;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.ECKeyPair;

/**
 * Builds an ANCHOR from the caller's commitment list and the due segments on L1.
 * The list is in anchor order: segments delivered outside {@code (prevSeq, seq]} first, then for
 * each height in that range the reward inputs, the messages whose {@code confirmHeight + D} is
 * that height, and the call inputs. {@code confirmHeight}
 * is the height the attester expects the block to be confirmed at; when that height
 * sits in the range the anchor's own reward is one of the commitments. Does not import
 * the block.
 */
public final class AnchorPublisher {

    private final ChainL1Store l1;
    private final ChainExecStore exec;
    private final long window;

    public AnchorPublisher(ChainL1Store l1, ChainExecStore exec, long window) {
        this.l1 = l1;
        this.exec = exec;
        this.window = window;
    }

    public ExtResult<ChainBlockBuilder.Built> publish(Config config, long timestamp, ECKeyPair attester, UInt64 nonce,
            XAmount headerFee, Bytes chain, long seq, long confirmHeight, Bytes32 mainBlock, Bytes32 stateRoot,
            Bytes32 outboxRoot, List<Bytes32> commitments) {
        ChainRecord record = l1.getChain(chain);
        if (record == null || commitments == null || mainBlock == null) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        AnchorHead head = AnchorHead.decode(l1.getAnchorHead(chain));
        long prevSeq = head == null ? record.createdHeight() : head.seq();
        Bytes32 prev = head == null ? AnchorBody.hashLow(record.createBlockHash()) : head.anchorHash();
        if (Long.compareUnsigned(seq, prevSeq) <= 0) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        List<CommitmentChain.Segment> segments = new ArrayList<>();
        long messages = 0L;
        for (DueSegments.Due due : DueSegments.due(l1, chain, seq, window)) {
            SegmentCursor cursor = SegmentCursor.decode(l1.getCursor(chain, Bytes.wrap(due.src())));
            long from = cursor == null ? 0L : cursor.to();
            byte[] mmr = exec.getMmr(ChainExecutor.chain32(due.src()), ChainExecutor.chain32(chain.toArray()));
            if (mmr == null || mmr.length < Integer.BYTES) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
            long to = Integer.toUnsignedLong(ByteBuffer.wrap(mmr).getInt());
            if (Long.compareUnsigned(to, from) < 0) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            messages += to - from;
            segments.add(new CommitmentChain.Segment(due.src(), due.seq(), from, to));
        }
        long calls = 0L;
        long h = prevSeq;
        while (Long.compareUnsigned(h, seq) < 0) {
            if (h == Long.MAX_VALUE) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            h++;
            calls += l1.getCallCount(chain, h);
        }
        long rewards = RewardInputs.count(l1, chain.toArray(), prevSeq, seq);
        if (RewardInputs.in(confirmHeight, prevSeq, seq)) {
            rewards++;
        }
        if (commitments.size() != calls + messages + rewards) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Bytes payload = Bytes.wrap(CommitmentChain.encode(segments, commitments));
        return ChainBlockBuilder.anchor(config, timestamp, attester, nonce, headerFee, chain, seq, stateRoot,
                outboxRoot, prevSeq, commitments.size(), segments.size(), prev, mainBlock, payload);
    }
}
