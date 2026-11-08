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
package io.xdag.chain.l1.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.AnchorHead;
import io.xdag.chain.anchor.DueSegments;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.anchor.BondAccount;
import io.xdag.chain.anchor.BondVault;
import io.xdag.chain.anchor.CommitmentChain;
import io.xdag.chain.anchor.SegmentCursor;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.AnchorExt;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.core.Block;
import io.xdag.utils.XdagTime;

/**
 * ANCHOR. The canonical record stays on the short {@code 0x06 ‖ chain ‖ seq} key. A same-seq loser
 * is written at {@code 0x06 ‖ chain ‖ seq ‖ anchorHash} so {@code getAnchor} still returns the
 * winner. Finality is queued at {@code confirmHeight + W}. {@code inputCount} is calls plus
 * declared message spans plus one reward for every canonical anchor whose confirm height sits in
 * {@code (prevSeq, seq]}, including this anchor when its own confirm height does. Deliverable
 * marks are written by {@link io.xdag.chain.anchor.HeightTriggers}, not here.
 */
public final class AnchorHandler implements ChainKindHandler {

    /** {@code hashLow} of the main block at {@code height}, or null when that height has no main block. */
    public interface Mains {
        Bytes32 hashLowAt(long height);
    }

    private final ChainL1Store store;
    private final ChunkChain.RawBlockLookup lookup;
    private final Mains mains;
    private final long minBond;
    private final long window;
    private final int maxChunks;

    public AnchorHandler(ChainL1Store store, ChunkChain.RawBlockLookup lookup, Mains mains, long minBond, long window,
            int maxChunks) {
        this.store = store;
        this.lookup = lookup;
        this.mains = mains;
        this.minBond = minBond;
        this.window = window;
        this.maxChunks = maxChunks;
    }

