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
import io.xdag.db.rocksdb.OrphanBlockStoreImpl.OrphanMeta;
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
