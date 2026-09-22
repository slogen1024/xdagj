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
package io.xdag.chain.orphan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.orphan.ChainOrphanPool.AccountLane;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl.OrphanMeta;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

/**
 * The pool's structure and ordering, with nothing wired to it: no quotas, no TTL, no store.
 */
public class ChainOrphanPoolTest {

    /** The ordering is the live one, term for term: link blocks by time, then hashlow. */
    @Test
    public void linkEntriesComeOutByTimeThenHashlow() {
        ChainOrphanPool pool = newPool();
        OrphanEntry a = link(hash(0x02), 100L);
        OrphanEntry b = link(hash(0x01), 100L);   // same time, smaller hashlow
        OrphanEntry c = link(hash(0x03), 50L);    // earlier
        pool.add(b);
        pool.add(a);
        pool.add(c);
        assertEquals(List.of(c, b, a), pool.peekAll(OrphanCategory.LINK));
    }

    /** Removal goes through the hashlow index — not the head of the set, not a rebuilt entry. */
    @Test
    public void removalByHashlowTakesTheRightEntryWhateverItsPosition() {
        ChainOrphanPool pool = newPool();
        OrphanEntry first = link(hash(0x01), 10L);
        OrphanEntry middle = link(hash(0x02), 20L);
        OrphanEntry last = link(hash(0x03), 30L);
        pool.add(first);
        pool.add(middle);
        pool.add(last);
        assertSame(middle, pool.remove(middle.hashlow()));
        assertEquals(List.of(first, last), pool.peekAll(OrphanCategory.LINK));
        assertEquals(2, pool.size(OrphanCategory.LINK));
        assertNull("removing twice must not take a different entry", pool.remove(middle.hashlow()));
    }

    /**
     * The production trap, pinned. {@code dealOrphan} stores a meta built from the block instance
     * {@code tryToConnect} received; {@code removeOrphan} re-fetches the block and recomputes
     * {@code getTxFee} after the chain has moved {@code info.fee}. The old
     * {@code PriorityBlockingQueue} removed by {@code equals} — hashlow only — so the drift did not
     * matter. A {@code TreeSet} removes by comparator, and the mtx order is by fee.
     */
    @Test
    public void mtxRemovalTakesTheStoredInstanceNotOneRebuiltFromTheCallersFee() {
        // First, that the hazard is real on a bare set with the production comparator.
        NavigableSet<OrphanEntry> bare = new TreeSet<>(ChainOrphanPool.MTX_ORDER);
        OrphanEntry asStored = mtx(hash(0x02), 500L, 2L);
        bare.add(asStored);
        OrphanEntry asTheCallerSeesItNow = mtx(hash(0x02), 7L, 2L);  // same block, fee has moved
        assertFalse("a rebuilt entry with a drifted fee must not be able to reach the stored one",
                bare.remove(asTheCallerSeesItNow));
        assertEquals("and the failure is silent: the entry is still in the set", 1, bare.size());

        // Then, that the pool is not fooled by it: the hashlow leads to the stored instance.
        ChainOrphanPool pool = newPool();
        OrphanEntry cheap = mtx(hash(0x01), 10L, 1L);
        OrphanEntry stored = mtx(hash(0x02), 500L, 2L);
        OrphanEntry dear = mtx(hash(0x03), 900L, 3L);
        pool.add(cheap);
        pool.add(stored);
        pool.add(dear);

        assertSame(stored, pool.remove(asTheCallerSeesItNow.hashlow()));
        assertEquals("the stored entry must really be out of the set",
                List.of(dear, cheap), pool.peekAll(OrphanCategory.MTX));
        assertEquals(2, pool.size(OrphanCategory.MTX));
        assertNull("its slot must be gone for good", pool.remove(hash(0x02)));
    }

    /** size() must be exact: the admission gate reads it. */
    @Test
    public void sizeIsExactAcrossAddAndRemove() {
        ChainOrphanPool pool = newPool();
        for (int i = 1; i <= 100; i++) {
            pool.add(link(hash(i), i));
        }
        assertEquals(100, pool.size(OrphanCategory.LINK));
        for (int i = 1; i <= 50; i++) {
            pool.remove(hash(i));
        }
        assertEquals(50, pool.size(OrphanCategory.LINK));
        assertEquals(50, pool.totalSize());
    }

    @Test
    public void duplicateHashlowIsRejectedNotDoubleCounted() {
        ChainOrphanPool pool = newPool();
        OrphanEntry e = link(hash(0x01), 10L);
        assertEquals(OrphanAdmission.ADMITTED, pool.add(e));
        assertEquals(OrphanAdmission.DUPLICATE, pool.add(link(hash(0x01), 99L)));
        assertEquals(1, pool.size(OrphanCategory.LINK));
        assertSame("the first entry must be the one that stayed", e, pool.get(hash(0x01)));
    }

