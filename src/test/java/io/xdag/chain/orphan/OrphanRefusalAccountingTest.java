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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import org.junit.Test;

/**
 * {@code nnoref} counts the orphans this node is holding, and every increment has to be one
 * something can give back.
 *
 * <p>The block that leaks one is a chunk that clears the import path's capacity gate and is then
 * turned away by its sender's own quota: it skipped {@code saveBlock}, so the pool was its only
 * home, and it is now in no pool and on no disk. {@code OrphanBlockStore#addOrphan} carries the
 * argument for why that refusal is reachable at all and what the drift costs;
 * {@code BlockchainImpl.isHeldByThisNode} carries the rule these four tests pin.
 *
 * <p>What they pin, in one line each: an admitted chunk counts, a refused one does not, another
 * sender's chunk still does (so the rule is not "stop counting chunks"), and an ordinary link block
 * counts unconditionally because its disk copy is what gives the count back.
 */
public class OrphanRefusalAccountingTest extends ChunkOrphanTestBase {

    /** A second source, so the per-peer budget can be shown to be per peer. */
    private static final String OTHER_PEER_IP = "203.0.113.17";

    /**
     * One chunk of budget per source peer, in place of the production allowance. The store reads the
     * caps once, in its constructor, so the change has to be on the config before
     * {@code setUpChain} builds it.
     */
    @Override
    protected Config newConfig() {
        // Named cfg, not config: this method's whole contract is "override this rather than
        // assigning the inherited config field", and a local that shadows that field inside it
        // reads like the thing the contract forbids.
        AbstractConfig cfg = (AbstractConfig) super.newConfig();
        cfg.setChainOrphanChunkPerPeer(1);
        return cfg;
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
}
