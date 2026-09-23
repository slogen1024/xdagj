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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.Network;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.ingest.PreValidator;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.AbstractConfig;
import io.xdag.consensus.XdagPow;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.net.Peer;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * {@code nnoref} counts the orphans this node is holding, and every increment has to be one
 * something can give back.
 *
 * <h2>The leak</h2>
 *
 * <p>The import path increments {@code nnoref} for every non-extra block it takes, whatever the
 * orphan pool then did with it. That was harmless while a refused block was still written to disk:
 * {@code removeOrphan} decrements for any stored block that is not yet {@code BI_REF}, so whatever
 * the pool thought, the count came back the moment anything referenced the block.
 *
 * <p>Task 11 broke that for one category. A chunk skips {@code saveBlock} — the pool holds the only
 * copy — so a chunk the pool refuses is in no pool, on no disk, and referenced by nothing that could
 * ever give its increment back. The category gate in {@code tryToConnect} deliberately asks only
 * about capacity ({@code ChainOrphanPool.isFull}), because the per-peer and per-chain quotas are
 * about attribution and a gate that knew only a category would refuse one sender's flood in
 * everybody's name — so a chunk that clears the gate and is then refused by its sender's own budget
 * is exactly the block that leaks.
 *
 * <p>It matters because {@code checkNewMain} divides {@code nnoref} by eleven to decide how many
 * link blocks to mine. A count that only ever drifts upward has this node mining link blocks for
 * orphans it is not holding — which is what a flooder would be buying with each refused chunk.
 *
 * <h2>Why the fix is chunk-shaped</h2>
 *
 * <p>The pairing is not "counted if pooled". For every category but CHUNK the block is on disk when
 * the increment happens, and {@code removeOrphan} gives it back from the disk copy without asking
 * the pool anything — so making those conditional on admission would take the count one too low the
 * first time a reference arrived. The rule is "counted if this node is holding it somewhere", and
 * CHUNK is the one category where the pool is the only somewhere there is.
 */
public class OrphanRefusalAccountingTest extends ChainL1TestBase {

    private static final String PEER_IP = "198.51.100.41";
    private static final String OTHER_PEER_IP = "203.0.113.17";

    /** Keeps a delivered block from out-weighing the mined chain top. */
    private static final BigInteger MAX_FLOOD_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per built block, so one block's redraws can never collide with the next one's. */
    private static final int SEEDS_PER_BLOCK = 64;

    /**
     * One chunk of budget per source peer, in place of the production allowance. Set on the config
     * before {@code setUpChain} builds the store, which reads the caps once in its constructor; the
     * superclass's field initialiser assigns {@code config}, this instance initialiser runs next,
     * and {@code @Before} runs after both.
     */
    {
        ((AbstractConfig) config).setChainOrphanChunkPerPeer(1);
    }

