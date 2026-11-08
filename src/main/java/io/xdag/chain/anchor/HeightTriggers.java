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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChallengeExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.fraud.Arbiter;
import io.xdag.chain.fraud.VerdictCache;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.handlers.ClaimHandler;
import io.xdag.core.Block;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.XdagTime;

/**
 * Runs at {@code onSetMainEnd}, after every block of the height has committed. Order: challenges
 * (at most {@code maxPerHeight}; the rest move to the next height), then finality, then unbond,
 * then a {@link TriggerList#DELIVER} mark for every source anchor whose {@code confirmHeight + D}
 * is this height. Reward inputs are not written here; they are read off the canonical anchors.
 * {@link #preview} fills the verdict cache off the import lock. {@link #unwind} reverses one
 * height and must run before that height's blocks are unapplied.
 */
public final class HeightTriggers {

    private final ChainL1Store store;
    private final ChunkChain.RawBlockLookup lookup;
    private final ClaimHandler.Settlement settlement;
    private final long window;
    private final int maxPerHeight;
    private final int maxWitness;
    private final int maxChunks;
    private final VerdictCache cache;
    private SourceVoided sourceVoided = (chain, seq, confirm) -> {
    };
    private final List<SourceNote> voids = new ArrayList<>();

    /** Execution-side hook. L1 has already committed the void. */
    public interface SourceVoided {
        void onSourceVoided(byte[] srcChain, long srcSeq, long confirmHeight);
    }

    public void setSourceVoided(SourceVoided listener) {
        this.sourceVoided = listener == null ? (chain, seq, confirm) -> {
        } : listener;
    }

    public HeightTriggers(ChainL1Store store, ChunkChain.RawBlockLookup lookup, ClaimHandler.Settlement settlement,
            long window, int maxPerHeight, int maxWitness, int maxChunks, VerdictCache cache) {
        this.store = store;
        this.lookup = lookup;
        this.settlement = settlement;
        this.window = window;
        this.maxPerHeight = maxPerHeight;
        this.maxWitness = maxWitness;
        this.maxChunks = maxChunks;
        this.cache = cache;
    }

    /**
     * Cache a verdict for a challenge that is already against a canonical anchor and whose witness
     * assembles. Anything else is left uncached so {@link #fire} recomputes it.
     */
    public void preview(Block block, Classified classified) {
        try {
            if (block == null) {
                return;
            }
            if (classified == null || !classified.isOk()) {
                if (block.getExtFields() == null || block.getExtFields().isEmpty()) {
                    return;
                }
                classified = ChainBlockClassifier.classify(block);
            }
            if (!classified.isOk() || classified.kind() != ExtKind.CHALLENGE
                    || !(classified.value() instanceof ChallengeExt ext)) {
                return;
            }
            Found found = find(ext.anchor());
            if (found == null || found.body.status() != AnchorRecord.Status.CANONICAL) {
                return;
            }
            byte[] witness = assemble(ext.witnessHead(), block);
            if (witness == null || witness.length > maxWitness) {
                return;
            }
            Block anchorBlock = lookup.get(found.body.anchorHash());
            if (anchorBlock == null) {
                return;
            }
            CommitmentChain.Body commitment = commitmentOf(found.body, anchorBlock);
            if (commitment == null) {
                return;
            }
            Bytes32 hash = AnchorBody.hashLow(block.getHash());
            cache.get(hash, () -> Arbiter.judge(store, found.chain, found.seq, found.body, commitment,
                    ext.inputIndex(), witness, lookup, maxChunks));
        } catch (RuntimeException e) {
            return;
        }
    }

