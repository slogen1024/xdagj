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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.xdag.config.spec.EvmSpec;
import io.xdag.evm.GenesisAllocEntry;
import java.math.BigInteger;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Test;

/** The evm HOCON section (spec §9): devnet enabled; mainnet/testnet scaffolded with reserved chain ids but disabled. */
public class EvmConfigSectionTest {

    @Test
    public void devnet_reads_evm_section() {
        EvmSpec spec = new DevnetConfig().getEvmSpec();
        assertTrue(spec.isEvmEnabled());
        assertEquals(0L, spec.getEvmActivationHeight());
        assertEquals(51966L, spec.getEvmChainId()); // 0xCAFE reserved devnet id
        // Devnet gas budget is deliberately non-binding (1e12): batches fill to MAX_BATCH_TXS instead.
        assertEquals(1_000_000_000_000L, spec.getEvmBlockGasLimit());
        assertEquals(3600L, spec.getEvmTxPoolTtlSeconds());
        assertEquals(131_072, spec.getEvmMaxP2pTxBytes());
        assertEquals("ADR-007: placeholder gas prices are legal on devnet", BigInteger.ONE, spec.getEvmMinGasPrice());
        assertEquals(1024L, spec.getEvmMaxLogScanRange());
    }

    @Test
    public void stateHistoryWindow_reads_from_evm_section() {
        // C4 window: devnet conf sets 128; the field default also happens to be 128.
        assertEquals(128, new DevnetConfig().getEvmSpec().getEvmStateHistoryWindow());
        // Mainnet and testnet also carry the explicit 128 in their conf files.
        assertEquals(128, new MainnetConfig().getEvmSpec().getEvmStateHistoryWindow());
        assertEquals(128, new TestnetConfig().getEvmSpec().getEvmStateHistoryWindow());
    }

    @Test
    public void mainnet_and_testnet_default_to_disabled() {
        assertFalse(new MainnetConfig().getEvmSpec().isEvmEnabled());
        assertFalse(new TestnetConfig().getEvmSpec().isEvmEnabled());
    }

    @Test
    public void shared_nets_pin_their_reserved_chain_ids() {
        // Defect-4: testnet/mainnet carry their reserved EIP-155 chain ids; devnet keeps 0xCAFE.
        // The values are reserved in EvmConfig; confirming no collision on ethereum-lists/chains
        // is an off-chain registration action that must precede launch.
        assertEquals(51964L, new MainnetConfig().getEvmSpec().getEvmChainId()); // 0xCAFC
        assertEquals(51965L, new TestnetConfig().getEvmSpec().getEvmChainId()); // 0xCAFD
        assertEquals(51966L, new DevnetConfig().getEvmSpec().getEvmChainId());  // 0xCAFE
    }

    @Test
    public void shared_nets_scaffold_every_fork_as_unscheduled() {
        // Defect-4 scaffold: the shared-net conf files carry the full evm block but keep every
        // hard-fork gate at Long.MAX_VALUE — including evm.activationHeight, so that a future
        // evm.enabled=true flip cannot silently activate the EVM at height 0. Enabling on a shared
        // network is gated on the §13.3 hard gates (see evm-mainnet-readiness-and-audit-scope.md).
        for (EvmSpec spec : List.of(new TestnetConfig().getEvmSpec(), new MainnetConfig().getEvmSpec())) {
            assertFalse(spec.isEvmEnabled());
            assertEquals(Long.MAX_VALUE, spec.getEvmActivationHeight());
            assertEquals(Long.MAX_VALUE, spec.getEvmBatchActivationHeight());
            assertEquals(Long.MAX_VALUE, spec.getEvmType2ActivationHeight());
            assertEquals(Long.MAX_VALUE, spec.getEvmBridgeActivationHeight());
        }
    }

