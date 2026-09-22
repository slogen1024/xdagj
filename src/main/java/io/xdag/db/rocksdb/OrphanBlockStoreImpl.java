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

package io.xdag.db.rocksdb;

import com.google.common.primitives.UnsignedBytes;
import io.xdag.Kernel;
import io.xdag.chain.orphan.ChainOrphanPool;
import io.xdag.chain.orphan.ChainOrphanPool.AccountLane;
import io.xdag.chain.orphan.OrphanAdmission;
import io.xdag.chain.orphan.OrphanCategory;
import io.xdag.chain.orphan.OrphanEntry;
import io.xdag.chain.orphan.OrphanLimits;
import io.xdag.chain.orphan.OrphanMeta;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.*;
import io.xdag.db.OrphanBlockStore;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;

import java.util.*;
import java.util.concurrent.*;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;

import com.google.common.collect.Lists;

/**
 * ORPHANIND's persistence shell: the database rows, the rebuild that refills memory from them, the
 * expiry tick, and the packing walk. Every in-memory collection this used to own now lives in
 * {@link ChainOrphanPool}.
 *
 * <h2>What moved, and why none of it is concurrent any more</h2>
 *
 * <p>Seven collections went across: {@code linkQueue}, {@code mtxQueue}, {@code accountTxMap},
 * {@code vipTxMap}, {@code orphanInsertTimeMap}, {@code mainRef} and {@code accountNonce}. Five of
 * them were concurrent implementations; inside the pool they are plain {@link TreeSet}s and
 * {@link HashMap}s. That is not a regression: since {@code 811deec0} put {@code getOrphan} under
 * the blockchain monitor, <em>every</em> access point already holds that monitor — the import path
 * through {@code tryToConnect}, the packing path through {@code getOrphan}, and the expiry tick
 * through {@link #cleanExpiredOrphans}. A concurrent collection over state that is already held
 * exclusively buys nothing and costs an atomic per operation. <b>This is a decision; do not "fix"
 * it back.</b>
 *
 * <p>The lock order is unchanged and must stay so: blockchain monitor, then the pool. Nothing here
 * holds the pool and then asks for the blockchain.
 *
 * <h2>Chunks are memory-only</h2>
 *
 * <p>A {@link OrphanCategory#CHUNK} orphan gets no ORPHANIND row and does not move
 * {@link #ORPHAN_SIZE}: it lives in the pool and nowhere else. Three reasons, from the plan's
 * specification note. A row would point at a block body that is not on disk either. The 34-byte key
 * and 36-byte value carry nothing that could tell a rebuilt chunk from any other link block without
 * changing the row format. And a chunk's whole life is two epochs, so losing them to a restart
 * costs one refetch down a path that already exists.
 *
 * <p>The consequence worth naming: {@link #rebuildMemoryFromDb} can only ever produce non-chunk
 * entries, by construction rather than by a check, which is why the rebuild needs no category byte.
 */
@Getter
@Slf4j
public class OrphanBlockStoreImpl implements OrphanBlockStore {

    // <hash,nexthash>
    private final KVSource<byte[], byte[]> orphanSource;

    /**
     * Every orphan this node is holding, and the only place they are held. Built with the caps
     * {@link #limitsFrom} reads out of the chain specification — see that method for why a
     * forgotten cap is worse than a small one.
     */
    private final ChainOrphanPool pool;

    private static final XAmount averageFee = XAmount.of(100, XUnit.MILLI_XDAG);

    /**
     * What {@link #chainEpoch} answers when this node has no main block yet. It is only ever the
     * left side of the retention comparison and nothing is added to it, so the effect is that no
     * chunk is aged out at all: with no main chain there is no chain progress to age one against,
     * and retaining is the safe direction.
     */
    private static final long NO_CHAIN_EPOCH = Long.MIN_VALUE;

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    private final Kernel kernel;

    public OrphanBlockStoreImpl(KVSource<byte[], byte[]> orphan, Kernel kernel) {
        this.orphanSource = orphan;
        this.kernel = kernel;
        this.pool = new ChainOrphanPool(limitsFrom(kernel.getConfig().getChainSpec()));
    }

