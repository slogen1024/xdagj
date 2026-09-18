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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.ext.ChainBlockBuilder;
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
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.node.Node;
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
import java.util.List;
import java.util.Locale;
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
 * {@code confirmed} imports and then mines main blocks linking every paying block until the last
 * one is applied, so it includes fake PoW, {@code setMain} and {@code applyBlock}; its blocks/s
 * counts paying blocks and chunks only, not the main blocks. The {@code phase.*} rows replay single
 * phases of the direct path on the last workload for cost attribution only; they are not a
 * consensus path. {@code calib.emptyMain} is the fixture's cost of an empty main block, so the
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
        assertTrue("import failed: " + r + " " + r.getErrorInfo(), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
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
        report(results, w, mainsPerRound, direct, syncPath, order);
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

    private void report(List<Measure> results, BenchWorkload w, int mainsPerRound, Measure[] direct, Measure[] syncPath, String[] order) throws IOException {
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
                + " confirmedMainsPerRound=" + mainsPerRound + " txHistoryStore=null";
        System.out.println(header);
        System.out.println(md);
        System.out.println("paired direct/syncPath (same round, fresh fixtures, back to back):");
        System.out.println(paired);
        Path dir = Paths.get("target", "bench");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder json = new StringBuilder("{\"jvm\":\"" + System.getProperty("java.version") + "\",\"cores\":"
                + Runtime.getRuntime().availableProcessors() + ",\"senders\":" + senders + ",\"blocks\":" + blocks
                + ",\"chunks\":" + w.chunkCount() + ",\"mix\":" + Arrays.toString(mix) + ",\"seed\":" + seed
                + ",\"confirmedMainsPerRound\":" + mainsPerRound + ",\"txHistoryStore\":null,\"results\":[");
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