    public void fire(long height) {
        voids.clear();
        List<TriggerList.Item> items = new ArrayList<>(TriggerOps.read(store, height));
        View view = new View();
        if (items.isEmpty()) {
            List<TriggerList.Item> marks = deliverable(height, view);
            if (marks.isEmpty()) {
                return;
            }
            TriggerOps.write(view.batch, height, marks);
            store.commit(view.batch);
            flushVoids();
            return;
        }
        List<TriggerList.Item> stay = new ArrayList<>();
        List<TriggerList.Item> defer = new ArrayList<>();
        int seen = 0;
        for (TriggerList.Item item : items) {
            if (item.kind() == TriggerList.CHALLENGE && !item.done()) {
                if (seen >= maxPerHeight) {
                    defer.add(item.deferredTo(height));
                    continue;
                }
                seen++;
                stay.add(judge(item, view));
            } else {
                stay.add(item);
            }
        }
        for (int i = 0; i < stay.size(); i++) {
            TriggerList.Item item = stay.get(i);
            if (item.kind() == TriggerList.FINALITY && !item.done()) {
                stay.set(i, finalize(item, view));
            }
        }
        for (int i = 0; i < stay.size(); i++) {
            TriggerList.Item item = stay.get(i);
            if (item.kind() == TriggerList.UNBOND && !item.done()) {
                stay.set(i, pay(item, height, view));
            }
        }
        stay.addAll(deliverable(height, view));
        TriggerOps.write(view.batch, height, stay);
        if (!defer.isEmpty()) {
            List<TriggerList.Item> next = new ArrayList<>(TriggerOps.read(store, height + 1));
            next.addAll(defer);
            view.batch.putTrigger(height + 1, TriggerList.encode(next));
        }
        store.commit(view.batch);
        flushVoids();
    }

    public void unwind(long height) {
        List<TriggerList.Item> items = new ArrayList<>(TriggerOps.read(store, height));
        View view = new View();
        List<TriggerList.Item> pulled = pull(height, view);
        if (items.isEmpty() && pulled.isEmpty()) {
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            TriggerList.Item item = items.get(i);
            if (item.kind() == TriggerList.DELIVER) {
                if (item.fromHeight() == height) {
                    items.remove(i);
                }
                continue;
            }
            if (!item.done()) {
                continue;
            }
            if (item.kind() == TriggerList.UNBOND) {
                undoUnbond(item, height, view);
            } else if (item.kind() == TriggerList.FINALITY) {
                undoFinality(item, view);
            } else if (item.kind() == TriggerList.CHALLENGE) {
                undoChallenge(item, view);
            }
            items.set(i, item.undone());
        }
        items.addAll(pulled);
        TriggerOps.write(view.batch, height, items);
        store.commit(view.batch);
    }

    private TriggerList.Item judge(TriggerList.Item item, View view) {
        ChallengeRecord record = ChallengeRecord.decode(store.getChallenge(item.headHash()));
        if (record == null || record.status() != ChallengeRecord.PENDING) {
            return item.done(0L);
        }
        AnchorBody anchor = view.anchor(item.chain(), item.seq());
        if (anchor == null || anchor.status() != AnchorRecord.Status.CANONICAL) {
            refund(item.headHash(), record.challenger(), record.deposit());
            view.batch.putChallenge(item.headHash(), judged(record, ChallengeRecord.INVALID, 0L, Bytes32.ZERO, new byte[20]));
            return item.done(0L);
        }
        Arbiter.Verdict verdict = cache.get(item.headHash(), () -> evaluate(item, record, anchor));
        if (verdict == Arbiter.Verdict.SUCCESS) {
            long slashed = slash(record, view);
            Bytes32 oldSeqHash = voidCascade(anchor, item.chain(), item.seq(), view);
            long oldSeq = oldSeqHash == null ? 0L : view.headSeq;
            view.batch.putChallenge(item.headHash(), judged(record, ChallengeRecord.SUCCESS, oldSeq,
                    oldSeqHash == null ? Bytes32.ZERO : oldSeqHash, view.slashedChain));
            return item.done(slashed);
        }
        if (verdict == Arbiter.Verdict.FAIL) {
            pay(BondVault.ADDRESS.toArray(), record.attester(), record.deposit(), item.headHash());
            view.batch.putChallenge(item.headHash(), judged(record, ChallengeRecord.FAIL, 0L, Bytes32.ZERO, new byte[20]));
            return item.done(0L);
        }
        refund(item.headHash(), record.challenger(), record.deposit());
        view.batch.putChallenge(item.headHash(), judged(record, ChallengeRecord.INVALID, 0L, Bytes32.ZERO, new byte[20]));
        return item.done(0L);
    }