    /**
     * The production caps, from the nine configuration keys.
     *
     * <p><b>Every cap has to be named here.</b> {@link OrphanLimits} leaves anything unnamed at
     * {@link OrphanLimits#UNLIMITED} rather than at a conservative default, so a cap this method
     * forgets is not a small cap — it is no cap at all, and the protection the pool exists to give
     * that category is silently absent with nothing to show for it. {@code
     * OrphanBlockStoreWiringTest} asserts that not one of the five comes out unlimited, and it will
     * fail the day a category is added without a key behind it.
     */
    public static OrphanLimits limitsFrom(ChainSpec spec) {
        return OrphanLimits.builder()
                .poolLimit(spec.getChainOrphanPoolLimit())
                .accountTx(spec.getChainOrphanAccountTxLimit())
                .mtx(spec.getChainOrphanMtxLimit())
                .chunk(spec.getChainOrphanChunkLimit())
                .link(spec.getChainOrphanLinkLimit())
                .chunkPerPeer(spec.getChainOrphanChunkPerPeer())
                .chunkPerChain(spec.getChainOrphanChunkPerChain())
                .chunkTtlEpochs(spec.getChainOrphanChunkTtlEpochs())
                .build();
    }

    public void start() {
        this.orphanSource.init();
        if (orphanSource.get(ORPHAN_SIZE) == null) {
            this.orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(0, false));
        }
        rebuildMemoryFromDb();
        startCleaner();
    }

    /**
     * Refills the pool from ORPHANIND.
     *
     * <p>Every row rebuilt here is a non-chunk entry by construction — a chunk never got a row in
     * the first place — so the category comes from the same two fields it always came from and the
     * row format is untouched.
     *
     * <p>{@code ChainOrphanPool.clearEntries} deliberately leaves the VIP nonce watermarks alone,
     * which is what today's rebuild does with {@code accountNonce}; see that method for why it is
     * preserved rather than tidied.
     */
    public void rebuildMemoryFromDb() {
        pool.clearEntries();

        List<Pair<byte[], byte[]>> raw = orphanSource.prefixKeyAndValueLookup(BytesUtils.of(ORPHAN_PREFEX));
        for (Pair<byte[], byte[]> pair : raw) {
            admit(OrphanMeta.parse(pair));
        }
        log.debug("init orphan size:{}", BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false));

        log.info("OrphanBlockStore memory queues rebuilt from DB: {} link, {} mtx, {} account tx over"
                        + " {} regular and {} vip addresses",
                pool.size(OrphanCategory.LINK), pool.size(OrphanCategory.MTX),
                pool.size(OrphanCategory.ACCOUNT_TX), pool.accountBucketCount(AccountLane.REGULAR),
                pool.accountBucketCount(AccountLane.VIP));
    }

    private void startCleaner() {
        cleaner.scheduleAtFixedRate(this::cleanExpiredOrphans, 5, 300, TimeUnit.SECONDS);
    }

    /**
     * I4 (SP0b-2): the cleaner goes first. Its scheduler owns a non-daemon thread, and a tick that
     * fires after the databases are closed would write through a stopped write-behind queue into a
     * closed RocksDB — poisoning the queue on the way out and logging a persistence failure that
     * never happened. {@code shutdownNow} is idempotent, so calling this twice is harmless.
     */
    @Override
    public void stop() {
        cleaner.shutdownNow();
        try {
            // Wait for a tick that is already running: shutdownNow only interrupts, and a tick that
            // is mid-write would otherwise reach the source after the close below.
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("the orphan cleaner did not stop within 5s; closing its source anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        orphanSource.close();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void reset() {
        this.orphanSource.reset();
        this.orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(0, false));
    }

    /**
     * C1 (SP0b-2): the whole body runs under the blockchain monitor. It writes ORPHANIND (the
     * orphan key and ORPHAN_SIZE) and INDEX (the stats), and it decrements {@code nnoref} — all on
     * this scheduler's own thread, which used to hold no lock at all. Three things that fixes: the
     * unsynchronized {@code nnoref--}, the read-modify-write on ORPHAN_SIZE racing
     * {@code addOrphan}/{@code deleteByKey}, and — since the write-behind layer — a stats save
     * QUEUED here landing after a concurrent transition's DIRECT save, which would persist
     * {@code nmain} ahead of the completion marker and make the next boot refuse to start.
     *
     * <p>The blockchain is null until the kernel has built it (this cleaner starts with the store,
     * five seconds earlier), and there is nothing to clean before then anyway.
     *
     * <p>Package-private rather than private so a test can run one tick deterministically instead
     * of waiting on the scheduler; the scheduled call and the test call are the same call.
     */
    void cleanExpiredOrphans() {
        Blockchain blockchain = kernel.getBlockchain();
        if (blockchain == null) {
            return;
        }
        synchronized (blockchain) {
            cleanExpiredOrphansLocked(blockchain);
        }
    }

    /**
     * One expiry tick: ask the pool what has aged out on each of its two clocks, then undo the rest
     * of what each evicted orphan owned.
     *
     * <p><b>{@code nnoref} is decremented per evicted entry, not per deleted row.</b> Those used to
     * be the same thing — every pooled orphan had a row — and the old tick gated the decrement on
     * finding one. A chunk has no row, so keeping that gate would leave {@code nnoref} permanently
     * one too high for every chunk that expires, and {@code nnoref} is what
     * {@code BlockchainImpl.checkNewMain} divides by eleven to decide how many link blocks to mine:
     * a count that only ever drifts upwards would have this node mining link blocks for orphans it
     * is no longer holding. The decrement is paired instead with leaving the pool, which is the
     * event {@code nnoref} is really counting, and an entry leaves the pool exactly once. The
     * database half stays gated on the row, because a chunk genuinely has none.
     */
    private void cleanExpiredOrphansLocked(Blockchain blockchain) {
        List<OrphanEntry> expired = pool.evictExpired(System.currentTimeMillis(), chainEpoch(blockchain));
        for (OrphanEntry entry : expired) {
            // Structurally a no-op — selection takes an entry out of the pool before parking it in
            // mainRef, so nothing is ever in both — and kept because the old tick did it, and
            // because it is the cheap half of an invariant a later change to the packing order
            // could quietly break.
            pool.mainRefRemove(entry.hashlow());

            byte[] key = getKeyFromMeta(entry.meta());
            if (orphanSource.get(key) != null) {
                orphanSource.delete(key);
                long currentSize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
                orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(currentSize - 1, false));
                log.debug("cleanExpiredOrphans orphan current size:{}", currentSize);
            }
            blockchain.getXdagStats().nnoref--;
            kernel.getBlockStore().saveXdagStatus(blockchain.getXdagStats());
            log.debug("Cleaned expired orphan: {}", Hex.toHexString(entry.hashlow().toArray()));
        }
    }

    /**
     * The epoch chunk expiry is measured against: <b>the epoch of this node's main-chain top, never
     * the wall clock</b>.
     *
     * <p>This is a consensus requirement, not a tuning choice. A chunk in epoch N may legitimately
     * be referenced by a paying block in epoch N or N+1 ({@code ChunkChain}'s age rule), and the
     * pool retains it exactly that long. While a node is catching up the two clocks come apart: it
     * is still importing epoch N+1's paying blocks when the wall clock has reached N+2, and a
     * wall-clock eviction would drop the chunks those blocks reference. The node that dropped them
     * then calls the chain invalid while the node that still holds them calls it fine — two honest
     * nodes disagreeing about one chain, which is the exact failure the age rule exists to prevent.
     *
     * <p>Following the chain instead makes a node that is behind evict just as far behind, and it
     * aligns again when the node catches up. {@code ChainOrphanPool.evictExpired} takes the epoch
     * as an argument precisely so this choice is made here, at the wiring, where the chain is in
     * view — the pool itself never reads a clock.
     *
     * <p>Before the first main block there is no chain progress to measure against, so nothing is
     * aged out ({@link #NO_CHAIN_EPOCH}). The other categories are unaffected either way: they run
     * on the local clock, which this method has nothing to do with.
     */
    private long chainEpoch(Blockchain blockchain) {
        long nmain = blockchain.getXdagStats().nmain;
        if (nmain <= 0) {
            return NO_CHAIN_EPOCH;
        }
        Block top = blockchain.getBlockByHeight(nmain);
        return top == null ? NO_CHAIN_EPOCH : XdagTime.getEpoch(top.getTimestamp());
    }

    /**
     * Deletes the ORPHANIND row. The in-memory half this used to do — dropping the expiry stamp —
     * is now inseparable from pool membership, and its partner {@link #deleteFromQueue} has already
     * dropped that; the two are called as a pair on the one path that removes an orphan
     * ({@code BlockchainImpl.removeOrphan}).
     */
    public void deleteByKey(byte[] hashlow, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address) {
        log.debug("deleteByKey");
        byte[] hashL = Arrays.copyOfRange(hashlow, 8, 32);
        byte[] nonceBytes = BytesUtils.bigIntegerToBytes(nonce, 8);
        byte[] isTx = BytesUtils.byteToBytes((byte) (isTxBlock ? 1 : 0), false);
        byte[] key = BytesUtils.merge(ORPHAN_PREFEX, BytesUtils.merge(hashL, nonceBytes, isTx));

        if (orphanSource.get(key) != null) {
            orphanSource.delete(key);

            long currentsize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
            orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(currentsize - 1, false));
            log.debug("deleteByKey current orphan size: {}", currentsize);
        }
    }

    /**
     * Takes an orphan out of memory, by hashlow.
     *
     * <p>It no longer rebuilds a meta out of the caller's {@code nonce}/{@code fee}/{@code address}
     * and then goes hunting for it. That used to work by accident: {@code
     * PriorityBlockingQueue.remove} goes through {@code equals}, and {@code OrphanMeta.equals}
     * compares the hashlow alone, so a rebuilt meta with a stale fee still matched. The pool's
     * ordered sets navigate by <em>comparator</em> instead, and the mtx order leads with the fee —
     * and the two sides really do disagree, because {@code dealOrphan} builds from the block
     * instance {@code tryToConnect} received while {@code removeOrphan} re-fetches the block and
     * recomputes its fee after the chain has moved {@code info.fee}. A rebuilt entry would sort
     * somewhere else and the removal would fail silently, leaving the entry pooled and its quota
     * slot spent. Going through the pool's hashlow index removes the instance that was actually
     * stored, which is the only removal that is safe.
     *
     * <p>The four value parameters stay because they are on the {@link OrphanBlockStore} interface
     * and the database half still needs them; this half does not.
     */
    public void deleteFromQueue(Block block, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address) {
        Bytes32 hashlow = block.getHashLow();
        pool.mainRefRemove(hashlow);
        pool.remove(hashlow);

        log.debug("vipTxCount: {}, accountTxQueue.size(): {}, mtxQueue.size(): {}, linkQueue.size(): {}, mainRef.size() :{}",
                pool.laneSize(AccountLane.VIP), pool.laneSize(AccountLane.REGULAR),
                pool.size(OrphanCategory.MTX), pool.size(OrphanCategory.LINK), pool.mainRefSize());
    }

    public void addOrphan(Block block, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address) {
        // key: 0x00 + hashlow(24B) + nonce(8B) + isTx(1B)
        byte[] hashlow = Arrays.copyOfRange(block.getHashLow().toArray(), 8, 32); // Extract effective 24B
        byte[] nonceBytes = BytesUtils.bigIntegerToBytes(nonce, 8);
        byte[] isTx = BytesUtils.byteToBytes((byte) (isTxBlock ? 1 : 0), false); // 1B
        byte[] key = BytesUtils.merge(ORPHAN_PREFEX, BytesUtils.merge(hashlow, nonceBytes, isTx));
        // value: time(8B) + fee(8B) + address(20B)，Non-account transaction blocks address 全 0
        byte[] timeBytes = BytesUtils.longToBytes(block.getTimestamp(), true);
        byte[] feeBytes = Bytes.wrap(BytesUtils.bigIntegerToBytes(fee.toXAmount(), 8)).toArray();
        byte[] addrBytes = (address == null) ? new byte[20] : address;
        byte[] value = BytesUtils.merge(timeBytes, feeBytes, addrBytes);

        OrphanMeta meta = OrphanMeta.parse(key, value);
        OrphanCategory category = categoryOf(meta);
        OrphanAdmission verdict = admit(meta);

        if (verdict != OrphanAdmission.ADMITTED && verdict != OrphanAdmission.DUPLICATE) {
            // The import path refuses a block whose category is full before it ever reaches here,
            // so what is left is the racing remainder. The row is skipped along with the memory
            // entry so that ORPHANIND keeps meaning "what the pool holds": a row written for an
            // entry the pool turned away would be resurrected by the next rebuild into a pool that
            // is presumably still full, and ORPHAN_SIZE would be counting a block this node cannot
            // pack.
            log.warn("orphan pool refused {} ({}): {}", Hex.toHexString(meta.getHashlow().toArray()),
                    category, verdict);
            return;
        }
        if (category == OrphanCategory.CHUNK) {
            // Memory-only: no row, no ORPHAN_SIZE. See this class's header for the three reasons.
            return;
        }

        if (orphanSource.get(key) == null) {
            orphanSource.put(key, value);

            long currentSize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
            orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(currentSize + 1, false));
            log.debug("orphan current size:{}", currentSize);
        }
        log.debug("vipTxCount: {}, accountTxQueue.size(): {}, mtxQueue.size(): {}, linkQueue.size(): {}, mainRef.size() :{}",
                pool.laneSize(AccountLane.VIP), pool.laneSize(AccountLane.REGULAR),
                pool.size(OrphanCategory.MTX), pool.size(OrphanCategory.LINK), pool.mainRefSize());
    }

    /**
     * The routing {@code addOrphanToMemory} used to do, now one pool call: the category picks the
     * collection, and for an account transaction {@link #laneFor} picks which of the two lanes.
     */
    private OrphanAdmission admit(OrphanMeta meta) {
        OrphanCategory category = categoryOf(meta);
        // The source peer, the chunk chain head and the chunk body are all null here. They arrive
        // with the ingest pipeline that carries them — peer and kind first, the deferred body after
        // — and until then no orphan is attributed to a peer and none classifies as a chunk.
        OrphanEntry entry = new OrphanEntry(meta, category, null, null, null);
        return category == OrphanCategory.ACCOUNT_TX
                ? pool.add(entry, laneFor(meta))
                : pool.add(entry);
    }

    /**
     * The category, from exactly the two fields the old routing looked at.
     *
     * <p>The kind is null because nothing on this path carries one yet, and a null kind can never
     * be {@link OrphanCategory#CHUNK} — so chunk blocks keep landing in {@link OrphanCategory#LINK}
     * exactly as they do today, and the chunk handling above stays dormant until the ingest
     * pipeline supplies the classification.
     */
    private static OrphanCategory categoryOf(OrphanMeta meta) {
        return OrphanCategory.of(meta.isTx(), meta.getAddress(), null);
    }

    /**
     * The VIP admission rule, moved across unchanged: an account transaction takes the fast lane
     * when its nonce is exactly one past the executed nonce <em>and</em> it pays more than the
     * average fee.
     *
     * <p>The watermark is what lets the lane run ahead of {@code AddressStore.getExecutedNonceNum}.
     * That counter only advances when a transaction is applied, so without remembering the nonce of
     * each VIP admission a sender could never have two VIP transactions pooled at once. The pool
     * writes the watermark when it admits into the lane and drops it when that address's VIP bucket
     * empties, which is the same three-site bookkeeping the store used to do by hand.
     */
    private AccountLane laneFor(OrphanMeta meta) {
        String addrKey = Hex.toHexString(meta.getAddress());
        UInt64 executedNonceNum = kernel.getAddressStore().getExecutedNonceNum(meta.getAddress());
        UInt64 pooled = pool.vipNonce(addrKey);
        if (pooled != null && executedNonceNum.compareTo(pooled) < 0) {
            executedNonceNum = pooled;
        }
        UInt64 blockNonce = UInt64.valueOf(meta.getNonce());
        if (blockNonce.compareTo(executedNonceNum.add(UInt64.ONE)) == 0
                && averageFee.lessThan(XAmount.ofXAmount(meta.getFee()))) {
            log.info("averageFee:{}, meta.fee: {}", averageFee.toDecimal(2, XUnit.XDAG).toPlainString(),
                    XAmount.ofXAmount(meta.getFee()).toDecimal(2, XUnit.XDAG).toPlainString());
            return AccountLane.VIP;
        }
        return AccountLane.REGULAR;
    }

    /**
     * D1: the whole body runs under the blockchain monitor — the same monitor {@link
     * #cleanExpiredOrphans} takes, and the one every mutator of these queues already holds
     * ({@code addOrphan} through {@code dealOrphan}, {@code deleteFromQueue}/{@code deleteByKey}
     * through {@code removeOrphan}, both inside the synchronized {@code tryToConnect}).
     *
     * <p>This was the one path left outside it. It runs on the PoW main thread
     * ({@code createMainBlock}) and on the check-main thread ({@code createLinkBlock}), so a
     * concurrent import could remove an entry between an {@code isEmpty()} and the {@code peek()}
     * that follows it — the dereference then threw straight into the mining loop — or between a
     * candidate's {@code peek()} and the {@code poll()} meant to consume it, which dropped a block
     * that was never selected out of the in-memory pool while only the selected one's expiry stamp
     * was cleared.
     *
     * <p>Lock order is blockchain → orphan pool, exactly the order {@code cleanExpiredOrphans}
     * established; nothing holds the pool and then asks for the blockchain, so no inversion is
     * introduced. The monitor is reentrant, so a caller that already holds it is unaffected, and
     * nothing under the monitor blocks on the mining thread (the only listener hands work to
     * unbounded queues), so the wait is bounded by the import itself.
     *
     * <p>The blockchain is null only before the kernel has built it; nothing can import then, so
     * there is no mutator to serialize against.
     */
    @Override
    public List<Address> getOrphan(long num, long[] sendtime, boolean isMain) {
        Blockchain blockchain = kernel.getBlockchain();
        if (blockchain == null) {
            return getOrphanLocked(num, sendtime, isMain);
        }
        synchronized (blockchain) {
            return getOrphanLocked(num, sendtime, isMain);
        }
    }

    private List<Address> getOrphanLocked(long num, long[] sendtime, boolean isMain) {
        List<Address> result = Lists.newArrayList();

        long addNum;
        if (!isMain) {
            addNum = Math.min(getOrphanSize(), num);
        } else {
            addNum = Math.min(getOrphanSize() + pool.mainRefSize(), num);
        }
        List<OrphanEntry> selected = selectBlocks(addNum, sendtime[0], isMain);

        for (OrphanEntry e : selected) {
            if (isMain && !pool.mainRefContains(e.hashlow())) pool.mainRefAdd(e);
            result.add(new Address(e.hashlow(), XdagField.FieldType.XDAG_FIELD_OUT, false));
            sendtime[1] = Math.max(sendtime[1], e.meta().getTime());
        }

        sendtime[1] = Math.min(sendtime[1] + 1, sendtime[0]);
        log.info("vipTxCount: {}, accountTxQueue.size(): {}, mtxQueue.size(): {}, linkQueue.size(): {}, mainRef.size() :{}",
                pool.laneSize(AccountLane.VIP), pool.laneSize(AccountLane.REGULAR),
                pool.size(OrphanCategory.MTX), pool.size(OrphanCategory.LINK), pool.mainRefSize());
        return result;

    }

    /**
     * The packing walk, branch for branch as it was, with every collection access going through the
     * pool. Selection takes an entry <em>out</em> of the pool: what it hands back is no longer
     * pooled, and for a main block the caller parks it in {@code mainRef}.
     */
    public List<OrphanEntry> selectBlocks(long totalRequired, long cutoffTime, boolean isMain) {
        List<OrphanEntry> result = new ArrayList<>();

        if (!pool.mainRefIsEmpty() && (isMain || pool.mainRefSize() >= 9)) {
            Iterator<OrphanEntry> it = pool.mainRefIterator();
            while (it.hasNext() && result.size() < totalRequired) {
                result.add(it.next());
                if (!isMain) it.remove();
            }
        }
        if (isMain && pool.accountBucketCount(AccountLane.VIP) != 0) {
            long remain = 0;
            Queue<CandidateEntry> candidateQueue = newCandidateQueue();
            for (String accountKey : pool.addressKeys(AccountLane.VIP)) {
                OrphanEntry head = pool.firstOf(accountKey, AccountLane.VIP);
                if (head != null) candidateQueue.offer(new CandidateEntry(head, accountKey, CandidateEntry.EntryType.ACCOUNT_TX));
            }
            while (!candidateQueue.isEmpty() && result.size() < totalRequired && remain < 6) {
                CandidateEntry chosen = candidateQueue.poll();
                if (chosen == null) continue;
                result.add(chosen.entry);
                remain++;
                // Remove the entry that was actually selected rather than "whatever is at the head
                // now": under the monitor they are the same entry, and saying so keeps a
                // dropped-but-unselected block impossible instead of merely unreachable. Emptying
                // an address's bucket drops the bucket and its nonce watermark inside the pool.
                pool.remove(chosen.entry.hashlow());
                OrphanEntry next = pool.firstOf(chosen.accountKey, AccountLane.VIP);
                if (next != null && next.meta().getTime() <= cutoffTime) {
                    candidateQueue.offer(new CandidateEntry(next, chosen.accountKey, CandidateEntry.EntryType.ACCOUNT_TX));
                }
            }
        }

        long accountTxCount = pool.laneSize(AccountLane.REGULAR);
        // LINK alone, as before: to this walk a chunk is a link block until the ingest pipeline
        // classifies it, and separating the two in the packing order is a later change.
        int linkCount = pool.size(OrphanCategory.LINK);
        if ((isMain || (accountTxCount + pool.size(OrphanCategory.MTX)) == 0 || linkCount >= totalRequired)
                && linkCount != 0) {
            while (result.size() < totalRequired) {
                OrphanEntry e = pool.first(OrphanCategory.LINK);
                if (e == null || e.meta().getTime() > cutoffTime) break;
                result.add(e);
                pool.remove(e.hashlow());
            }
            return result;
        }

        if ((pool.size(OrphanCategory.MTX) != 0 || accountTxCount != 0) && result.size() < totalRequired) {
            Queue<CandidateEntry> candidateQueue = newCandidateQueue();
            OrphanEntry mtxHead = pool.first(OrphanCategory.MTX);
            if (mtxHead != null) {
                candidateQueue.offer(new CandidateEntry(mtxHead, null, CandidateEntry.EntryType.MTX));
            }
            for (String accountKey : pool.addressKeys(AccountLane.REGULAR)) {
                OrphanEntry head = pool.firstOf(accountKey, AccountLane.REGULAR);
                if (head != null) {
                    candidateQueue.offer(new CandidateEntry(head, accountKey, CandidateEntry.EntryType.ACCOUNT_TX));
                }
            }

            while (!candidateQueue.isEmpty() && result.size() < totalRequired) {
                CandidateEntry chosen = candidateQueue.poll();
                if (chosen == null) continue;
                result.add(chosen.entry);
                pool.remove(chosen.entry.hashlow());
                if (chosen.type == CandidateEntry.EntryType.MTX) {
                    OrphanEntry next = pool.first(OrphanCategory.MTX);
                    if (next != null && next.meta().getTime() <= cutoffTime) {
                        candidateQueue.offer(new CandidateEntry(next, null, CandidateEntry.EntryType.MTX));
                    }
                } else {
                    OrphanEntry next = pool.firstOf(chosen.accountKey, AccountLane.REGULAR);
                    if (next != null && next.meta().getTime() <= cutoffTime) {
                        candidateQueue.offer(new CandidateEntry(next, chosen.accountKey, CandidateEntry.EntryType.ACCOUNT_TX));
                    }
                }
            }
        }

        return result;
    }

    /**
     * D1: the working set of {@code selectBlocks} is a local, not an instance field. It used to be
     * shared state that every call {@code clear()}ed and refilled, so two callers — the PoW main
     * thread and the check-main thread — could corrupt each other's selection even while each one
     * looked correct on its own.
     */
    private static Queue<CandidateEntry> newCandidateQueue() {
        return new PriorityQueue<>(20, Comparator
                .comparingLong((CandidateEntry e) -> -e.entry.meta().getFee())
                .thenComparingLong(e -> e.entry.meta().getTime())
                .thenComparing(e -> e.entry.meta().getHashlow().toArray(), UnsignedBytes.lexicographicalComparator()));
    }

    private byte[] getKeyFromMeta(OrphanMeta meta) {
        byte[] hashL = Arrays.copyOfRange(meta.getHashlow().toArray(), 8, 32); // Extract effective 24B
        byte[] nonceBytes = BytesUtils.longToBytes(meta.getNonce(), false);
        byte[] isTx = BytesUtils.byteToBytes((byte) (meta.isTx() ? 1 : 0), false);
        return BytesUtils.merge(ORPHAN_PREFEX, BytesUtils.merge(hashL, nonceBytes, isTx));
    }

    /**
     * How many orphans this node holds — the same four queues this summed before they became the
     * pool's four categories, {@code mainRef} excluded exactly as it always was.
     */
    public long getOrphanSize() {
        return pool.totalSize();
    }

    @Getter
    public static class CandidateEntry {
        public enum EntryType {ACCOUNT_TX, MTX}

        public final OrphanEntry entry;
        public final String accountKey;
        public final EntryType type;

        public CandidateEntry(OrphanEntry entry, String accountKey, EntryType type) {
            this.entry = entry;
            this.accountKey = accountKey;
            this.type = type;
        }
    }

}
