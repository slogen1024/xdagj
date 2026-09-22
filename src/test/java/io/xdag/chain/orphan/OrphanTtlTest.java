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
import static org.junit.Assert.assertTrue;

import io.xdag.chain.orphan.ChainOrphanPool.AccountLane;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl.OrphanMeta;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * The two TTL regimes, and the one of them that is on the consensus path.
 *
 * <h2>Why a chunk may be evicted at all</h2>
 *
 * <p>{@code ChunkChain}'s age rule accepts a chunk only from the paying block's epoch or the one
 * immediately before it ({@code ChunkChain.java:76-88}, enforced at {@code :145} and {@code :210}),
 * and {@code ChainL1Processor} passes that bound on every walk it makes at {@code setMain} time
 * ({@code ChainL1Processor.java:80-89}). So a chunk in epoch N can only ever be referenced
 * legitimately by a paying block in epoch N or N+1; anything later is refused <em>whether or not
 * this node still holds the bytes</em>. Dropping it after that point therefore cannot change a
 * consensus outcome — and dropping it before that point can.
 *
 * <p>That is the whole safety argument, and it is why the chunk clock is the block's own timestamp
 * and never the moment this node happened to receive it.
 */
public class OrphanTtlTest {

    /**
     * 纪元 N 的分片块能被纪元 N 或 N+1 的付费块合法引用，最后一刻是 N+1 结束。
     * 所以 N+1 之内不得淘汰，N+2 起可以。
     */
    @Test
    public void aChunkSurvivesTheEpochAfterItsOwn() {
        ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
        pool.add(chunkAtEpoch(hash(1), 100L));
        pool.evictExpired(0L, 100L);
        assertEquals("same epoch", 1, pool.size(OrphanCategory.CHUNK));
        pool.evictExpired(0L, 101L);
        assertEquals("the epoch a paying block may still reference it from", 1,
                pool.size(OrphanCategory.CHUNK));
        pool.evictExpired(0L, 102L);
        assertEquals("past the age rule's reach", 0, pool.size(OrphanCategory.CHUNK));
    }

    /** 计时量是块自己的时间戳，不是本节点收到的时刻——重启后结论必须不变。 */
    @Test
    public void chunkTtlIsBlockTimestampNotLocalReceipt() {
        ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
        pool.add(chunkAtEpoch(hash(1), 100L));
        ChainOrphanPool restarted = newPool(limits().chunkTtlEpochs(2).build());
        restarted.add(chunkAtEpoch(hash(1), 100L));   // 重建：同一个块，新的本地时刻
        pool.evictExpired(0L, 102L);
        restarted.evictExpired(0L, 102L);
        assertEquals(pool.size(OrphanCategory.CHUNK), restarted.size(OrphanCategory.CHUNK));
        assertEquals(0, restarted.size(OrphanCategory.CHUNK));
    }

    /** 非分片块没有协议年龄界，保持今天的十五分钟本地时钟。 */
    @Test
    public void nonChunkOrphansKeepTheLocalFifteenMinuteTtl() {
        ChainOrphanPool pool = newPool(limits().build());
        pool.add(linkReceivedAt(hash(1), 0L));
        pool.evictExpired(14 * 60_000L, 0L);
        assertEquals(1, pool.size(OrphanCategory.LINK));
        pool.evictExpired(16 * 60_000L, 0L);
        assertEquals(0, pool.size(OrphanCategory.LINK));
    }

