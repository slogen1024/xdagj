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

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * Black-box L1 import baseline. Skipped unless {@code -Dxdag.bench=true}. Prints a Markdown table and
 * writes {@code target/bench/l1-import-<timestamp>.json}; the numbers go into docs/benchmarks by hand.
 *
 * <p>Three measured paths, {@code ROUNDS} rounds each on a fresh fixture: {@code direct} calls
 * {@code tryToConnect} on pre-parsed blocks; {@code syncPath} goes through
 * {@link SyncManager#validateAndAddNewBlock} (the network entry, which re-parses the raw bytes);
 * {@code confirmed} imports and then mines main blocks linking every paying block until the last one
 * is applied, so it includes fake PoW, {@code setMain} and {@code applyBlock}. The four
 * {@code phase.*} rows replay single phases of the direct path on the last workload for cost
 * attribution only; they are not a consensus path. {@code calib.emptyMain} is the fixture's cost of
 * an empty main block, so the confirmed rows can be split into fake PoW and setMain/apply. For the
 * {@code confirmed.*} and {@code calib.emptyMain} rows the p50/p95 columns are per main block.
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

    private int senders;
    private int blocks;
    private long seed;
    private int[] mix;
    private long fixtureStart;
    private Bytes chainId;
    private Bytes contract;
    private Bytes32 codeHash;

    @Before
    public void benchGate() {
        assumeTrue("set -Dxdag.bench=true to run the import benchmark", Boolean.getBoolean("xdag.bench"));
        senders = Integer.getInteger("xdag.bench.senders", 64);
        blocks = Integer.getInteger("xdag.bench.blocks", 20_000);
        seed = Long.getLong("xdag.bench.seed", 20260917L);
        mix = Arrays.stream(System.getProperty("xdag.bench.mix", "60,25,10,5").split(",")).mapToInt(Integer::parseInt).toArray();
        // setUpChain() has run (superclass @Before) but mined nothing yet: this is the mining timeline's origin.
        fixtureStart = generateTime;
    }

    /**
     * Tears the fixture down and builds a fresh one on the same mining timeline, so every round sees
     * byte-identical blocks (same keys are NOT reused: the workload draws fresh senders per round).
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
        for (ECKeyPair k : w.senders) {
            addressStore.updateBalance(k.toAddress().toArray(), XAmount.of(10_000, XUnit.XDAG));
        }
        return w;
    }

    private record Measure(String name, double blocksPerSec, double p50Micros, double p95Micros, long totalMillis) {
    }

    /** Imports every item (chunks tail-first, then the paying block); per-block latency is the paying block's only. */
    private Measure timeImport(String name, BenchWorkload w, Consumer<Block> importer) {
        long[] perBlock = new long[w.items.size()];
        long t0 = System.nanoTime();
        int i = 0;
        for (BenchWorkload.Item item : w.items) {
            for (int c = item.chunks().size() - 1; c >= 0; c--) {
                importer.accept(item.chunks().get(c));
            }
            long a = System.nanoTime();
            importer.accept(item.block());
            perBlock[i++] = System.nanoTime() - a;
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        Arrays.sort(perBlock);
        int all = w.items.size() + w.chunkCount();
        return new Measure(name, all * 1000.0 / Math.max(1, total), perBlock[perBlock.length / 2] / 1000.0,
                perBlock[(int) (perBlock.length * 0.95)] / 1000.0, total);
    }

    private void expectImported(Block b) {
        ImportResult r = blockchain.tryToConnect(b);
        assertTrue("import failed: " + r + " " + r.getErrorInfo(), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
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
        int mainsPerRound = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) {
                freshFixture();
            }
            prepareChain();
            BenchWorkload w = workload();
            results.add(timeImport("direct.r" + round, w, this::expectImported));
        }
        for (int round = 0; round < ROUNDS; round++) {
            freshFixture();
            prepareChain();
            BenchWorkload w = workload();
            SyncManager sync = syncManager();
            results.add(timeImport("syncPath.r" + round, w, b -> {
                ImportResult r = sync.validateAndAddNewBlock(new BlockWrapper(b, 0));
                assertTrue(String.valueOf(r), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
            }));
        }
        for (int round = 0; round < ROUNDS; round++) {
            freshFixture();
            prepareChain();
            BenchWorkload w = workload();
            List<Bytes32> refs = new ArrayList<>();
            long[] perMain = new long[w.items.size() / LINKS_PER_MAIN + 1];
            int mains = 0;
            long t0 = System.nanoTime();
            for (BenchWorkload.Item item : w.items) {
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
            confirm(w.items.get(w.items.size() - 1).block());
            long total = (System.nanoTime() - t0) / 1_000_000;
            mainsPerRound = mains;
            // p50/p95 here are per LOADED main block (mineMain incl. checkMain), not per paying block.
            results.add(new Measure("confirmed.r" + round, (w.items.size() + w.chunkCount()) * 1000.0 / Math.max(1, total),
                    percentile(perMain, mains, 0.5), percentile(perMain, mains, 0.95), total));
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
        long calibTotal = (System.nanoTime() - c0) / 1_000_000;
        results.add(new Measure("calib.emptyMain", CALIB_MAINS * 1000.0 / Math.max(1, calibTotal),
                percentile(perEmpty, CALIB_MAINS, 0.5), percentile(perEmpty, CALIB_MAINS, 0.95), calibTotal));
        BenchWorkload w = workload();
        // Imported once, untimed, so the refLookup phase resolves chunk refs that exist, as the real
        // path does (a lookup that misses is answered by the bloom filter and would read too cheap).
        for (BenchWorkload.Item item : w.items) {
            for (int c = item.chunks().size() - 1; c >= 0; c--) {
                expectImported(item.chunks().get(c));
            }
            expectImported(item.block());
        }
        List<byte[]> raw = new ArrayList<>();
        for (BenchWorkload.Item item : w.items) {
            raw.add(item.block().toBytes());
        }
        results.add(phase("parse", raw.size(), () -> {
            for (byte[] b : raw) {
                new Block(new XdagBlock(b));
            }
        }));
        List<Block> parsed = new ArrayList<>();
        for (byte[] b : raw) {
            parsed.add(new Block(new XdagBlock(b)));
        }
        results.add(phase("signature", parsed.size(), () -> {
            for (Block b : parsed) {
                blockchain.canUseInput(b);
            }
        }));
        results.add(phase("refLookup", parsed.size(), () -> {
            for (Block b : parsed) {
                for (Address a : b.getLinks()) {
                    if (!a.getIsAddress()) {
                        kernel.getBlockStore().getBlockInfoByHash(a.getAddress());
                    }
                }
            }
        }));
        // A scratch store of the node's layout in its own directory: RocksDB is single-writer per
        // directory, so the fixture's store dir must never be reopened.
        DevnetConfig scratchConfig = new DevnetConfig();
        scratchConfig.setRootDir(root.newFolder("scratch").getAbsolutePath());
        scratchConfig.setDir();
        RocksdbFactory scratch = new RocksdbFactory(scratchConfig);
        BlockStore scratchStore = BlockStoreImpl.forNode(scratch);
        scratchStore.reset();
        results.add(phase("persist", parsed.size(), () -> {
            for (Block b : parsed) {
                scratchStore.saveBlock(b);
            }
        }));
        scratch.close();
        report(results, w, mainsPerRound);
    }

    /** Mines one main block linking {@code refs} (fixture fake PoW + import + checkMain); returns its wall time in ns. */
    private long mineTimed(List<Bytes32> refs) {
        long a = System.nanoTime();
        mineMain(refs);
        return System.nanoTime() - a;
    }

    /** Percentile in microseconds over the first {@code n} samples. */
    private static double percentile(long[] samples, int n, double q) {
        long[] sorted = Arrays.copyOf(samples, n);
        Arrays.sort(sorted);
        return sorted[Math.min(n - 1, (int) (n * q))] / 1000.0;
    }

    private Measure phase(String name, int count, Runnable body) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            body.run();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return new Measure("phase." + name, count * 1e9 / best, best / 1000.0 / count, 0, best / 1_000_000);
    }

    private void report(List<Measure> results, BenchWorkload w, int mainsPerRound) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("| measure | blocks/s | p50 µs | p95 µs | total ms |\n|---|---|---|---|---|\n");
        for (Measure m : results) {
            md.append(String.format("| %s | %.0f | %.1f | %.1f | %d |%n", m.name(), m.blocksPerSec(), m.p50Micros(), m.p95Micros(), m.totalMillis()));
        }
        System.out.println("L1 import benchmark: senders=" + senders + " blocks=" + blocks + " chunks=" + w.chunkCount()
                + " mix=" + Arrays.toString(mix) + " seed=" + seed + " confirmedMainsPerRound=" + mainsPerRound);
        System.out.println(md);
        Path dir = Paths.get("target", "bench");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        StringBuilder json = new StringBuilder("{\"jvm\":\"" + System.getProperty("java.version") + "\",\"cores\":"
                + Runtime.getRuntime().availableProcessors() + ",\"senders\":" + senders + ",\"blocks\":" + blocks
                + ",\"chunks\":" + w.chunkCount() + ",\"mix\":" + Arrays.toString(mix) + ",\"seed\":" + seed
                + ",\"confirmedMainsPerRound\":" + mainsPerRound + ",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            Measure m = results.get(i);
            json.append(i > 0 ? "," : "").append("{\"name\":\"").append(m.name()).append("\",\"blocksPerSec\":")
                    .append(m.blocksPerSec()).append(",\"p50us\":").append(m.p50Micros()).append(",\"p95us\":")
                    .append(m.p95Micros()).append(",\"totalMs\":").append(m.totalMillis()).append("}");
        }
        json.append("]}");
        Files.writeString(dir.resolve("l1-import-" + stamp + ".json"), json.toString(), StandardCharsets.UTF_8);
    }
}
