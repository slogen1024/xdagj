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

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.Network;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.ingest.PreValidator;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.XdagPow;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.net.Peer;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.mockito.Mockito;

/**
 * The scaffolding every chunk-and-orphan test needs: a node that pools, a way to deliver a block as
 * a peer would, and blocks light enough not to take the chain top.
 *
 * <h2>Why delivery goes through {@code PreValidator.reimport}</h2>
 *
 * <p>That is what {@code SyncManager.importBlock} does for a block that arrived from a peer: it
 * re-parses the wrapper's own 512 bytes into a private copy and computes the chain-extension
 * classification. The classification is not decoration — {@code OrphanCategory.of} is handed a null
 * kind on the bare {@code tryToConnect(Block)} path and a null kind can never answer CHUNK, so a
 * chunk delivered that way is an ordinary link block, goes to disk on arrival, and tests none of
 * the behaviour these classes are about.
 *
 * <h2>Why every built block is redrawn</h2>
 *
 * <p>A block with no links has a chain weight equal to its own raw-hash difficulty, and that is
 * {@code 2^256 / hash} — heavy-tailed, so an unfiltered draw occasionally out-weighs the mined
 * chain top and drags a reorg through the middle of a measurement that has nothing to do with
 * reorgs. Redrawing until the block is lighter than {@link #MAX_DELIVERED_DIFFICULTY} — the floor
 * of the window {@code mineMain} searches in — keeps the chain top where the test put it, at a cost
 * of a handful of extra draws in thousands.
 *
 * <h2>What is deliberately not on this base</h2>
 *
 * <p>Four classes that look like they belong here do not, and the reasons differ enough that a
 * reader cannot otherwise tell deliberate from stale:
 *
 * <ul>
 *   <li>{@code OrphanFloodTest} — its own {@code armTheOrphanPool} does not mine a main block, and
 *       it delivers through the bare {@code tryToConnect}; both halves of this base's scaffolding
 *       would change what it measures.</li>
 *   <li>{@code OrphanPeerAttributionTest} — delivers through a real
 *       {@link io.xdag.chain.ingest.IngestPipeline}{@code .submit}, collecting the committer's
 *       verdicts, which is the path its subject (per-source attribution across pre-validation)
 *       actually lives on.</li>
 *   <li>{@code BlockLookupEquivalenceTest} — a different package, {@code io.xdag.core}: it is about
 *       {@code BlockchainImpl}'s merged lookup rather than about the pool.</li>
 *   <li>{@code ChunkDeferredPersistTest} — an earlier task's regression test, left on its own
 *       fixture so that a change to this base cannot quietly change what it pins. Its {@code
 *       deliver} body is byte for byte this one's, so "it delivers by a different mechanism" is
 *       untrue of it; being a regression test is the whole of the reason.</li>
 * </ul>
 */
public abstract class ChunkOrphanTestBase extends ChainL1TestBase {

    /** The source peer a delivery is attributed to unless a test names another. */
    protected static final String PEER_IP = "198.51.100.23";

    /**
     * The floor of the difficulty window {@code mineMain} searches in, so a block drawn below it can
     * never out-weigh the mined chain top. See the class header.
     */
    protected static final BigInteger MAX_DELIVERED_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per built block, so one block's redraws can never collide with the next one's. */
    protected static final int SEEDS_PER_BLOCK = 64;

    /**
     * {@code dealOrphan} pools nothing unless the node is configured to generate blocks and a PoW
     * instance exists. Devnet sets {@code node.generate.block.enable = true}; the mock supplies the
     * other half, and nothing in the import path calls into it.
     *
     * <p>One real main block first, so the chain top weighs at least 2^46 — the floor every
     * delivered block below is kept under. Without it the top is the fixture's address block and
     * the first block through the door takes the chain over.
     */
    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
        mineMain(List.of());
    }

    protected ChainOrphanPool pool() {
        return ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getPool();
    }

    /** Delivers as {@link #PEER_IP} would; see the class header for why this path and not the bare one. */
    protected ImportResult deliver(Block block) {
        return deliver(block, PEER_IP);
    }

    /** Delivers as a named source peer would, which is what the pool's per-source quota charges. */
    protected ImportResult deliver(Block block, String peerIp) {
        BlockWrapper wrapper = new BlockWrapper(block, 0, peer(peerIp), false);
        Block fresh = new Block(new XdagBlock(block.getXdagBlock().getData().toArray()));
        return blockchain.tryToConnect(PreValidator.reimport(wrapper, fresh));
    }

    /** Asserts a delivery landed and, just as importantly, that it did not take the chain top. */
    protected void assertImported(ImportResult r) {
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        assertEquals("a delivered block hijacked the chain top; change the seed",
                topRef, Bytes32.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    protected static Peer peer(String ip) {
        return new Peer(Network.DEVNET, (short) 0, "peer-" + ip, ip, 8001, "xdagj", new String[0],
                0, false, "tag");
    }

    /** A one-chunk chain, drawn light. See the class header. */
    protected Block lightChunk(int seed) {
        return lightChain(seed, 32).get(0);
    }

    /**
     * A chunk chain of however many chunks {@code payloadBytes} needs, head first, drawn until the
     * whole chain weighs less than one mined main block — a chunk's weight folds in its successor's,
     * so it is the sum that has to stay under the window.
     *
     * <p>Built straight from {@link ChunkChainBuilder} rather than through {@code ChainBlockBuilder},
     * which refuses a chain longer than the configured {@code chain.chunk.maxPerChain}. That cap
     * bounds what a node will walk, not what a sender can emit, so building past it directly is the
     * honest reproduction rather than a way around a check.
     */
    protected List<Block> lightChain(int seed, int payloadBytes) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            List<Block> chain = ChunkChainBuilder.split(config, payload(payloadBytes, s), txTime());
            BigInteger total = BigInteger.ZERO;
            for (Block chunk : chain) {
                total = total.add(blockchain.calculateCurrentBlockDiff(chunk));
            }
            if (total.compareTo(MAX_DELIVERED_DIFFICULTY) < 0) {
                return chain;
            }
        }
    }

    /**
     * A plain link block naming one other block, drawn light. No extension field, so it classifies
     * as nothing at all — which is the point: an ordinary block that happens to reference a chunk
     * chain is what pays for one.
     */
    protected Block linkTo(Bytes32 target, int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block raw = new Block(config, txTime() + 1, null,
                    List.of(new Address(target, XDAG_FIELD_OUT, false)), false, null, "s" + s, -1,
                    XAmount.ZERO, null);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_DELIVERED_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }
}