    /** 淘汰必须把三层配额计数都还回来。 */
    @Test
    public void evictionReleasesEveryQuotaCounter() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).chunkTtlEpochs(2).build());
        pool.add(chunkFromAtEpoch("peerA", hash(1), head(1), 100L));
        pool.evictExpired(0L, 102L);
        assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(2), head(1))));
    }

    /**
     * And the chain tier with it. The per-peer assertion above passes on its own the moment the
     * peer map is released; this one fails if the chain map is the tier that was forgotten.
     */
    @Test
    public void evictionReleasesTheChainTierToo() {
        ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerChain(1).chunkTtlEpochs(2).build());
        pool.add(chunkFromAtEpoch("peerA", hash(1), head(1), 100L));
        pool.evictExpired(0L, 102L);
        assertEquals("a chain's budget must come back when its chunk is evicted",
                OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(2), head(1))));
    }

    /**
     * A released quota bucket is deleted, not left sitting at zero. Eviction is the path that runs
     * unattended for the life of the node, so a bucket it forgets is the leak that outlives every
     * flood that caused it.
     */
    @Test
    public void evictionDropsZeroedQuotaBuckets() {
        ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
        pool.add(chunkFromAtEpoch("peerA", hash(1), head(1), 100L));
        assertEquals(1, pool.peerBucketCount());
        assertEquals(1, pool.chainBucketCount());
        pool.evictExpired(0L, 102L);
        assertEquals("a peer bucket at zero must be gone, not zero", 0, pool.peerBucketCount());
        assertEquals("a chain bucket at zero must be gone, not zero", 0, pool.chainBucketCount());
    }

    /** And an emptied address bucket is reclaimed, exactly as a removal reclaims it. */
    @Test
    public void evictionReclaimsEmptiedAddressBuckets() {
        ChainOrphanPool pool = newPool(limits().build());
        pool.add(accountTxReceivedAt(hash(1), addr(1), 1L, 0L), AccountLane.REGULAR);
        pool.add(accountTxReceivedAt(hash(2), addr(2), 1L, 0L), AccountLane.VIP);
        assertEquals(1, pool.accountBucketCount(AccountLane.REGULAR));
        assertEquals(1, pool.accountBucketCount(AccountLane.VIP));
        pool.evictExpired(16 * 60_000L, 0L);
        assertEquals(0, pool.size(OrphanCategory.ACCOUNT_TX));
        assertEquals("an emptied regular bucket must be reclaimed",
                0, pool.accountBucketCount(AccountLane.REGULAR));
        assertEquals("an emptied VIP bucket must be reclaimed",
                0, pool.accountBucketCount(AccountLane.VIP));
    }

    /**
     * The chunk set is ordered by time ascending and an epoch is a monotone function of time, so
     * the expired chunks are a prefix and the sweep stops at the first one still in reach. The
     * assertion is on the outcome, which is what a reader has to be able to trust: a chunk that is
     * still referenceable survives a sweep that dropped an older one in the same pass.
     */
    @Test
    public void theSweepStopsAtTheFirstChunkStillInReach() {
        ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
        pool.add(chunkAtEpoch(hash(1), 100L));
        pool.add(chunkAtEpoch(hash(2), 101L));
        pool.add(chunkAtEpoch(hash(3), 102L));
        List<OrphanEntry> evicted = pool.evictExpired(0L, 102L);
        assertEquals("only the epoch-100 chunk is past the age rule's reach", 1, evicted.size());
        assertEquals(hash(1), evicted.get(0).hashlow());
        assertEquals(2, pool.size(OrphanCategory.CHUNK));
        assertTrue("the epoch-101 chunk is still referenceable from epoch 102",
                pool.contains(hash(2)));
        assertTrue(pool.contains(hash(3)));
    }

    /**
     * One sweep, both regimes. The caller holds the blockchain monitor for the duration and must
     * not have to guess which clock to sweep on, which is why there is one method taking both.
     */
    @Test
    public void oneSweepEvictsBothRegimes() {
        ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
        pool.add(chunkAtEpoch(hash(1), 100L));
        pool.add(linkReceivedAt(hash(2), 0L));
        pool.add(mtxReceivedAt(hash(3), 7L, 0L));
        List<OrphanEntry> evicted = pool.evictExpired(16 * 60_000L, 102L);
        assertEquals(3, evicted.size());
        assertEquals(0, pool.totalSize());
    }

    // ---- helpers -------------------------------------------------------------------------

    private static OrphanLimits.Builder limits() {
        return OrphanLimits.builder();
    }

    private static ChainOrphanPool newPool(OrphanLimits limits) {
        return new ChainOrphanPool(limits);
    }

    /**
     * A chunk whose header sits in {@code epoch}. An epoch is the XDAG timestamp shifted down by
     * 16 ({@code XdagTime.getEpoch}), so the first timestamp of an epoch is the epoch shifted back
     * up. Which instant inside the epoch is irrelevant by construction — that is the point of
     * measuring the chunk TTL in epochs.
     */
    private static OrphanEntry chunkAtEpoch(Bytes32 hashlow, long epoch) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, epoch << 16, 0L, new byte[20]), null,
                null, null);
    }

    private static OrphanEntry chunkFromAtEpoch(String peerKey, Bytes32 hashlow, Bytes32 chainHead,
            long epoch) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, epoch << 16, 0L, new byte[20]), peerKey,
                chainHead, null);
    }

    private static OrphanEntry chunkFrom(String peerKey, Bytes32 hashlow, Bytes32 chainHead) {
        return OrphanEntry.chunk(meta(hashlow, false, 0L, 0L, 0L, new byte[20]), peerKey, chainHead,
                null);
    }

    /** A link orphan this node received at {@code receivedAtMillis} on its own clock. */
    private static OrphanEntry linkReceivedAt(Bytes32 hashlow, long receivedAtMillis) {
        return OrphanEntry.link(meta(hashlow, false, 0L, 0L, 0L, new byte[20]), null,
                receivedAtMillis);
    }

    private static OrphanEntry mtxReceivedAt(Bytes32 hashlow, long fee, long receivedAtMillis) {
        return OrphanEntry.mtx(meta(hashlow, true, 0L, 0L, fee, new byte[20]), null,
                receivedAtMillis);
    }

    private static OrphanEntry accountTxReceivedAt(Bytes32 hashlow, byte[] address, long nonce,
            long receivedAtMillis) {
        return OrphanEntry.accountTx(meta(hashlow, true, nonce, 0L, 0L, address), null,
                receivedAtMillis);
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

    /** A chunk chain head, marked so it never reads like a block hash in a failure message. */
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
}
