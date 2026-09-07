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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.util.List;
import org.junit.Test;

/**
 * Audit round 2, E6: the EVM consensus parameters (chain id, gas limit, min gas price, state-root lag,
 * withdrawal delay, recovery address, genesis alloc and every fork height) decide receipts, budgets,
 * the GASLIMIT opcode and the chained root, yet they were plain node-local HOCON. On a shared network
 * they are now PINNED in code: the loaded config must match the network's constants or the node
 * refuses to start; every network additionally fails fast on nonsensical values (lag 0, gas limit 0).
 */
public class EvmConsensusParamsTest {

    @Test
    public void shared_network_configs_match_their_pinned_constants() {
        assertTrue(EvmConsensusParams.TESTNET.mismatches(new TestnetConfig().getEvmSpec()).isEmpty());
        assertTrue(EvmConsensusParams.MAINNET.mismatches(new MainnetConfig().getEvmSpec()).isEmpty());
        assertEquals(51965L, EvmConsensusParams.TESTNET.chainId());
        assertEquals(51964L, EvmConsensusParams.MAINNET.chainId());
    }

    @Test
    public void a_deviating_effective_value_is_reported_by_key() {
        EvmConsensusParams tweaked = EvmConsensusParams.TESTNET.withBlockGasLimit(31_000_000L);
        List<String> mismatches = tweaked.mismatches(new TestnetConfig().getEvmSpec());
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0), mismatches.get(0).startsWith("evm.blockGasLimit"));
    }

    @Test
    public void a_shared_network_node_refuses_to_start_on_a_consensus_param_override() {
        // Simulates an operator override (e.g. a higher minGasPrice "for anti-spam") on a shared net:
        // the pinned constants disagree with the effective config -> fail fast at load, never fork.
        try {
            new TestnetConfig() {
                @Override
                protected EvmConsensusParams pinnedEvmConsensusParams() {
                    return EvmConsensusParams.TESTNET.withMinGasPrice(BigInteger.valueOf(2_000_000_000L));
                }
            };
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("evm.minGasPrice"));
        }
    }

    @Test
    public void devnet_is_not_pinned() {
        assertTrue(new DevnetConfig().getEvmSpec().getEvmChainId() > 0);
        // Devnet is the throwaway/test network: HOCON-driven, no pin (documented).
        assertEquals(null, new DevnetConfig() {
            EvmConsensusParams pinned() {
                return pinnedEvmConsensusParams();
            }
        }.pinned());
    }

    @Test
    public void nonsensical_values_fail_fast_on_every_network() {
        assertSanityRejects(0L, 30_000_000L, 51966L, BigInteger.ONE, 16L, "evm.stateRootLag");
        assertSanityRejects(16L, 0L, 51966L, BigInteger.ONE, 16L, "evm.blockGasLimit");
        assertSanityRejects(16L, 30_000_000L, 0L, BigInteger.ONE, 16L, "evm.chainId");
        assertSanityRejects(16L, 30_000_000L, 51966L, BigInteger.valueOf(-1), 16L, "evm.minGasPrice");
        assertSanityRejects(16L, 30_000_000L, 51966L, BigInteger.ONE, 0L, "evm.bridgeWithdrawalDelay");
        AbstractConfig.validateEvmConsensusSanity(16L, 30_000_000L, 51966L, BigInteger.ONE, 16L); // ok
    }

    private static void assertSanityRejects(long lag, long gasLimit, long chainId, BigInteger minGasPrice,
                                            long withdrawalDelay, String key) {
        try {
            AbstractConfig.validateEvmConsensusSanity(lag, gasLimit, chainId, minGasPrice, withdrawalDelay);
            fail("expected rejection of " + key);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(key));
        }
    }
}
