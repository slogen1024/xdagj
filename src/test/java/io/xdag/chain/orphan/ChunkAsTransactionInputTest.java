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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_IN;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.xdag.BlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.utils.BytesUtils;
import org.junit.Test;

/**
 * A transaction block whose {@code XDAG_FIELD_IN} input names a block this node holds only as a
 * chunk body.
 *
 * <h2>The widest of the sites this subproject opened</h2>
 *
 * <p>The three the persist budget opened need a chain longer than {@code chain.chunk.maxPerChain}
 * to reach. This one needs nothing: since Task 11 <em>every</em> chunk is memory-only until
 * something pays for it, so one chunk block and one transaction block naming it are the whole
 * setup, and the sender chooses both.
 *
 * <p>Two changes put it there together. {@code tryToConnect}'s reference check accepts a
 * non-address reference resolved either from the block store or from the pool's chunk bodies —
 * which is what lets a chunk chain be received at all, since chunk <i>i</i> names chunk <i>i+1</i>
 * and <i>i+1</i> is memory-only until the chain is paid for. Then Task 12's merged lookup answers
 * the non-raw form for such a block with null. So the block clears validation and {@code
 * canUseInput} asks the same question again a few lines later and gets a null it dereferences.
 *
 * <p>{@code tryToConnect} catches {@code Throwable}, so the cost was not a crash: it was
 * {@code ImportResult.ERROR}, on demand, with a full stack trace in the error log for every such
 * block a peer cared to send — and {@code ERROR} falls into {@code releaseWaiters}' {@code default}
 * arm, so it stranded every child waiting on the refused block.
 *
 * <h2>What the right verdict is, and how it was chosen</h2>
 *
 * <p>Not by reasoning about what ought to happen, but by asking a node that had already persisted
 * the same chunk. It refuses the identical block with {@code INVALID_BLOCK} and "Block's input
 * can't be used", because a chunk block carries no out-signature and so nothing for {@code
 * verifiedKeys()} to match. The fix makes the memory-only node reach that same verdict, so the two
 * tests below are deliberately written as a pair: what is being pinned is not a rule about chunks
 * but the <em>absence of a difference</em> between a node that has written one down and a node that
 * is still holding it. That difference is the only thing the deferred persist had any business
 * introducing, and consensus must not be able to see it.
 */
public class ChunkAsTransactionInputTest extends ChunkOrphanTestBase {

    /**
     * The block this subproject made reachable. One classified chunk, one transaction block
     * spending from it, and no budget overrun anywhere.
     */
    @Test
    public void spendingFromAChunkThisNodeHoldsInMemoryIsRefusedAsAnUnusableInput() {
        Block chunk = lightChunk(60);
        assertImported(deliver(chunk));
        assertNotNull("the chunk is held, and only in memory",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
        assertNull("so it has no chain metadata to authorise anything with",
                blockchain.getBlockByHash(hashLow(chunk), false));

        assertRefusedAsUnusableInput(blockchain.tryToConnect(spendingFrom(chunk)));
    }

    /**
     * The other half of the pair, and the one that makes the first mean something: the same block,
     * on a node that has written the same chunk to disk, gets the same answer. Were these to
     * diverge, two honest nodes would disagree about a block for no reason other than which of them
     * had got round to persisting a chunk.
     */
    @Test
    public void aNodeThatHasWrittenTheChunkDownRefusesTheSameSpendTheSameWay() {
        Block chunk = lightChunk(61);
        assertImported(deliver(chunk));
        // A plain link block is what pays for the chain, so the chunk reaches the block store.
        assertImported(deliver(linkTo(hashLow(chunk), 61)));
        assertNotNull("the chunk is on disk now",
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        assertNotNull("and answers the non-raw lookup the memory-only one could not",
                blockchain.getBlockByHash(hashLow(chunk), false));

        assertRefusedAsUnusableInput(blockchain.tryToConnect(spendingFrom(chunk)));
    }

    /**
     * A refusal must not be a refusal-after-mutation. {@code canUseInput} runs before the
     * link-removal loop that deletes ORPHANIND rows and writes {@code BI_REF} into other blocks, so
     * a block turned away here leaves the chunk it named exactly where it was — which is what keeps
     * this from being something a sender can aim at another peer's orphans.
     */
    @Test
    public void theRefusedSpendLeavesTheChunkExactlyWhereItWas() {
        Block chunk = lightChunk(62);
        assertImported(deliver(chunk));
        long noRefBefore = blockchain.getXdagStats().nnoref;
        long blocksBefore = blockchain.getXdagStats().nblocks;

        Block spend = spendingFrom(chunk);
        assertRefusedAsUnusableInput(blockchain.tryToConnect(spend));

        assertNotNull("the chunk is still pooled",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
        assertNull("still not on disk",
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        assertNull("and the refused block itself was not written either",
                kernel.getBlockStore().getRawBlockByHash(spend.getHashLow()));
        assertFalse("nor did it leave a BlockInfo row behind",
                kernel.getBlockStore().hasBlockInfo(spend.getHashLow()));
        assertEquals("a refused block is not an orphan this node holds", noRefBefore,
                blockchain.getXdagStats().nnoref);
        assertEquals("nor a block it has", blocksBefore, blockchain.getXdagStats().nblocks);
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * The one verdict both node states have to reach. Written once so that the equality the pair
     * exists to pin lives in the code rather than in the reader's head: neither side can be edited
     * without the other following.
     */
    private static void assertRefusedAsUnusableInput(ImportResult r) {
        assertSame("a chunk cannot authorise a spend, and saying so is an unusable input"
                + " -- not an internal error, and not a missing parent", ImportResult.INVALID_BLOCK, r);
        assertEquals("Block's input can't be used", r.getErrorInfo());
    }

    /**
     * A transaction block whose single input is an {@code XDAG_FIELD_IN} block reference to
     * {@code source} — the old block-to-block transfer form, which is the one shape that reaches
     * {@code verifySignature} with a block reference rather than an address.
     */
    private Block spendingFrom(Block source) {
        Address from = new Address(hashLow(source), XDAG_FIELD_IN, false);
        Address to = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()),
                XDAG_FIELD_OUTPUT, true);
        return BlockBuilder.generateOldTransactionBlock(config, poolKey, txTime() + 2, from, to,
                XAmount.of(1, XUnit.XDAG));
    }
}
