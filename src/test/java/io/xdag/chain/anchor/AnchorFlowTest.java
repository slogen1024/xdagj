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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.ClaimProof;
import io.xdag.chain.asset.Settlement;
import io.xdag.chain.asset.WithdrawOutbox;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.InputStreamBuilder;
import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.ext.AnchorExt;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChallengeExt;
import io.xdag.chain.ext.ClaimExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.fraud.Arbiter;
import io.xdag.chain.fraud.Challenger;
import io.xdag.chain.fraud.VerdictCache;
import io.xdag.chain.fraud.Witness;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.chain.l1.handlers.AnchorHandler;
import io.xdag.chain.l1.handlers.BondHandler;
import io.xdag.chain.l1.handlers.ChallengeHandler;
import io.xdag.chain.l1.handlers.ClaimHandler;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.AddressStoreImpl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class AnchorFlowTest {

    private static final long TS = 0x16a00000000L;
    private static final long MIN_BOND = 1_000L;
    private static final long WINDOW = 4L;

    @Test
    public void vaultAddressesAreTheTaggedDigests() {
        assertArrayEquals(tag("xdag-chain-bond"), BondVault.ADDRESS.toArray());
        assertArrayEquals(tag("xdag-chain-burn"), BondVault.BURN.toArray());
    }

    @Test
    public void bondGrowsAndASecondUnbondIsRejected() {
        World w = new World();
        assertTrue(w.bond(w.chain, 1_000L, 1L));
        assertEquals(1_000L, w.account(w.attester, w.chain).amount());
        assertTrue(w.bond(w.chain, 250L, 2L));
        assertEquals(1_250L, w.account(w.attester, w.chain).amount());
        assertTrue(w.unbond(w.chain, 400L, 3L));
        assertTrue(w.account(w.attester, w.chain).pending());
        assertFalse(w.unbond(w.chain, 100L, 4L));
        assertEquals(400L, w.account(w.attester, w.chain).unbondAmount());
    }

    @Test
    public void maturityPaysTheSmallerOfRequestAndRemaining() {
        World w = new World();
        w.fund(BondVault.ADDRESS, 1_000L);
        assertTrue(w.bond(w.chain, 1_000L, 1L));
        assertTrue(w.unbond(w.chain, 800L, 5L));
        w.putBond(w.attester, w.chain, new BondAccount(300L, 5L, 800L));
        w.triggers.fire(5L + WINDOW);
        assertEquals(300L, w.balance(w.attester));
        assertEquals(700L, w.balance(BondVault.ADDRESS));
        BondAccount left = w.account(w.attester, w.chain);
        assertEquals(0L, left.amount());
        assertFalse(left.pending());
        w.triggers.unwind(5L + WINDOW);
        assertEquals(0L, w.balance(w.attester));
        assertEquals(1_000L, w.balance(BondVault.ADDRESS));
        assertEquals(800L, w.account(w.attester, w.chain).unbondAmount());
    }

    @Test
    public void shortBondRejectsTheAnchorAndAGoodOneMovesTheHead() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        AnchorExt ext = w.ext(w.chain, 2L, 1L, 0L, 0L);
        assertFalse(w.anchor(ext, w.hashLow("a1"), new byte[0], 10L));
        assertNull(w.head(w.chain));
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        assertTrue(w.anchor(ext, w.hashLow("a1"), new byte[0], 10L));
        AnchorHead head = w.head(w.chain);
        assertEquals(2L, head.seq());
        assertEquals(w.hashLow("a1"), head.anchorHash());
        assertEquals(AnchorRecord.Status.CANONICAL, w.body(w.chain, 2L).status());
    }

    @Test
    public void sameSeqIsIgnoredBesideTheShortKey() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        AnchorExt ext = w.ext(w.chain, 2L, 1L, 0L, 0L);
        Bytes32 winner = w.hashLow("win");
        Bytes32 loser = w.hashLow("lose");
        assertTrue(w.anchor(ext, winner, new byte[0], 10L));
        assertTrue(w.anchor(ext, loser, new byte[0], 11L));
        assertEquals(winner, w.body(w.chain, 2L).anchorHash());
        assertEquals(winner, w.head(w.chain).anchorHash());
        assertTrue(w.hasIgnored(w.chain, 2L, loser));
        w.undoAnchor(ext, loser, new byte[0]);
        assertEquals(winner, w.body(w.chain, 2L).anchorHash());
        assertFalse(w.hasIgnored(w.chain, 2L, loser));
    }

    @Test
    public void badCommitmentAndInputCountAreRejected() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        AnchorExt badLen = w.ext(w.chain, 2L, 1L, 0L, 1L);
        assertFalse(w.anchor(badLen, w.hashLow("bad"), new byte[10], 10L));
        assertNull(w.head(w.chain));
        AnchorExt badCount = w.ext(w.chain, 2L, 1L, 5L, 0L);
        assertFalse(w.anchor(badCount, w.hashLow("count"), new byte[0], 10L));
        assertNull(w.body(w.chain, 2L));
    }

    @Test
    public void finalityFlipsAndUnwindRestores() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        AnchorExt ext = w.ext(w.chain, 2L, 1L, 0L, 0L);
        Bytes32 hash = w.hashLow("fin");
        assertTrue(w.anchor(ext, hash, new byte[0], 10L));
        assertEquals(1, TriggerOps.read(w.store, 10L + WINDOW).size());
        w.undoAnchor(ext, hash, new byte[0]);
        assertNull(w.body(w.chain, 2L));
        assertNull(w.head(w.chain));
        assertTrue(TriggerOps.read(w.store, 10L + WINDOW).isEmpty());

        assertTrue(w.anchor(ext, hash, new byte[0], 10L));
        w.triggers.fire(10L + WINDOW);
        assertEquals(AnchorRecord.Status.FINAL, w.body(w.chain, 2L).status());
        w.triggers.unwind(10L + WINDOW);
        assertEquals(AnchorRecord.Status.CANONICAL, w.body(w.chain, 2L).status());
        assertFalse(TriggerOps.read(w.store, 10L + WINDOW).get(0).done());
    }

    @Test
    public void wrongCallInputVoidsAndSplitsTheBond() {
        slashBadCall();
    }

    /** The SP4 three-node drill calls this after the destinations have diverged on delivery. */
    public static void slashBadCall() {
        World w = new World();
        OpenedAnchor opened = w.openAnchor(w.hashLow("wrong-call"), false);
        w.fund(BondVault.ADDRESS, MIN_BOND + 100L);
        w.fileChallenge(opened, Witness.e1(opened.opening), 100L, 4L);
        w.triggers.fire(4L);
        assertEquals(AnchorRecord.Status.VOIDED, w.body(w.chain, opened.ext.seq()).status());
        assertEquals(opened.ext.prevSeq(), w.head(w.chain).seq());
        assertEquals(0L, w.account(w.attester, w.chain).amount());
        assertEquals(MIN_BOND / 2 + 100L, w.balance(w.challenger));
        assertEquals(MIN_BOND - MIN_BOND / 2, w.balance(BondVault.BURN));
        assertEquals(0L, w.balance(BondVault.ADDRESS));
        assertEquals(ChallengeRecord.SUCCESS, w.challenge(opened.challenge).status());

        w.triggers.unwind(4L);
        w.undoChallenge(opened.challenge);
        w.undoAnchor(opened.ext, opened.anchorHash, opened.commitment);
        w.undoBond(w.chain, MIN_BOND);
        assertNull(w.body(w.chain, opened.ext.seq()));
        assertNull(w.head(w.chain));
        assertNull(w.account(w.attester, w.chain));
        assertNull(w.challenge(opened.challenge));
        assertEquals(0L, w.balance(w.challenger));
        assertEquals(0L, w.balance(BondVault.BURN));
        assertEquals(MIN_BOND + 100L, w.balance(BondVault.ADDRESS));
    }

    @Test
    public void matchingCallInputPaysTheAttester() {
        World w = new World();
        OpenedAnchor opened = w.openAnchor(w.hashLow("right-call"), true);
        w.fund(BondVault.ADDRESS, 100L);
        w.fileChallenge(opened, Witness.e1(opened.opening), 100L, 4L);
        w.triggers.fire(4L);
        assertEquals(AnchorRecord.Status.CANONICAL, w.body(w.chain, opened.ext.seq()).status());
        assertEquals(MIN_BOND, w.account(w.attester, w.chain).amount());
        assertEquals(100L, w.balance(w.attester));
        assertEquals(0L, w.balance(w.challenger));
        assertEquals(ChallengeRecord.FAIL, w.challenge(opened.challenge).status());
    }

    @Test
    public void outboxSizeMismatchVoids() {
        World w = new World();
        w.plantSourceOutbox(4L);
        byte[] dst = w.chain.toArray();
        CommitmentChain.Segment segment = new CommitmentChain.Segment(w.source.toArray(), 2L, 0L, 9L);
        List<Bytes32> commitments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            commitments.add(Bytes32.repeat((byte) i));
        }
        byte[] payload = CommitmentChain.encode(List.of(segment), commitments);
        OpenedAnchor opened = w.openAnchorWith(payload, 10L, 1L, 1L);
        var proof = w.lastMap.proof(ChainExecutor.chain32(dst));
        byte[] witness = Witness.e3(w.source.toArray(), 2L, dst, 9L, true, proof.value(), proof.siblings());
        w.fund(BondVault.ADDRESS, MIN_BOND + 100L);
        w.fileChallenge(opened, witness, 100L, 4L);
        byte[] kept = new byte[20];
        kept[0] = 1;
        byte[] dropped = new byte[20];
        dropped[0] = 2;
        ChainL1Batch cursors = new ChainL1Batch();
        cursors.putCursor(Bytes.wrap(kept), w.chain, new SegmentCursor(1L, 1L).encode());
        cursors.putCursor(Bytes.wrap(dropped), w.chain, new SegmentCursor(9L, opened.ext.seq()).encode());
        w.store.commit(cursors);
        w.triggers.fire(4L);
        assertNull(w.store.getCursor(Bytes.wrap(dropped), w.chain));
        assertEquals(1L, SegmentCursor.decode(w.store.getCursor(Bytes.wrap(kept), w.chain)).srcSeq());
        assertEquals(ChallengeRecord.SUCCESS, w.challenge(opened.challenge).status());
        assertEquals(AnchorRecord.Status.VOIDED, w.body(w.chain, opened.ext.seq()).status());
        assertEquals(0L, w.account(w.attester, w.chain).amount());
    }

    @Test
    public void fifthChallengeWaitsForTheNextHeight() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        Bytes32 anchor = w.hashLow("queued");
        w.putBody(w.chain, 2L, anchor, 1L);
        w.fund(BondVault.ADDRESS, 400L);
        List<Bytes32> hashes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Bytes32 hash = w.hashLow("ch" + i);
            hashes.add(hash);
            ChallengeExt ext = new ChallengeExt(0L, 100L, anchor, Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            assertTrue(w.challenges.file(ext, hash, w.challenger, 100L, Witness.e2(), 4L, batch));
            w.store.commit(batch);
        }
        w.triggers.fire(4L);
        assertEquals(4, TriggerOps.read(w.store, 4L).stream().filter(TriggerList.Item::done).count());
        List<TriggerList.Item> next = TriggerOps.read(w.store, 5L);
        assertEquals(1, next.size());
        assertEquals(hashes.get(4), next.get(0).headHash());
        assertEquals(4L, next.get(0).fromHeight());
        assertEquals(ChallengeRecord.PENDING, w.challenge(hashes.get(4)).status());
        assertEquals(400L, w.balance(w.attester));
    }

    @Test
    public void malformedE2PaysTheAttester() {
        World w = new World();
        OpenedAnchor opened = w.openAnchor(w.hashLow("e2"), true);
        w.fund(BondVault.ADDRESS, 100L);
        w.fileChallenge(opened, Witness.e2(), 100L, 4L);
        w.triggers.fire(4L);
        assertEquals(ChallengeRecord.FAIL, w.challenge(opened.challenge).status());
        assertEquals(MIN_BOND, w.account(w.attester, w.chain).amount());
        assertEquals(100L, w.balance(w.attester));
        assertEquals(0L, w.balance(w.challenger));
        assertEquals(AnchorRecord.Status.CANONICAL, w.body(w.chain, opened.ext.seq()).status());
        assertEquals(0L, w.balance(BondVault.BURN));
    }

    @Test
    public void deliverableMarkAppearsAndUnwinds() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        w.plantSourceOutbox(1L);
        w.triggers.fire(1L);
        List<TriggerList.Item> marks = new InputStreamBuilder(w.store).deliverableMarks(w.chain, 1L);
        assertEquals(1, marks.size());
        assertEquals(TriggerList.DELIVER, marks.get(0).kind());
        assertArrayEquals(w.source.toArray(), marks.get(0).attester());
        assertEquals(2L, marks.get(0).seq());
        w.triggers.unwind(1L);
        assertTrue(new InputStreamBuilder(w.store).deliverableMarks(w.chain, 1L).isEmpty());
    }

    @Test
    public void previewCachesTheVerdictOffTheFirePath() {
        World w = new World();
        OpenedAnchor opened = w.openAnchor(w.hashLow("cached"), true);
        var missing = ChainBlockBuilder.challenge(w.config, TS, w.challengerKey, UInt64.valueOf(40), XAmount.ZERO, 0L,
                100L, w.hashLow("no-anchor"), Bytes.wrap(Witness.e2()));
        assertTrue(missing.isOk());
        w.keep(missing.value());
        w.triggers.preview(missing.value().block(), null);
        assertEquals(0, w.cache.misses());
        var built = ChainBlockBuilder.challenge(w.config, TS, w.challengerKey, UInt64.valueOf(41), XAmount.ZERO, 0L,
                100L, opened.anchorHash, Bytes.wrap(Witness.e2()));
        assertTrue(built.isOk());
        w.keep(built.value());
        w.triggers.preview(built.value().block(), null);
        w.triggers.preview(built.value().block(), null);
        assertEquals(1, w.cache.misses());
        w.fund(BondVault.ADDRESS, 100L);
        ChainL1Batch batch = new ChainL1Batch();
        w.challenges.onApplied(built.value().block(), ChainBlockClassifier.classify(built.value().block()),
                new ApplyContext(4L, Bytes32.ZERO), batch);
        w.store.commit(batch);
        w.triggers.fire(4L);
        assertEquals(1, w.cache.misses());
        assertEquals(ChallengeRecord.FAIL, w.challenge(AnchorBody.hashLow(built.value().block().getHash())).status());
    }

    @Test
    public void publisherBuildsTheRewardSlot() {
        World w = new World();
        w.plantChain(w.chain, 1L);
        assertTrue(w.bond(w.chain, MIN_BOND, 1L));
        Bytes32 main = w.hashLow("pub-main");
        w.mains.put(3L, main);
        ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        exec.start();
        var built = new AnchorPublisher(w.store, exec, WINDOW).publish(w.config, TS, w.attesterKey, UInt64.valueOf(7),
                XAmount.ZERO, w.chain, 3L, 3L, main, Bytes32.ZERO, Bytes32.ZERO, List.of(Bytes32.ZERO));
        assertTrue(built.isOk());
        AnchorExt ext = ChainBlockClassifier.classify(built.value().block()).as(AnchorExt.class);
        assertEquals(1L, ext.inputCount());
        assertEquals(0L, ext.segmentCount());
        assertTrue(w.anchor(ext, AnchorBody.hashLow(built.value().block().getHash()), Bytes32.ZERO.toArray(), 3L));
        assertEquals(3L, w.head(w.chain).seq());
    }

    @Test
    public void challengerFilesTheFirstDivergence() {
        World w = new World();
        List<Bytes32> local = List.of(Bytes32.ZERO, Bytes32.repeat((byte) 1));
        List<Bytes32> claimed = List.of(Bytes32.ZERO, Bytes32.repeat((byte) 2));
        assertEquals(1, Challenger.diverge(local, claimed));
        assertEquals(-1, Challenger.diverge(local, local));
        Bytes32 anchor = w.hashLow("div");
        var built = Challenger.file(w.config, TS, w.challengerKey, UInt64.valueOf(8), XAmount.ZERO, 1L, 100L, anchor,
                Witness.e2());
        assertTrue(built.isOk());
        ChallengeExt ext = ChainBlockClassifier.classify(built.value().block()).as(ChallengeExt.class);
        assertEquals(1L, ext.inputIndex());
        assertEquals(anchor, ext.anchor());
    }

    @Test
    public void withdrawCapBlocksTheClaimPastTheBond() {
        World w = new World();
        byte[] small = WithdrawOutbox.withdraw(w.chain, w.attester, w.user, 100L, 1L, 0L);
        byte[] big = WithdrawOutbox.withdraw(w.chain, w.attester, w.user, 101L, 1L, 1L);
        Mmr mmr = new Mmr();
        mmr.append(HashUtils.sha256(Bytes.wrap(small)));
        mmr.append(HashUtils.sha256(Bytes.wrap(big)));
        OutboxMap map = new OutboxMap();
        Bytes32 key = ChainExecutor.chain32(new byte[20]);
        map.put(key, mmr.root(), mmr.size());
        byte[] proof100 = ClaimProof.encode(new byte[20], map.proof(key), mmr.proof(0), small);
        byte[] proof101 = ClaimProof.encode(new byte[20], map.proof(key), mmr.proof(1), big);
        Bytes32 anchor = w.hashLow("claim");
        ChainL1Batch batch = new ChainL1Batch();
        batch.putAnchor(w.chain, 2L, new AnchorRecord(AnchorRecord.Status.FINAL, anchor, map.root()).encode());
        batch.putAnchorHead(w.chain, new AnchorHead(2L, anchor, 0L, 0L).encode());
        batch.putBond(Bytes.wrap(w.attester), w.chain, new BondAccount(100L, 0L, 0L).encode());
        w.store.commit(batch);
        w.fund(w.chain, 1_000L);
        ClaimHandler claims = new ClaimHandler(w.store, hash -> null, 64, w.settlement, WithdrawWindow.of(w.store, WINDOW));
        assertFalse(claims.settle(w.claim(anchor, 101L, 1L), proof101, w.hashLow("c101"), 0L, new ChainL1Batch()));
        assertEquals(1_000L, w.balance(w.chain));
        ChainL1Batch ok = new ChainL1Batch();
        assertTrue(claims.settle(w.claim(anchor, 100L, 0L), proof100, w.hashLow("c100"), 0L, ok));
        w.store.commit(ok);
        assertEquals(900L, w.balance(w.chain));
        assertEquals(100L, w.balance(w.user));
        assertEquals(100L, w.head(w.chain).withdrawn());
        assertFalse(claims.settle(w.claim(anchor, 101L, 1L), proof101, w.hashLow("c101b"), 0L, new ChainL1Batch()));
        claims.undo(w.hashLow("c100"), ok = new ChainL1Batch());
        w.store.commit(ok);
        assertEquals(0L, w.head(w.chain).withdrawn());
        assertEquals(1_000L, w.balance(w.chain));
    }

    @Test
    public void verdictCacheReturnsTheFirstAnswer() {
        VerdictCache cache = new VerdictCache();
        int[] calls = {0};
        Arbiter.Verdict first = cache.get(Bytes32.ZERO, () -> {
            calls[0]++;
            return Arbiter.Verdict.FAIL;
        });
        Arbiter.Verdict second = cache.get(Bytes32.ZERO, () -> {
            calls[0]++;
            return Arbiter.Verdict.SUCCESS;
        });
        assertEquals(first, second);
        assertEquals(Arbiter.Verdict.FAIL, second);
        assertEquals(1, calls[0]);
        assertEquals(1, cache.size());
    }

    private static byte[] tag(String name) {
        return HashUtils.sha256(Bytes.wrap(name.getBytes(StandardCharsets.US_ASCII))).slice(0, 20).toArray();
    }

    private static final class World {
        final DevnetConfig config = new DevnetConfig();
        final ECKeyPair attesterKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        final ECKeyPair challengerKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY2);
        final byte[] attester = attesterKey.toAddress().toArray();
        final byte[] challenger = challengerKey.toAddress().toArray();
        final byte[] user = new byte[20];
        final Bytes chain = chain(1);
        final Bytes source = chain(2);
        final ChainL1Store store = new ChainL1Store(new InMemoryKVSource());
        final AddressStoreImpl addresses = new AddressStoreImpl(new InMemoryKVSource());
        final Map<Bytes32, Block> blocks = new HashMap<>();
        final Map<Long, Bytes32> mains = new HashMap<>();
        final ClaimHandler.Settlement settlement = new ClaimHandler.Settlement() {
            @Override
            public boolean transfer(byte[] from, byte[] to, long amount, Bytes32 journalKey) {
                return Settlement.move(addresses, from, to, amount);
            }

            @Override
            public void reverse(byte[] from, byte[] to, long amount, Bytes32 journalKey) {
                if (!Settlement.move(addresses, to, from, amount)) {
                    throw new IllegalStateException("short");
                }
            }
        };
        final BondHandler bonds;
        final AnchorHandler anchors;
        final ChallengeHandler challenges;
        final HeightTriggers triggers;
        final VerdictCache cache = new VerdictCache();
        OutboxMap lastMap;
        int nonce = 1;

        World() {
            user[0] = 9;
            store.start();
            addresses.start();
            bonds = new BondHandler(store, WINDOW);
            anchors = new AnchorHandler(store, blocks::get, mains::get, MIN_BOND, WINDOW, 64);
            challenges = new ChallengeHandler(store, blocks::get, settlement, MIN_BOND, 1 << 20, 64);
            triggers = new HeightTriggers(store, blocks::get, settlement, WINDOW, 4, 1 << 20, 64, cache);
        }

        boolean bond(Bytes id, long paid, long height) {
            ChainL1Batch batch = new ChainL1Batch();
            boolean ok = bonds.apply(new BondExt(false, id, 0L), attester, paid, height, batch);
            if (ok) {
                store.commit(batch);
            }
            return ok;
        }

        boolean unbond(Bytes id, long amount, long height) {
            ChainL1Batch batch = new ChainL1Batch();
            boolean ok = bonds.apply(new BondExt(true, id, amount), attester, 0L, height, batch);
            if (ok) {
                store.commit(batch);
            }
            return ok;
        }

        void undoBond(Bytes id, long paid) {
            ChainL1Batch batch = new ChainL1Batch();
            bonds.undo(new BondExt(false, id, 0L), attester, paid, batch);
            store.commit(batch);
        }

        boolean anchor(AnchorExt ext, Bytes32 hash, byte[] commitment, long height) {
            ChainL1Batch batch = new ChainL1Batch();
            boolean ok = anchors.apply(ext, hash, attester, height, commitment, batch);
            if (ok) {
                store.commit(batch);
            }
            return ok;
        }

        void undoAnchor(AnchorExt ext, Bytes32 hash, byte[] commitment) {
            ChainL1Batch batch = new ChainL1Batch();
            anchors.undo(ext, hash, commitment, batch);
            store.commit(batch);
        }

        AnchorExt ext(Bytes id, long seq, long prev, long inputs, long segments) {
            Bytes32 main = hashLow("main" + seq);
            mains.put(seq, main);
            Bytes32 parent = AnchorBody.hashLow(deploy(id));
            return new AnchorExt(id, seq, Bytes32.ZERO, Bytes32.ZERO, prev, inputs, segments, parent, main, Bytes32.ZERO);
        }

        void plantChain(Bytes id, long created) {
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(id, new ChainRecord(created, deploy(id), 1L, 0L, 1_000_000L, 0L));
            store.commit(batch);
        }

        Bytes32 deploy(Bytes id) {
            byte[] raw = new byte[32];
            System.arraycopy(id.toArray(), 0, raw, 12, 20);
            raw[0] = 7;
            return Bytes32.wrap(raw);
        }

        OpenedAnchor openAnchor(Bytes32 inputHash, boolean match) {
            plantChain(chain, 1L);
            assertTrue(bond(chain, MIN_BOND, 1L));
            Bytes32 openedHash = match ? inputHash : hashLow("opened");
            byte[] opening = ProofBodies.encodeCommitment(new ProofBodies.Opened(openedHash, Bytes32.ZERO, Bytes32.ZERO,
                    Bytes32.ZERO, 1, 0L));
            byte[] payload = new byte[64];
            System.arraycopy(HashUtils.sha256(Bytes.wrap(opening)).toArray(), 0, payload, 0, 32);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putCallCount(chain, 2L, 1L);
            batch.putInput(chain, 2L, 0L, new InputRecord(inputHash, ExtKind.CALL, InputStatus.OK, Bytes.wrap(new byte[20])));
            store.commit(batch);
            return openAnchorWith(payload, 2L, 0L, 1L, opening);
        }

        OpenedAnchor openAnchorWith(byte[] payload, long inputs, long segments, long segmentCount) {
            return openAnchorWith(payload, inputs, segments, segmentCount, new byte[0]);
        }

        OpenedAnchor openAnchorWith(byte[] payload, long inputs, long segments, long ignored, byte[] opening) {
            plantChain(chain, 1L);
            if (account(attester, chain) == null) {
                assertTrue(bond(chain, MIN_BOND, 1L));
            }
            Bytes32 main = hashLow("main3");
            mains.put(3L, main);
            XAmount fee = XAmount.ZERO;
            var built = ChainBlockBuilder.anchor(config, TS, attesterKey, UInt64.valueOf(nonce++), fee, chain, 3L,
                    Bytes32.ZERO, Bytes32.ZERO, 1L, inputs, segments,
                    AnchorBody.hashLow(deploy(chain)), main, Bytes.wrap(payload));
            assertTrue(built.isOk());
            keep(built.value());
            AnchorExt ext = ChainBlockClassifier.classify(built.value().block()).as(AnchorExt.class);
            ChainL1Batch batch = new ChainL1Batch();
            anchors.onApplied(built.value().block(), ChainBlockClassifier.classify(built.value().block()),
                    new ApplyContext(3L, Bytes32.ZERO), batch);
            assertFalse(batch.isEmpty());
            store.commit(batch);
            return new OpenedAnchor(ext, AnchorBody.hashLow(built.value().block().getHash()), payload, opening, null);
        }

        void fileChallenge(OpenedAnchor opened, byte[] witness, long deposit, long height) {
            var built = ChainBlockBuilder.challenge(config, TS, challengerKey, UInt64.valueOf(nonce++), XAmount.ZERO,
                    0L, deposit, opened.anchorHash, Bytes.wrap(witness));
            assertTrue(built.isOk());
            keep(built.value());
            ChainL1Batch batch = new ChainL1Batch();
            challenges.onApplied(built.value().block(), ChainBlockClassifier.classify(built.value().block()),
                    new ApplyContext(height, Bytes32.ZERO), batch);
            assertFalse("challenge was not filed", batch.isEmpty());
            store.commit(batch);
            opened.challenge = AnchorBody.hashLow(built.value().block().getHash());
        }

        void undoChallenge(Bytes32 hash) {
            ChainL1Batch batch = new ChainL1Batch();
            challenges.undo(hash, batch);
            store.commit(batch);
        }

        Bytes32 plantSourceOutbox(long size) {
            plantChain(source, 1L);
            lastMap = new OutboxMap();
            Mmr mmr = new Mmr();
            mmr.append(HashUtils.sha256(Bytes.wrap("leaf".getBytes(StandardCharsets.US_ASCII))));
            lastMap.put(ChainExecutor.chain32(chain.toArray()), mmr.root(), size);
            Bytes32 hash = hashLow("src-anchor");
            AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, hash, lastMap.root(), Bytes32.ZERO, 1L, 0L,
                    0L, 1L, AnchorBody.hashLow(deploy(source)), attester, Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(source, 2L, body.encode());
            store.commit(batch);
            return mmr.root();
        }

        void putBody(Bytes id, long seq, Bytes32 hash, long inputs) {
            AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, hash, Bytes32.ZERO, Bytes32.ZERO, 1L,
                    inputs, 0L, 1L, AnchorBody.hashLow(deploy(id)), attester, Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(id, seq, body.encode());
            store.commit(batch);
        }

        void putBond(byte[] who, Bytes id, BondAccount account) {
            ChainL1Batch batch = new ChainL1Batch();
            batch.putBond(Bytes.wrap(who), id, account.encode());
            store.commit(batch);
        }

        ClaimExt claim(Bytes32 anchor, long amount, long index) {
            return new ClaimExt(false, chain, 2L, index, amount, Bytes.wrap(user), anchor, Bytes32.ZERO);
        }

        void keep(ChainBlockBuilder.Built built) {
            blocks.put(Bytes32.wrap(built.block().getHashLow().toArray()), built.block());
            for (Block chunk : built.chunks()) {
                blocks.put(Bytes32.wrap(chunk.getHashLow().toArray()), chunk);
            }
        }

        void fund(Bytes address, long nano) {
            addresses.updateBalance(address.toArray(), XAmount.of(nano));
        }

        long balance(byte[] address) {
            return Long.parseLong(addresses.getBalanceByAddress(address).toString());
        }

        long balance(Bytes address) {
            return balance(address.toArray());
        }

        BondAccount account(byte[] who, Bytes id) {
            return BondAccount.decode(store.getBond(Bytes.wrap(who), id));
        }

        AnchorHead head(Bytes id) {
            return AnchorHead.decode(store.getAnchorHead(id));
        }

        AnchorBody body(Bytes id, long seq) {
            return AnchorBody.decode(store.getAnchorRaw(id, seq));
        }

        ChallengeRecord challenge(Bytes32 hash) {
            return ChallengeRecord.decode(store.getChallenge(hash));
        }

        boolean hasIgnored(Bytes id, long seq, Bytes32 hash) {
            byte[] key = ChainL1Keys.ignoredAnchor(id, seq, hash);
            for (byte[] have : store.sortedKeys()) {
                if (java.util.Arrays.equals(have, key)) {
                    return true;
                }
            }
            return false;
        }

        Bytes32 hashLow(String name) {
            return AnchorBody.hashLow(HashUtils.sha256(Bytes.wrap(name.getBytes(StandardCharsets.US_ASCII))));
        }
    }

    private static Bytes chain(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }

    private static final class OpenedAnchor {
        final AnchorExt ext;
        final Bytes32 anchorHash;
        final byte[] commitment;
        final byte[] opening;
        Bytes32 challenge;

        OpenedAnchor(AnchorExt ext, Bytes32 anchorHash, byte[] commitment, byte[] opening, Bytes32 challenge) {
            this.ext = ext;
            this.anchorHash = anchorHash;
            this.commitment = commitment;
            this.opening = opening;
            this.challenge = challenge;
        }
    }
}