    /**
     * {@code dealOrphan} pools nothing unless the node is configured to generate blocks and a PoW
     * instance exists. Devnet supplies the first; the mock supplies the second. One real main block
     * first, so the chain top weighs at least 2^46.
     */
    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
        mineMain(List.of());
    }

    /**
     * The control, and it is not decoration: without it the leak test would pass just as well on a
     * node that had stopped counting chunks altogether.
     */
    @Test
    public void anAdmittedChunkIsCountedAsTheOrphanItIs() {
        long before = blockchain.getXdagStats().nnoref;

        Block chunk = lightChunk(40);
        assertImported(deliver(chunk, PEER_IP));

        assertNotNull("the pool took it", pool().get(hashLow(chunk)));
        assertEquals("an orphan this node is holding must be counted",
                before + 1, blockchain.getXdagStats().nnoref);
    }

    /**
     * The leak itself. The second chunk from this sender clears the category gate — there is room
     * in the pool — and is then refused by the sender's own budget, so it ends up held nowhere at
     * all. Before the admission verdict came back up the pipeline it was still counted, and nothing
     * could ever decrement it again.
     */
    @Test
    public void aChunkRefusedByItsSendersOwnBudgetIsNotCountedAtAll() {
        Block first = lightChunk(41);
        Block second = lightChunk(42);
        assertImported(deliver(first, PEER_IP));
        long afterFirst = blockchain.getXdagStats().nnoref;
        assertEquals("this sender has spent its whole budget", 1, pool().chunksHeldBy(PEER_IP));

        assertImported(deliver(second, PEER_IP));

        assertNull("the refused chunk is not in the pool", pool().get(hashLow(second)));
        assertNull("its body is held nowhere",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(second)));
        assertNull("and a chunk skips saveBlock, so it is not on disk either",
                kernel.getBlockStore().getRawBlockByHash(second.getHashLow()));
        assertEquals("the sender is still at one, so nothing was quietly admitted",
                1, pool().chunksHeldBy(PEER_IP));

        assertEquals("a block this node holds nowhere must not be counted as an orphan it holds",
                afterFirst, blockchain.getXdagStats().nnoref);
    }

    /**
     * And the budget really is the sender's own: another source's first chunk still counts, so the
     * rule above cannot be a blanket "stop counting chunks once one has been refused".
     */
    @Test
    public void anotherSendersChunkIsStillCounted() {
        assertImported(deliver(lightChunk(43), PEER_IP));
        assertImported(deliver(lightChunk(44), PEER_IP));
        long spent = blockchain.getXdagStats().nnoref;

        Block fromElsewhere = lightChunk(45);
        assertImported(deliver(fromElsewhere, OTHER_PEER_IP));

        assertNotNull("a fresh source has its own budget", pool().get(hashLow(fromElsewhere)));
        assertEquals(spent + 1, blockchain.getXdagStats().nnoref);
    }

    /**
     * A link block is the other half of the rule. It is on disk whatever the pool decided, so its
     * increment is always one {@code removeOrphan} can give back — and it must keep being made
     * unconditionally.
     */
    @Test
    public void anOrdinaryBlockIsStillCountedOnArrival() {
        long before = blockchain.getXdagStats().nnoref;
        Block link = lightChunk(46);
        // Delivered through the bare entry point, which asks for no classification: with a null
        // kind OrphanCategory.of can never answer CHUNK, so this very same block is filed as a link
        // block and goes to disk.
        ImportResult r = blockchain.tryToConnect(link);
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);

        assertEquals("with no classification it is a link block", OrphanCategory.LINK,
                pool().get(hashLow(link)).category());
        assertNotNull("and a link block is written on arrival",
                kernel.getBlockStore().getRawBlockByHash(link.getHashLow()));
        assertEquals(before + 1, blockchain.getXdagStats().nnoref);
    }

    // ---- helpers -------------------------------------------------------------------------

    private ChainOrphanPool pool() {
        return ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getPool();
    }

    /**
     * Imports a block the way a block from a peer is imported: a private re-parse of its 512 bytes,
     * the arriving wrapper's source peer, and the extension classification. The classification is
     * what makes the block a chunk at all, and the peer is what the quota charges.
     */
    private ImportResult deliver(Block block, String peerIp) {
        BlockWrapper wrapper = new BlockWrapper(block, 0, peer(peerIp), false);
        Block fresh = new Block(new XdagBlock(block.getXdagBlock().getData().toArray()));
        return blockchain.tryToConnect(PreValidator.reimport(wrapper, fresh));
    }

    private void assertImported(ImportResult r) {
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        assertEquals("a delivered block hijacked the chain top; change the seed",
                topRef, Bytes32.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    private static Peer peer(String ip) {
        return new Peer(Network.DEVNET, (short) 0, "peer-" + ip, ip, 8001, "xdagj", new String[0],
                0, false, "tag");
    }

    /**
     * A one-chunk chain, drawn again until its raw-hash difficulty is below the window
     * {@code mineMain} searches in. A link-less block's chain weight is just its own difficulty,
     * which is heavy-tailed, so an unfiltered chunk can out-weigh the mined top and drag a reorg
     * through the middle of the measurement.
     */
    private Block lightChunk(int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block chunk = ChunkChainBuilder.split(config, payload(32, s), txTime()).get(0);
            if (blockchain.calculateCurrentBlockDiff(chunk).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return chunk;
            }
        }
    }
}