    /** Account transactions keep the two-map, per-address shape the store has today. */
    @Test
    public void accountTransactionsAreBucketedPerAddressAndLane() {
        ChainOrphanPool pool = newPool();
        OrphanEntry aliceSecond = accountTx(hash(0x01), addr(1), 2L);
        OrphanEntry aliceFirst = accountTx(hash(0x02), addr(1), 1L);
        OrphanEntry bob = accountTx(hash(0x03), addr(2), 1L);
        OrphanEntry aliceVip = accountTx(hash(0x04), addr(1), 3L);
        pool.add(aliceSecond);
        pool.add(aliceFirst);
        pool.add(bob);
        pool.add(aliceVip, AccountLane.VIP);

        assertEquals("one address's regular bucket orders by nonce",
                List.of(aliceFirst, aliceSecond), pool.peekAccount(key(addr(1)), AccountLane.REGULAR));
        assertEquals("another address is a bucket of its own",
                List.of(bob), pool.peekAccount(key(addr(2)), AccountLane.REGULAR));
        assertEquals("the VIP lane is a separate map, not a flag on the regular one",
                List.of(aliceVip), pool.peekAccount(key(addr(1)), AccountLane.VIP));
        assertTrue(aliceVip.isVip());
        assertFalse(aliceFirst.isVip());
        assertEquals("both lanes count against the one account-transaction total",
                4, pool.size(OrphanCategory.ACCOUNT_TX));

        assertSame(aliceVip, pool.remove(hash(0x04)));
        assertEquals(3, pool.size(OrphanCategory.ACCOUNT_TX));
        assertEquals(List.of(), pool.peekAccount(key(addr(1)), AccountLane.VIP));
    }

    /** An address bucket that empties is dropped, or a flood leaves unbounded empty buckets. */
    @Test
    public void anEmptyAddressBucketIsDroppedNotKept() {
        ChainOrphanPool pool = newPool();
        for (int i = 1; i <= 2000; i++) {
            pool.add(accountTx(hash(i), addr(i), 1L));
            pool.remove(hash(i));
        }
        assertEquals(0, pool.size(OrphanCategory.ACCOUNT_TX));
        assertEquals("a per-address bucket must be dropped when it empties",
                0, pool.accountBucketCount(AccountLane.REGULAR));
    }

    /** Chunks get their own set, with the link ordering, so neither can crowd the other out. */
    @Test
    public void chunksAreTheirOwnSetOrderedLikeLinkBlocks() {
        ChainOrphanPool pool = newPool();
        OrphanEntry later = chunk(hash(0x01), 200L);
        OrphanEntry earlier = chunk(hash(0x02), 100L);
        pool.add(later);
        pool.add(earlier);
        pool.add(link(hash(0x03), 150L));

        assertEquals(List.of(earlier, later), pool.peekAll(OrphanCategory.CHUNK));
        assertEquals("a chunk must not land in the link set", 1, pool.size(OrphanCategory.LINK));
        assertEquals(2, pool.size(OrphanCategory.CHUNK));
        assertEquals(3, pool.totalSize());
    }

    /** The routing is the one {@code addOrphanToMemory} already makes, plus the new chunk branch. */
    @Test
    public void categoryRoutingMatchesTodaysAddOrphanToMemory() {
        assertEquals(OrphanCategory.MTX, OrphanCategory.of(true, new byte[20], null));
        assertEquals(OrphanCategory.ACCOUNT_TX, OrphanCategory.of(true, addr(1), null));
        assertEquals("a transaction is never a chunk, whatever kind rides along",
                OrphanCategory.ACCOUNT_TX, OrphanCategory.of(true, addr(1), ExtKind.CHUNK));
        assertEquals(OrphanCategory.CHUNK, OrphanCategory.of(false, new byte[20], ExtKind.CHUNK));
        assertEquals(OrphanCategory.LINK, OrphanCategory.of(false, new byte[20], ExtKind.CALL));
        assertEquals("an unclassified block falls through to LINK exactly as today",
                OrphanCategory.LINK, OrphanCategory.of(false, new byte[20], null));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static ChainOrphanPool newPool() {
        return new ChainOrphanPool();
    }

    private static OrphanEntry link(Bytes32 hashlow, long time) {
        return OrphanEntry.link(meta(hashlow, false, 0L, time, 0L, new byte[20]), null);
    }

    private static OrphanEntry chunk(Bytes32 hashlow, long time) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, time, 0L, new byte[20]), null, null, null);
    }

    private static OrphanEntry mtx(Bytes32 hashlow, long fee, long time) {
        return OrphanEntry.mtx(meta(hashlow, true, 0L, time, fee, new byte[20]), null);
    }

    private static OrphanEntry accountTx(Bytes32 hashlow, byte[] address, long nonce) {
        return OrphanEntry.accountTx(meta(hashlow, true, nonce, 0L, 0L, address), null);
    }

    private static OrphanMeta meta(Bytes32 hashlow, boolean isTx, long nonce, long time, long fee,
            byte[] address) {
        return new OrphanMeta()
                .setHashlow(hashlow)
                .setTx(isTx)
                .setNonce(nonce)
                .setTime(time)
                .setFee(fee)
                .setAddress(address);
    }

    private static Bytes32 hash(int seed) {
        byte[] raw = new byte[32];
        raw[28] = (byte) (seed >>> 24);
        raw[29] = (byte) (seed >>> 16);
        raw[30] = (byte) (seed >>> 8);
        raw[31] = (byte) seed;
        return Bytes32.wrap(raw);
    }

    private static byte[] addr(int seed) {
        byte[] raw = new byte[20];
        raw[18] = (byte) (seed >>> 8);
        raw[19] = (byte) seed;
        return raw;
    }

    private static String key(byte[] address) {
        return Hex.toHexString(address);
    }
}
