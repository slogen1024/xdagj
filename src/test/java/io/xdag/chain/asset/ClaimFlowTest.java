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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.WithdrawWindow;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ClaimExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.handlers.ClaimHandler;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.AddressStoreImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class ClaimFlowTest {

    private static final long TS = 0x16a00000000L;
    private static final long SEQ = 4L;
    private static final Bytes32 ANCHOR = HashUtils.sha256(Bytes.wrap("anchor".getBytes()));
    private static final ApplyContext CTX = new ApplyContext(1L, Bytes32.ZERO);

    @Test
    public void entryRoundTripsAndLeafHasNoTag() {
        byte[] raw = WithdrawOutbox.withdraw(chain(1), addr(2), addr(3), 9L, 8L, 0L);
        OutboxEntry entry = OutboxEntry.decode(raw);
        assertNotNull(entry);
        assertArrayEquals(raw, entry.encode());
        assertArrayEquals(addr(3), entry.contract());
        assertTrue(isZero(entry.dst()));
        assertEquals(HashUtils.sha256(Bytes.wrap(raw)), entry.leaf());
    }

    @Test
    public void goodProofPaysBadProofDoesNotAndNonFinalIsRefused() {
        byte[] src = chain(1).toArray();
        byte[] user = addr(3);
        byte[] raw = WithdrawOutbox.withdraw(Bytes.wrap(src), addr(2), user, 9L, 8L, 0L);
        BuiltProof built = prove(src, new byte[20], List.of(raw), 0);
        ClaimExt claim = ext(false, src, 0L, 9L, user, built.root);
        assertEquals(ClaimVerifier.Reason.OK, verify(claim, built.proof, built.root, false).reason());
        byte[] bad = built.proof.clone();
        bad[bad.length - 1] ^= 0x01;
        assertEquals(ClaimVerifier.Reason.BAD_PROOF, verify(claim, bad, built.root, false).reason());
        assertEquals(ClaimVerifier.Reason.ALREADY_CLAIMED, verify(claim, built.proof, built.root, true).reason());
        assertEquals(ClaimVerifier.Reason.NO_ANCHOR, ClaimVerifier.verify(claim, built.proof, null, (d, i) -> false).reason());
        AnchorRecord canonical = new AnchorRecord(AnchorRecord.Status.CANONICAL, ANCHOR, built.root);
        assertEquals(ClaimVerifier.Reason.NOT_FINAL,
                ClaimVerifier.verify(claim, built.proof, canonical, (d, i) -> false).reason());
        ClaimExt wrongAmount = ext(false, src, 0L, 8L, user, built.root);
        assertEquals(ClaimVerifier.Reason.AMOUNT, verify(wrongAmount, built.proof, built.root, false).reason());
    }

    @Test
    public void twoDestinationsDoNotCollide() {
        byte[] src = chain(1).toArray();
        byte[] l1 = new byte[20];
        byte[] remote = chain(2).toArray();
        byte[] withdraw = WithdrawOutbox.withdraw(Bytes.wrap(src), addr(4), addr(5), 3L, 1L, 0L);
        byte[] send = WithdrawOutbox.send(Bytes.wrap(src), remote, addr(4), addr(6), 7, new byte[0], 4L, 1L, 0L);
        OutboxMap map = new OutboxMap();
        Mmr l1Mmr = mmr(withdraw);
        Mmr remoteMmr = mmr(send);
        map.put(ChainExecutor.chain32(l1), l1Mmr.root(), l1Mmr.size());
        map.put(ChainExecutor.chain32(remote), remoteMmr.root(), remoteMmr.size());
        byte[] l1Proof = ClaimProof.encode(l1, map.proof(ChainExecutor.chain32(l1)), l1Mmr.proof(0), withdraw);
        byte[] remoteProof = ClaimProof.encode(remote, map.proof(ChainExecutor.chain32(remote)), remoteMmr.proof(0), send);
        ClaimExt toUser = ext(false, src, 0L, 3L, addr(5), map.root());
        ClaimExt toVault = ext(true, src, 0L, 4L, remote, map.root());
        assertTrue(verify(toUser, l1Proof, map.root(), false).ok());
        assertTrue(verify(toVault, remoteProof, map.root(), false).ok());
        assertEquals(ClaimVerifier.Reason.AMOUNT, verify(toVault, l1Proof, map.root(), false).reason());
        assertEquals(ClaimVerifier.Reason.RECIPIENT,
                verify(ext(true, src, 0L, 3L, remote, map.root()), l1Proof, map.root(), false).reason());
        byte[] misplaced = WithdrawOutbox.withdraw(Bytes.wrap(src), addr(4), addr(5), 4L, 1L, 0L);
        Mmr misplacedMmr = mmr(misplaced);
        OutboxMap underRemote = new OutboxMap();
        underRemote.put(ChainExecutor.chain32(remote), misplacedMmr.root(), misplacedMmr.size());
        byte[] dstProof = ClaimProof.encode(remote, underRemote.proof(ChainExecutor.chain32(remote)),
                misplacedMmr.proof(0), misplaced);
        assertEquals(ClaimVerifier.Reason.DST,
                verify(ext(true, src, 0L, 4L, remote, underRemote.root()), dstProof, underRemote.root(), false).reason());
    }

    @Test
    public void applyUnwindAndReapplyRestoresBalances() {
        Harness h = new Harness();
        XAmount five = XAmount.of(5, XUnit.XDAG);
        XAmount one = XAmount.of(1, XUnit.XDAG);
        long nano = Long.parseLong(one.toString());
        h.fund(h.src, five);
        Built built = h.block(nano, false, h.user);
        h.plant(built.claim.anchor(), built.root);
        assertEquals(five, h.balance(h.src));
        assertTrue(h.apply(built));
        h.commit();
        assertEquals(XAmount.of(4, XUnit.XDAG), h.balance(h.src));
        assertEquals(one, h.balance(h.user));
        assertNotNull(h.store.getClaimed(h.src, Bytes.wrap(new byte[20]), 0L));
        assertNotNull(h.store.getJournal(built.block.getHash()));
        assertFalse(h.apply(built));
        assertEquals(one, h.balance(h.user));
        h.handler.onUnapplied(built.block, built.classified, h.batch());
        h.commit();
        assertEquals(five, h.balance(h.src));
        assertEquals(XAmount.ZERO, h.balance(h.user));
        assertNull(h.store.getClaimed(h.src, Bytes.wrap(new byte[20]), 0L));
        assertNull(h.store.getJournal(built.block.getHash()));
        assertTrue(h.apply(built));
        h.commit();
        assertEquals(one, h.balance(h.user));
    }

    @Test
    public void shortVaultAndMissingProofWriteNothing() {
        Harness h = new Harness();
        long nano = Long.parseLong(XAmount.of(1, XUnit.XDAG).toString());
        Built built = h.block(nano, false, h.user);
        h.plant(built.claim.anchor(), built.root);
        assertFalse(h.apply(built));
        assertTrue(h.current().isEmpty());
        assertEquals(XAmount.ZERO, h.balance(h.user));
        h.fund(h.src, XAmount.of(5, XUnit.XDAG));
        ClaimHandler blind = new ClaimHandler(h.store, hash -> null, 64, h.settlement, WithdrawWindow.unlimited());
        ChainL1Batch batch = new ChainL1Batch();
        blind.onApplied(built.block, built.classified, CTX, batch);
        assertTrue(batch.isEmpty());
        assertEquals(XAmount.of(5, XUnit.XDAG), h.balance(h.src));
    }

    @Test
    public void undoWithoutAJournalIsANoOp() {
        Harness h = new Harness();
        h.fund(h.src, XAmount.of(2, XUnit.XDAG));
        ChainL1Batch batch = new ChainL1Batch();
        h.handler.undo(Bytes32.repeat((byte) 7), batch);
        assertTrue(batch.isEmpty());
        assertEquals(XAmount.of(2, XUnit.XDAG), h.balance(h.src));
    }

    @Test
    public void snapshotAndFullNodeAgreeOnTheClaim() {
        Harness a = new Harness();
        Harness b = new Harness();
        long nano = Long.parseLong(XAmount.of(1, XUnit.XDAG).toString());
        Built built = a.block(nano, false, a.user);
        a.plant(built.claim.anchor(), built.root);
        b.plant(built.claim.anchor(), built.root);
        a.fund(a.src, XAmount.of(5, XUnit.XDAG));
        b.fund(b.src, XAmount.of(5, XUnit.XDAG));
        assertTrue(a.handler.settle(built.claim, built.proof, built.block.getHash(), a.batch()));
        assertTrue(b.handler.settle(built.claim, built.proof, built.block.getHash(), b.batch()));
        a.commit();
        b.commit();
        assertEquals(a.store.stateHash(), b.store.stateHash());
        assertFalse(a.handler.settle(built.claim, built.proof, built.block.getHash(), a.batch()));
        InMemoryKVSource snap = new InMemoryKVSource();
        a.store.exportSnapshot(snap);
        ChainL1Store imported = new ChainL1Store(new InMemoryKVSource());
        imported.importSnapshot(snap);
        assertEquals(a.store.stateHash(), imported.stateHash());
        ClaimHandler replay = new ClaimHandler(imported, hash -> null, 64, a.settlement, WithdrawWindow.unlimited());
        assertFalse(replay.settle(built.claim, built.proof, Bytes32.repeat((byte) 9), new ChainL1Batch()));
        assertNotNull(imported.getClaimed(a.src, Bytes.wrap(new byte[20]), 0L));
    }

    @Test
    public void randomFlowHoldsI1AndI2() {
        Books books = new Books(new Random(20261107L));
        for (int step = 0; step < 200; step++) {
            books.step();
            assertTrue(books.holds());
            assertEquals(books.deposits - books.paid, books.vault);
            assertEquals(books.received, books.remoteVault);
        }
        assertTrue(books.paid > 0);
        assertTrue(books.rejectedBad > 0);
        assertTrue(books.rejectedFinal > 0);
        assertTrue(books.reorgs > 0);
    }

    private static ClaimVerifier.Verdict verify(ClaimExt claim, byte[] proof, Bytes32 root, boolean claimed) {
        return ClaimVerifier.verify(claim, proof, anchor(root), (d, i) -> claimed);
    }

    private static AnchorRecord anchor(Bytes32 root) {
        return new AnchorRecord(AnchorRecord.Status.FINAL, ANCHOR, root);
    }

    private static ClaimExt ext(boolean toVault, byte[] src, long index, long amount, byte[] recipient, Bytes32 root) {
        return new ClaimExt(toVault, Bytes.wrap(src), SEQ, index, amount, Bytes.wrap(recipient), ANCHOR, Bytes32.ZERO);
    }

    private static Mmr mmr(byte[] entry) {
        Mmr mmr = new Mmr();
        mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
        return mmr;
    }

    private static BuiltProof prove(byte[] src, byte[] dst, List<byte[]> entries, int index) {
        Mmr mmr = new Mmr();
        for (byte[] entry : entries) {
            mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
        }
        OutboxMap map = new OutboxMap();
        Bytes32 key = ChainExecutor.chain32(dst);
        map.put(key, mmr.root(), mmr.size());
        byte[] proof = ClaimProof.encode(dst, map.proof(key), mmr.proof(index), entries.get(index));
        return new BuiltProof(proof, map.root());
    }

    private static Bytes chain(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }

    private static byte[] addr(int n) {
        byte[] raw = new byte[20];
        raw[0] = (byte) n;
        return raw;
    }

    private static boolean isZero(byte[] raw) {
        for (byte b : raw) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private record BuiltProof(byte[] proof, Bytes32 root) {
    }

    private static final class Harness {
        final DevnetConfig config = new DevnetConfig();
        final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        final ChainL1Store store = new ChainL1Store(new InMemoryKVSource());
        final AddressStoreImpl addresses = new AddressStoreImpl(new InMemoryKVSource());
        final Bytes src = chain(1);
        final byte[] user = addr(9);
        final Map<Bytes32, Block> chunks = new HashMap<>();
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
        final ClaimHandler handler;
        ChainL1Batch open;

        Harness() {
            store.start();
            addresses.start();
            handler = new ClaimHandler(store, chunks::get, config.getChainSpec().getChainMaxChunksPerChain(),
                    settlement, WithdrawWindow.unlimited());
        }

        void fund(Bytes address, XAmount amount) {
            addresses.updateBalance(address.toArray(), amount);
        }

        XAmount balance(byte[] address) {
            return addresses.getBalanceByAddress(address);
        }

        XAmount balance(Bytes address) {
            return balance(address.toArray());
        }

        Built block(long amount, boolean toVault, byte[] recipient) {
            byte[] dst = toVault ? recipient : new byte[20];
            byte[] entry = toVault
                    ? WithdrawOutbox.send(src, dst, user, recipient, 0, new byte[0], amount, 1L, 0L)
                    : WithdrawOutbox.withdraw(src, user, recipient, amount, 1L, 0L);
            BuiltProof proof = prove(src.toArray(), dst, List.of(entry), 0);
            var built = ChainBlockBuilder.claim(config, TS, sender, UInt64.ONE, XAmount.ZERO, toVault, src, SEQ, 0L,
                    amount, Bytes.wrap(recipient), ANCHOR, Bytes.wrap(proof.proof));
            assertTrue(built.isOk());
            for (Block chunk : built.value().chunks()) {
                chunks.put(Bytes32.wrap(chunk.getHashLow().toArray()), chunk);
            }
            Classified classified = ChainBlockClassifier.classify(built.value().block());
            assertTrue(classified.isOk());
            return new Built(built.value().block(), classified, classified.as(ClaimExt.class), proof.proof, proof.root);
        }

        void plant(Bytes32 anchorHash, Bytes32 root) {
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(src, SEQ, new AnchorRecord(AnchorRecord.Status.FINAL, anchorHash, root).encode());
            store.commit(batch);
        }

        ChainL1Batch batch() {
            open = new ChainL1Batch();
            return open;
        }

        ChainL1Batch current() {
            return open == null ? new ChainL1Batch() : open;
        }

        void commit() {
            store.commit(open);
        }

        boolean apply(Built built) {
            open = new ChainL1Batch();
            handler.onApplied(built.block, built.classified, CTX, open);
            return !open.isEmpty();
        }
    }

    private record Built(Block block, Classified classified, ClaimExt claim, byte[] proof, Bytes32 root) {
    }

    private static final class Books {
        final Random random;
        final Bytes src = chain(1);
        final byte[] remote = chain(2).toArray();
        final byte[] user = addr(3);
        final List<byte[]> l1 = new ArrayList<>();
        final List<byte[]> sent = new ArrayList<>();
        final List<byte[]> l1Pending = new ArrayList<>();
        final List<byte[]> sentPending = new ArrayList<>();
        final Set<String> claimed = new HashSet<>();
        long vault;
        long bal;
        long outF;
        long remoteVault;
        long remoteBal;
        long remoteIn;
        long deposits;
        long paid;
        long received;
        int rejectedBad;
        int rejectedFinal;
        int reorgs;
        int lastKind = -1;
        int lastIndex = -1;
        long lastAmount;
        boolean anchored;

        Books(Random random) {
            this.random = random;
            deposit(1_000L);
        }

        void step() {
            switch (random.nextInt(8)) {
                case 0 -> deposit(1 + random.nextInt(20));
                case 1 -> withdraw(1 + random.nextInt(15));
                case 2 -> send(1 + random.nextInt(15));
                case 3 -> finalizeAnchor();
                case 4 -> claim(false);
                case 5 -> claim(true);
                case 6 -> bad();
                default -> reorg();
            }
        }

        boolean holds() {
            return VaultLedger.holds(bal, outF, 0L, vault) && VaultLedger.holds(remoteBal, 0L, remoteIn, remoteVault);
        }

        void deposit(long amount) {
            vault += amount;
            bal += amount;
            deposits += amount;
        }

        void withdraw(long amount) {
            if (bal < amount) {
                return;
            }
            l1Pending.add(WithdrawOutbox.withdraw(src, user, user, amount, 1L, l1.size() + l1Pending.size()));
            bal -= amount;
            outF += amount;
        }

        void send(long amount) {
            if (bal < amount) {
                return;
            }
            sentPending.add(WithdrawOutbox.send(src, remote, user, remote, 1, new byte[0], amount, 1L,
                    sent.size() + sentPending.size()));
            bal -= amount;
            outF += amount;
            remoteBal += amount;
            remoteIn += amount;
        }

        void finalizeAnchor() {
            l1.addAll(l1Pending);
            sent.addAll(sentPending);
            l1Pending.clear();
            sentPending.clear();
            anchored = true;
        }

        void claim(boolean toVault) {
            List<byte[]> entries = toVault ? sent : l1;
            if (!anchored || entries.isEmpty()) {
                rejectedFinal++;
                ClaimExt early = ClaimFlowTest.ext(toVault, src.toArray(), 0L, 1L, toVault ? remote : user, Bytes32.ZERO);
                assertEquals(ClaimVerifier.Reason.NO_ANCHOR,
                        ClaimVerifier.verify(early, new byte[0], null, (d, i) -> false).reason());
                return;
            }
            int index = random.nextInt(entries.size());
            long amount = OutboxEntry.decode(entries.get(index)).value();
            byte[] proof = proof(toVault, index);
            Bytes32 root = root();
            ClaimExt ext = ClaimFlowTest.ext(toVault, src.toArray(), index, amount, toVault ? remote : user, root);
            boolean taken = claimed.contains(key(toVault, index));
            ClaimVerifier.Verdict verdict = verify(ext, proof, root, taken);
            if (taken) {
                assertEquals(ClaimVerifier.Reason.ALREADY_CLAIMED, verdict.reason());
                return;
            }
            assertTrue(verdict.ok());
            if (vault < amount) {
                return;
            }
            vault -= amount;
            outF -= amount;
            paid += amount;
            claimed.add(key(toVault, index));
            lastKind = toVault ? 1 : 0;
            lastIndex = index;
            lastAmount = amount;
            if (toVault) {
                remoteVault += amount;
                remoteIn -= amount;
                received += amount;
            }
        }

        void bad() {
            if (!anchored || l1.isEmpty()) {
                return;
            }
            byte[] proof = proof(false, 0);
            proof[proof.length - 1] ^= 0x01;
            long before = vault;
            ClaimExt ext = ClaimFlowTest.ext(false, src.toArray(), 0L, OutboxEntry.decode(l1.get(0)).value(), user, root());
            assertEquals(ClaimVerifier.Reason.BAD_PROOF, verify(ext, proof, root(), false).reason());
            assertEquals(before, vault);
            rejectedBad++;
        }

        void reorg() {
            if (lastKind < 0) {
                return;
            }
            boolean toVault = lastKind == 1;
            claimed.remove(key(toVault, lastIndex));
            vault += lastAmount;
            outF += lastAmount;
            paid -= lastAmount;
            if (toVault) {
                remoteVault -= lastAmount;
                remoteIn += lastAmount;
                received -= lastAmount;
            }
            lastKind = -1;
            reorgs++;
        }

        private byte[] proof(boolean toVault, int index) {
            List<byte[]> entries = toVault ? sent : l1;
            byte[] dst = toVault ? remote : new byte[20];
            Mmr mmr = new Mmr();
            for (byte[] entry : entries) {
                mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
            }
            OutboxMap map = map();
            return ClaimProof.encode(dst, map.proof(ChainExecutor.chain32(dst)), mmr.proof(index), entries.get(index));
        }

        private Bytes32 root() {
            return map().root();
        }

        private OutboxMap map() {
            OutboxMap map = new OutboxMap();
            put(map, new byte[20], l1);
            put(map, remote, sent);
            return map;
        }

        private static void put(OutboxMap map, byte[] dst, List<byte[]> entries) {
            if (entries.isEmpty()) {
                return;
            }
            Mmr mmr = new Mmr();
            for (byte[] entry : entries) {
                mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
            }
            map.put(ChainExecutor.chain32(dst), mmr.root(), mmr.size());
        }

        private static String key(boolean toVault, int index) {
            return (toVault ? "r" : "l") + index;
        }
    }
}