    private Arbiter.Verdict evaluate(TriggerList.Item item, ChallengeRecord record, AnchorBody anchor) {
        Block challenge = lookup.get(item.headHash());
        Block anchorBlock = lookup.get(anchor.anchorHash());
        if (challenge == null || anchorBlock == null) {
            return Arbiter.Verdict.FAIL;
        }
        Classified classified = ChainBlockClassifier.classify(challenge);
        if (!classified.isOk() || !(classified.value() instanceof ChallengeExt ext)) {
            return Arbiter.Verdict.FAIL;
        }
        byte[] witness = assemble(ext.witnessHead(), challenge);
        if (witness == null || witness.length > maxWitness) {
            return Arbiter.Verdict.FAIL;
        }
        byte[] commitmentBytes = anchor.inputCount() == 0 && anchor.segmentCount() == 0
                ? new byte[0]
                : assemble(anchor.commitmentHead(), anchorBlock);
        if (commitmentBytes == null) {
            return Arbiter.Verdict.FAIL;
        }
        CommitmentChain.Body commitment = anchor.inputCount() == 0 && anchor.segmentCount() == 0
                ? new CommitmentChain.Body(List.of(), List.of())
                : CommitmentChain.decode(commitmentBytes, anchor.segmentCount(), anchor.inputCount());
        return Arbiter.judge(store, item.chain(), item.seq(), anchor, commitment, record.index(), witness, lookup,
                maxChunks);
    }

    private long slash(ChallengeRecord record, View view) {
        byte[] bondChain = record.chain();
        BondAccount account = view.bond(record.attester(), bondChain);
        if (account == null || account.amount() <= 0) {
            bondChain = BondVault.GLOBAL;
            account = view.bond(record.attester(), bondChain);
        }
        long slash = account == null || account.amount() <= 0 ? 0L : account.amount();
        long half = slash / 2;
        long rest = slash - half;
        if (half > 0) {
            pay(BondVault.ADDRESS.toArray(), record.challenger(), half, tag("slash", record));
        }
        if (rest > 0) {
            pay(BondVault.ADDRESS.toArray(), BondVault.BURN.toArray(), rest, tag("burn", record));
        }
        refund(tag("deposit", record), record.challenger(), record.deposit());
        if (account != null && slash > 0) {
            view.putBond(record.attester(), bondChain, new BondAccount(0L, account.unbondHeight(), account.unbondAmount()));
        }
        view.slashedChain = slash > 0 ? bondChain : new byte[20];
        return slash;
    }

