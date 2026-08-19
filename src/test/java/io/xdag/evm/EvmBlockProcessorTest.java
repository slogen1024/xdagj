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
package io.xdag.evm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.rpc.eth.LogFilter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.Before;
import org.junit.Test;

/**
 * EvmBlockProcessor: per-main-block execution over the persisted world state, receipts and chained
 * commitment checkpoints in EVM_META, and wipe-and-replay rollback (spec §7, R1 Option A).
 */
public class EvmBlockProcessorTest {

    /** Initcode deploying runtime 0x602a600055 (SSTORE slot0 = 42 on every call). */
    private static final Bytes INIT_CODE = Bytes.fromHexString("0x64602a6000556000526005601bf3");
    private static final Bytes RUNTIME = Bytes.fromHexString("0x602a600055");

    /**
     * Initcode that returns the 6-byte runtime {@code 60006000a000} = PUSH1 0 (len) PUSH1 0 (offset)
     * LOG0 STOP: every CALL to the deployed contract emits one topic-less log carrying the contract's
     * address (enough to set the bloom's address bits). Constructor trace: PUSH1 0x06 PUSH1 0x0c PUSH1
     * 0x00 CODECOPY (copy 6 runtime bytes from code offset 12 to mem 0) PUSH1 0x06 PUSH1 0x00 RETURN.
     */
    private static final Bytes LOG_INIT_CODE = Bytes.fromHexString("0x6006600c60003960066000f360006000a000");
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(0xCAFE);

    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final Address sender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    private InMemoryKVSource stateSource;
    private EvmTxStore txStore;
    private EvmMetaStore metaStore;
    private EvmBlockProcessor processor;

    @Before
    public void setUp() {
        stateSource = new InMemoryKVSource();
        txStore = new EvmTxStore(new InMemoryKVSource());
        metaStore = new EvmMetaStore(new InMemoryKVSource());
        // Fund the sender via genesis alloc (the production on-ramp) so its balance survives a
        // rollback's reset+replay. An out-of-band createAccount would be wiped by stateStore.reset()
        // now that gas is charged, which would break the replay.
        processor = new EvmBlockProcessor(EvmConfig.devnet(), stateSource, txStore, metaStore, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        processor.seedGenesisIfAbsent();
    }

    private EvmTransaction storedTx(long nonce, Optional<Address> to, Bytes payload, long gasLimit) {
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.of(1), gasLimit, to, Wei.ZERO,
                payload, CHAIN_ID).sign(key, algo);
        txStore.put(tx);
        return tx;
    }

    private static Bytes32 ref(EvmTransaction tx) {
        return Bytes32.wrap(tx.getHash().getBytes());
    }

    private Account account(Address address) {
        return new RocksDbWorldUpdater(stateSource).getAccount(address);
    }

    private static final Bytes32 BLOCK_HASH_1 = Bytes32.fromHexString("0x" + "01".repeat(32));
    private static final Bytes32 BLOCK_HASH_2 = Bytes32.fromHexString("0x" + "02".repeat(32));
    private static final Bytes32 BLOCK_HASH_3 = Bytes32.fromHexString("0x" + "03".repeat(32));

    @Test
    public void chained_root_folds_in_world_state_not_just_the_execution_log() {
        // Two nodes execute the SAME signed tx (identical txHash, status, gasUsed) but from different
        // starting balances. The pre-fix root — keccak over (txHash, status, gasUsed) only — was
        // identical on both, hiding the divergence. Folding the persisted world-state delta in makes
        // the roots differ, so the replay self-check can catch a state-only fork.
        Address recipient = Address.fromHexString("0x00000000000000000000000000000000000000aa");
        EvmTransaction transfer = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L, Optional.of(recipient),
                Wei.of(1000), Bytes.EMPTY, CHAIN_ID).sign(key, algo);

        Bytes32 rootRich = rootAfterTransfer(transfer, Wei.fromEth(2));
        Bytes32 rootPoor = rootAfterTransfer(transfer, Wei.fromEth(1));

