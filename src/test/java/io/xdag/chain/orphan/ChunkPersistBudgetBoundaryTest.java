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

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_MAIN_CHAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * What happens past the edge of {@code persistReferencedChunkChains}' budget, which is where this
 * subproject left three live null dereferences on the consensus apply path.
 *
 * <h2>The shape</h2>
 *
 * <p>Since Task 11 a chunk block is held in memory only and reaches the block store when a block
 * that references it is imported. That walk is bounded — {@code chain.chunk.maxPerChain}, one
 * budget for the whole importing block — so a chain longer than the budget has its head on disk and
 * its tail still in memory, and {@code getBlockByHash(hash, false)} answers null for the tail: the
 * merged lookup deliberately serves memory-only chunks in their RAW form only, because a
 * {@code BlockInfo} parsed fresh out of 512 bytes is not "unknown", it is a set of specific wrong
 * claims about flags, difficulty and ref.
 *
 * <p>Before chunks became memory-only every block in the DAG was on disk, so all three sites were
 * unreachable. This subproject made them live; these tests are what hold them shut.
 *
 * <h2>Why the budget is one here</h2>
 *
 * <p>{@code chain.chunk.maxPerChain} is 4096 in production, and a test that built 4097 chunk blocks
 * would be a test about patience. One chunk of budget reaches exactly the same boundary: the first
 * chunk of the first chain the importing block names is written, and everything past it — the rest
 * of that chain, and every other chain the same block names — stays in memory.
 */
public class ChunkPersistBudgetBoundaryTest extends ChunkOrphanTestBase {

    /**
     * One chunk of persist budget, in place of the production 4096. The chain settings are
     * consensus values and deliberately have no setters, so a whole {@code Config} is what a test
     * supplies to change one.
     */
    @Override
    protected Config newConfig() {
        return new OneChunkBudgetDevnetConfig();
    }

    private static final class OneChunkBudgetDevnetConfig extends DevnetConfig {
        @Override
        public int getChainMaxChunksPerChain() {
            return 1;
        }
    }

    /**
     * The fixture's own premise: with a budget of one, a referenced chain really does end up half on
     * disk and half in memory. Everything below is only worth anything while this holds.
     */
    @Test
    public void theBudgetLeavesTheTailOfAChainInMemory() {
        List<Block> chain = lightChain(30);
        assertEquals("a three-chunk chain, so there is a tail to leave behind", 3, chain.size());
        deliverChainTailFirst(chain);

        Block payer = linkTo(hashLow(chain.get(0)), 30);
        assertImported(deliver(payer));

        assertNotNull("the head is what the budget paid for",
                kernel.getBlockStore().getRawBlockByHash(chain.get(0).getHashLow()));
        for (int i = 1; i < chain.size(); i++) {
            Bytes32 tail = hashLow(chain.get(i));
            assertNull("everything past the budget must still be memory-only",
                    kernel.getBlockStore().getRawBlockByHash(tail));
            assertNull("and so has no chain metadata to answer with",
                    blockchain.getBlockByHash(tail, false));
            assertNotNull("while its bytes are still servable",
                    blockchain.getBlockByHash(tail, true));
        }
    }

    /**
     * {@code applyBlock}. The main block confirms the paying block, the paying block's reference is
     * the chain head, and the head's own reference is the first chunk the budget did not reach — so
     * the apply walk descends straight into the null.
     *
     * <p>Without the guard this is an {@code NullPointerException} out of {@code setMain}, on the
     * consensus path, provoked by a peer that sends one chunk more than the budget allows.
     */
    @Test
    public void applyingABlockWhoseChainRunsPastTheBudgetDoesNotCrash() {
        List<Block> chain = lightChain(31);
        deliverChainTailFirst(chain);
        Block payer = linkTo(hashLow(chain.get(0)), 31);
        assertImported(deliver(payer));
        assertNull("the chunk the apply walk will reach for must be memory-only",
                kernel.getBlockStore().getRawBlockByHash(chain.get(1).getHashLow()));

        mineMain(List.of(hashLow(payer)));
        confirm(payer);

        assertTrue("the paying block must still be applied, unreferenceable tail and all",
                (blockchain.getBlockByHash(hashLow(payer), false).getInfo().getFlags() & BI_APPLIED) != 0);
    }