    /** Returns the previous head hash and stashes its seq on the view. */
    private Bytes32 voidCascade(AnchorBody challenged, byte[] chain, long seq, View view) {
        AnchorHead head = view.head(chain);
        if (head == null) {
            return null;
        }
        long top = head.seq();
        view.headSeq = top;
        Bytes32 old = head.anchorHash();
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), chain)) {
                continue;
            }
            long candidate = ExtCodec.u64(key, 21);
            boolean in = candidate == seq || (Long.compareUnsigned(candidate, seq) > 0
                    && Long.compareUnsigned(candidate, top) <= 0);
            if (!in) {
                continue;
            }
            AnchorBody body = view.anchor(chain, candidate);
            if (body != null && body.status() == AnchorRecord.Status.CANONICAL) {
                view.putAnchor(chain, candidate, body.withStatus(AnchorRecord.Status.VOIDED));
                voids.add(new SourceNote(chain.clone(), candidate, body.confirmHeight()));
            }
        }
        view.putHead(chain, new AnchorHead(challenged.prevSeq(), challenged.parent(), head.windowBase(), head.withdrawn()));
        rewindConsumers(chain, seq, view);
        return old;
    }

    /**
     * A cursor whose source seq is the voided anchor or a later one moves back to the earliest
     * declared {@code from} still claimed by a live destination anchor. No such segment deletes it.
     */
    private void rewindConsumers(byte[] src, long voidedSeq, View view) {
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 41 || key[0] != ChainL1Keys.SEGMENT_CURSOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 21, 41), src)) {
                continue;
            }
            byte[] dst = Arrays.copyOfRange(key, 1, 21);
            SegmentCursor cursor = SegmentCursor.decode(store.getCursor(Bytes.wrap(dst), Bytes.wrap(src)));
            if (cursor == null || Long.compareUnsigned(cursor.srcSeq(), voidedSeq) < 0) {
                continue;
            }
            Long from = consumedFrom(dst, src, voidedSeq);
            if (from == null) {
                continue;
            }
            Long prev = previousLive(src, voidedSeq, view);
            if (from == 0L || prev == null) {
                view.batch.deleteCursor(Bytes.wrap(dst), Bytes.wrap(src));
            } else {
                view.batch.putCursor(Bytes.wrap(dst), Bytes.wrap(src), new SegmentCursor(from, prev).encode());
            }
        }
    }

    /** Smallest {@code from} of a live segment at or after {@code voidedSeq}, or 0 when none is declared. {@code null} when a commitment will not decode. */
    private Long consumedFrom(byte[] dst, byte[] src, long voidedSeq) {
        Long from = null;
        boolean undecodable = false;
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), dst)) {
                continue;
            }
            long seq = ExtCodec.u64(key, 21);
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(dst), seq));
            if (body == null || body.segmentCount() == 0L) {
                continue;
            }
            if (body.status() != AnchorRecord.Status.CANONICAL && body.status() != AnchorRecord.Status.FINAL) {
                continue;
            }
            Block block = lookup.get(body.anchorHash());
            CommitmentChain.Body commitment = block == null ? null : commitmentOf(body, block);
            if (commitment == null) {
                undecodable = true;
                continue;
            }
            for (CommitmentChain.Segment segment : commitment.segments()) {
                if (!Arrays.equals(segment.src(), src) || Long.compareUnsigned(segment.srcSeq(), voidedSeq) < 0) {
                    continue;
                }
                if (from == null || Long.compareUnsigned(segment.from(), from) < 0) {
                    from = segment.from();
                }
            }
        }
        if (from != null) {
            return from;
        }
        return undecodable ? null : 0L;
    }

    private Long previousLive(byte[] src, long seq, View view) {
        Long best = null;
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), src)) {
                continue;
            }
            long candidate = ExtCodec.u64(key, 21);
            if (Long.compareUnsigned(candidate, seq) >= 0) {
                continue;
            }
            AnchorBody body = view.anchor(src, candidate);
            if (body == null || (body.status() != AnchorRecord.Status.CANONICAL
                    && body.status() != AnchorRecord.Status.FINAL)) {
                continue;
            }
            if (best == null || Long.compareUnsigned(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private void flushVoids() {
        if (voids.isEmpty()) {
            return;
        }
        List<SourceNote> notes = List.copyOf(voids);
        voids.clear();
        for (SourceNote note : notes) {
            sourceVoided.onSourceVoided(note.chain(), note.seq(), note.confirm());
        }
    }

    private record SourceNote(byte[] chain, long seq, long confirm) {
    }

    private TriggerList.Item finalize(TriggerList.Item item, View view) {
        AnchorBody body = view.anchor(item.chain(), item.seq());
        if (body != null && body.status() == AnchorRecord.Status.CANONICAL) {
            view.putAnchor(item.chain(), item.seq(), body.withStatus(AnchorRecord.Status.FINAL));
        }
        return item.done(0L);
    }

    private TriggerList.Item pay(TriggerList.Item item, long height, View view) {
        BondAccount account = view.bond(item.attester(), item.chain());
        long pay = 0L;
        if (account != null && account.pending() && account.amount() > 0) {
            pay = Math.min(account.unbondAmount(), account.amount());
            if (pay > 0) {
                pay(BondVault.ADDRESS.toArray(), item.attester(), pay, tag("unbond", item.attester(), item.chain(), height));
            }
            view.putBond(item.attester(), item.chain(), new BondAccount(account.amount() - pay, 0L, 0L));
        }
        return item.done(pay);
    }

    private void undoUnbond(TriggerList.Item item, long height, View view) {
        if (item.paid() > 0) {
            settlement.reverse(BondVault.ADDRESS.toArray(), item.attester(), item.paid(),
                    tag("unbond", item.attester(), item.chain(), height));
        }
        BondAccount account = view.bond(item.attester(), item.chain());
        long amount = account == null ? 0L : account.amount();
        view.putBond(item.attester(), item.chain(), new BondAccount(amount + item.paid(), height - window, item.amount()));
    }

    private void undoFinality(TriggerList.Item item, View view) {
        AnchorBody body = view.anchor(item.chain(), item.seq());
        if (body != null && body.status() == AnchorRecord.Status.FINAL) {
            view.putAnchor(item.chain(), item.seq(), body.withStatus(AnchorRecord.Status.CANONICAL));
        }
    }

    private void undoChallenge(TriggerList.Item item, View view) {
        ChallengeRecord record = ChallengeRecord.decode(store.getChallenge(item.headHash()));
        if (record == null) {
            return;
        }
        if (record.status() == ChallengeRecord.SUCCESS) {
            long slash = item.paid();
            long half = slash / 2;
            long rest = slash - half;
            if (half > 0) {
                settlement.reverse(BondVault.ADDRESS.toArray(), record.challenger(), half, tag("slash", record));
            }
            if (rest > 0) {
                settlement.reverse(BondVault.ADDRESS.toArray(), BondVault.BURN.toArray(), rest, tag("burn", record));
            }
            settlement.reverse(BondVault.ADDRESS.toArray(), record.challenger(), record.deposit(), tag("deposit", record));
            if (slash > 0) {
                BondAccount account = view.bond(record.attester(), record.slashed());
                long amount = account == null ? 0L : account.amount();
                long unbondHeight = account == null ? 0L : account.unbondHeight();
                long unbondAmount = account == null ? 0L : account.unbondAmount();
                view.putBond(record.attester(), record.slashed(), new BondAccount(amount + slash, unbondHeight, unbondAmount));
            }
            restore(record, view);
        } else if (record.status() == ChallengeRecord.FAIL) {
            settlement.reverse(BondVault.ADDRESS.toArray(), record.attester(), record.deposit(), item.headHash());
        } else if (record.status() == ChallengeRecord.INVALID) {
            settlement.reverse(BondVault.ADDRESS.toArray(), record.challenger(), record.deposit(), item.headHash());
        }
        view.batch.putChallenge(item.headHash(), judged(record, ChallengeRecord.PENDING, 0L, Bytes32.ZERO, new byte[20]));
    }

    private void restore(ChallengeRecord record, View view) {
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), record.chain())) {
                continue;
            }
            long candidate = ExtCodec.u64(key, 21);
            boolean in = Long.compareUnsigned(candidate, record.seq()) >= 0
                    && Long.compareUnsigned(candidate, record.oldHeadSeq()) <= 0;
            if (!in) {
                continue;
            }
            AnchorBody body = view.anchor(record.chain(), candidate);
            if (body != null && body.status() == AnchorRecord.Status.VOIDED) {
                view.putAnchor(record.chain(), candidate, body.withStatus(AnchorRecord.Status.CANONICAL));
            }
        }
        AnchorHead head = view.head(record.chain());
        long base = head == null ? 0L : head.windowBase();
        long withdrawn = head == null ? 0L : head.withdrawn();
        view.putHead(record.chain(), new AnchorHead(record.oldHeadSeq(), record.oldHeadHash(), base, withdrawn));
    }

    private List<TriggerList.Item> pull(long height, View view) {
        List<TriggerList.Item> next = new ArrayList<>(TriggerOps.read(store, height + 1));
        List<TriggerList.Item> stay = new ArrayList<>();
        List<TriggerList.Item> back = new ArrayList<>();
        for (TriggerList.Item item : next) {
            if (item.kind() == TriggerList.CHALLENGE && item.fromHeight() == height) {
                if (item.done()) {
                    throw new IllegalStateException("deferred challenge is still judged at " + (height + 1));
                }
                back.add(item.home());
            } else {
                stay.add(item);
            }
        }
        if (!back.isEmpty()) {
            TriggerOps.write(view.batch, height + 1, stay);
        }
        return back;
    }

    private CommitmentChain.Body commitmentOf(AnchorBody anchor, Block anchorBlock) {
        byte[] commitmentBytes = anchor.inputCount() == 0 && anchor.segmentCount() == 0 ? new byte[0]
                : assemble(anchor.commitmentHead(), anchorBlock);
        if (commitmentBytes == null) {
            return null;
        }
        if (anchor.inputCount() == 0 && anchor.segmentCount() == 0) {
            return new CommitmentChain.Body(List.of(), List.of());
        }
        return CommitmentChain.decode(commitmentBytes, anchor.segmentCount(), anchor.inputCount());
    }

    private Found find(Bytes32 anchorHash) {
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            byte[] chain = Arrays.copyOfRange(key, 1, 21);
            long seq = ExtCodec.u64(key, 21);
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(chain), seq));
            if (body != null && anchorHash.equals(body.anchorHash())) {
                return new Found(chain, seq, body);
            }
        }
        return null;
    }

    private List<TriggerList.Item> deliverable(long height, View view) {
        List<byte[]> chains = new ArrayList<>();
        List<byte[]> anchors = new ArrayList<>();
        for (byte[] key : store.sortedKeys()) {
            if (key.length == 21 && key[0] == ChainL1Keys.CHAIN) {
                chains.add(Arrays.copyOfRange(key, 1, 21));
            } else if (key.length == 29 && key[0] == ChainL1Keys.ANCHOR) {
                anchors.add(key);
            }
        }
        List<TriggerList.Item> out = new ArrayList<>();
        for (byte[] key : anchors) {
            byte[] src = Arrays.copyOfRange(key, 1, 21);
            long srcSeq = ExtCodec.u64(key, 21);
            AnchorBody body = view.anchor(src, srcSeq);
            if (body == null) {
                continue;
            }
            for (byte[] dst : chains) {
                if (Arrays.equals(dst, src)) {
                    continue;
                }
                ChainRecord record = store.getChain(Bytes.wrap(dst));
                if (record == null) {
                    continue;
                }
                long delay = record.deliveryDelayD();
                boolean finalOnly = delay == window;
                if (finalOnly) {
                    if (body.status() != AnchorRecord.Status.FINAL) {
                        continue;
                    }
                } else if (body.status() != AnchorRecord.Status.CANONICAL
                        && body.status() != AnchorRecord.Status.FINAL) {
                    continue;
                }
                long ready = body.confirmHeight() + delay;
                if (Long.compareUnsigned(ready, body.confirmHeight()) < 0 || ready != height) {
                    continue;
                }
                out.add(new TriggerList.Item(TriggerList.DELIVER, dst, srcSeq, src, body.confirmHeight(), 0L,
                        new byte[20], srcSeq, body.anchorHash(), height, true));
            }
        }
        return out;
    }

    private record Found(byte[] chain, long seq, AnchorBody body) {
    }

    private byte[] assemble(Bytes32 head, Block paying) {
        long minEpoch = XdagTime.getEpoch(paying.getTimestamp()) - 1;
        var assembled = ChunkChain.assemble(head, lookup, maxChunks, minEpoch);
        return assembled.isOk() ? assembled.value().toArray() : null;
    }

    private void pay(byte[] from, byte[] to, long amount, Bytes32 key) {
        if (!settlement.transfer(from, to, amount, key)) {
            throw new IllegalStateException("chain payout short");
        }
    }

    private void refund(Bytes32 key, byte[] challenger, long deposit) {
        pay(BondVault.ADDRESS.toArray(), challenger, deposit, key);
    }

    private static byte[] judged(ChallengeRecord record, int status, long oldSeq, Bytes32 oldHash, byte[] slashed) {
        return new ChallengeRecord(status, record.index(), record.deposit(), record.challenger(), record.attester(),
                record.chain(), record.seq(), oldSeq, oldHash, slashed).encode();
    }

    private static Bytes32 tag(String name, ChallengeRecord record) {
        return tag(name, record.attester(), record.chain(), record.seq());
    }

    private static Bytes32 tag(String name, byte[] a, byte[] b, long n) {
        byte[] raw = new byte[name.length() + 20 + 20 + 8];
        System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, raw, 0, name.length());
        System.arraycopy(a, 0, raw, name.length(), 20);
        System.arraycopy(b, 0, raw, name.length() + 20, 20);
        ExtCodec.putU64(raw, name.length() + 40, n);
        return HashUtils.sha256(Bytes.wrap(raw));
    }

    private final class View {
        final ChainL1Batch batch = new ChainL1Batch();
        final Map<Bytes, AnchorBody> anchors = new HashMap<>();
        final Map<Bytes, BondAccount> bonds = new HashMap<>();
        final Map<Bytes, AnchorHead> heads = new HashMap<>();
        byte[] slashedChain = new byte[20];
        long headSeq;

        AnchorBody anchor(byte[] chain, long seq) {
            Bytes key = akey(chain, seq);
            if (anchors.containsKey(key)) {
                return anchors.get(key);
            }
            return AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(chain), seq));
        }

        void putAnchor(byte[] chain, long seq, AnchorBody body) {
            anchors.put(akey(chain, seq), body);
            batch.putAnchor(Bytes.wrap(chain), seq, body.encode());
        }

        BondAccount bond(byte[] attester, byte[] chain) {
            Bytes key = bkey(attester, chain);
            if (bonds.containsKey(key)) {
                return bonds.get(key);
            }
            return BondAccount.decode(store.getBond(Bytes.wrap(attester), Bytes.wrap(chain)));
        }

        void putBond(byte[] attester, byte[] chain, BondAccount account) {
            bonds.put(bkey(attester, chain), account);
            batch.putBond(Bytes.wrap(attester), Bytes.wrap(chain), account.encode());
        }

        AnchorHead head(byte[] chain) {
            Bytes key = Bytes.wrap(chain);
            if (heads.containsKey(key)) {
                return heads.get(key);
            }
            return AnchorHead.decode(store.getAnchorHead(Bytes.wrap(chain)));
        }

        void putHead(byte[] chain, AnchorHead head) {
            heads.put(Bytes.wrap(chain), head);
            batch.putAnchorHead(Bytes.wrap(chain), head.encode());
        }
    }

    private static Bytes akey(byte[] chain, long seq) {
        byte[] raw = new byte[28];
        System.arraycopy(chain, 0, raw, 0, 20);
        ExtCodec.putU64(raw, 20, seq);
        return Bytes.wrap(raw);
    }

    private static Bytes bkey(byte[] a, byte[] b) {
        byte[] raw = new byte[40];
        System.arraycopy(a, 0, raw, 0, 20);
        System.arraycopy(b, 0, raw, 20, 20);
        return Bytes.wrap(raw);
    }
}
