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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.config.spec.EvmSpec;
import io.xdag.evm.GenesisAllocEntry;
import java.math.BigInteger;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Test;

/** The evm HOCON section (spec §9): devnet enabled with the provisional chain id, mainnet/testnet off. */
public class EvmConfigSectionTest {

    @Test
    public void devnet_reads_evm_section() {
        EvmSpec spec = new DevnetConfig().getEvmSpec();
        assertTrue(spec.isEvmEnabled());
        assertEquals(0L, spec.getEvmActivationHeight());
        assertEquals(51966L, spec.getEvmChainId()); // 0xCAFE provisional
        assertEquals(30_000_000L, spec.getEvmBlockGasLimit());
        assertEquals(3600L, spec.getEvmTxPoolTtlSeconds());
        assertEquals(131_072, spec.getEvmMaxP2pTxBytes());
        assertEquals("ADR-007: placeholder gas prices are legal on devnet", BigInteger.ONE, spec.getEvmMinGasPrice());
        assertEquals(1024L, spec.getEvmMaxLogScanRange());
    }

    @Test
    public void mainnet_and_testnet_default_to_disabled() {
        assertFalse(new MainnetConfig().getEvmSpec().isEvmEnabled());
        assertFalse(new TestnetConfig().getEvmSpec().isEvmEnabled());
    }

    @Test
    public void devnet_funds_the_standard_test_address_in_genesis_alloc() {
        // The funding on-ramp: devnet pre-funds the standard test address (private key 1); the other
        // networks fund nothing at genesis.
        List<GenesisAllocEntry> alloc = new DevnetConfig().getEvmSpec().getEvmGenesisAlloc();
        assertEquals(1, alloc.size());
        assertEquals(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"),
                alloc.get(0).address());
        assertEquals(Wei.of(new BigInteger("1000000000000000000000000")), alloc.get(0).balance());

        assertTrue("testnet funds nothing at genesis",
                new TestnetConfig().getEvmSpec().getEvmGenesisAlloc().isEmpty());
        assertTrue("mainnet funds nothing at genesis",
                new MainnetConfig().getEvmSpec().getEvmGenesisAlloc().isEmpty());
    }
}