    @Override
    public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
        AnchorExt ext = classified.as(AnchorExt.class);
        int expect = CommitmentChain.length(ext.segmentCount(), ext.inputCount());
        byte[] commitment = new byte[0];
        if (expect > 0) {
            long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
            var assembled = ChunkChain.assemble(ext.commitmentHead(), lookup, maxChunks, minEpoch);
            if (!assembled.isOk()) {
                return;
            }
            commitment = assembled.value().toArray();
        }
        apply(ext, AnchorBody.hashLow(block.getHash()), BondHandler.firstInput(block), ctx.height(), commitment, batch);
    }

    @Override
    public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        AnchorExt ext = classified.as(AnchorExt.class);
        byte[] commitment = new byte[0];
        int expect = CommitmentChain.length(ext.segmentCount(), ext.inputCount());
        if (expect > 0) {
            long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
            var assembled = ChunkChain.assemble(ext.commitmentHead(), lookup, maxChunks, minEpoch);
            if (!assembled.isOk()) {
                throw new IllegalStateException("anchor unwind cannot reassemble its commitment");
            }
            commitment = assembled.value().toArray();
        }
        undo(ext, AnchorBody.hashLow(block.getHash()), commitment, batch);
    }

    public boolean apply(AnchorExt ext, Bytes32 anchorHash, byte[] attester, long confirmHeight, byte[] commitment,
            ChainL1Batch batch) {
        if (ext == null || attester == null || attester.length != 20 || anchorHash == null) {
            return false;
        }
        ChainRecord chain = store.getChain(ext.chainId());
        if (chain == null) {
            return false;
        }
        if (Long.compareUnsigned(ext.seq(), ext.prevSeq()) <= 0) {
            return false;
        }
        boolean taken = store.getAnchorRaw(ext.chainId(), ext.seq()) != null;
        AnchorHead head = AnchorHead.decode(store.getAnchorHead(ext.chainId()));
        if (!taken) {
            if (head == null) {
                if (ext.prevSeq() != chain.createdHeight()
                        || !ext.prevAnchor().equals(AnchorBody.hashLow(chain.createBlockHash()))) {
                    return false;
                }
            } else if (ext.prevSeq() != head.seq() || !ext.prevAnchor().equals(head.anchorHash())) {
                return false;
            }
        }
        Bytes32 main = mains.hashLowAt(ext.seq());
        if (main == null || !main.equals(ext.mainBlock())) {
            return false;
        }
        if (!bonded(attester, ext.chainId())) {
            return false;
        }
        CommitmentChain.Body body = commitmentOf(ext, commitment);
        if (body == null || !inputsMatch(ext, body, confirmHeight)) {
            return false;
        }
        long due = confirmHeight + window;
        if (window != 0 && Long.compareUnsigned(due, confirmHeight) < 0) {
            return false;
        }
        AnchorBody record = new AnchorBody(AnchorRecord.Status.CANONICAL, anchorHash, ext.outboxMapRoot(),
                ext.stateRoot(), ext.prevSeq(), ext.inputCount(), ext.segmentCount(), confirmHeight, ext.prevAnchor(),
                attester, ext.commitmentHead());
        // The winner already moved the head, so a same-seq block's parent is the old head.
        // Store it beside the short key and leave the cursors where the winner put them.
        if (store.getAnchorRaw(ext.chainId(), ext.seq()) != null) {
            batch.putIgnoredAnchor(ext.chainId(), ext.seq(), anchorHash,
                    record.withStatus(AnchorRecord.Status.IGNORED).encode());
            return true;
        }
        if (!segmentsMatch(ext, body, batch)) {
            return false;
        }
        batch.putAnchor(ext.chainId(), ext.seq(), record.encode());
        long base = head == null ? 0L : head.windowBase();
        long withdrawn = head == null ? 0L : head.withdrawn();
        batch.putAnchorHead(ext.chainId(), new AnchorHead(ext.seq(), anchorHash, base, withdrawn).encode());
        TriggerOps.append(store, batch, due, new TriggerList.Item(TriggerList.FINALITY, ext.chainId().toArray(),
                ext.seq(), attester, 0L, 0L, new byte[20], 0L, Bytes32.ZERO, 0L, false));
        return true;
    }

    public void undo(AnchorExt ext, Bytes32 anchorHash, byte[] commitment, ChainL1Batch batch) {
        if (ext == null || anchorHash == null) {
            return;
        }
        AnchorBody current = AnchorBody.decode(store.getAnchorRaw(ext.chainId(), ext.seq()));
        if (current == null || !current.anchorHash().equals(anchorHash)) {
            batch.deleteIgnoredAnchor(ext.chainId(), ext.seq(), anchorHash);
            return;
        }
        AnchorHead head = AnchorHead.decode(store.getAnchorHead(ext.chainId()));
        if (head == null || head.seq() != ext.seq() || !head.anchorHash().equals(anchorHash)) {
            throw new IllegalStateException("anchor unwind is not the head");
        }
        CommitmentChain.Body body = commitmentOf(ext, commitment);
        if (body == null) {
            throw new IllegalStateException("anchor unwind commitment");
        }
        rewindCursors(ext.chainId(), body, batch);
        batch.deleteAnchor(ext.chainId(), ext.seq());
        ChainRecord chain = store.getChain(ext.chainId());
        boolean genesis = chain != null && ext.prevSeq() == chain.createdHeight()
                && ext.prevAnchor().equals(AnchorBody.hashLow(chain.createBlockHash()));
        if (genesis) {
            batch.deleteAnchorHead(ext.chainId());
        } else {
            batch.putAnchorHead(ext.chainId(),
                    new AnchorHead(ext.prevSeq(), ext.prevAnchor(), head.windowBase(), head.withdrawn()).encode());
        }
        long due = current.confirmHeight() + window;
        TriggerOps.removeFirst(store, batch, due, item -> item.kind() == TriggerList.FINALITY && !item.done()
                && item.seq() == ext.seq() && Arrays.equals(item.chain(), ext.chainId().toArray()));
    }

    private boolean bonded(byte[] attester, Bytes chain) {
        long specific = amount(store.getBond(Bytes.wrap(attester), chain));
        long global = amount(store.getBond(Bytes.wrap(attester), Bytes.wrap(BondVault.GLOBAL)));
        if (specific < 0 || global < 0) {
            return false;
        }
        long have;
        try {
            have = Math.addExact(specific, global);
        } catch (ArithmeticException e) {
            return true;
        }
        return Long.compareUnsigned(have, minBond) >= 0;
    }

    private static long amount(byte[] raw) {
        BondAccount account = BondAccount.decode(raw);
        return account == null ? 0L : account.amount();
    }

    private CommitmentChain.Body commitmentOf(AnchorExt ext, byte[] commitment) {
        int expect = CommitmentChain.length(ext.segmentCount(), ext.inputCount());
        if (expect < 0) {
            return null;
        }
        if (expect == 0) {
            return new CommitmentChain.Body(List.of(), List.of());
        }
        return CommitmentChain.decode(commitment, ext.segmentCount(), ext.inputCount());
    }

    private boolean inputsMatch(AnchorExt ext, CommitmentChain.Body body, long confirmHeight) {
        long calls = 0L;
        long h = ext.prevSeq();
        while (Long.compareUnsigned(h, ext.seq()) < 0) {
            if (h == Long.MAX_VALUE) {
                return false;
            }
            h++;
            try {
                calls = Math.addExact(calls, store.getCallCount(ext.chainId(), h));
            } catch (ArithmeticException e) {
                return false;
            }
        }
        long messages = 0L;
        for (CommitmentChain.Segment segment : body.segments()) {
            if (Long.compareUnsigned(segment.to(), segment.from()) < 0) {
                return false;
            }
            try {
                messages = Math.addExact(messages, segment.to() - segment.from());
            } catch (ArithmeticException e) {
                return false;
            }
        }
        long rewards;
        try {
            rewards = RewardInputs.count(store, ext.chainId().toArray(), ext.prevSeq(), ext.seq());
            if (RewardInputs.in(confirmHeight, ext.prevSeq(), ext.seq())) {
                rewards = Math.addExact(rewards, 1);
            }
            return Math.addExact(Math.addExact(calls, messages), rewards) == ext.inputCount();
        } catch (ArithmeticException e) {
            return false;
        }
    }

    /**
     * Due source anchors, in (confirmHeight, src, seq) order. The declared list must be that
     * sequence, and each {@code from} must be the cursor's {@code to} (0 when the cursor is absent).
     * {@code write} advances the cursors; the check-only path used while validating a loser does not.
     */
    private boolean segmentsMatch(AnchorExt ext, CommitmentChain.Body body, ChainL1Batch batch) {
        List<DueSegments.Due> due = due(ext.chainId(), ext.seq());
        if (body.segments().size() != due.size()) {
            return false;
        }
        Map<Bytes, SegmentCursor> running = new HashMap<>();
        for (int i = 0; i < due.size(); i++) {
            CommitmentChain.Segment segment = body.segments().get(i);
            DueSegments.Due expected = due.get(i);
            if (!Arrays.equals(segment.src(), expected.src()) || segment.srcSeq() != expected.seq()) {
                return false;
            }
            if (Long.compareUnsigned(segment.to(), segment.from()) < 0) {
                return false;
            }
            Bytes src = Bytes.wrap(segment.src());
            SegmentCursor cursor = running.get(src);
            if (cursor == null) {
                cursor = SegmentCursor.decode(store.getCursor(ext.chainId(), src));
                if (cursor == null) {
                    cursor = new SegmentCursor(0L, 0L);
                }
            }
            if (segment.from() != cursor.to()) {
                return false;
            }
            running.put(src, new SegmentCursor(segment.to(), segment.srcSeq()));
        }
        for (Map.Entry<Bytes, SegmentCursor> e : running.entrySet()) {
            batch.putCursor(ext.chainId(), e.getKey(), e.getValue().encode());
        }
        return true;
    }

    private void rewindCursors(Bytes chain, CommitmentChain.Body body, ChainL1Batch batch) {
        Map<Bytes, CommitmentChain.Segment> first = new HashMap<>();
        for (CommitmentChain.Segment segment : body.segments()) {
            first.putIfAbsent(Bytes.wrap(segment.src()), segment);
        }
        for (Map.Entry<Bytes, CommitmentChain.Segment> e : first.entrySet()) {
            Long prev = previousSeq(e.getKey(), e.getValue().srcSeq());
            if (prev == null) {
                batch.deleteCursor(chain, e.getKey());
            } else {
                batch.putCursor(chain, e.getKey(), new SegmentCursor(e.getValue().from(), prev).encode());
            }
        }
    }

    private Long previousSeq(Bytes src, long seq) {
        Long best = null;
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), src.toArray())) {
                continue;
            }
            long candidate = io.xdag.chain.ext.ExtCodec.u64(key, 21);
            if (Long.compareUnsigned(candidate, seq) >= 0) {
                continue;
            }
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(src, candidate));
            if (body == null || (body.status() != AnchorRecord.Status.CANONICAL && body.status() != AnchorRecord.Status.FINAL)) {
                continue;
            }
            if (best == null || Long.compareUnsigned(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private List<DueSegments.Due> due(Bytes chain, long seq) {
        return DueSegments.due(store, chain, seq, window);
    }
}