    @Test
    public void devnet_activates_batch_packing_at_genesis_and_shared_nets_do_not() {
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmBatchActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmBatchActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmBatchActivationHeight());
    }

    @Test
    public void devnet_activates_type2_at_genesis_and_shared_nets_do_not() {
        // Defect-1 fork gate: devnet accepts type-2 (EIP-1559) txs from genesis; testnet/mainnet
        // omit the key (= Long.MAX_VALUE, not scheduled) until the fork is coordinated.
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmType2ActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmType2ActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmType2ActivationHeight());
    }

    @Test
    public void devnet_activates_eip3529_at_genesis_and_shared_nets_do_not() {
        // EIP-3529 precise gas refund cap: devnet active from genesis; testnet/mainnet unscheduled
        // (Long.MAX_VALUE) until the hard-fork height is coordinated — changing the cap alters
        // receipt.gasUsed which is folded into the chained state root.
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmEip3529ActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmEip3529ActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmEip3529ActivationHeight());
    }

    @Test
    public void fee_reward_activation_height_per_network() {
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmFeeRewardActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmFeeRewardActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmFeeRewardActivationHeight());
    }

    @Test
    public void state_root_anchoring_knobs_are_scaffolded_per_network() {
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmStateRootActivationHeight());
        assertEquals(1L, new DevnetConfig().getEvmSpec().getEvmStateRootLag());

        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmStateRootActivationHeight());
        assertEquals(16L, new TestnetConfig().getEvmSpec().getEvmStateRootLag());

        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmStateRootActivationHeight());
        assertEquals(16L, new MainnetConfig().getEvmSpec().getEvmStateRootLag());
    }

    @Test
    public void state_root_hard_reject_is_off_by_default_and_on_for_mainnet() {
        assertFalse(new DevnetConfig().getEvmSpec().isEvmStateRootHardReject());
        assertFalse(new TestnetConfig().getEvmSpec().isEvmStateRootHardReject());
        assertTrue(new MainnetConfig().getEvmSpec().isEvmStateRootHardReject());
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

    private static final String A1 = "0x1111111111111111111111111111111111111111";
    private static final String A2 = "0x2222222222222222222222222222222222222222";

    @Test
    public void evm_alloc_parses_valid_entries_and_defaults_to_empty() {
        assertTrue("absent alloc -> empty", AbstractConfig.parseEvmAlloc(ConfigFactory.empty()).isEmpty());
        List<GenesisAllocEntry> alloc = AbstractConfig.parseEvmAlloc(ConfigFactory.parseString(
                "evm.alloc=[{address=\"" + A1 + "\",balance=\"1\"},{address=\"" + A2 + "\",balance=\"2\"}]"));
        assertEquals(2, alloc.size());
        assertEquals(Wei.of(1), alloc.get(0).balance());
    }

    @Test
    public void devnet_activates_bridge_at_genesis_and_shared_nets_do_not() {
        // Defect-3 phase-3a bridge gate: devnet activates at genesis with a recovery address;
        // testnet/mainnet omit the keys (= Long.MAX_VALUE / null, not scheduled).
        EvmSpec devSpec = new DevnetConfig().getEvmSpec();
        assertEquals(0L, devSpec.getEvmBridgeActivationHeight());
        assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", devSpec.getEvmBridgeRecoveryAddress());
        // Phase-3b withdrawal maturation delay N (spec §3.2): devnet 2 (~2 min turnaround).
        assertEquals(2L, devSpec.getEvmBridgeWithdrawalDelay());

        EvmSpec testSpec = new TestnetConfig().getEvmSpec();
        assertEquals(Long.MAX_VALUE, testSpec.getEvmBridgeActivationHeight());
        assertNull(testSpec.getEvmBridgeRecoveryAddress());
        assertEquals(16L, testSpec.getEvmBridgeWithdrawalDelay()); // the shared-net default

        EvmSpec mainSpec = new MainnetConfig().getEvmSpec();
        assertEquals(Long.MAX_VALUE, mainSpec.getEvmBridgeActivationHeight());
        assertNull(mainSpec.getEvmBridgeRecoveryAddress());
        assertEquals(16L, mainSpec.getEvmBridgeWithdrawalDelay()); // the shared-net default
    }

    @Test
    public void bridge_activation_without_recovery_address_fails_fast() {
        Config c = ConfigFactory.parseString("evm.bridgeActivationHeight = 5");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }

    @Test
    public void bridge_recovery_address_must_be_20_byte_hex() {
        Config c = ConfigFactory.parseString(
                "evm.bridgeActivationHeight = 5\nevm.bridgeRecoveryAddress = \"0x1234\"");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }

    @Test
    public void bridge_scheduled_before_the_evm_itself_fails_fast() {
        // Deposits confirmed in [bridgeActivation, evmActivation) would be deterministically
        // dropped (funds stranded at the lock address), so refuse to start.
        Config c = ConfigFactory.parseString("evm.enabled = true\n"
                + "evm.activationHeight = 10\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }

    @Test
    public void well_formed_or_unscheduled_bridge_config_passes_validation() {
        AbstractConfig.validateBridgeConfig(ConfigFactory.parseString(
                "evm.enabled = true\nevm.bridgeActivationHeight = 5\n"
                        + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\""));
        // explicit MAX_VALUE = not scheduled
        AbstractConfig.validateBridgeConfig(ConfigFactory.parseString(
                "evm.bridgeActivationHeight = 9223372036854775807"));
    }

    @Test
    public void bridge_on_an_evm_disabled_network_fails_fast() {
        // explicit false
        Config c = ConfigFactory.parseString("evm.enabled = false\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
        IllegalStateException ex1 = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(c));
        assertTrue("message must name the predicate", ex1.getMessage().contains("evm.enabled is not"));

        // absent key — should also fail (unset resolves to disabled)
        Config cAbsent = ConfigFactory.parseString("evm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(cAbsent));
        assertTrue("message must name the predicate", ex2.getMessage().contains("evm.enabled is not"));
    }

    @Test
    public void bridge_withdrawal_delay_must_be_positive_when_scheduled() {
        Config c = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n"
                + "evm.bridgeWithdrawalDelay = 0");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(c));
        assertTrue("message must name the constraint", ex.getMessage().contains("bridgeWithdrawalDelay must be >= 1"));
    }

    @Test
    public void fee_routing_requires_a_scheduled_bridge() {
        // fee-routing scheduled but the bridge unscheduled -> fatal: there is no deposit lock to source
        // the net EVM fee from (the transfer-from-lock model, spec 2026-08-31).
        Config bad = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.feeRewardActivationHeight = 100\n"
                + "evm.bridgeActivationHeight = 9223372036854775807"); // bridge unscheduled (MAX)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(bad));
        assertTrue("message must name the fee->bridge requirement",
                ex.getMessage().contains("fee-routing requires a scheduled bridge"));

        // fee-routing scheduled WITH a scheduled bridge (+ recovery address + evm.enabled) -> no throw.
        Config ok = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.feeRewardActivationHeight = 100\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
        AbstractConfig.validateBridgeConfig(ok); // no exception
    }

    @Test
    public void bridge_withdrawal_delay_must_satisfy_state_root_lag_invariant() {
        // W < lag-1: under delta-lagged execution the burn height is never executed by release time,
        // so every release would CRITICAL-skip. Must fail fast at load.
        Config bad = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n"
                + "evm.stateRootLag = 4\nevm.bridgeWithdrawalDelay = 2");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(bad));
        assertTrue("message must name the invariant",
                ex.getMessage().contains("must be >= evm.stateRootLag - 1"));

        // Boundary W == lag-1 is valid: processConfirmedBlock matures M-lag+1 BEFORE releaseMaturedWithdrawals(M),
        // so the burn height is executed within the same setMain. Must NOT throw.
        Config boundary = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n"
                + "evm.stateRootLag = 4\nevm.bridgeWithdrawalDelay = 3");
        AbstractConfig.validateBridgeConfig(boundary); // no exception
    }

    @Test
    public void evm_alloc_on_a_bridged_net_must_be_whole_nano_and_fit_the_native_ceiling() {
        String base = "evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n";

        // Not a whole number of nano (1 nano = 1e9 wei): the lock seed could not equal the
        // redeemable wei -> fail fast.
        Config dust = ConfigFactory.parseString(base
                + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
                + "balance = \"1000000001\"}]");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(dust));
        assertTrue("message must name the whole-nano requirement",
                ex.getMessage().contains("whole number of nano"));

        // Total above the native XAmount ceiling (2^62 nano) -> fail fast.
        Config huge = ConfigFactory.parseString(base
                + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
                + "balance = \"9000000000000000000000000000\"}]"); // 9e27 wei = 9e18 nano > 2^62
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> AbstractConfig.validateBridgeConfig(huge));
        assertTrue("message must name the supply ceiling",
                ex2.getMessage().contains("native supply ceiling"));

        // Whole-nano and under the ceiling -> passes.
        Config ok = ConfigFactory.parseString(base
                + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
                + "balance = \"1000000000000000000000000\"}]"); // devnet's 1e24 wei
        AbstractConfig.validateBridgeConfig(ok); // no exception

        // Bridge UNSCHEDULED: dust alloc is allowed (never seeded, never redeemable).
        Config unbridged = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
                + "balance = \"1000000001\"}]");
        AbstractConfig.validateBridgeConfig(unbridged); // no exception
    }

    @Test
    public void evm_alloc_rejects_duplicate_zero_negative_and_oversized() {
        assertThrows("duplicate address", IllegalArgumentException.class, () -> AbstractConfig.parseEvmAlloc(
                ConfigFactory.parseString("evm.alloc=[{address=\"" + A1 + "\",balance=\"1\"},"
                        + "{address=\"" + A1 + "\",balance=\"2\"}]")));
        assertThrows("zero balance", IllegalArgumentException.class, () -> AbstractConfig.parseEvmAlloc(
                ConfigFactory.parseString("evm.alloc=[{address=\"" + A1 + "\",balance=\"0\"}]")));
        assertThrows("negative balance", IllegalArgumentException.class, () -> AbstractConfig.parseEvmAlloc(
                ConfigFactory.parseString("evm.alloc=[{address=\"" + A1 + "\",balance=\"-1\"}]")));
        // 2^256 is one past the Wei ceiling (max is 2^256-1).
        String overMax = BigInteger.TWO.pow(256).toString();
        assertThrows("balance > 2^256-1", IllegalArgumentException.class, () -> AbstractConfig.parseEvmAlloc(
                ConfigFactory.parseString("evm.alloc=[{address=\"" + A1 + "\",balance=\"" + overMax + "\"}]")));
    }

    @Test
    public void devnet_activates_invalid_tx_skip_at_genesis_and_shared_nets_do_not() {
        // Audit round 2 P3 gate: validation-failed refs are dropped (no receipt) from this height on.
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmInvalidTxSkipActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmInvalidTxSkipActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmInvalidTxSkipActivationHeight());
    }
}
