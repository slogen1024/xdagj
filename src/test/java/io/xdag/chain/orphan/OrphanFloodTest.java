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
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.XdagPow;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.utils.BytesUtils;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The admission half of the starvation SP0b-3 exists to kill, end to end through the real import
 * path.
 *
 * <p>Before this was fixed, {@code BlockchainImpl:471} read
 * {@code isAccountTx(block) && orphanBlockStore.getOrphanSize() >= MAX_ORPHAN_SIZE}: a count of all
 * four queues together, compared against 3750, and consulted <em>only</em> when an account
 * transaction arrived. Nothing looked at the gate on the way in for a link, chunk or mtx block, so
 * those three filled the pool for free and then account transactions — the one category that was
 * checked — started being refused for room that none of them had ever been asked to leave.
 *
 * <h2>Why the flood is counted as LINK</h2>
 *
 * <p>These really are chunk blocks, built by {@link ChunkChainBuilder}. They are nonetheless filed
 * under {@link OrphanCategory#LINK}, because they are delivered through
 * {@code blockchain.tryToConnect(block)} — the bare entry point, the one local mining and the tools
 * use — and that path asks for no classification at all. {@code OrphanCategory.of} is handed a null
 * kind on both sides of the decision, the gate and the store, and a null kind is never
 * {@code CHUNK}. (Blocks arriving from the network do carry one; see
 * {@code OrphanPeerAttributionTest}.)
 *
 * <p>So this test asserts nothing about the chunk cap. It floods the category these blocks actually
 * land in and pins the only thing that matters here — that filling one category does not close
 * another. Keeping the flood in LINK is if anything the harder case: a link block is the expensive
 * kind to hold, and it is the queue a mined block packs from.
 */
public class OrphanFloodTest extends ChainL1TestBase {

    /** Well above 3750, the global count the old gate compared against, and well under the 30000 link cap. */
    private static final int FLOOD = 5000;

    /**
     * The floor of the difficulty window {@link #mineMain} searches in, so a flood block below it
     * can never out-weigh the mined chain top. See {@link #floodChunk}.
     */
    private static final BigInteger MAX_FLOOD_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per flood block, so one block's redraws can never collide with the next one's. */
    private static final int SEEDS_PER_CHUNK = 64;

    /**
     * Arms the pool the way {@link ChunkOrphanTestBase#armTheOrphanPool()} does and for the reasons
     * given there, which includes which categories the mining gate still applies to. No main block
     * here: this class mines its own inside the test that needs it, which is one of the two reasons
     * it keeps a fixture of its own.
     */
    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
    }

    @Test
    public void aChunkFloodDoesNotStarveAccountTransactions() {
        // One real main block first, so the chain top weighs at least 2^46 -- the floor of the
        // window mineMain searches in, and the ceiling floodChunk keeps every flood block under.
        // Without it the top is the fixture's address block and the first chunk through the door
        // takes the chain over, which is a fixture artefact and not the subject here. Nothing is
        // mined afterwards, so nothing links -- and therefore un-orphans -- the flood.
        mineMain(List.of());

        for (int i = 0; i < FLOOD; i++) {
            assertImported(floodChunk(i));
        }
        // Not decoration: without this the test could pass because nothing was ever pooled.
        assertEquals("the flood must really be sitting in the pool",
                FLOOD, blockchain.getOrphanBlockStore().getOrphanSize());

        ImportResult r = blockchain.tryToConnect(accountTx());
        assertTrue("a chunk flood must not push account transactions out of the pool: "
                        + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
    }

    /**
     * One chunk block that references nothing, so it stays an orphan. A multi-chunk chain would
     * not flood anything: each chunk links the next, and importing a block removes everything it
     * links from the pool, so a chain of n chunks leaves exactly one entry behind.
     *
     * <p>The difficulty filter is the interesting part. A link-less block's chain weight is just
     * its own raw-hash difficulty, and that is {@code 2^256 / hash} — a heavy-tailed quantity whose
     * <em>maximum</em> over five thousand draws lands around 2^48, well past the [2^46, 2^47)
     * window {@link #mineMain} searches for. So a flood this size reliably contains a chunk that
     * out-weighs the mined chain top and takes it over, which has nothing to do with orphan
     * admission and would drag a reorg through the middle of the measurement. Drawing again until
     * the block is lighter than any mined main block keeps the flood deterministic and keeps the
     * chain top where the test put it. Costs about five extra draws in five thousand.
     */
    private Block floodChunk(int seed) {
        for (long s = (long) seed * SEEDS_PER_CHUNK; ; s++) {
            Block chunk = ChunkChainBuilder.split(config, payload(32, s), txTime()).get(0);
            if (blockchain.calculateCurrentBlockDiff(chunk).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return chunk;
            }
        }
    }

    private Block accountTx() {
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(Bytes.random(20).toArray()), XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, to, ONE_XDAG, nextNonce()).toBytes()));
    }
}
