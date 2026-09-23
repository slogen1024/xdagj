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
package io.xdag.chain.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.bench.BenchWorkload;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.node.Node;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * P1 (SP0b-2): the same block sequence through the pipeline and through the synchronous path gives
 * the same {@link ImportResult} per block and byte-identical INDEX/TIME/BLOCK/ORPHANIND, stats and
 * top. Both runs use the write-behind layer (started, real writer thread) and drain before
 * comparing, so the comparison is of what actually reached RocksDB.
 *
 * <p>The two runs are two fresh fixtures on the same mining timeline, so the blocks are byte
 * identical (the workload's senders and payloads are seed-derived and the signatures are RFC 6979).
 * The import order deliberately puts one paying block in front of its own chunk chain, so the run
 * exercises NO_PARENT, {@code syncPushBlock}, and the re-parsing re-import out of {@code
 * syncPopBlock} on both paths.
 */
public class IngestEquivalenceTest extends ChainL1TestBase {

    private static final int BLOCKS = 400;
    private static final long SEED = 20260918L;
    private static final int SENDERS = 8;
    private static final int[] MIX = {60, 25, 10, 5};

    private WriteBehindQueue queue;

    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        queue = new WriteBehindQueue(4096, 64, 20, true);
        queue.start();
        return new WriteBehindFactory(raw, queue, 4096);
    }

    /**
     * The production shape: a node without {@code node.transaction.history.enable} has a null store
     * and {@code onNewTxHistory} returns at once, while the fixture's Mockito mock would answer
     * false and send every amount link down the MySQL-failure fallback.
     */
    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.setTxHistoryStore(null);
    }

    private record Prepared(Bytes chainId, Bytes contract, Bytes32 codeHash) {
    }

    /**
     * One confirmed chain with a shared code blob so the CALL/DEPLOY items have a target. Built with
     * an explicit nonce 1 rather than through the fixture's {@code deployNewChain}: that helper
     * draws from a counter {@code setUpChain} never resets, so on the second fresh fixture it would
     * hand out nonce 2 against an address store whose executed nonce is 0 again.
     */
    private Prepared prepareChain() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        Bytes wasm = BenchWorkload.payload(600, 7);
        Bytes initArgs = BenchWorkload.payload(10, 8);
        int chunks = ChainBlockBuilder.deployNewChainChunks(wasm.size(), initArgs.size());
        XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
        ChainBlockBuilder.Built deploy = ChainBlockBuilder
                .deployNewChain(config, txTime(), poolKey, UInt64.ONE, fee, wasm, CFG, initArgs, 1000L).value();
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        return new Prepared(ChainIds.chainIdOf(deploy.block().getHash()),
                ChainIds.contractIdOf(deploy.block().getHash()), HashUtils.sha256(wasm));
    }

    /** Import order: chunks tail-first then the paying block, with one deliberate NO_PARENT pair. */
    private List<Block> order(BenchWorkload w) {
        List<Block> out = new ArrayList<>();
        boolean swapped = false;
        for (BenchWorkload.Item item : w.items()) {
            List<Block> chunks = item.chunks();
            if (!swapped && chunks.size() > 1) {
                out.add(item.block());            // paying block before its chain: NO_PARENT, then re-import
                for (int c = chunks.size() - 1; c >= 0; c--) {
                    out.add(chunks.get(c));
                }
                swapped = true;
                continue;
            }
            for (int c = chunks.size() - 1; c >= 0; c--) {
                out.add(chunks.get(c));
            }
            out.add(item.block());
        }
        assertTrue("no multi-chunk item in the workload, the NO_PARENT pair was never built", swapped);
        return out;
    }

    private BenchWorkload workload(Prepared p) {
        BenchWorkload w = new BenchWorkload(config, SEED, SENDERS, BLOCKS, MIX, txTime(),
                p.chainId(), p.contract(), p.codeHash());
        for (ECKeyPair k : w.senders()) {
            addressStore.updateBalance(k.toAddress().toArray(), XAmount.of(10_000, XUnit.XDAG));
        }
        return w;
    }

    /**
     * The network entry point needs a wired kernel: {@code SyncManager} takes the blockchain and the
     * channel manager from it, and {@code finishImport} evaluates {@code kernel.getClient().getNode()}
     * on every IMPORTED_* result before it looks at the ttl.
     */
    private SyncManager syncManager() {
        wireKernel();
        return new SyncManager(kernel);
    }

    /**
     * Run A's {@link SyncManager}: the production one, with the commit step instrumented in place.
     *
     * <p>Run A goes through {@code submitBlock}, which means it needs the {@code SyncManager}'s own
     * pipeline (the one {@code start()} builds), not a pipeline of the test's. That entry point is
     * the reason: since SP0b-3 it carries the chunk fee gate, and a run A that called
     * {@code IngestPipeline.submit} directly would be the only ungated path in a test whose whole
     * claim is that the two paths agree. A gate refusal in the workload would then surface as an
     * {@code ImportResult} sequence mismatch and read as a pipeline/synchronous divergence that
     * does not exist.
     *
     * <p>The committer is {@code this::importPreValidated}, so overriding it is how the two facts
     * the pipeline exists to deliver are still counted. Counted, not asserted: {@code
     * IngestPipeline.commit} catches {@code Throwable}, so an {@code AssertionError} raised on the
     * commit thread would be logged and swallowed instead of failing the test.
     */
    private SyncManager instrumentedSyncManager(Instrumentation into) {
        wireKernel();
        return new SyncManager(kernel) {
            @Override
            public synchronized ImportResult importPreValidated(PreValidated pv) {
                if (!pv.block().getInputs().isEmpty()) {
                    into.signedBlocks().incrementAndGet();
                    if (!pv.hasKeys()) {
                        into.missingKeys().incrementAndGet();
                    }
                }
                ImportResult r = super.importPreValidated(pv);
                into.results().add(r);
                return r;
            }
        };
    }

    /**
     * What run A's commit step records as it goes: the verdict per block, and the two counts that
     * say the pre-validation really did its work off the lock. {@code results} is written from the
     * commit thread and read from the test thread once it has stopped.
     */
    private record Instrumentation(List<ImportResult> results, AtomicInteger signedBlocks,
                                   AtomicInteger missingKeys) {
        Instrumentation() {
            this(Collections.synchronizedList(new ArrayList<>()), new AtomicInteger(), new AtomicInteger());
        }
    }

    /** Everything {@code new SyncManager(kernel)} reads out of the kernel, set before it is built. */
    private void wireKernel() {
        ChannelManager channels = mock(ChannelManager.class);
        when(channels.getActiveChannels()).thenReturn(List.of());
        kernel.setChannelMgr(channels);
        PeerClient client = mock(PeerClient.class);
        when(client.getNode()).thenReturn(new Node("127.0.0.1", 8001));
        kernel.setClient(client);
        kernel.setBlockchain(blockchain);
    }

    private TreeMap<Bytes, Bytes> dump(DatabaseName name) {
        KVSource<byte[], byte[]> db = dbFactory.getDB(name);
        TreeMap<Bytes, Bytes> m = new TreeMap<>();
        for (byte[] k : db.keys()) {
            m.put(Bytes.wrap(k), Bytes.wrap(db.get(k)));
        }
        return m;
    }

    private record Outcome(List<ImportResult> results, TreeMap<Bytes, Bytes> index, TreeMap<Bytes, Bytes> time,
                           TreeMap<Bytes, Bytes> block, TreeMap<Bytes, Bytes> orphan, long nblocks, Bytes top) {
    }

    private Outcome snapshot(List<ImportResult> results) {
        queue.flushSync();
        return new Outcome(List.copyOf(results), dump(DatabaseName.INDEX), dump(DatabaseName.TIME),
                dump(DatabaseName.BLOCK), dump(DatabaseName.ORPHANIND), blockchain.getXdagStats().nblocks,
                Bytes.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    /** A second fixture on the same mining timeline, so both runs build byte-identical blocks. */
    private void freshFixture() throws Exception {
        tearDownChain();
        // setUpChain() calls root.newFolder("node"), which throws if the folder still exists.
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = FIXTURE_START;
        setUpChain();
    }

    @Test
    public void pipelineAndSynchronousPathAgreeByteForByte() throws Exception {
        // Run A: pipeline (parallel pre-validation, in-order commit)
        Prepared p = prepareChain();
        BenchWorkload w = workload(p);
        List<Block> blocks = order(w);
        Instrumentation runA = new Instrumentation();
        SyncManager sync = instrumentedSyncManager(runA);
        sync.start();
        try {
            assertNotNull("chain.ingest.threads must be > 0 for run A to have a pipeline at all",
                    sync.getPipeline());
            for (Block b : blocks) {
                // submitBlock returns nothing, so a block the fee gate refuses would leave run A one
                // result short of run B and fail the sequence comparison below as if the two paths
                // had diverged. This workload must not produce one -- every chain block pays
                // minHeaderFee exactly -- and this says so instead of assuming it.
                assertFalse("the chunk fee gate refused a workload block: run A cannot report that",
                        sync.getFeePolicy().refuses(b));
                // Nothing reads b after this: the pool parses it, and Block.parse() is a lazy mutator.
                sync.submitBlock(new BlockWrapper(b, 0));
            }
            assertTrue("the pipeline did not drain", sync.getPipeline().awaitIdle(120, TimeUnit.SECONDS));
        } finally {
            sync.stop();
        }
        // The point of the pipeline: a block whose inputs need verifying must reach the lock with
        // its keys already computed, or the ECDSA quietly moved back under the monitor.
        assertTrue("no block with inputs went through the pipeline", runA.signedBlocks().get() > 0);
        assertEquals("a block with inputs reached the lock without pre-validated keys",
                0, runA.missingKeys().get());
        Outcome a = snapshot(runA.results());

        // Run B: the synchronous path on a fresh fixture with the same bytes
        freshFixture();
        Prepared p2 = prepareChain();
        assertEquals("same chain, same bytes", p.chainId(), p2.chainId());
        BenchWorkload w2 = workload(p2);
        List<Block> blocks2 = order(w2);
        SyncManager sync2 = syncManager();
        List<ImportResult> resultsB = new ArrayList<>();
        for (Block b : blocks2) {
            resultsB.add(sync2.validateAndAddNewBlock(new BlockWrapper(b, 0)));
        }
        Outcome b = snapshot(resultsB);

        assertEquals("ImportResult sequence", b.results(), a.results());
        assertTrue("the NO_PARENT pair was exercised", a.results().contains(ImportResult.NO_PARENT));
        assertEquals("INDEX", b.index(), a.index());
        assertEquals("TIME", b.time(), a.time());
        assertEquals("BLOCK", b.block(), a.block());
        assertEquals("ORPHANIND", b.orphan(), a.orphan());
        assertEquals(b.nblocks(), a.nblocks());
        assertEquals(b.top(), a.top());
    }
}
