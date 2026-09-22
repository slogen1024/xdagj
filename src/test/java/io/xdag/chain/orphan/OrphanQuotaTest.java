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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.orphan.ChainOrphanPool.AccountLane;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

/**
 * The category caps and the global cap, and what a refusal is allowed to touch (nothing).
 *
 * <p>The starvation these caps exist to kill is live in the code today: {@code MAX_ORPHAN_SIZE}
 * counts all four queues together but is only consulted when an <em>account transaction</em>
 * arrives ({@code BlockchainImpl:471}), so link, chunk and mtx blocks fill the pool unchecked and
 * then account transactions start being refused for room that nothing stopped them taking.
 */
public class OrphanQuotaTest {

    /** A full category refuses only itself. This is exactly the starvation that is live today. */
    @Test
    public void aFullCategoryDoesNotBlockAnother() {
        ChainOrphanPool pool = newPool(limits().chunk(2).accountTx(10).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(1))));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(2))));
        assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(chunk(hash(3))));
        assertEquals("a full chunk category must not touch account transactions",
                OrphanAdmission.ADMITTED, pool.add(accountTx(hash(4), addr(1), 1L)));
    }

    /** The global cap is the backstop: at the ceiling nothing gets in, whatever its category. */
    @Test
    public void theGlobalLimitBackstopsEverything() {
        ChainOrphanPool pool = newPool(limits().poolLimit(3).chunk(100).link(100).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(1))));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(link(hash(2), 1L)));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(3))));
        assertEquals("the pool is at its ceiling with room left in the link category",
                OrphanAdmission.POOL_FULL, pool.add(link(hash(4), 2L)));
    }

    /**
     * The global cap answers before the category cap, so a refusal names the constraint that
     * admitting could not have got around. Whoever reads the Task 8 log needs POOL_FULL to mean
     * "the node is at its memory ceiling" and CATEGORY_FULL to mean "there was room, this kind had
     * used its share" — never the other way round.
     */
    @Test
    public void whenBothCapsAreReachedTheRefusalNamesTheGlobalOne() {
        ChainOrphanPool pool = newPool(limits().poolLimit(2).chunk(2).build());
        pool.add(chunk(hash(1)));
        pool.add(chunk(hash(2)));
        assertEquals(OrphanAdmission.POOL_FULL, pool.add(chunk(hash(3))));
    }

    /** And with global room to spare, the category cap is the one that answers. */
    @Test
    public void withGlobalRoomToSpareTheCategoryCapAnswers() {
        ChainOrphanPool pool = newPool(limits().poolLimit(100).chunk(1).build());
        pool.add(chunk(hash(1)));
        assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(chunk(hash(2))));
    }

    /** A removal must really hand the slot back. */
    @Test
    public void removingFreesTheSlot() {
        ChainOrphanPool pool = newPool(limits().chunk(1).build());
        pool.add(chunk(hash(1)));
        assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(chunk(hash(2))));
        pool.remove(hash(1));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(2))));
    }

    /**
     * A refused add changes nothing at all. A counter nudged on the refusal path, or an address
     * bucket created for an entry that never went in, does not fail a test that only reads
     * verdicts — it leaks, slowly, until the category can never be entered again.
     */
    @Test
    public void aRefusedAddChangesNothing() {
        ChainOrphanPool pool = newPool(limits().poolLimit(3).accountTx(1).link(1).build());
        pool.add(link(hash(1), 1L));
        pool.add(accountTx(hash(2), addr(1), 1L));

        // Refused by the category cap: a brand new address, so a bucket would be created for it.
        assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(accountTx(hash(3), addr(2), 1L)));
        assertEquals("the refused entry must not be counted",
                1, pool.size(OrphanCategory.ACCOUNT_TX));
        assertEquals(2, pool.totalSize());
        assertFalse("the refused entry must not be indexed", pool.contains(hash(3)));
        assertNull(pool.get(hash(3)));
        assertEquals("a refusal must not leave an empty address bucket behind",
                1, pool.accountBucketCount(AccountLane.REGULAR));
        assertEquals(List.of(), pool.peekAccount(key(addr(2)), AccountLane.REGULAR));

        // And refused by the VIP lane too, which is the other map that would grow a bucket.
        assertEquals(OrphanAdmission.CATEGORY_FULL,
                pool.add(accountTx(hash(4), addr(2), 1L), AccountLane.VIP));
        assertEquals(0, pool.accountBucketCount(AccountLane.VIP));

        // Refused by the global cap, on a category that is nowhere near its own limit.
        pool.add(mtx(hash(5), 10L, 1L));
        assertEquals(3, pool.totalSize());
        assertEquals(OrphanAdmission.POOL_FULL, pool.add(mtx(hash(6), 20L, 2L)));
        assertEquals("the refused entry must not be counted", 1, pool.size(OrphanCategory.MTX));
        assertEquals(3, pool.totalSize());
        assertFalse(pool.contains(hash(6)));
        assertEquals(List.of(pool.get(hash(5))), pool.peekAll(OrphanCategory.MTX));

        // The refusals cost nothing: a slot freed is a slot usable, on either cap.
        pool.remove(hash(2));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(accountTx(hash(3), addr(2), 1L)));
        assertEquals(1, pool.size(OrphanCategory.ACCOUNT_TX));
        assertEquals(3, pool.totalSize());
        assertEquals("the bucket the refusal must not have made is made now",
                1, pool.accountBucketCount(AccountLane.REGULAR));
    }

    /** A duplicate is answered as a duplicate even at the ceiling: there is nothing to admit. */
    @Test
    public void aDuplicateIsStillADuplicateWhenTheCapIsReached() {
        ChainOrphanPool pool = newPool(limits().poolLimit(1).chunk(1).build());
        pool.add(chunk(hash(1)));
        assertEquals(OrphanAdmission.DUPLICATE, pool.add(chunk(hash(1))));
        assertEquals(1, pool.totalSize());
    }

    /** A cap of zero or less is a typo, not a policy; the config layer floors every key at one. */
    @Test
    public void aLimitBelowOneIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> limits().chunk(0));
        assertTrue("the message must name the limit: " + e.getMessage(),
                e.getMessage().contains("chunk"));
        assertThrows(IllegalArgumentException.class, () -> limits().poolLimit(-1));
    }

    /** An unnamed limit is not enforced: the tests above name only what they are exercising. */
    @Test
    public void anUnnamedLimitIsNotEnforced() {
        OrphanLimits limits = limits().chunk(2).build();
        assertEquals(2, limits.limit(OrphanCategory.CHUNK));
        assertEquals(OrphanLimits.UNLIMITED, limits.limit(OrphanCategory.LINK));
        assertEquals(OrphanLimits.UNLIMITED, limits.limit(OrphanCategory.MTX));
        assertEquals(OrphanLimits.UNLIMITED, limits.limit(OrphanCategory.ACCOUNT_TX));
        assertEquals(OrphanLimits.UNLIMITED, limits.poolLimit());
    }

    // ---- the two chunk-only tiers --------------------------------------------------------

    /**
     * One source cannot take the whole chunk category. This is the tier the category cap cannot
     * stand in for: a single flooder fills all sixty thousand chunk slots on its own and locks
     * every honest peer out of the category. With a per-source budget the flood costs the flooder
     * its own share and nobody else's.
     */
    @Test
    public void onePeerCannotUseTheWholeChunkBudget() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(2).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(1), head(1))));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(2), head(1))));
        assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(3), head(1))));
        assertEquals("another peer must be unaffected",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(4), head(2))));
    }

    /**
     * And one chunk chain cannot take it either, however many sources feed it. The per-peer tier
     * alone leaves a coordinated set of addresses free to pile everything onto one chain.
     */
    @Test
    public void oneChunkChainCannotUseTheWholeChunkBudget() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerChain(2).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(1), head(1))));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(2), head(1))));
        assertEquals("the chain is full even though peerB has spent nothing",
                OrphanAdmission.CHAIN_FULL, pool.add(chunkFrom("peerB", hash(3), head(1))));
        assertEquals("another chain must be unaffected",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(4), head(2))));
    }

    /**
     * A locally produced block — mined, or built by RPC or the CLI — arrived from no peer, so
     * there is no budget for it to spend. Unattributed is the correct reading of a null source,
     * not a degraded one: charging local blocks to some catch-all bucket would let this node's own
     * mining shut its own chunk intake down.
     */
    @Test
    public void locallyOriginatedChunksAreUnattributed() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom(null, hash(1), head(1))));
        assertEquals("an unattributed chunk must not consume a peer's budget",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom(null, hash(2), head(1))));
        assertEquals("and it must not open a bucket either", 0, pool.peerBucketCount());
    }

    /**
     * A chunk that could not be grouped onto a chain head is ungrouped, and an ungrouped chunk
     * spends no chain budget — but it is still a chunk from somewhere, so the peer and global
     * tiers still hold it. Otherwise "send chunks that group onto nothing" would be a way past
     * every tier at once.
     */
    @Test
    public void anUngroupedChunkStillCountsAgainstPeerAndGlobal() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(1), null)));
        assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(2), null)));
        assertEquals("an ungrouped chunk must not open a chain bucket", 0, pool.chainBucketCount());
    }

    /**
     * Over both budgets at once, the refusal names the peer. The two tiers are orthogonal, so
     * neither "could not have been got around" in the way the global cap outranks the category
     * cap; the tie is broken on what the verdict tells whoever reads the Task 8 log. PEER_FULL is
     * a statement about this sender alone and is always true when it is returned. CHAIN_FULL is a
     * statement about shared state that another peer's traffic may have filled, so it is kept for
     * the case where this sender really was still within its own rights — never used to blame a
     * sender's neighbours for a budget the sender had itself already spent.
     */
    @Test
    public void whenBothChunkQuotasAreReachedTheRefusalNamesThePeer() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).chunkPerChain(1).build());
        pool.add(chunkFrom("peerA", hash(1), head(1)));
        assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(2), head(1))));
    }

    /**
     * A refusal by one tier must not have spent the other. Reserving the peer slot before the
     * chain check — or the reverse — leaks one count per refused block, and a flood of refusals is
     * cheap: the leak would close the pool to that peer, or that chain, permanently.
     */
    @Test
    public void aRefusedChunkTouchesNeitherQuotaCounter() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).chunkPerChain(1).build());
        pool.add(chunkFrom("peerA", hash(1), head(1)));

        // Refused by the peer tier, naming a chain head the pool has never seen.
        assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(2), head(2))));
        assertEquals("a refusal must not open a bucket for a head that never went in",
                1, pool.chainBucketCount());
        assertEquals(1, pool.peerBucketCount());
        assertEquals(1, pool.size(OrphanCategory.CHUNK));
        assertFalse(pool.contains(hash(2)));
        assertEquals("the head the refusal named must still have its whole budget",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(3), head(2))));

        // Refused by the chain tier, from a peer the pool has never seen.
        assertEquals(OrphanAdmission.CHAIN_FULL, pool.add(chunkFrom("peerC", hash(4), head(1))));
        assertEquals("a refusal must not open a bucket for a peer that never got in",
                2, pool.peerBucketCount());
        assertEquals("and peerC must still have its whole budget",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerC", hash(5), head(3))));
    }

    /** Both tiers are chunk-only: nothing else is bucketed by peer, so nothing else is charged. */
    @Test
    public void theChunkQuotasDoNotReachOtherCategories() {
        ChainOrphanPool pool = newPool(limits().chunkPerPeer(1).chunkPerChain(1).build());
        assertEquals(OrphanAdmission.ADMITTED, pool.add(linkFrom("peerA", hash(1), 1L)));
        assertEquals("a link block is bounded by its category and the pool, nothing else",
                OrphanAdmission.ADMITTED, pool.add(linkFrom("peerA", hash(2), 2L)));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(mtxFrom("peerA", hash(3), 10L, 1L)));
        assertEquals(OrphanAdmission.ADMITTED,
                pool.add(accountTxFrom("peerA", hash(4), addr(1), 1L)));
        assertEquals("a non-chunk must not open a peer bucket", 0, pool.peerBucketCount());
    }

    /** Removing one chunk hands back that chunk's slots and no one else's. */
    @Test
    public void removingOneChunkFreesOnlyItsOwnQuotaSlot() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(2).chunkPerChain(2).build());
        pool.add(chunkFrom("peerA", hash(1), head(1)));
        pool.add(chunkFrom("peerA", hash(2), head(1)));
        assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(3), head(1))));
        pool.remove(hash(1));
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(3), head(1))));
        assertEquals("a bucket still in use must not have been dropped", 1, pool.peerBucketCount());
        assertEquals(1, pool.chainBucketCount());
    }

    /** Dropping to zero deletes the key: otherwise one flood leaves an unbounded number of empty buckets. */
    @Test
    public void emptyQuotaBucketsAreReclaimed() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(10).build());
        for (int i = 0; i < 10_000; i++) {
            pool.add(chunkFrom("peer" + i, hash(i), head(i)));
            pool.remove(hash(i));
        }
        assertEquals("a per-peer bucket must be dropped when it reaches zero", 0, pool.peerBucketCount());
        assertEquals("a per-chain bucket must be dropped when it reaches zero", 0, pool.chainBucketCount());
        assertEquals(0, pool.totalSize());
    }

    /** The two new tiers obey the same "unnamed is unenforced" bargain as the caps before them. */
    @Test
    public void theChunkQuotasAreUnenforcedUntilNamed() {
        OrphanLimits named = limits().chunkPerPeer(7).chunkPerChain(9).build();
        assertEquals(7, named.chunkPerPeer());
        assertEquals(9, named.chunkPerChain());
        OrphanLimits unnamed = limits().chunk(2).build();
        assertEquals(OrphanLimits.UNLIMITED, unnamed.chunkPerPeer());
        assertEquals(OrphanLimits.UNLIMITED, unnamed.chunkPerChain());
    }

    /** And the same floor of one: a quota of zero admits nothing and is only ever a typo. */
    @Test
    public void aChunkQuotaBelowOneIsRefused() {
        IllegalArgumentException perPeer = assertThrows(IllegalArgumentException.class,
                () -> limits().chunkPerPeer(0));
        assertTrue("the message must name the limit: " + perPeer.getMessage(),
                perPeer.getMessage().contains("chunkPerPeer"));
        IllegalArgumentException perChain = assertThrows(IllegalArgumentException.class,
                () -> limits().chunkPerChain(-1));
        assertTrue("the message must name the limit: " + perChain.getMessage(),
                perChain.getMessage().contains("chunkPerChain"));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static OrphanLimits.Builder limits() {
        return OrphanLimits.builder();
    }

    private static ChainOrphanPool newPool(OrphanLimits limits) {
        return new ChainOrphanPool(limits);
    }

    private static OrphanEntry link(Bytes32 hashlow, long time) {
        return OrphanEntry.link(meta(hashlow, false, 0L, time, 0L, new byte[20]), null);
    }

    private static OrphanEntry chunk(Bytes32 hashlow) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, 0L, 0L, new byte[20]), null, null, null);
    }

    /**
     * A chunk with both quota keys named. {@code peerKey} stands for what Task 10 will pass — the
     * source peer's IP — and null for a block this node produced itself; {@code chainHead} for the
     * chunk chain it groups onto, null when it groups onto none.
     */
    private static OrphanEntry chunkFrom(String peerKey, Bytes32 hashlow, Bytes32 chainHead) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, 0L, 0L, new byte[20]), peerKey, chainHead,
                null);
    }

    private static OrphanEntry linkFrom(String peerKey, Bytes32 hashlow, long time) {
        return OrphanEntry.link(meta(hashlow, false, 0L, time, 0L, new byte[20]), peerKey);
    }

    private static OrphanEntry mtxFrom(String peerKey, Bytes32 hashlow, long fee, long time) {
        return OrphanEntry.mtx(meta(hashlow, true, 0L, time, fee, new byte[20]), peerKey);
    }

    private static OrphanEntry accountTxFrom(String peerKey, Bytes32 hashlow, byte[] address,
            long nonce) {
        return OrphanEntry.accountTx(meta(hashlow, true, nonce, 0L, 0L, address), peerKey);
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

    /**
     * A chunk chain head. Shaped like a hashlow because that is what one is — the hashlow of the
     * chain's head block — with a leading marker byte so a head and a block hash never read alike
     * in a failure message.
     */
    private static Bytes32 head(int seed) {
        byte[] raw = hash(seed).toArray();
        raw[0] = (byte) 0xc4;
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