    /**
     * {@code unApplyBlock}, the mirror. A skip in the apply walk is only defensible if the unwind
     * skips the same link — and the unwind's trailing loop dereferences the reference <em>before</em>
     * it asks whether this main block ever applied it, so the guard has to be on both sides.
     *
     * <p>The competing branch is mined until it really is the top rather than for a fixed count.
     * Each block's weight is only bounded to [2^46, 2^47), so "enough blocks" is not something a
     * count can promise; asking the chain is the only honest stopping condition.
     */
    @Test
    public void unwindingABlockWhoseChainRanPastTheBudgetDoesNotCrash() {
        List<Block> chain = lightChain(32);
        deliverChainTailFirst(chain);
        Block payer = linkTo(hashLow(chain.get(0)), 32);
        assertImported(deliver(payer));

        Block forkPoint = blockchain.getBlockByHash(topRef, true);
        long forkTime = generateTime;
        Block carrier = mineMain(List.of(hashLow(payer)));
        confirm(payer);
        assertTrue("the block to be unwound must have been applied",
                (blockchain.getBlockByHash(hashLow(payer), false).getInfo().getFlags() & BI_APPLIED) != 0);

        rewindTo(forkPoint, forkTime);
        mineUntilTop();
        assertEquals("and the branch carrying the chunk chain was really unwound", 0,
                blockchain.getBlockByHash(hashLow(carrier), false).getInfo().getFlags() & BI_APPLIED);
    }

    /**
     * {@code updateNewChain}. One budget covers a whole importing block however many chains it
     * names, so a main block naming two chunk chains gets the first head written and leaves the
     * second exactly where it was. When the fork path then flags a stretch of the competing branch
     * onto the main chain, its un-orphaning loop walks that block's links and reaches for chain
     * metadata the second head does not have.
     *
     * <p>The crash lands inside {@code tryToConnect}, which catches {@code Throwable} — so a node
     * without the guard does not fall over, it silently answers {@code ERROR} to a perfectly good
     * main block and stops following the chain. That is what the mining helper's rejection
     * assertion catches here.
     */
    @Test
    public void reflaggingAMainBlockThatNamesAnUnwrittenChunkDoesNotCrash() {
        List<Block> first = lightChain(33);
        List<Block> second = lightChain(34);
        deliverChainTailFirst(first);
        deliverChainTailFirst(second);

        Block forkPoint = blockchain.getBlockByHash(topRef, true);
        long forkTime = generateTime;
        // Branch A: three plain main blocks, nothing to do with chunks.
        for (int i = 0; i < 3; i++) {
            mineMain(List.of());
        }

        rewindTo(forkPoint, forkTime);
        // Branch B's first block names both chain heads. The budget writes the first and stops, so
        // the second head is still memory-only when the re-flagging loop walks these links.
        Block carrier = mineMain(List.of(hashLow(first.get(0)), hashLow(second.get(0))), false);
        assertNotNull("the first head is what the budget paid for",
                kernel.getBlockStore().getRawBlockByHash(first.get(0).getHashLow()));
        assertNull("the second head had no budget left and is memory-only",
                kernel.getBlockStore().getRawBlockByHash(second.get(0).getHashLow()));

        mineUntilTop();
        assertTrue("and the block that names the unwritten chunk was flagged onto the main chain,"
                        + " which is the very loop that used to crash on it",
                (blockchain.getBlockByHash(hashLow(carrier), false).getInfo().getFlags()
                        & BI_MAIN_CHAIN) != 0);
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * Mines the competing branch until it owns the top. A fixed count cannot promise an overtake —
     * a mined block's weight is only bounded to [2^46, 2^47), so N new blocks are not reliably
     * heavier than the M they replace — and the bound here is a runaway guard, not the stopping
     * condition.
     */
    private void mineUntilTop() {
        for (int i = 0; i < 40; i++) {
            Block tip = mineMain(List.of(), false);
            if (Bytes32.wrap(blockchain.getXdagTopStatus().getTop()).equals(hashLow(tip))) {
                return;
            }
        }
        fail("the competing branch did not overtake within 40 blocks");
    }

    /** Chunks are imported oldest first: a block may not reference one that came after it. */
    private void deliverChainTailFirst(List<Block> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            assertImported(deliver(chain.get(i)));
        }
    }

    /** The three-chunk chain every test here works from. */
    private List<Block> lightChain(int seed) {
        return lightChain(seed, 1000);
    }
}
