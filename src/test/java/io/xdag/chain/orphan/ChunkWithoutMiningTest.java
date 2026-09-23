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

import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.core.Block;
import io.xdag.utils.XdagTime;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Task 11 made a chunk's only home the orphan pool, and the pool's entrance sits behind the mining
 * gate, so a node that does not mine stored an arriving chunk in neither place and every paying
 * block naming it was {@code NO_PARENT} for ever. Two shapes reach that: a node whose PoW instance
 * does not exist yet, and a node with block generation turned off in config.
 *
 * <h2>Which half of the gate this class holds, and why it is two classes</h2>
 *
 * <p>The gate is {@code getEnableGenerateBlock() && getPow() != null}. Here the config half is left
 * alone — devnet sets {@code node.generate.block.enable = true} — so {@code getPow() == null} is
 * the only half holding it shut. That is the startup window every node passes through whatever its
 * configuration says, which is why it is worth a test of its own rather than a second spelling of
 * the config shape; the config shape is {@link ChunkWithGenerationDisabledTest}.
 *
 * <p>They are two classes rather than two methods because {@code AbstractConfig.enableGenerateBlock}
 * is a field with no setter and {@link io.xdag.chain.l1.ChainL1TestBase#newConfig()} is answered
 * once per class, so one class cannot show generation enabled to one test and disabled to another.
 * This paragraph is the canonical statement of that; the sibling points here for it.
 *
 * <h2>The second thing this class holds: that the restored holding is still bounded</h2>
 *
 * <p>Opening the pool to chunks on a node that does not mine is new memory held on a class of node
 * that held none before, and the design's answer to "is that safe" is that no new mechanism is
 * needed — the CHUNK tier cap, the per-peer and per-chain chunk budgets and the two-epoch TTL were
 * built to bound chunk holding and they do not consult the mining gate. That answer is worth no
 * more than the evidence for it, and the two tests at the bottom of this class are that evidence.
 * They belong here rather than in a flood class of their own because the shape they have to be
 * shown in is the non-mining one, and this class is where that shape lives.
 *
 * <p>They take one bound of each kind — a budget, which limits how much is held at once, and the
 * TTL, which limits for how long — because what is in question is whether the bounds apply in this
 * shape at all, not how each one counts. {@link OrphanQuotaTest} is where the counting lives, per
 * tier, including the two this class does not drive to their limit (the CHUNK tier cap and the
 * per-chain budget); none of them so much as reads the mining gate.
 */
public class ChunkWithoutMiningTest extends ChunkOrphanTestBase {

    /** Small enough that a flood hits it, large enough that one chunk does not. */
    private static final int BUDGET = 3;

    /** Comfortably past {@link #BUDGET}, so what stops the flood is a budget and not the supply. */
    private static final int CHUNKS_SENT = 10;

    /** A source of its own, so the flood is charged to a budget no other delivery has spent. */
    private static final String FLOODER_IP = "198.51.100.42";

    /**
     * Quotas only. <b>The generation switch is deliberately untouched</b> — devnet leaves
     * {@code node.generate.block.enable = true} and this class's whole premise is that
     * {@code getPow() == null} is the only half holding the mining gate shut. An override here that
     * reached for {@code getEnableGenerateBlock} would close the other half too and quietly turn
     * every test in this class into a second spelling of {@link ChunkWithGenerationDisabledTest}.
     *
     * <p>Shrinking both chunk budgets to {@link #BUDGET} is what lets a flood of ten reach one in a
     * unit test; the production defaults are in the thousands.
     */
    @Override
    protected Config newConfig() {
        AbstractConfig cfg = (AbstractConfig) super.newConfig();
        cfg.setChainOrphanChunkPerPeer(BUDGET);
        cfg.setChainOrphanChunkPerChain(BUDGET);
        return cfg;
    }

    /**
     * The base's {@code @Before} minus the one line that installs the PoW mock, which is what makes
     * this a node that does not mine.
     *
     * <p>An override rather than simply not calling it: the base declares
     * {@link ChunkOrphanTestBase#armTheOrphanPool()} {@code @Before}, so JUnit runs it for every
     * subclass and the only way out is to replace the body. Its other half has to stay — one real
     * main block, so the chain top weighs at least 2^46 and a delivered block drawn below that
     * floor cannot hijack it. Mining it needs no PoW instance: {@code mineMain} searches a nonce
     * itself and imports the result through {@code tryToConnect}.
     */
    @Override
    @Before
    public void armTheOrphanPool() {
        mineMain(List.of());
    }

    @Test
    public void aNodeWhosePowDoesNotExistYetStillKeepsAnArrivingChunk() {
        // The premise, pinned rather than assumed. This class's half of the gate is an absence
        // maintained by the override above, and an absence is the fragile kind of premise: let
        // anything in a base class install a PoW instance by another route and devnet's
        // node.generate.block.enable = true opens the gate, the chunk is pooled for the ordinary
        // reason, and everything below stays green while testing nothing. The sibling pins the
        // mirror image of this -- that the same soft half is still *open* over there.
        assertNull("this fixture must be unarmed, or the gate is open for the ordinary reason",
                kernel.getPow());

        Block chunk = lightChunk(711);
        assertImported(deliver(chunk));
        assertNotNull("the startup window before the PoW instance exists must not lose chunks",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));

        assertLanded("the block that pays for the chunk must not hang on NO_PARENT",
                deliver(linkTo(hashLow(chunk), 712)));
    }

    /**
     * The holding restored above is bounded by the machinery that already exists for it, and a
     * per-source budget is the first half of that: a non-mining node is not a node with no limits,
     * it is the same pool with the same bounds.
     *
     * <p><b>Which tier stops this flood.</b> Both budgets are set to {@link #BUDGET}, so the count
     * alone would not say. {@link #lightChunk} builds a one-chunk chain whose successor is null, so
     * each delivery groups onto its own chain head and the per-chain tier holds one apiece — which
     * the bucket count below states outright, making the per-peer tier the only one that can be
     * refusing. That is the shape the two tiers are split for: per-chain bounds many chunks piling
     * onto one chain, per-peer bounds one sender however it spreads them out.
     */
    @Test
    public void aNonMiningNodeStillChargesAFloodToItsSenderBudget() {
        assertNull("a bounded flood on a node that turns out to mine tests the wrong node",
                kernel.getPow());
        for (int i = 0; i < CHUNKS_SENT; i++) {
            deliver(lightChunk(720 + i), FLOODER_IP);
        }
        assertEquals("the per-peer budget binds whether or not this node mines",
                BUDGET, pool().size(OrphanCategory.CHUNK));
        assertEquals("one chain head apiece, so the per-chain tier was never the one refusing",
                BUDGET, pool().chainBucketCount());
    }

    /**
     * The other half: what is kept is kept for two epochs and not for ever. Without it, "a
     * non-mining node's memory is bounded" would rest on the budget alone — and a budget bounds how
     * much is held at once, never for how long, so a node fed one chunk per epoch for a week stays
     * inside every budget it has.
     */
    @Test
    public void aNonMiningNodeStillAgesChunksOut() {
        assertNull("a chunk aged out of a node that turns out to mine tests the wrong node",
                kernel.getPow());
        Block chunk = lightChunk(740);
        assertImported(deliver(chunk));
        assertNotNull("the chunk is held before its TTL runs out",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));

        // evictExpired(nowMillis, currentEpoch) -- the memory half of the cleaner tick, driven the
        // way ChunkFloodAdversarialTest drives it. Two epochs past the chunk's own header is what
        // the age rule makes the cut-off.
        long epoch = XdagTime.getEpoch(chunk.getTimestamp());
        assertEquals("the TTL must actually fire, or this is not the state the test says it is",
                1, pool().evictExpired(0L, epoch + 2).size());

        assertNull("the two-epoch TTL is what bounds a non-mining node's holding over time",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
    }
}
