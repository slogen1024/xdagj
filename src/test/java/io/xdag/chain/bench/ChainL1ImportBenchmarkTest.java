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

package io.xdag.chain.bench;

import static io.xdag.chain.bench.BenchWorkload.payload;
import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ingest.IngestPipeline;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.DevnetConfig;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.PersistControl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.node.Node;
import io.xdag.utils.BytesUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Black-box L1 import baseline. Skipped unless {@code -Dxdag.bench=true}. Prints Markdown tables and
 * writes {@code target/bench/l1-import-<timestamp>.json}; the numbers go into docs/benchmarks by hand.
 *
 * <p>Three measured paths, {@code ROUNDS} rounds each on a fresh fixture. {@code direct} calls
 * {@code tryToConnect} on network-form blocks (parsed, raw bytes attached); {@code syncPath} goes
 * through {@link SyncManager#validateAndAddNewBlock} (the network entry: {@code parse()} is a no-op
 * on such a block, {@code importBlock} copies the 512 bytes and re-parses them into a new
 * {@code Block} before {@code tryToConnect}). The two are interleaved — round r runs both legs back
 * to back, alternating which goes first — and reported as PAIRED per-round deltas, so monotone
 * drift over the run (JIT, log growth, page cache) does not get charged to one of them.
 * {@code pipeline} submits the same workload to an {@link IngestPipeline} whose committer is
 * {@code SyncManager.importPreValidated} — SP0b-2's network path — so pre-validation (parse, hash,
 * ECDSA) runs on the pool and only the commit is serialized; its mean/p50/p95 are the IN-LOCK
 * commit time of a paying block and its blocks/s is end to end, from the first submit to the last
 * commit plus a {@code flushSync()} of the write-behind queue, so persistence is inside the number.
 * {@code confirmed} imports and then mines main blocks linking every paying block until the last
 * one is applied, so it includes fake PoW, {@code setMain} and {@code applyBlock}; its blocks/s
 * counts paying blocks and chunks only, not the main blocks. The {@code phase.*} rows replay single
 * phases of the direct path on the last workload for cost attribution only; they are not a
 * consensus path. The two {@code phase.orphan.*} rows are the exception: the fixture sets no pow, so
 * {@code dealOrphan} is a no-op and no other row in this file touches the orphan pool — they are an
 * estimate of what the pool would cost a node that does mine, not a replay of something the direct
 * path did. {@code calib.emptyMain} is the fixture's cost of an empty main block, so the
 * confirmed rows can be split into fake PoW and setMain/apply. For the {@code confirmed.*} and
 * {@code calib.emptyMain} rows the mean/p50/p95 columns are per main block.
 *
 * <p>Statistics: {@code mean} is the arithmetic mean over the {@code n} samples, {@code p50}/{@code p95}
 * their percentiles. Phase rows are best-of-3 wall time divided by {@code n} — a mean — so a phase
 * share is phase mean / direct mean, never phase mean / direct p50 (the distribution is right-skewed).
 */
public class ChainL1ImportBenchmarkTest extends ChainL1TestBase {

    private static final int ROUNDS = 3;
    /**
     * Paying blocks one fixture main block can link: 16 fields minus header, topRef, coinbase, the two
     * sign_out fields and the nonce. {@code Block.setType} throws above that budget.
     */
    private static final int LINKS_PER_MAIN = 10;
    /** Empty main blocks mined for the {@code calib.emptyMain} row. */
    private static final int CALIB_MAINS = 100;
    /** Empty main blocks mined after the last paying block before the confirmed round gives up (the fixture's own confirm() budget). */
    private static final int CONFIRM_MAINS = 6;
    /**
     * Addresses the {@code orphan.removeWorst} flood concentrates the paying blocks on, so each
     * accountTxMap bucket is {@code blocks / WORST_SENDERS} deep rather than {@code blocks / 64}.
     */
    private static final int WORST_SENDERS = 4;
    /**
     * Entries handed to {@code mainRef} before the worst-case removal — a backlog of main-block
     * references not yet confirmed. {@code deleteFromQueue} walks this deque on EVERY removal.
     */
    private static final int MAIN_REF_DEPTH = 256;
    /**
     * Per-address bucket depths the {@code orphan.removeWorst.d*} rows are measured at. Two points,
     * because the question the row exists to answer — does a removal cost more when the queue is
     * deeper? — is answered by the RATIO between depths, not by either number on its own. They are
     * deliberately far below the other rows' 20000 samples: the flood concentrates onto
     * {@link #WORST_SENDERS} addresses, so the work per pass grows with the SQUARE of the depth and
     * a 5000-deep bucket costs ~10^8 comparisons per pass before the restores.
     */
    private static final int[] WORST_DEPTHS = {125, 500};
    /**
     * Item n is stamped {@code txTime() + n} and every item must stay inside the one epoch the next
     * main block closes; {@code txTime()} is 60000 ticks before that epoch's end.
     */
    private static final int MAX_BLOCKS = 60_000;

    private static int senders;
    private static int blocks;
    private static long seed;
    private static int[] mix;
    private long fixtureStart;
    private Bytes chainId;
    private Bytes contract;
    private Bytes32 codeHash;