        assertNotEquals("a balance-only divergence must change the chained state root", rootRich, rootPoor);
    }

    /** Runs {@code tx} at height 1 on a fresh processor whose sender starts with {@code senderBalance}. */
    private Bytes32 rootAfterTransfer(EvmTransaction tx, Wei senderBalance) {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta);
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(state);
        world.createAccount(sender, 0L, senderBalance);
        world.commit();
        txs.put(tx);
        proc.processMainBlock(List.of(ref(tx)), 1L, 1001L, BLOCK_HASH_1);
        return meta.getHeightRecord(1L).orElseThrow().stateRoot();
    }

    @Test
    public void genesis_alloc_credits_configured_balance_and_is_idempotent() {
        // The funding on-ramp: configured genesis balances are seeded into EVM_STATE exactly once. A
        // second seed (e.g. a node restart) must NOT reset a balance that transactions have changed.
        InMemoryKVSource state = new InMemoryKVSource();
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state,
                new EvmTxStore(new InMemoryKVSource()), new EvmMetaStore(new InMemoryKVSource()), 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(3))));

        proc.seedGenesisIfAbsent();
        assertEquals(Wei.fromEth(3), new RocksDbWorldUpdater(state).getAccount(sender).getBalance());

        // Change the balance, then re-seed (as on restart): the marker is present, so it is a no-op.
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.getAccount(sender).setBalance(Wei.fromEth(1));
        w.commit();
        proc.seedGenesisIfAbsent();
        assertEquals("re-seeding must not reset a changed balance",
                Wei.fromEth(1), new RocksDbWorldUpdater(state).getAccount(sender).getBalance());
    }

    @Test
    public void genesis_funded_account_can_pay_gas_for_a_deploy() {
        // Closes the loop with gas settlement: a genesis-funded sender has the wei to pay for gas, so
        // its deploy succeeds. Without the on-ramp the sender is broke and the deploy would fail.
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));

        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txs.put(deploy);
        proc.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("genesis-funded deploy must succeed", 1,
                meta.getReceipt(deploy.getHash()).orElseThrow().status());
    }

    @Test
    public void genesis_is_restored_after_a_reorg_wipe() {
        // rollbackTo wipes EVM_STATE entirely; genesis must be re-seeded before replay, or the funded
        // accounts would vanish and every replayed tx would fail for lack of a gas balance.
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(2))));

        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txs.put(deploy);
        proc.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        proc.rollbackTo(0L); // unwind the only executed height back to genesis
        assertEquals("genesis balance must be restored after the wipe", Wei.fromEth(2),
                new RocksDbWorldUpdater(state).getAccount(sender).getBalance());
    }

    @Test
    public void gas_price_below_minimum_is_rejected_at_execution_without_charging() {
        // Fix: execution re-checks gasPrice >= minGasPrice (not just != 0), so a hand-crafted carrier
        // referencing an underpriced tx cannot buy near-free compute below the network floor.
        EvmConfig highFloor = new EvmConfig(EvmSpecVersion.SHANGHAI, BigInteger.valueOf(0xCAFE),
                30_000_000L, BigInteger.valueOf(1_000));
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(highFloor, state, txs, meta);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, Wei.fromEth(1));
        w.commit();

        EvmTransaction underpriced = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo); // gasPrice 1 < minGasPrice 1000
        txs.put(underpriced);
        proc.processMainBlock(List.of(ref(underpriced)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("underpriced tx must be rejected with a status-0 receipt", 0,
                meta.getReceipt(underpriced.getHash()).orElseThrow().status());
        assertEquals("a rejected tx must not be charged any gas", Wei.fromEth(1),
                new RocksDbWorldUpdater(state).getAccount(sender).getBalance());
    }

    @Test
    public void a_tx_that_cannot_afford_gas_is_rejected_without_charging_the_sender() {
        // Affordability guard: a sender that covers the value but not the gas fee gets a status-0
        // receipt and is NOT partially debited (the check returns before the upfront gas debit).
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, Wei.of(500)); // covers value 100, not 200000*1 gas
        w.commit();

        EvmTransaction tx = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(100), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txs.put(tx);
        proc.processMainBlock(List.of(ref(tx)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals(0, meta.getReceipt(tx.getHash()).orElseThrow().status());
        assertEquals("sender must not be charged when the tx is rejected", Wei.of(500),
                new RocksDbWorldUpdater(state).getAccount(sender).getBalance());
    }

    @Test
    public void gas_fee_charged_at_gasprice_with_unused_gas_refunded() {
        // 缺口2 / Path α: the sender pays exactly gasUsed * gasPrice (burned); the unused gas is
        // refunded, so the balance drop equals receipt.gasUsed * gasPrice — no more, no less.
        Wei gasPrice = Wei.of(1_000L);
        long gasLimit = 200_000L;
        EvmTransaction deploy = EvmTransaction.unsigned(0L, gasPrice, gasLimit, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txStore.put(deploy);

        BigInteger before = account(sender).getBalance().getAsBigInteger();
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        long gasUsed = metaStore.getReceipt(deploy.getHash()).orElseThrow().gasUsed();
        assertTrue("the deploy must use less than its 200k limit so the refund path is exercised",
                gasUsed < gasLimit);
        BigInteger fee = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger());
        assertEquals("sender pays exactly gasUsed * gasPrice (value is zero)",
                before.subtract(fee), account(sender).getBalance().getAsBigInteger());
    }

    @Test
    public void genesis_misconfig_changes_the_root_even_when_the_account_is_untouched() {
        // Phase 2, chain origin: two nodes disagree on account X's genesis balance, but the executed
        // tx never touches X. Phase 1 alone missed this (X's delta never appears in any height). Folding
        // the genesis-alloc digest into the chain origin makes the roots differ, catching the misconfig.
        Address x = Address.fromHexString("0x00000000000000000000000000000000000000ff");
        EvmTransaction tx = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(1), Bytes.EMPTY, CHAIN_ID).sign(key, algo);

        Bytes32 rootA = rootWithAlloc(tx, List.of(
                new GenesisAllocEntry(sender, Wei.fromEth(2)), new GenesisAllocEntry(x, Wei.fromEth(5))));
        Bytes32 rootB = rootWithAlloc(tx, List.of(
                new GenesisAllocEntry(sender, Wei.fromEth(2)), new GenesisAllocEntry(x, Wei.fromEth(9))));

        assertNotEquals("a genesis-alloc difference on an untouched account must change the root",
                rootA, rootB);
    }

    @Test
    public void the_block_height_is_folded_into_the_chained_root() {
        // Phase 2: the same tx executed as the first height at height 1 vs height 2 must produce
        // different roots — the chain commits to which height executed, not just the tx outcomes.
        EvmTransaction tx = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(1), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        List<GenesisAllocEntry> alloc = List.of(new GenesisAllocEntry(sender, Wei.fromEth(2)));

        Bytes32 atHeight1 = rootAtHeight(tx, alloc, 1L);
        Bytes32 atHeight2 = rootAtHeight(tx, alloc, 2L);

        assertNotEquals("the height must be committed in the root", atHeight1, atHeight2);
    }

    private Bytes32 rootWithAlloc(EvmTransaction tx, List<GenesisAllocEntry> alloc) {
        return rootAtHeight(tx, alloc, 1L);
    }

    private Bytes32 rootAtHeight(EvmTransaction tx, List<GenesisAllocEntry> alloc, long height) {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta, 0L, alloc);
        txs.put(tx);
        proc.processMainBlock(List.of(ref(tx)), height, 1001L, BLOCK_HASH_1);
        return meta.getHeightRecord(height).orElseThrow().stateRoot();
    }

    @Test
    public void state_root_comparison_detects_agreement_divergence_and_unknown() {
        // P2P divergence detection (Phase 2): after executing a height the node exposes its (height,
        // root) to gossip, and compares a peer's claim — a matching root AGREEs, a different root
        // DIVERGEs, and a height this node has not executed is UNKNOWN (behind / no EVM txs).
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        EvmBlockProcessor.StateRootAt latest = processor.latestExecutedStateRoot().orElseThrow();
        assertEquals(1L, latest.height());
        assertEquals(EvmBlockProcessor.RootComparison.AGREE, processor.compareStateRoot(1L, latest.root()));
        assertEquals(EvmBlockProcessor.RootComparison.DIVERGE,
                processor.compareStateRoot(1L, Bytes32.fromHexString("0x" + "99".repeat(32))));
        assertEquals(EvmBlockProcessor.RootComparison.UNKNOWN,
                processor.compareStateRoot(999L, latest.root()));
    }

    @Test
    public void deploy_then_call_persists_state_receipts_and_checkpoints() {
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt deployReceipt = metaStore.getReceipt(deploy.getHash()).orElseThrow();
        assertEquals(1, deployReceipt.status());
        assertTrue(deployReceipt.gasUsed() > 21_000L);
        Address contract = deployReceipt.contractAddress().orElseThrow();
        assertEquals(RUNTIME, account(contract).getCode());
        assertEquals(UInt256.ZERO, account(contract).getStorageValue(UInt256.ZERO));

        EvmTransaction call = storedTx(1, Optional.of(contract), Bytes.EMPTY, 100_000L);
        processor.processMainBlock(List.of(ref(call)), 2L, 1002L, BLOCK_HASH_2);

        assertEquals(1, (int) metaStore.getReceipt(call.getHash()).orElseThrow().status());
        assertEquals(UInt256.valueOf(42), account(contract).getStorageValue(UInt256.ZERO));
        assertEquals(2L, account(sender).getNonce());

        EvmMetaStore.HeightRecord rec1 = metaStore.getHeightRecord(1L).orElseThrow();
        EvmMetaStore.HeightRecord rec2 = metaStore.getHeightRecord(2L).orElseThrow();
        assertEquals(1, rec1.txCount());
        assertEquals(List.of(call.getHash()), metaStore.getTxList(2L));
        assertNotEquals("chained roots must differ", rec1.stateRoot(), rec2.stateRoot());
        assertEquals(Optional.of(2L), metaStore.highestHeight());
    }

    @Test
    public void forward_and_reverted_execution_fire_the_subscription_sink() {
        // C6: executeAndCheckpoint fires onLogs(removed=false) for a height that emitted logs; a
        // rollback re-emits each reorged-out height's logs with removed=true BEFORE the EVM_META wipe,
        // sourced from the canonical store so re-filtering reproduces exactly the delivered set.
        List<String> calls = new ArrayList<>();
        processor.setSubscriptionSink(new EvmSubscriptionSink() {
            @Override
            public void onNewMainHead(long h, Bytes32 hash, long ts) {
            }

            @Override
            public void onLogs(long h, Bytes32 hash, List<LogRecord> logs, boolean removed) {
                calls.add("h=" + h + " removed=" + removed + " n=" + logs.size());
            }
        });

        // Deploy the LOG0 contract (h1, no log) then CALL it (h2, emits one log).
        EvmTransaction deploy = storedTx(0, Optional.empty(), LOG_INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Address contract = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();
        EvmTransaction call = storedTx(1, Optional.of(contract), Bytes.EMPTY, 100_000L);
        processor.processMainBlock(List.of(ref(call)), 2L, 1002L, BLOCK_HASH_2);

        // Forward: h2 fired onLogs(removed=false) with its 1 log. (h1 has no logs -> no forward call.)
        assertTrue("forward onLogs at h2", calls.contains("h=2 removed=false n=1"));

        // Reorg to height 1: h2's log must be re-emitted with removed=true BEFORE the wipe.
        processor.rollbackTo(1);
        assertTrue("reverted onLogs at h2", calls.contains("h=2 removed=true n=1"));
    }

    @Test
    public void executeList_writes_a_height_bloom_covering_emitted_logs() {
        // Deploy the LOG0 contract at height 1 (constructor emits nothing), then CALL it at height 2 so
        // its runtime emits a log. Height 1's bloom must be all-zero (no logs); height 2's bloom must
        // cover the emitted log's address and reject an unrelated one.
        EvmTransaction deploy = storedTx(0, Optional.empty(), LOG_INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Address contract = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();

        EvmTransaction call = storedTx(1, Optional.of(contract), Bytes.EMPTY, 100_000L);
        processor.processMainBlock(List.of(ref(call)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt callReceipt = metaStore.getReceipt(call.getHash()).orElseThrow();
        assertEquals("call must succeed", 1, callReceipt.status());
        assertFalse("the LOG0 runtime must emit a log", callReceipt.logs().isEmpty());

        // Height 1 (deploy, no logs) -> present, all-zero bloom.
        Bytes bloom1 = metaStore.getHeightBloom(1).orElseThrow();
        assertTrue("no-log height -> zero bloom", bloom1.isZero());

        // Height 2 -> bloom covers the emitting contract's address, rejects an unrelated address.
        LogsBloomFilter hb2 = new LogsBloomFilter(metaStore.getHeightBloom(2).orElseThrow());
        LogFilter present = LogFilter.parse(Map.of("address", contract.toHexString()));
        LogFilter absent = LogFilter.parse(Map.of("address", "0x000000000000000000000000000000000000dead"));
        assertTrue("bloom must cover the emitting contract's address", present.couldMatch(hb2));
        assertFalse("bloom must reject an unrelated address", absent.couldMatch(hb2));
    }

    @Test
    public void plain_transfer_at_exactly_21000_gas_succeeds_and_moves_value() {
        // C1 regression: messageGas == 0 must execute as a pure value transfer, never throw out
        // of setMain. This is the single most common Ethereum transaction shape.
        Address recipient = Address.fromHexString("0x9999999999999999999999999999999999999999");
        EvmTransaction transfer = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L,
                Optional.of(recipient), Wei.of(12_345L), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txStore.put(transfer);

        processor.processMainBlock(List.of(ref(transfer)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt receipt = metaStore.getReceipt(transfer.getHash()).orElseThrow();
        assertEquals(1, receipt.status());
        assertEquals(21_000L, receipt.gasUsed());
        assertEquals(Wei.of(12_345L), account(recipient).getBalance());
        assertEquals(1L, account(sender).getNonce());
    }

    @Test
    public void zero_message_gas_call_to_contract_code_fails_cleanly() {
        // 21000 gas cannot run a single opcode: out-of-gas revert, nonce bumped, value untouched.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Address contract = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();

        EvmTransaction starved = EvmTransaction.unsigned(1L, Wei.of(1), 21_000L,
                Optional.of(contract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txStore.put(starved);
        processor.processMainBlock(List.of(ref(starved)), 2L, 1002L, BLOCK_HASH_2);

        assertEquals(0, (int) metaStore.getReceipt(starved.getHash()).orElseThrow().status());
        assertEquals("out-of-gas call still burns the nonce", 2L, account(sender).getNonce());
        assertEquals(UInt256.ZERO, account(contract).getStorageValue(UInt256.ZERO));
    }

    @Test
    public void duplicate_ref_is_not_re_executed_and_keeps_the_original_receipt() {
        // I1: a hostile link block re-referencing an executed tx must not falsify its receipt.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt original = metaStore.getReceipt(deploy.getHash()).orElseThrow();
        assertEquals(1, original.status());

        processor.processMainBlock(List.of(ref(deploy), ref(deploy)), 2L, 1002L, BLOCK_HASH_2);

        assertEquals("receipt must be first-write-wins", 1,
                (int) metaStore.getReceipt(deploy.getHash()).orElseThrow().status());
        assertTrue("a block of only duplicates checkpoints nothing", metaStore.getHeightRecord(2L).isEmpty());
    }

    @Test
    public void per_block_aggregate_gas_budget_skips_excess_refs() {
        // I2: the sum of tx gas limits per main block is capped at the block gas limit.
        EvmTransaction big = storedTx(0, Optional.empty(), INIT_CODE, 30_000_000L);
        EvmTransaction excess = storedTx(1, Optional.of(
                Address.fromHexString("0x9999999999999999999999999999999999999999")), Bytes.EMPTY, 21_000L);

        processor.processMainBlock(List.of(ref(big), ref(excess)), 1L, 1001L, BLOCK_HASH_1);

        assertTrue("first tx fills the whole budget", metaStore.getReceipt(big.getHash()).isPresent());
        assertTrue("over-budget ref is deterministically skipped, no receipt",
                metaStore.getReceipt(excess.getHash()).isEmpty());
        assertEquals(List.of(big.getHash()), metaStore.getTxList(1L));
        assertEquals("skipped ref stays executable later", 0,
                (int) new RocksDbWorldUpdater(stateSource).getAccount(sender).getNonce() - 1);
    }

    @Test
    public void rollback_erases_receipts_of_reorged_out_transactions() {
        // M2: without receipt truncation, a reorged-out deploy would keep advertising success.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmTransaction deploy2 = storedTx(1, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy2)), 2L, 1002L, BLOCK_HASH_2);

        processor.rollbackTo(1L);

        assertTrue("reorged-out receipt must vanish", metaStore.getReceipt(deploy2.getHash()).isEmpty());
        assertTrue("still-canonical receipt survives", metaStore.getReceipt(deploy.getHash()).isPresent());
    }

    @Test
    public void nonce_mismatch_yields_failed_receipt_and_no_state_change() {
        EvmTransaction badNonce = storedTx(7, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(badNonce)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt receipt = metaStore.getReceipt(badNonce.getHash()).orElseThrow();
        assertEquals(0, receipt.status());
        assertEquals(0L, receipt.gasUsed());
        assertEquals("failed validation must not burn the account nonce", 0L, account(sender).getNonce());
        assertEquals("failed txs still enter consensus history", 1,
                metaStore.getHeightRecord(1L).orElseThrow().txCount());
    }

    @Test
    public void missing_blob_stalls_the_height_then_drains_when_the_blob_arrives() {
        // I4: a referenced-but-missing blob must NOT be skipped-and-forgotten (that diverges nodes).
        // The height stalls with no checkpoint; once the blob is stored, draining executes it.
        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo); // NOT put into txStore yet
        Bytes32 ref = ref(deploy);

        processor.processMainBlock(List.of(ref), 1L, 1001L, BLOCK_HASH_1);
        assertTrue("no checkpoint while the blob is missing", metaStore.getHeightRecord(1L).isEmpty());
        assertTrue(metaStore.getReceipt(deploy.getHash()).isEmpty());
        assertEquals("height is queued, not dropped", List.of(1L), metaStore.pendingHeights());
        assertTrue(processor.isAwaitingBlob(deploy.getHash()));

        // Blob arrives (as it would over P2P); draining now finalizes the stalled height.
        txStore.put(deploy);
        processor.onBlobsAvailable();

        assertEquals(1, (int) metaStore.getReceipt(deploy.getHash()).orElseThrow().status());
        assertTrue("no longer pending", metaStore.pendingHeights().isEmpty());
        assertEquals(1, metaStore.getHeightRecord(1L).orElseThrow().txCount());
        assertEquals(RUNTIME, account(
                metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow()).getCode());
    }

    @Test
    public void pending_missing_blob_hashes_lists_only_absent_refs_across_pending_heights() {
        // I4 convergence: the retry loop asks peers for exactly the blobs pending heights still lack.
        EvmTransaction a = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);   // withheld
        EvmTransaction b = EvmTransaction.unsigned(1L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);   // withheld
        assertTrue(processor.pendingMissingBlobHashes().isEmpty());

        processor.processMainBlock(List.of(ref(a)), 1L, 1001L, BLOCK_HASH_1);
        processor.processMainBlock(List.of(ref(b)), 2L, 1002L, BLOCK_HASH_2);
        assertEquals("both pending heights' blobs are missing",
                List.of(ref(a), ref(b)), processor.pendingMissingBlobHashes());

        // Once a's blob arrives, only the still-missing b remains requested.
        txStore.put(a);
        assertEquals(List.of(ref(b)), processor.pendingMissingBlobHashes());
    }

    @Test
    public void later_heights_queue_behind_a_stalled_one_and_drain_in_order() {
        // A missing blob at height 1 must hold back height 2 even if height 2's blob is present —
        // otherwise execution order (and thus state) would differ from a node that had both blobs.
        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        // deploy's blob withheld; deploy2 (nonce 1) present.
        EvmTransaction deploy2 = storedTx(1, Optional.empty(), INIT_CODE, 200_000L);

        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        processor.processMainBlock(List.of(ref(deploy2)), 2L, 1002L, BLOCK_HASH_2);
        assertEquals("both heights queued in order", List.of(1L, 2L), metaStore.pendingHeights());
        assertTrue("height 2 must not jump ahead", metaStore.getReceipt(deploy2.getHash()).isEmpty());

        txStore.put(deploy);
        processor.onBlobsAvailable();

        assertTrue("both drained", metaStore.pendingHeights().isEmpty());
        assertEquals(1, (int) metaStore.getReceipt(deploy.getHash()).orElseThrow().status());
        assertEquals(1, (int) metaStore.getReceipt(deploy2.getHash()).orElseThrow().status());
        // Deterministic: identical to a node that had both blobs and executed 1 then 2 directly.
        assertEquals(2L, account(deploy.getSender()).getNonce());
    }

    @Test
    public void stall_then_drain_matches_direct_execution_root() {
        // Determinism proof: stalled-then-drained roots equal roots from never-stalled execution.
        EvmTransaction d1 = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        EvmTransaction d2 = EvmTransaction.unsigned(1L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);

        // Node A: both blobs present, no stall.
        txStore.put(d1);
        txStore.put(d2);
        processor.processMainBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1);
        processor.processMainBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2);
        Bytes32 directRoot1 = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();
        Bytes32 directRoot2 = metaStore.getHeightRecord(2L).orElseThrow().stateRoot();

        // Node B: same inputs, but d1 missing until after both heights are seen.
        InMemoryKVSource stateB = new InMemoryKVSource();
        EvmTxStore txB = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaB = new EvmMetaStore(new InMemoryKVSource());
        // Node B must share Node A's genesis config to reach the same roots (both fund sender at genesis).
        EvmBlockProcessor procB = new EvmBlockProcessor(EvmConfig.devnet(), stateB, txB, metaB, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        procB.seedGenesisIfAbsent();
        txB.put(d2); // d1 withheld
        procB.processMainBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1);
        procB.processMainBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2);
        txB.put(d1);
        procB.onBlobsAvailable();

        assertEquals(directRoot1, metaB.getHeightRecord(1L).orElseThrow().stateRoot());
        assertEquals(directRoot2, metaB.getHeightRecord(2L).orElseThrow().stateRoot());
    }

    @Test
    public void rollback_discards_pending_heights_above_the_fork() {
        EvmTransaction executed = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        // A distinct nonce-1 tx whose blob is withheld, so height 2 stalls.
        EvmTransaction deploy = EvmTransaction.unsigned(1L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        processor.processMainBlock(List.of(ref(executed)), 1L, 1001L, BLOCK_HASH_1);
        processor.processMainBlock(List.of(ref(deploy)), 2L, 1002L, BLOCK_HASH_2);
        assertEquals(List.of(2L), metaStore.pendingHeights());

        processor.rollbackTo(1L);

        assertTrue("pending above the fork is discarded", metaStore.pendingHeights().isEmpty());
        assertTrue(processor.isAwaitingBlob(deploy.getHash()) == false);
    }

    @Test
    public void refs_below_activation_height_do_not_execute() {
        // M1: the EVM hard-fork gate. A processor activated at height 5 ignores refs in earlier
        // main blocks even when their blobs are present.
        EvmBlockProcessor gated = new EvmBlockProcessor(EvmConfig.devnet(), stateSource, txStore,
                metaStore, 5L);
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);

        gated.processMainBlock(List.of(ref(deploy)), 4L, 1001L, BLOCK_HASH_1);
        assertTrue("pre-activation block executes nothing", metaStore.getHeightRecord(4L).isEmpty());
        assertTrue(metaStore.getReceipt(deploy.getHash()).isEmpty());

        gated.processMainBlock(List.of(ref(deploy)), 5L, 1002L, BLOCK_HASH_2);
        assertEquals(1, (int) metaStore.getReceipt(deploy.getHash()).orElseThrow().status());
    }

    @Test
    public void journalsEachHeightAndReadsHistoricalBalance() {
        // C4: the processor records a reverse-delta journal per executed height so HistoricalStateReader
        // can reconstruct a past balance. Fund SENDER at genesis, run two value transfers at heights 1
        // and 2, then read SENDER's balance as it was at height 1 — it must equal the post-height-1
        // balance, and be strictly below the genesis start (value moved + gas burned).
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        // Oversized on purpose: the test runs at heights 1-2, so any window >= 2 behaves identically and
        // pruning never triggers. One local keeps the processor and reader windows from drifting apart.
        int historyWindow = 128;
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))), journal, historyWindow);
        proc.seedGenesisIfAbsent();

        BigInteger balanceStart = balanceOf(state, sender);
        executeOneTransferAtHeight(proc, txs, 0L, 1L, BLOCK_HASH_1);
        BigInteger balanceAfter1 = balanceOf(state, sender);
        executeOneTransferAtHeight(proc, txs, 1L, 2L, BLOCK_HASH_2);

        HistoricalStateReader reader = new HistoricalStateReader(state, journal, historyWindow);
        WorldUpdater at1 = reader.worldAt(1L, 2L);
        assertEquals("historical read at height 1 must equal the post-height-1 balance",
                balanceAfter1, at1.getAccount(sender).getBalance().getAsBigInteger());
        assertTrue("each transfer must reduce the sender's balance (value + gas)",
                balanceAfter1.compareTo(balanceStart) < 0);
    }

    /** The sender's balance in {@code state}, read through a fresh root updater. */
    private static BigInteger balanceOf(InMemoryKVSource state, Address address) {
        return new RocksDbWorldUpdater(state).getAccount(address).getBalance().getAsBigInteger();
    }

    /**
     * Stores a signed value transfer (nonce {@code nonce}, value 100 wei, 21000 gas) from {@code sender}
     * and executes it as a single-tx main block at {@code height}, reusing the file's signing path so the
     * sender's balance drops by value + gas.
     */
    private void executeOneTransferAtHeight(EvmBlockProcessor proc, EvmTxStore txs, long nonce, long height,
                                            Bytes32 blockHash) {
        Address recipient = Address.fromHexString("0x00000000000000000000000000000000000000aa");
        EvmTransaction transfer = EvmTransaction.unsigned(nonce, Wei.of(1), 21_000L,
                Optional.of(recipient), Wei.of(100), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txs.put(transfer);
        proc.processMainBlock(List.of(ref(transfer)), height, 1000L + height, blockHash);
    }

    @Test
    public void rollback_wipes_and_replays_deterministically() {
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Address contract1 = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();

        EvmTransaction call = storedTx(1, Optional.of(contract1), Bytes.EMPTY, 100_000L);
        processor.processMainBlock(List.of(ref(call)), 2L, 1002L, BLOCK_HASH_2);
        Bytes32 rootAt2 = metaStore.getHeightRecord(2L).orElseThrow().stateRoot();

        EvmTransaction deploy2 = storedTx(2, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy2)), 3L, 1003L, BLOCK_HASH_3);
        Address contract2 = metaStore.getReceipt(deploy2.getHash()).orElseThrow().contractAddress().orElseThrow();
        assertEquals(RUNTIME, account(contract2).getCode());

        processor.rollbackTo(2L);

        assertTrue("meta above the fork point is gone", metaStore.getHeightRecord(3L).isEmpty());
        assertNull("the reorged-out contract must vanish", account(contract2));
        assertEquals("replayed state is intact", UInt256.valueOf(42),
                account(contract1).getStorageValue(UInt256.ZERO));
        assertEquals(2L, account(sender).getNonce());
        assertEquals("replay reproduces the identical chained root", rootAt2,
                metaStore.getHeightRecord(2L).orElseThrow().stateRoot());
    }

    // -------------------------------------------------------------------------
    // D2: dual-lookup batch expansion tests
    // -------------------------------------------------------------------------

    @Test
    public void a_batch_ref_expands_to_its_ordered_txs_in_one_block() {
        // Two funded signed txs (nonce 0 and 1) stored in txStore; a batch body is stored and
        // processMainBlock is called with the single commitment ref. Both receipts must be present
        // and getTxList(height) must equal the flat ordered list [h0, h1].
        EvmTransaction tx0 = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        EvmTransaction tx1 = storedTx(1,
                Optional.of(Address.fromHexString("0x9999999999999999999999999999999999999999")),
                Bytes.EMPTY, 21_000L);

        Bytes batchBody = EvmTxStore.encodeBatch(List.of(ref(tx0), ref(tx1)));
        Hash batchHash = txStore.putBatch(batchBody);
        Bytes32 commitRef = Bytes32.wrap(batchHash.getBytes());

        processor.processMainBlock(List.of(commitRef), 1L, 1001L, BLOCK_HASH_1);

        // Both receipts present
        assertTrue("tx0 receipt present", metaStore.getReceipt(tx0.getHash()).isPresent());
        assertTrue("tx1 receipt present", metaStore.getReceipt(tx1.getHash()).isPresent());
        // getTxList must match the batch's flat ordering
        assertEquals("getTxList equals flat expansion",
                List.of(tx0.getHash(), tx1.getHash()), metaStore.getTxList(1L));
    }

    @Test
    public void a_legacy_single_tx_ref_still_executes_via_dual_lookup() {
        // Regression pin: plain single-tx ref (no batch) must still execute normally after the
        // dual-lookup change.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);

        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("single-tx legacy ref must yield a success receipt", 1,
                metaStore.getReceipt(deploy.getHash()).orElseThrow().status());
        assertEquals(List.of(deploy.getHash()), metaStore.getTxList(1L));
    }

    @Test
    public void a_missing_batch_body_defers_and_reports_both_kinds_of_missing_hashes() {
        // Commitment computed (keccak of the body) but body NOT stored → processMainBlock must
        // defer. The commitment hash must appear in BOTH pendingMissingBatchHashes and
        // pendingMissingBlobHashes (ambiguous ref). isAwaitingBatch(hash) must return true.
        // After putBatch + onBlobsAvailable the tx receipt appears and both lists become empty.
        EvmTransaction tx = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        Bytes batchBody = EvmTxStore.encodeBatch(List.of(ref(tx)));
        Hash batchHash = Hash.hash(batchBody); // compute hash WITHOUT storing the body
        Bytes32 commitRef = Bytes32.wrap(batchHash.getBytes());

        processor.processMainBlock(List.of(commitRef), 1L, 1001L, BLOCK_HASH_1);

        assertTrue("no receipt while batch body is missing",
                metaStore.getReceipt(tx.getHash()).isEmpty());
        assertEquals("height is queued", List.of(1L), metaStore.pendingHeights());

        List<Bytes32> missingBlobs = processor.pendingMissingBlobHashes();
        List<Bytes32> missingBatches = processor.pendingMissingBatchHashes();
        assertEquals("commit ref is the only entry in pendingMissingBlobHashes",
                List.of(commitRef), missingBlobs);
        assertEquals("commit ref is the only entry in pendingMissingBatchHashes",
                List.of(commitRef), missingBatches);
        assertTrue("isAwaitingBatch(hash) true", processor.isAwaitingBatch(batchHash));

        // Body and tx blob arrive; drain resolves the stall.
        txStore.putBatch(batchBody);
        processor.onBlobsAvailable();

        assertTrue("receipt present after drain",
                metaStore.getReceipt(tx.getHash()).isPresent());
        assertTrue("pendingMissingBlobHashes empty after drain",
                processor.pendingMissingBlobHashes().isEmpty());
        assertTrue("pendingMissingBatchHashes empty after drain",
                processor.pendingMissingBatchHashes().isEmpty());
    }

    @Test
    public void a_batch_with_a_missing_member_blob_defers_until_the_blob_arrives() {
        // Batch body stored, but member tx1's blob is removed from txStore → processMainBlock
        // must stall. tx1 must be in pendingMissingBlobHashes but NOT in
        // pendingMissingBatchHashes. isAwaitingBlob(tx1) must be true. After putRaw +
        // onBlobsAvailable both receipts appear.
        EvmTransaction tx0 = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        EvmTransaction tx1 = storedTx(1,
                Optional.of(Address.fromHexString("0x9999999999999999999999999999999999999999")),
                Bytes.EMPTY, 21_000L);

        Bytes batchBody = EvmTxStore.encodeBatch(List.of(ref(tx0), ref(tx1)));
        Hash batchHash = txStore.putBatch(batchBody);
        Bytes32 commitRef = Bytes32.wrap(batchHash.getBytes());

        // Remove tx1's blob AFTER storing the batch (body present, member missing).
        txStore.remove(tx1.getHash());

        processor.processMainBlock(List.of(commitRef), 1L, 1001L, BLOCK_HASH_1);

        assertTrue("no checkpoint while member blob is missing",
                metaStore.getHeightRecord(1L).isEmpty());
        assertEquals(List.of(1L), metaStore.pendingHeights());

        List<Bytes32> missingBlobs = processor.pendingMissingBlobHashes();
        List<Bytes32> missingBatches = processor.pendingMissingBatchHashes();
        assertTrue("tx1 in pendingMissingBlobHashes", missingBlobs.contains(ref(tx1)));
        assertFalse("tx1 NOT in pendingMissingBatchHashes", missingBatches.contains(ref(tx1)));
        assertTrue("isAwaitingBlob(tx1) true", processor.isAwaitingBlob(tx1.getHash()));

        // Restore the missing member blob and drain.
        txStore.putRaw(tx1.getHash(), tx1.getRawRlp());
        processor.onBlobsAvailable();

        assertTrue("both receipts present after drain",
                metaStore.getReceipt(tx0.getHash()).isPresent());
        assertTrue("both receipts present after drain",
                metaStore.getReceipt(tx1.getHash()).isPresent());
        assertTrue("no longer pending", metaStore.pendingHeights().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Phase 3a: bridge deposits mint before the height's txs, replay-covered via EVM_META 0x06
    // -------------------------------------------------------------------------

    @Test
    public void deposit_mints_before_the_heights_txs_so_a_funded_sender_can_spend_same_height() {
        // Sender S starts with ZERO EVM balance (no genesis alloc). ONE processMainBlock call carries
        // BOTH the deposit that funds S and a transfer FROM S: the mint must land before the height's
        // first tx on the SAME root updater, or the transfer fails "balance below value + gas fee"
        // with a status-0 receipt and no value movement.
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta);
        Address recipient = Address.fromHexString("0x00000000000000000000000000000000000000cc");
        EvmTransaction transfer = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L,
                Optional.of(recipient), Wei.of(1234), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txs.put(transfer);

        proc.processMainBlock(List.of(ref(transfer)), 1L, 1001L, BLOCK_HASH_1,
                List.of(new BridgeDeposit(sender, 1_000_000_000L))); // 1e9 nano -> 1e18 wei

        assertEquals("the same-height transfer must spend the deposited funds", 1,
                meta.getReceipt(transfer.getHash()).orElseThrow().status());
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(state);
        assertEquals("sender keeps mint - value - gas (21000-gas transfer: refund is zero)",
                BigInteger.TEN.pow(18).subtract(BigInteger.valueOf(1234L + 21_000L)),
                world.getAccount(sender).getBalance().getAsBigInteger());
        assertEquals("recipient receives the transferred value",
                Wei.of(1234L), world.getAccount(recipient).getBalance());
    }

    @Test
    public void deposit_only_height_checkpoints_and_advances_the_chained_root() {
        // A main block with deposits but NO tx refs must still checkpoint (spec §2.2): the mint is a
        // state change, so skipping the height would fork the chained root against a node that
        // checkpoints it.
        Address a = Address.fromHexString("0x00000000000000000000000000000000000000a1");
        processor.processMainBlock(List.of(), 1L, 1001L, BLOCK_HASH_1,
                List.of(new BridgeDeposit(a, 5L)));

        assertEquals("5 nano mints 5e9 wei", Wei.of(5_000_000_000L), account(a).getBalance());
        EvmMetaStore.HeightRecord rec1 = metaStore.getHeightRecord(1L).orElseThrow();
        assertEquals("deposit-only height checkpoints with zero txs", 0, rec1.txCount());
        assertEquals(Optional.of(1L), metaStore.highestHeight());

        // A second deposit-only height must chain onto the first: its root differs, proving each
        // deposit-only height advances the chained root rather than repeating it.
        processor.processMainBlock(List.of(), 2L, 1002L, BLOCK_HASH_2,
                List.of(new BridgeDeposit(a, 5L)));
        assertNotEquals("each deposit-only height advances the chained root", rec1.stateRoot(),
                metaStore.getHeightRecord(2L).orElseThrow().stateRoot());
    }

    @Test
    public void deposit_replay_is_byte_identical_after_rollback() {
        // Mirror of rollback_wipes_and_replays_deterministically with one new ingredient: an executed
        // height carries a deposit. executeList reads deposits from EVM_META 0x06, so the replay
        // re-mints without any extra plumbing and the chained root matches byte-for-byte.
        Address depositee = Address.fromHexString("0x00000000000000000000000000000000000000dd");
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Address contract1 = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();

        EvmTransaction call = storedTx(1, Optional.of(contract1), Bytes.EMPTY, 100_000L);
        processor.processMainBlock(List.of(ref(call)), 2L, 1002L, BLOCK_HASH_2,
                List.of(new BridgeDeposit(depositee, 42L)));
        Bytes32 rootAt2 = metaStore.getHeightRecord(2L).orElseThrow().stateRoot();
        assertEquals("42 nano mints 42e9 wei", Wei.of(42_000_000_000L), account(depositee).getBalance());

        EvmTransaction deploy2 = storedTx(2, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy2)), 3L, 1003L, BLOCK_HASH_3);

        processor.rollbackTo(2L);

        assertEquals("replay must re-mint the height-2 deposit from EVM_META 0x06",
                Wei.of(42_000_000_000L), account(depositee).getBalance());
        assertEquals("replay reproduces the identical chained root", rootAt2,
                metaStore.getHeightRecord(2L).orElseThrow().stateRoot());
        assertEquals("replayed contract state is intact", UInt256.valueOf(42),
                account(contract1).getStorageValue(UInt256.ZERO));
    }

    @Test
    public void rollback_wipes_a_deposited_balance() {
        // Reorging out a deposit-carrying height must unwind the mint: the wipe-and-replay resets
        // EVM_STATE and removeAbove sweeps the 0x06 record, so neither the balance nor the deposit
        // record survives below-the-fork replay.
        Address b = Address.fromHexString("0x00000000000000000000000000000000000000ee");
        processor.processMainBlock(List.of(), 1L, 1001L, BLOCK_HASH_1,
                List.of(new BridgeDeposit(b, 7L)));
        assertEquals("pre-condition: the deposit minted", Wei.of(7_000_000_000L), account(b).getBalance());

        processor.rollbackTo(0L);

        assertNull("the deposited balance must be wiped with the reorg", account(b));
        assertTrue("removeAbove must sweep the 0x06 deposit record", metaStore.getDeposits(1L).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Defect 1: type-2 (EIP-1559) execution behind evm.type2ActivationHeight
    // -------------------------------------------------------------------------

    @Test
    public void type2_executes_post_activation_and_charges_effective_price() {
        // Devnet default config: type-2 active from height 0. The sender must be charged at the
        // EFFECTIVE price min(priority 1, maxFee 3) = 1, not the fee cap — a feeCap-debit bug would
        // charge 3 per gas and fail the exact balance assertion (21000-gas transfer: refund is zero,
        // so the debit itself is what the balance drop pins).
        Address recipient = Address.fromHexString("0x00000000000000000000000000000000000000bb");
        EvmTransaction transfer = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(3), 21_000L,
                Optional.of(recipient), Wei.of(12_345L), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        txStore.put(transfer);

        BigInteger before = account(sender).getBalance().getAsBigInteger();
        processor.processMainBlock(List.of(ref(transfer)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt receipt = metaStore.getReceipt(transfer.getHash()).orElseThrow();
        assertEquals("type-2 transfer must succeed post-activation", 1, receipt.status());
        assertEquals("a plain transfer burns exactly the 21000 intrinsic gas", 21_000L, receipt.gasUsed());
        BigInteger expectedDrop = BigInteger.valueOf(12_345L)
                .add(BigInteger.valueOf(receipt.gasUsed())); // + gasUsed * effectiveGasPrice(1)
        assertEquals("sender pays exactly value + gasUsed * effectiveGasPrice(1)",
                before.subtract(expectedDrop), account(sender).getBalance().getAsBigInteger());
        assertEquals("recipient receives the transferred value",
                Wei.of(12_345L), account(recipient).getBalance());
    }

    @Test
    public void type2_balance_check_uses_fee_cap() {
        // Ethereum's affordability rule validates against value + feeCap * gasLimit — not the
        // effective price — so a sender that could afford the effective charge but not the cap is
        // rejected with a status-0 receipt and no debit at all.
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta);
        long gasLimit = 21_000L;
        long value = 100L;
        // Covers value + effective(1) * gasLimit + 1 wei, but NOT value + feeCap(1000) * gasLimit.
        Wei funded = Wei.of(value + gasLimit + 1);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, funded);
        w.commit();

        EvmTransaction tx = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(1_000L), gasLimit,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(value), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        txs.put(tx);
        proc.processMainBlock(List.of(ref(tx)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt receipt = meta.getReceipt(tx.getHash()).orElseThrow();
        assertEquals("unaffordable at the fee cap -> status-0 receipt", 0, receipt.status());
        assertEquals("rejected before execution -> zero gas used", 0L, receipt.gasUsed());
        assertEquals("sender must not be debited when the fee-cap check rejects the tx",
                funded, new RocksDbWorldUpdater(state).getAccount(sender).getBalance());
    }

    @Test
    public void type2_before_activation_is_byte_identical_to_an_undecodable_blob() {
        // The upgrade-window safety property: pre-activation, an upgraded node's "type-2 before
        // activation" receipt must equal — field for field, hence byte for byte — the "undecodable
        // blob" receipt a non-upgraded node produces for the same ref (receipts carry no reason
        // string), so the chained roots stay identical across upgraded and non-upgraded nodes.
        EvmConfig gated = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE, Long.MAX_VALUE);
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(gated, state, txs, meta);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, Wei.fromEth(1)); // the type-2 WOULD execute if it were activated
        w.commit();

        EvmTransaction type2 = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(3), 21_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(100), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        txs.put(type2);
        Bytes garbage = Bytes.fromHexString("0xdeadbeef");
        Hash garbageHash = Hash.hash(garbage);
        txs.putRaw(garbageHash, garbage);

        proc.processMainBlock(List.of(ref(type2), Bytes32.wrap(garbageHash.getBytes())), 1L, 1001L,
                BLOCK_HASH_1);

        EvmReceipt gatedReceipt = meta.getReceipt(type2.getHash()).orElseThrow();
        EvmReceipt undecodableReceipt = meta.getReceipt(garbageHash).orElseThrow();
        assertEquals("pre-activation type-2 must fail validation", 0, gatedReceipt.status());
        assertEquals("garbage blob must fail validation", 0, undecodableReceipt.status());
        assertEquals("pre-activation type-2 receipt must be byte-identical to the undecodable-blob "
                + "receipt (record equality = field equality)", undecodableReceipt, gatedReceipt);
    }

    @Test
    public void pre_activation_type2_does_not_consume_block_gas_budget() {
        // Spec §4 completion: a non-upgraded node cannot decode a type-2 blob at all, so its budget
        // peek reads -1 and deducts nothing. An upgraded node must match: if its peek decoded the
        // pre-activation type-2 and deducted its 21000 gas limit, the 30000 block budget would drop
        // to 9000 and the legacy ref behind it would be SKIPPED (no receipt, no digest entry) while
        // a non-upgraded node executes it — splitting the chained root during the upgrade window.
        EvmConfig gated = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                30_000L, EvmConfig.DEFAULT_MIN_GAS_PRICE, Long.MAX_VALUE);
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(gated, state, txs, meta);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, Wei.fromEth(1));
        w.commit();

        // The gated type-2 fails validation BEFORE the nonce check (no nonce burn), so the legacy
        // transfer behind it still executes at account nonce 0.
        EvmTransaction type2 = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(3), 21_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000aa")),
                Wei.of(100), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        EvmTransaction legacy = EvmTransaction.unsigned(0L, Wei.of(1), 21_000L,
                Optional.of(Address.fromHexString("0x00000000000000000000000000000000000000bb")),
                Wei.of(100), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txs.put(type2);
        txs.put(legacy);

        proc.processMainBlock(List.of(ref(type2), ref(legacy)), 1L, 1001L, BLOCK_HASH_1);

        EvmReceipt legacyReceipt = meta.getReceipt(legacy.getHash()).orElseThrow();
        assertEquals("the legacy tx behind the gated type-2 must execute — the type-2 must not "
                + "consume any of the 30000 block gas budget", 1, legacyReceipt.status());
        EvmReceipt type2Receipt = meta.getReceipt(type2.getHash()).orElseThrow();
        assertEquals("pre-activation type-2 keeps the status-0 undecodable-path receipt", 0,
                type2Receipt.status());
        assertEquals("pre-activation type-2 burns zero gas", 0L, type2Receipt.gasUsed());
    }

    @Test
    public void type2_gate_flips_exactly_at_the_activation_height() {
        // Pins the boundary predicate `height < type2ActivationHeight` with gate = 3: height 2 is
        // the LAST gated height, height 3 the FIRST live one. A `<=` mutation would gate height 3
        // and fail the second assertion; an always-open mutation would execute height 2 and fail
        // the first.
        EvmConfig gated = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE, 3L);
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(gated, state, txs, meta);
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
        w.createAccount(sender, 0L, Wei.fromEth(1));
        w.commit();
        Address recipient = Address.fromHexString("0x00000000000000000000000000000000000000aa");

        EvmTransaction gatedTx = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(3), 21_000L,
                Optional.of(recipient), Wei.of(100), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        txs.put(gatedTx);
        proc.processMainBlock(List.of(ref(gatedTx)), 2L, 1001L, BLOCK_HASH_1);
        EvmReceipt gatedReceipt = meta.getReceipt(gatedTx.getHash()).orElseThrow();
        assertEquals("height 2 (last pre-activation height) must gate the type-2 to status 0",
                0, gatedReceipt.status());
        assertEquals("gated type-2 burns zero gas", 0L, gatedReceipt.gasUsed());

        // The gated tx never advanced the account nonce, so the height-3 tx is nonce 0 as well.
        // A DIFFERENT value makes the hash differ — re-signing identical fields would reproduce
        // the height-2 hash, and the receipt dedup would skip it instead of executing it.
        EvmTransaction liveTx = EvmTransaction.unsignedType2(0L, Wei.of(1), Wei.of(3), 21_000L,
                Optional.of(recipient), Wei.of(101), Bytes.EMPTY, List.of(), CHAIN_ID).sign(key, algo);
        txs.put(liveTx);
        proc.processMainBlock(List.of(ref(liveTx)), 3L, 1002L, BLOCK_HASH_2);
        EvmReceipt liveReceipt = meta.getReceipt(liveTx.getHash()).orElseThrow();
        assertEquals("height 3 (the activation height itself) must execute the type-2",
                1, liveReceipt.status());
    }
}
