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

package io.xdag.config;

import static io.xdag.config.ConfigOverrides.withProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.spec.ChainSpec;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * The nine node-local SP0b-3 keys: their defaults, their parsing, and the validation that
 * refuses a set of values the orphan pool could not honour.
 */
public class ChainOrphanConfigTest {

    /** 低于 2 会让本节点拒绝其它节点接受的链，是共识分歧，必须启动即失败。 */
    @Test
    public void chunkTtlEpochsBelowTwoIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.chunkTtlEpochs", "1", DevnetConfig::new));
        assertTrue("the message must name the key and the floor: " + e.getMessage(),
                e.getMessage().contains("chain.orphan.chunkTtlEpochs") && e.getMessage().contains("2"));
    }

    @Test
    public void perPeerQuotaAboveTheChunkLimitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.chunkPerPeer", "999999", DevnetConfig::new));
    }

    @Test
    public void defaultsMatchTheSpec() {
        ChainSpec c = new DevnetConfig().getChainSpec();
        assertEquals(100000, c.getChainOrphanPoolLimit());
        assertEquals(3750, c.getChainOrphanAccountTxLimit());
        assertEquals(3750, c.getChainOrphanMtxLimit());
        assertEquals(60000, c.getChainOrphanChunkLimit());
        assertEquals(30000, c.getChainOrphanLinkLimit());
        assertEquals(5000, c.getChainOrphanChunkPerPeer());
        assertEquals(20000, c.getChainOrphanChunkPerChain());
        assertEquals(2, c.getChainOrphanChunkTtlEpochs());
        assertTrue(c.isChainIngestFeePolicy());
    }

    @Test
    public void perChainQuotaAboveTheChunkLimitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.chunkPerChain", "999999", DevnetConfig::new));
    }

    /** The four category limits are what the pool actually admits, so their sum must fit in it. */
    @Test
    public void categoryLimitsSummingAboveThePoolLimitAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.poolLimit", "1000", DevnetConfig::new));
    }

    @Test
    public void eachTierCanBeTuned() {
        assertEquals(200000, (int) withProperty("chain.orphan.poolLimit", "200000",
                () -> new DevnetConfig().getChainSpec().getChainOrphanPoolLimit()));
        assertEquals(1000, (int) withProperty("chain.orphan.accountTxLimit", "1000",
                () -> new DevnetConfig().getChainSpec().getChainOrphanAccountTxLimit()));
        assertEquals(1000, (int) withProperty("chain.orphan.mtxLimit", "1000",
                () -> new DevnetConfig().getChainSpec().getChainOrphanMtxLimit()));
        // Stays above the two default sub-tiers (5000 per peer, 20000 per chain), which a lower
        // chunk limit would legitimately refuse.
        assertEquals(50000, (int) withProperty("chain.orphan.chunkLimit", "50000",
                () -> new DevnetConfig().getChainSpec().getChainOrphanChunkLimit()));
        assertEquals(1000, (int) withProperty("chain.orphan.linkLimit", "1000",
                () -> new DevnetConfig().getChainSpec().getChainOrphanLinkLimit()));
        assertEquals(64, (int) withProperty("chain.orphan.chunkPerPeer", "64",
                () -> new DevnetConfig().getChainSpec().getChainOrphanChunkPerPeer()));
        assertEquals(64, (int) withProperty("chain.orphan.chunkPerChain", "64",
                () -> new DevnetConfig().getChainSpec().getChainOrphanChunkPerChain()));
        assertEquals(8, (int) withProperty("chain.orphan.chunkTtlEpochs", "8",
                () -> new DevnetConfig().getChainSpec().getChainOrphanChunkTtlEpochs()));
        assertFalse(withProperty("chain.ingest.feePolicy", "false",
                () -> new DevnetConfig().getChainSpec().isChainIngestFeePolicy()));
    }

    /** Every tier is a count, so zero and below are refused with the key named. */
    @Test
    public void nonPositiveTiersAreRefused() {
        String[] keys = {"chain.orphan.poolLimit", "chain.orphan.accountTxLimit", "chain.orphan.mtxLimit",
                "chain.orphan.chunkLimit", "chain.orphan.linkLimit", "chain.orphan.chunkPerPeer",
                "chain.orphan.chunkPerChain"};
        for (String key : keys) {
            IllegalArgumentException e = assertThrows(key, IllegalArgumentException.class,
                    () -> withProperty(key, "0", DevnetConfig::new));
            assertTrue(key + " must be named in: " + e.getMessage(), e.getMessage().contains(key));
        }
    }

    /**
     * A default set that failed its own cross-key validation would leave the node unbootable, so
     * every network's defaults have to construct. Devnet, testnet and mainnet share these nine
     * values today; this is what would catch the day one of them stops sharing them.
     */
    @Test
    public void everyNetworkDefaultSetPassesItsOwnValidation() {
        for (Supplier<Config> network : java.util.List.<Supplier<Config>>of(
                DevnetConfig::new, TestnetConfig::new, MainnetConfig::new)) {
            ChainSpec c = network.get().getChainSpec();
            long categorySum = (long) c.getChainOrphanAccountTxLimit() + c.getChainOrphanMtxLimit()
                    + c.getChainOrphanChunkLimit() + c.getChainOrphanLinkLimit();
            assertTrue("category limits must fit in the pool limit", categorySum <= c.getChainOrphanPoolLimit());
            assertTrue("per-peer quota must fit in the chunk limit",
                    c.getChainOrphanChunkPerPeer() <= c.getChainOrphanChunkLimit());
            assertTrue("per-chain quota must fit in the chunk limit",
                    c.getChainOrphanChunkPerChain() <= c.getChainOrphanChunkLimit());
            assertTrue("the chunk TTL floor is a consensus boundary",
                    c.getChainOrphanChunkTtlEpochs() >= ChainSpec.MIN_ORPHAN_CHUNK_TTL_EPOCHS);
        }
    }
}