    /** Class-level gate: a plain {@code mvn test} skips the class before the superclass @Before builds a fixture. */
    @BeforeClass
    public static void benchGate() {
        assumeTrue("set -Dxdag.bench=true to run the import benchmark", Boolean.getBoolean("xdag.bench"));
        senders = Integer.getInteger("xdag.bench.senders", 64);
        blocks = Integer.getInteger("xdag.bench.blocks", 20_000);
        seed = Long.getLong("xdag.bench.seed", 20260917L);
        mix = Arrays.stream(System.getProperty("xdag.bench.mix", "60,25,10,5").split(",")).mapToInt(Integer::parseInt).toArray();
        assertTrue("xdag.bench.blocks must be in 1.." + (MAX_BLOCKS - 1) + " (one epoch's tick budget)", blocks > 0 && blocks < MAX_BLOCKS);
        assertTrue("xdag.bench.senders must be positive", senders > 0);
    }

    @Before
    public void benchOrigin() {
        // setUpChain() has run (superclass @Before) but mined nothing yet: this is the mining timeline's origin.
        fixtureStart = generateTime;
    }

    /**
     * The production path: a node started without {@code node.transaction.history.enable} has
     * {@code kernel.getTxHistoryStore() == null} and {@code onNewTxHistory} returns at once. The
     * fixture's Mockito mock instead answers {@code saveTxHistory} with false, which sends every
     * amount link down the MySQL-failure fallback (a WARN, an INFO and a RocksDB put). BlockchainImpl
     * copies the store once, in its constructor, so this has to happen before it is built.
     */
    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.setTxHistoryStore(null);
    }

    /**
     * The node's real storage path: INDEX/BLOCK/TIME/ORPHANIND behind one {@link WriteBehindQueue}
     * with the kernel's own defaults. {@code -Dxdag.bench.writeBehind=false} gives every row the
     * old synchronous stores instead, which is the "before" leg of the SP0b-2 comparison.
     * {@link ChainL1TestBase#setUpChain()} installs the queue as the kernel's {@code PersistControl}
     * when the factory is a {@link WriteBehindFactory}, and {@code dbFactory.close()} in
     * {@code tearDownChain()} flushes and stops the writer thread, so every fresh fixture gets a
     * fresh queue and none is leaked.
     */
    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        if (!writeBehind()) {
            return raw;
        }
        WriteBehindQueue q = new WriteBehindQueue(4096, 256, 20, true);
        q.start();
        return new WriteBehindFactory(raw, q, 65536);
    }

    private static boolean writeBehind() {
        return Boolean.parseBoolean(System.getProperty("xdag.bench.writeBehind", "true"));
    }

    private static int ingestThreads() {
        return Integer.getInteger("xdag.bench.ingestThreads", Runtime.getRuntime().availableProcessors());
    }

    /** The fixture's persistence control: the live write-behind queue, or a no-op when it is off. */
    private PersistControl persist() {
        return dbFactory instanceof WriteBehindFactory wb ? wb.queue() : PersistControl.NONE;
    }

    /**
     * Tears the fixture down and builds a fresh one on the same mining timeline, so every round sees
     * byte-identical blocks (the workload's senders are seed-derived as well).
     */
    private void freshFixture() throws Exception {
        tearDownChain();
        // setUpChain() calls root.newFolder("node"), which throws if the folder still exists.
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = fixtureStart;
        setUpChain();
    }

    /**
     * One confirmed chain with a shared code blob so CALL/DEPLOY items have a target. The deploy is
     * built here with nonce 1 rather than through the fixture's {@code deployNewChain}: that helper
     * draws from a counter {@code setUpChain} never resets, so on the second fresh fixture it would
     * hand out nonce 2 against an address store whose executed nonce is 0 again, and the deploy
     * would never be applied ("tx nonce error").
     */
    private void prepareChain() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        Bytes wasm = payload(600, 7);
        Bytes initArgs = payload(10, 8);
        int chunks = ChainBlockBuilder.deployNewChainChunks(wasm.size(), initArgs.size());
        XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
        ChainBlockBuilder.Built deploy = ChainBlockBuilder.deployNewChain(config, txTime(), poolKey, UInt64.ONE, fee, wasm, CFG, initArgs, 1000L).value();
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        chainId = ChainIds.chainIdOf(deploy.block().getHash());
        contract = ChainIds.contractIdOf(deploy.block().getHash());
        codeHash = HashUtils.sha256(wasm);
    }

    private BenchWorkload workload() {
        BenchWorkload w = new BenchWorkload(config, seed, senders, blocks, mix, txTime(), chainId, contract, codeHash);
        for (ECKeyPair k : w.senders()) {
            addressStore.updateBalance(k.toAddress().toArray(), XAmount.of(10_000, XUnit.XDAG));
        }
        return w;
    }

    /**
     * One row. {@code n} is the sample count behind mean/p50/p95 (paying blocks, main blocks, or
     * phase units such as lookups); {@code perSec} is the row's throughput unit per second (blocks
     * incl. chunks for the import rows, main blocks for calib, phase units for phase rows).
     * NaN marks a column that has no meaning for the row and prints as "-".
     */
    private record Measure(String name, int n, double perSec, double meanMicros, double p50Micros, double p95Micros, long totalMillis) {
    }

    /**
     * The ORPHANIND row count as the store itself persists it. {@code getOrphanSize()} counts the
     * in-memory queues only, and the two are what separate "this pass really wrote" from "this pass
     * found every key already present and skipped its writes" — {@code addOrphan}'s idempotence
     * guard is on the database, not on the queues.
     *
     * <p>Benchmark-only cast: the counter is not on the {@link OrphanBlockStore} interface.
     */
    private static long persistedOrphanSize(OrphanBlockStore pool) {
        byte[] v = ((OrphanBlockStoreImpl) pool).getOrphanSource().get(OrphanBlockStore.ORPHAN_SIZE);
        return v == null ? 0 : BytesUtils.bytesToLong(v, 0, false);
    }

    // The three in-memory collections orphan.removeWorst asserts the shape of. Benchmark-only casts:
    // none of them is on the OrphanBlockStore interface, and without them the row could silently
    // stop being the worst case (an empty linkQueue, an unseeded mainRef) and still look healthy.
    private static Collection<?> mainRef(OrphanBlockStore pool) {
        return ((OrphanBlockStoreImpl) pool).getMainRef();
    }

    private static Collection<?> linkQueue(OrphanBlockStore pool) {
        return ((OrphanBlockStoreImpl) pool).getLinkQueue();
    }

    private static Map<?, ?> accountBuckets(OrphanBlockStore pool) {
        return ((OrphanBlockStoreImpl) pool).getAccountTxMap();
    }

    /**
     * A flood-shaped entry list over the workload's first {@code items} items: every chunk of those
     * items as a link entry, then every paying block reassigned to one of {@link #WORST_SENDERS}
     * addresses so each accountTxMap bucket ends up {@code items / WORST_SENDERS} deep instead of
     * {@code items / senders}.
     *
     * <p>Only the address argument is synthesized. The pool is handed exactly what a flood from a
     * handful of addresses would present it with, and they are the workload's own sender addresses —
     * real entries in the AddressStore — so {@code getExecutedNonceNum} answers as it does in
     * production rather than against a key that was never funded.
     */
    private List<OrphanEntry> floodEntries(BenchWorkload w, List<OrphanEntry> paying, int items) {
        List<OrphanEntry> flood = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            for (Block c : w.items().get(i).chunks()) {
                flood.add(orphanEntry(c));
            }
        }
        List<ECKeyPair> floodSenders = w.senders().subList(0, WORST_SENDERS);
        for (int i = 0; i < items; i++) {
            OrphanEntry e = paying.get(i);
            flood.add(new OrphanEntry(e.block(), e.isTx(), e.nonce(), e.fee(),
                    floodSenders.get(i % WORST_SENDERS).toAddress().toArray()));
        }
        return flood;
    }

    /**
     * One {@code orphan.removeWorst.d<depth>} row: {@code deleteFromQueue} over a flood-shaped pool,
     * in an order uncorrelated with any queue's comparator. Three things separate it from
     * {@code orphan.remove}, and all three are what makes it the case SP0b-3 has to move:
     *
     * <ol>
     *   <li>FEW SENDERS, DEEP QUEUES — see {@link #floodEntries}.
     *   <li>{@code linkQueue} AND {@code mainRef}, not just the account buckets. Chunks are not
     *       transaction blocks, so they land in {@code linkQueue} (spec §1: 分片块就住在里面), and one
     *       {@code getOrphan(isMain = true)} seeds {@code mainRef} — a {@code ConcurrentLinkedDeque}
     *       that {@code deleteFromQueue} walks on EVERY removal whatever the entry's category. Both
     *       are O(n) with no heap structure to help (spec §3 table).
     *   <li>OUT-OF-ORDER REMOVAL — a fixed-seed shuffle, so {@code contains()}/{@code remove()} land
     *       mid-queue instead of at index 0 the way the workload-ordered {@code orphan.remove} does.
     * </ol>
     *
     * <p>{@code n} is this row's own removal count, far below the 20000 of the other phase rows:
     * the flood is quadratic in depth, so matching their sample count would cost ~10^8 comparisons
     * per pass. Compare the two depths against EACH OTHER, not against the 20000-sample rows.
     *
     * <p>Still an understatement of the ceiling: {@code chain.orphan.chunkLimit} is 60000 (spec §2).
     */
    private Measure worstRow(OrphanBlockStore pool, BenchWorkload w, List<OrphanEntry> paying, int items) {
        List<OrphanEntry> flood = floodEntries(w, paying, items);
        int links = (int) flood.stream().filter(e -> !e.isTx()).count();
        int expectedMainRef = Math.min(MAIN_REF_DEPTH, links);
        // Fixed seed: the removal order is part of what this row measures, so it must be reproducible.
        List<OrphanEntry> order = new ArrayList<>(flood);
        Collections.shuffle(order, new Random(seed));
        int[] pass = {0};
        long nanos = bestOf3(() -> {
            if (pass[0]++ > 0) {
                assertEquals("a timed worst-case pass did not drain the pool", 0, (int) pool.getOrphanSize());
                assertTrue("a timed worst-case pass left entries in mainRef", mainRef(pool).isEmpty());
            }
            emptyPool(pool, flood);
            fillPool(pool, flood);
            // The shape is the row's whole point, so assert it rather than assume it.
            assertEquals("the restore left a partial pool before a timed worst-case pass", flood.size(), (int) pool.getOrphanSize());
            assertEquals("chunks must flood linkQueue", links, linkQueue(pool).size());
            assertEquals("the paying blocks must be concentrated on WORST_SENDERS buckets", WORST_SENDERS, accountBuckets(pool).size());
            // Seeds mainRef exactly as a main block's selection does. The exact count is selectBlocks'
            // business (a linkQueue draw plus whatever the VIP branch contributes) and SP0b-3 is
            // going to change that order, so pin mainRef against what getOrphan actually handed out
            // rather than against a number that encodes today's selection policy.
            int seeded = pool.getOrphan(MAIN_REF_DEPTH, new long[]{Long.MAX_VALUE, 0}, true).size();
            assertEquals("mainRef was not seeded with what getOrphan handed out", seeded, mainRef(pool).size());
            assertTrue("mainRef is too shallow for a worst case: " + seeded, seeded >= expectedMainRef);
            persist().flushSync();
        }, () -> {
            for (OrphanEntry e : order) {
                pool.deleteFromQueue(e.block(), e.isTx(), e.nonce(), e.fee(), e.address());
            }
        });
        assertEquals("orphan.removeWorst must leave the pool empty", 0, (int) pool.getOrphanSize());
        assertTrue("orphan.removeWorst must drain mainRef too", mainRef(pool).isEmpty());
        emptyPool(pool, flood);
        return phaseRow("orphan.removeWorst.d" + (items / WORST_SENDERS), flood.size(), nanos);
    }

    /** Drops every entry from the pool in memory AND on disk, then drains the write-behind queue. */
    private void emptyPool(OrphanBlockStore pool, List<OrphanEntry> entries) {
        for (OrphanEntry e : entries) {
            pool.deleteFromQueue(e.block(), e.isTx(), e.nonce(), e.fee(), e.address());
            pool.deleteByKey(e.block().getHashLow().toArray(), e.isTx(), e.nonce(), e.fee(), e.address());
        }
        persist().flushSync();
    }

    private static void fillPool(OrphanBlockStore pool, List<OrphanEntry> entries) {
        for (OrphanEntry e : entries) {
            pool.addOrphan(e.block(), e.isTx(), e.nonce(), e.fee(), e.address());
        }
    }

    /** One orphan-pool call's arguments, derived once so the orphan phase rows time the pool only. */
    private record OrphanEntry(Block block, boolean isTx, UInt64 nonce, XAmount fee, byte[] address) {
    }

    /**
     * {@code BlockchainImpl.dealOrphan}'s own argument derivation, hoisted out of the timed window.
     * {@code removeOrphan} derives the same five values for the same block, so one entry serves both
     * the add and the remove row.
     */
    private OrphanEntry orphanEntry(Block b) {
        UInt64 nonce = UInt64.ZERO;
        XAmount fee = blockchain.getTxFee(b);
        byte[] address = null;
        if (blockchain.isAccountTx(b)) {
            for (Address ref : b.getLinks()) {
                if (ref.getType() == XDAG_FIELD_INPUT) {
                    address = BytesUtils.byte32ToArray(ref.getAddress()).toArray();
                    nonce = b.getTxNonceField().getTransactionNonce();
                    break;
                }
            }
        }
        return new OrphanEntry(b, blockchain.isTxBlock(b), nonce, fee, address);
    }

    /** Mean (summed before sorting), p50 and p95 over the first {@code n} samples of {@code samples}. */
    private static Measure measure(String name, long[] samples, int n, long unitsForRate, long totalMillis) {
        long[] s = Arrays.copyOf(samples, n);
        long sum = 0;
        for (long v : s) {
            sum += v;
        }
        Arrays.sort(s);
        return new Measure(name, n, unitsForRate * 1000.0 / Math.max(1, totalMillis), sum / 1000.0 / n,
                s[n / 2] / 1000.0, s[Math.min(n - 1, (int) (n * 0.95))] / 1000.0, totalMillis);
    }

    /** Imports every item (chunks tail-first, then the paying block); per-block latency is the paying block's only. */
    private Measure timeImport(String name, BenchWorkload w, Consumer<Block> importer) {
        List<BenchWorkload.Item> items = w.items();
        long[] perBlock = new long[items.size()];
        long t0 = System.nanoTime();
        int i = 0;
        for (BenchWorkload.Item item : items) {
            for (int c = item.chunks().size() - 1; c >= 0; c--) {
                importer.accept(item.chunks().get(c));
            }
            long a = System.nanoTime();
            importer.accept(item.block());
            perBlock[i++] = System.nanoTime() - a;
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        assertTop();
        return measure(name, perBlock, perBlock.length, items.size() + w.chunkCount(), total);
    }

    private void expectImported(Block b) {
        ImportResult r = blockchain.tryToConnect(b);
        // Build the message only on failure: this runs inside the timed window, and the syncPath
        // leg's assertion does not concatenate, so an eager message would skew the paired delta.
        if (r != ImportResult.IMPORTED_BEST && r != ImportResult.IMPORTED_NOT_BEST) {
            fail("import failed: " + r + " " + r.getErrorInfo());
        }
    }

    /** Outside the timed loops: a silent fork (a paying block or chunk taking the top) must not be measured as success. */
    private void assertTop() {
        assertArrayEquals("a non-main block hijacked the chain top; change the payload seed (plan §0.5)",
                topRef.toArray(), blockchain.getXdagTopStatus().getTop());
    }

    private boolean applied(Block b) {
        Block stored = blockchain.getBlockByHash(b.getHashLow(), false);
        assertNotNull("block was never stored: " + b.getHashLow(), stored);
        return (stored.getInfo().getFlags() & BI_APPLIED) != 0;
    }

    /**
     * The network entry point needs a wired kernel: {@code SyncManager} takes the blockchain and the
     * channel manager from it, and {@code importBlock} evaluates {@code kernel.getClient().getNode()}
     * on every IMPORTED_* result before it looks at the ttl. ttl stays 0 so nothing is distributed;
     * {@code isOld} stays false so the measured path is the live one.
     */
    private SyncManager syncManager() {
        ChannelManager channels = mock(ChannelManager.class);
        when(channels.getActiveChannels()).thenReturn(List.of());
        kernel.setChannelMgr(channels);
        PeerClient client = mock(PeerClient.class);
        when(client.getNode()).thenReturn(new Node("127.0.0.1", 8001));
        kernel.setClient(client);
        kernel.setBlockchain(blockchain);
        return new SyncManager(kernel);
    }

    @Test
    public void baseline() throws Exception {
        List<Measure> results = new ArrayList<>();
        Measure[] direct = new Measure[ROUNDS];
        Measure[] syncPath = new Measure[ROUNDS];
        String[] order = new String[ROUNDS];
        // direct and syncPath interleaved: round r runs both legs back to back on fresh fixtures,
        // even rounds direct first, odd rounds syncPath first.
        for (int round = 0; round < ROUNDS; round++) {
            boolean directFirst = round % 2 == 0;
            order[round] = directFirst ? "direct>syncPath" : "syncPath>direct";
            for (int leg = 0; leg < 2; leg++) {
                if (round > 0 || leg > 0) {
                    freshFixture();
                }
                prepareChain();
                BenchWorkload w = workload();
                if ((leg == 0) == directFirst) {
                    direct[round] = timeImport("direct.r" + round, w, this::expectImported);
                    results.add(direct[round]);
                } else {
                    SyncManager sync = syncManager();
                    syncPath[round] = timeImport("syncPath.r" + round, w, b -> {
                        ImportResult r = sync.validateAndAddNewBlock(new BlockWrapper(b, 0));
                        assertTrue(String.valueOf(r), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
                    });
                    results.add(syncPath[round]);
                }
            }
        }
        // The SP0b-2 acceptance row: the same workload through IngestPipeline (parallel
        // pre-validation off the lock, in-order commit through SyncManager.importPreValidated).
        // mean/p50/p95 are the IN-LOCK commit time of a paying block; blocks/s is end to end, from
        // the first submit to the last commit PLUS the final drain, so the throughput includes
        // persistence.
        Measure[] pipelineRows = new Measure[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            freshFixture();
            prepareChain();
            BenchWorkload w = workload();
            SyncManager sync = syncManager();
            long[] inLock = new long[w.items().size()];
            int[] k = {0};
            // The committer runs on the pipeline's commit thread, where an AssertionError would be
            // caught and only logged: record the first bad result and assert on this thread instead.
            String[] failure = {null};
            IngestPipeline pipeline = new IngestPipeline(ingestThreads(), 4096, pv -> {
                long a = System.nanoTime();
                ImportResult r = sync.importPreValidated(pv);
                long spent = System.nanoTime() - a;
                Block b = pv.block();
                if (b != null && b.getInputs() != null && !b.getInputs().isEmpty() && k[0] < inLock.length) {
                    inLock[k[0]++] = spent;
                }
                if (r != ImportResult.IMPORTED_BEST && r != ImportResult.IMPORTED_NOT_BEST && failure[0] == null) {
                    failure[0] = "import failed: " + r + " " + r.getErrorInfo();
                }
                return r;
            });
            pipeline.start();
            long t0 = System.nanoTime();
            for (BenchWorkload.Item item : w.items()) {
                for (int c = item.chunks().size() - 1; c >= 0; c--) {
                    pipeline.submit(new BlockWrapper(item.chunks().get(c), 0));
                }
                pipeline.submit(new BlockWrapper(item.block(), 0));
            }
            assertTrue("pipeline did not drain in 600s", pipeline.awaitIdle(600, TimeUnit.SECONDS));
            persist().flushSync();
            long total = (System.nanoTime() - t0) / 1_000_000;
            pipeline.stop();
            if (failure[0] != null) {
                fail(failure[0]);
            }
            // Chunk blocks carry no inputs (ChunkChainBuilder passes links == null), so the in-lock
            // samples must be exactly the paying blocks; if that ever changed the row would be
            // timing the wrong blocks rather than being visibly wrong.
            assertEquals("in-lock samples must be one per paying block", w.items().size(), k[0]);
            assertTop();
            pipelineRows[round] = measure("pipeline.r" + round, inLock, k[0], w.items().size() + w.chunkCount(), total);
            results.add(pipelineRows[round]);
        }
        int mainsPerRound = 0;
        for (int round = 0; round < ROUNDS; round++) {
            freshFixture();
            prepareChain();
            BenchWorkload w = workload();
            List<BenchWorkload.Item> items = w.items();
            List<Bytes32> refs = new ArrayList<>();
            long[] perMain = new long[items.size() / LINKS_PER_MAIN + 1 + CONFIRM_MAINS];
            int mains = 0;
            long t0 = System.nanoTime();
            for (BenchWorkload.Item item : items) {
                for (int c = item.chunks().size() - 1; c >= 0; c--) {
                    expectImported(item.chunks().get(c));
                }
                expectImported(item.block());
                refs.add(hashLow(item.block()));
                if (refs.size() == LINKS_PER_MAIN) {
                    perMain[mains++] = mineTimed(refs);
                    refs.clear();
                }
            }
            if (!refs.isEmpty()) {
                perMain[mains++] = mineTimed(refs);
            }
            // The fixture's confirm(): empty mains until the last paying block is applied — timed and
            // counted here, so mainsPerRound is every main block the round mined.
            Block last = items.get(items.size() - 1).block();
            for (int i = 0; i < CONFIRM_MAINS && !applied(last); i++) {
                perMain[mains++] = mineTimed(List.of());
            }
            long total = (System.nanoTime() - t0) / 1_000_000;
            assertTrue("last paying block not applied after " + CONFIRM_MAINS + " empty main blocks", applied(last));
            assertTop();
            mainsPerRound = mains;
            // mean/p50/p95 here are per LOADED main block (mineMain incl. checkMain), not per paying
            // block; blocks/s counts paying blocks + chunks, not the main blocks.
            results.add(measure("confirmed.r" + round, perMain, mains, items.size() + w.chunkCount(), total));
        }
        // phase replay on the last workload's blocks (cost attribution, not a consensus path)
        freshFixture();
        prepareChain();
        // Calibration: the fixture's own cost of one EMPTY main block (nonce search + tryToConnect +
        // checkMain with nothing to apply). confirmed.total - mains * this - direct.total ~ setMain/apply.
        long[] perEmpty = new long[CALIB_MAINS];
        long c0 = System.nanoTime();
        for (int i = 0; i < CALIB_MAINS; i++) {
            perEmpty[i] = mineTimed(List.of());
        }
        results.add(measure("calib.emptyMain", perEmpty, CALIB_MAINS, CALIB_MAINS, (System.nanoTime() - c0) / 1_000_000));
        BenchWorkload w = workload();
        // Imported once, untimed, so the refLookup phase resolves chunk refs that exist, as the real
        // path does (a lookup that misses is answered by the bloom filter and would read too cheap).
        for (BenchWorkload.Item item : w.items()) {
            for (int c = item.chunks().size() - 1; c >= 0; c--) {
                expectImported(item.chunks().get(c));
            }
            expectImported(item.block());
        }
        assertTop();
        // The paying blocks' raw bytes exactly as they arrived (not a re-encoding).
        List<byte[]> raw = new ArrayList<>();
        for (BenchWorkload.Item item : w.items()) {
            raw.add(item.block().getXdagBlock().getData().toArray());
        }
        results.add(phaseRow("parse", raw.size(), bestOf3(() -> {
            for (byte[] b : raw) {
                new Block(new XdagBlock(b));
            }
        })));
        List<Block> parsed = new ArrayList<>();
        for (byte[] b : raw) {
            parsed.add(new Block(new XdagBlock(b)));
        }
        results.add(phaseRow("signature", parsed.size(), bestOf3(() -> {
            for (Block b : parsed) {
                blockchain.canUseInput(b);
            }
        })));
        // Only non-address links are looked up in the block store; count them so the row is per lookup.
        int lookups = 0;
        for (Block b : parsed) {
            for (Address a : b.getLinks()) {
                if (!a.getIsAddress()) {
                    lookups++;
                }
            }
        }
        results.add(phaseRow("refLookup", lookups, bestOf3(() -> {
            for (Block b : parsed) {
                for (Address a : b.getLinks()) {
                    if (!a.getIsAddress()) {
                        kernel.getBlockStore().getBlockInfoByHash(a.getAddress());
                    }
                }
            }
        })));
        // Orphan-pool add and remove, the two halves SP0b-2b named as a prime suspect for the
        // in-lock rest. The fixture never sets a pow, so BlockchainImpl.dealOrphan returns at once
        // and every other row in this file imports without ever touching the pool: it is empty here,
        // and these two rows are the only place its cost shows up at all.
        //
        // Inside `orphan.add`: the in-memory offer (a linear contains() over the sender's queue,
        // then an O(log n) offer), ONE AddressStore read per account transaction
        // (getExecutedNonceNum, a synchronous RocksDB get — ADDRESS is not behind the write-behind
        // queue), and two ORPHANIND reads plus two ORPHANIND writes through the node's write-behind
        // queue (the key itself and the ORPHAN_SIZE counter). Inside `orphan.remove`: deleteFromQueue
        // only, which touches no database at all — removeOrphan's database half is deleteByKey, and
        // that is deliberately not in this row, so the number is the pure removal walk.
        //
        // Every paying block here carries an XDAG_FIELD_INPUT, so all of them are account
        // transactions and land in accountTxMap keyed by sender: the per-sender queues whose linear
        // scans SP0b-3 is about. Chunks are not in these rows, so linkQueue stays empty, and
        // getOrphan() is never called, so mainRef stays empty too.
        //
        // `orphan.remove` is the BEST case of the current removal, not its worst. The removals run
        // in workload order, which per sender is ascending nonce — exactly the order accountTxMap's
        // comparator heaps them in — so contains() and remove() both hit index 0 and the row is an
        // O(log n) sift, not the O(n) scan. That is the shape of the happy path (selectBlocks pops
        // heads too), so it is the right BEFORE number for "SP0b-3 must not make the normal path
        // slower", but it says nothing about the flood this plan exists to survive. Proving the
        // O(n) claim needs a different workload: few senders, deep queues, out-of-order removal.
        //
        // Both bodies mutate the pool, so each of the three repetitions is preceded by an untimed
        // restore of the same starting state (see the two-argument bestOf3): plain bestOf3 would
        // have addOrphan hit its idempotence guards on runs two and three and deleteFromQueue walk
        // an already-empty pool, and the reported minimum would be whichever of those read cheapest.
        OrphanBlockStore pool = kernel.getOrphanBlockStore();
        List<OrphanEntry> orphans = new ArrayList<>();
        for (Block b : parsed) {
            orphans.add(orphanEntry(b));
        }
        // Empties the pool in memory AND on disk, and drains the write-behind queue, so a timed run
        // never starts against a backlog the previous run's restore queued up.
        Runnable emptyPool = () -> emptyPool(pool, orphans);
        Runnable fillPool = () -> fillPool(pool, orphans);
        // bestOf3 reports the CHEAPEST of three passes, so "each pass did the same work" cannot be
        // left to the reader's trust: every timed pass is bracketed by state checks, in memory AND
        // on disk. A pass that started against a pool someone else had already filled or drained —
        // the way a plain bestOf3 would have run it — fails the build instead of quietly becoming
        // the reported minimum. addPass/removePass skip the entry check on the first pass only,
        // where there is no previous pass to have left the state behind.
        int[] addPass = {0};
        results.add(phaseRow("orphan.add", orphans.size(), bestOf3(() -> {
            if (addPass[0]++ > 0) {
                assertEquals("a timed add pass did not fill the pool", orphans.size(), (int) pool.getOrphanSize());
                assertEquals("a timed add pass skipped its ORPHANIND writes", orphans.size(), (int) persistedOrphanSize(pool));
            }
            emptyPool.run();
            assertEquals("the restore left entries in the pool before a timed add pass", 0, (int) pool.getOrphanSize());
            assertEquals("the restore left ORPHANIND rows before a timed add pass", 0, (int) persistedOrphanSize(pool));
        }, fillPool)));
        assertEquals("orphan.add must leave one pool entry per paying block", orphans.size(), (int) pool.getOrphanSize());
        assertEquals("orphan.add must leave one ORPHANIND row per paying block", orphans.size(), (int) persistedOrphanSize(pool));
        int[] removePass = {0};
        results.add(phaseRow("orphan.remove", orphans.size(), bestOf3(() -> {
            if (removePass[0]++ > 0) {
                assertEquals("a timed removal pass did not drain the pool", 0, (int) pool.getOrphanSize());
            }
            emptyPool.run();
            fillPool.run();
            assertEquals("the restore left a partial pool before a timed removal pass", orphans.size(), (int) pool.getOrphanSize());
            persist().flushSync();
        }, () -> {
            for (OrphanEntry e : orphans) {
                pool.deleteFromQueue(e.block(), e.isTx(), e.nonce(), e.fee(), e.address());
            }
        })));
        assertEquals("orphan.remove must leave the pool empty", 0, (int) pool.getOrphanSize());
        emptyPool.run();

        // orphan.removeWorst.d*: the same removal against the flood this subproject exists to
        // survive (spec §7.2 "垃圾分片洪泛" / §3 "移除复杂度"), because orphan.remove above is the BEST
        // case and would show nothing after the rework. Measured at two bucket depths, because the
        // question is whether a removal costs more when the queue is deeper — a ratio, not a number.
        // See worstRow for the shape and floodEntries for how it is built.
        int deepest = 0;
        for (int depth : WORST_DEPTHS) {
            int items = Math.min(depth * WORST_SENDERS, orphans.size());
            if (items <= deepest) {
                continue; // a reduced -Dxdag.bench.blocks can collapse both depths onto one row
            }
            deepest = items;
            results.add(worstRow(pool, w, orphans, items));
        }
        // A scratch store of the node's layout in its own directory: RocksDB is single-writer per
        // directory, so the fixture's store dir must never be reopened.
        DevnetConfig scratchConfig = new DevnetConfig();
        scratchConfig.setRootDir(root.newFolder("scratch").getAbsolutePath());
        scratchConfig.setDir();
        RocksdbFactory scratch = new RocksdbFactory(scratchConfig);
        try {
            BlockStore scratchStore = BlockStoreImpl.forNode(scratch);
            scratchStore.reset();
            // persist.first is the first pass into the empty store (every key new, sums arrays
            // created); persist is best-of-3, where passes 2 and 3 overwrite the same keys.
            long[] passes = runs(3, () -> {
                for (Block b : parsed) {
                    scratchStore.saveBlock(b);
                }
            });
            results.add(phaseRow("persist.first", parsed.size(), passes[0]));
            results.add(phaseRow("persist", parsed.size(), Arrays.stream(passes).min().orElseThrow()));
        } finally {
            scratch.close();
        }
        report(results, w, mainsPerRound, direct, syncPath, order, pipelineRows);
    }

    /** Mines one main block linking {@code refs} (fixture fake PoW + import + checkMain); returns its wall time in ns. */
    private long mineTimed(List<Bytes32> refs) {
        long a = System.nanoTime();
        mineMain(refs);
        return System.nanoTime() - a;
    }

    /** Wall time in ns of each of {@code times} runs of {@code body}, in run order. */
    private static long[] runs(int times, Runnable body) {
        long[] t = new long[times];
        for (int i = 0; i < times; i++) {
            long t0 = System.nanoTime();
            body.run();
            t[i] = System.nanoTime() - t0;
        }
        return t;
    }

    private static long bestOf3(Runnable body) {
        return Arrays.stream(runs(3, body)).min().orElseThrow();
    }

    /**
     * Best of three timed runs of {@code body}, each preceded by an UNTIMED {@code restore} that puts
     * the state {@code body} mutates back where the first run found it. {@link #bestOf3(Runnable)} is
     * only honest for a body that leaves nothing behind; a body that does (the orphan-pool rows fill
     * or drain a queue) would otherwise have its three runs measure three different things and the
     * minimum would report the cheapest of them.
     */
    private static long bestOf3(Runnable restore, Runnable body) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            restore.run();
            long t0 = System.nanoTime();
            body.run();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }

    /** A phase row: {@code nanos} of wall time over {@code count} units; the per-unit figure is a mean. */
    private static Measure phaseRow(String name, int count, long nanos) {
        return new Measure("phase." + name, count, count * 1e9 / nanos, nanos / 1000.0 / count, Double.NaN, Double.NaN, nanos / 1_000_000);
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    private static String cell(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String jsonNum(double v) {
        return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.3f", v);
    }

    private void report(List<Measure> results, BenchWorkload w, int mainsPerRound, Measure[] direct, Measure[] syncPath,
            String[] order, Measure[] pipelineRows) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |\n|---|---|---|---|---|---|---|\n");
        for (Measure m : results) {
            md.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %s | %s | %s | %d |%n", m.name(), m.n(), m.perSec(),
                    cell(m.meanMicros()), cell(m.p50Micros()), cell(m.p95Micros()), m.totalMillis()));
        }
        // Paired per-round deltas, syncPath - direct: the two legs of a round ran back to back.
        double[] dMean = new double[ROUNDS];
        double[] dP50 = new double[ROUNDS];
        double[] dTotal = new double[ROUNDS];
        StringBuilder paired = new StringBuilder();
        paired.append("| round | order | syncPath-direct mean µs | p50 µs | total ms |\n|---|---|---|---|---|\n");
        for (int r = 0; r < ROUNDS; r++) {
            dMean[r] = syncPath[r].meanMicros() - direct[r].meanMicros();
            dP50[r] = syncPath[r].p50Micros() - direct[r].p50Micros();
            dTotal[r] = syncPath[r].totalMillis() - direct[r].totalMillis();
            paired.append(String.format(Locale.ROOT, "| r%d | %s | %+.1f | %+.1f | %+.0f |%n", r, order[r], dMean[r], dP50[r], dTotal[r]));
        }
        paired.append(String.format(Locale.ROOT, "| median | | %+.1f | %+.1f | %+.0f |%n", median(dMean), median(dP50), median(dTotal)));
        String header = "L1 import benchmark: senders=" + senders + " blocks=" + blocks + " chunks=" + w.chunkCount()
                + " mix=" + Arrays.toString(mix) + " kinds=[PLAIN " + w.kindCount(BenchWorkload.Kind.PLAIN)
                + ", CALL_INLINE " + w.kindCount(BenchWorkload.Kind.CALL_INLINE) + ", CALL_CHAIN " + w.kindCount(BenchWorkload.Kind.CALL_CHAIN)
                + ", DEPLOY " + w.kindCount(BenchWorkload.Kind.DEPLOY) + "] seed=" + seed
                + " confirmedMainsPerRound=" + mainsPerRound + " txHistoryStore=null"
                + " writeBehind=" + writeBehind() + " ingestThreads=" + ingestThreads();
        double[] pipeRate = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            pipeRate[r] = pipelineRows[r].perSec();
        }
        double pipeMedian = median(pipeRate);
        String verdict = String.format(Locale.ROOT,
                "acceptance: pipeline median = %.0f blocks/s over %d rounds, target 10000 -> %s",
                pipeMedian, ROUNDS, pipeMedian >= 10_000 ? "MET" : "NOT MET");
        System.out.println(header);
        System.out.println(md);
        System.out.println("paired direct/syncPath (same round, fresh fixtures, back to back):");
        System.out.println(paired);
        System.out.println(verdict);
        Path dir = Paths.get("target", "bench");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder json = new StringBuilder("{\"jvm\":\"" + System.getProperty("java.version") + "\",\"cores\":"
                + Runtime.getRuntime().availableProcessors() + ",\"senders\":" + senders + ",\"blocks\":" + blocks
                + ",\"chunks\":" + w.chunkCount() + ",\"mix\":" + Arrays.toString(mix) + ",\"seed\":" + seed
                + ",\"confirmedMainsPerRound\":" + mainsPerRound + ",\"txHistoryStore\":null"
                + ",\"writeBehind\":" + writeBehind() + ",\"ingestThreads\":" + ingestThreads()
                + ",\"pipelineMedianPerSec\":" + jsonNum(pipeMedian) + ",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            Measure m = results.get(i);
            json.append(i > 0 ? "," : "").append("{\"name\":\"").append(m.name()).append("\",\"n\":").append(m.n())
                    .append(",\"perSec\":").append(jsonNum(m.perSec())).append(",\"meanUs\":").append(jsonNum(m.meanMicros()))
                    .append(",\"p50us\":").append(jsonNum(m.p50Micros())).append(",\"p95us\":").append(jsonNum(m.p95Micros()))
                    .append(",\"totalMs\":").append(m.totalMillis()).append("}");
        }
        json.append("],\"paired\":[");
        for (int r = 0; r < ROUNDS; r++) {
            json.append(r > 0 ? "," : "").append("{\"round\":").append(r).append(",\"order\":\"").append(order[r])
                    .append("\",\"dMeanUs\":").append(jsonNum(dMean[r])).append(",\"dP50us\":").append(jsonNum(dP50[r]))
                    .append(",\"dTotalMs\":").append(jsonNum(dTotal[r])).append("}");
        }
        json.append("],\"pairedMedian\":{\"dMeanUs\":").append(jsonNum(median(dMean))).append(",\"dP50us\":")
                .append(jsonNum(median(dP50))).append(",\"dTotalMs\":").append(jsonNum(median(dTotal))).append("}}");
        Path out = dir.resolve("l1-import-" + stamp + ".json");
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        System.out.println("json: " + out);
    }
}
