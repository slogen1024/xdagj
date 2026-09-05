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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.bridge.BridgeContract;
import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.bridge.BridgeWithdrawal;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.EvmStateSchema;
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
    public void process_main_block_returns_the_net_fee_actually_settled() {
        // A successful deploy: the returned net fee equals gasUsed * gasPrice (the wei the sender lost).
        Wei gasPrice = Wei.of(1_000L);
        long gasLimit = 200_000L;
        EvmTransaction deploy = EvmTransaction.unsigned(0L, gasPrice, gasLimit, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txStore.put(deploy);

        BigInteger netFee = processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        long gasUsed = metaStore.getReceipt(deploy.getHash()).orElseThrow().gasUsed();
        assertTrue("the deploy must use less than its limit so the refund path runs", gasUsed < gasLimit);
        assertEquals("returned net fee == gasUsed * gasPrice",
                BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger()), netFee);
    }

    @Test
    public void process_main_block_sums_net_fees_across_txs_in_wei() {
        // G3-T1 Test A: two txs in one main-block height; the returned BigInteger must equal f1+f2 in
        // wei, proving that processMainBlock accumulates each tx's (gasUsed * gasPrice) contribution
        // rather than reporting only the first or the last settled fee.
        Wei gasPrice = Wei.of(1_000L);
        long gasLimit = 200_000L;
        EvmTransaction tx0 = EvmTransaction.unsigned(0L, gasPrice, gasLimit, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        EvmTransaction tx1 = EvmTransaction.unsigned(1L, gasPrice, gasLimit, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txStore.put(tx0);
        txStore.put(tx1);

        BigInteger netFee = processor.processMainBlock(List.of(ref(tx0), ref(tx1)), 1L, 1001L, BLOCK_HASH_1);

        long g0 = metaStore.getReceipt(tx0.getHash()).orElseThrow().gasUsed();
        long g1 = metaStore.getReceipt(tx1.getHash()).orElseThrow().gasUsed();
        assertEquals("tx0 must succeed", 1, metaStore.getReceipt(tx0.getHash()).orElseThrow().status());
        assertEquals("tx1 must succeed", 1, metaStore.getReceipt(tx1.getHash()).orElseThrow().status());
        BigInteger expected = BigInteger.valueOf(g0).add(BigInteger.valueOf(g1))
                .multiply(gasPrice.getAsBigInteger());
        assertEquals("returned fee is the wei sum of both txs", expected, netFee);
    }

    @Test
    public void process_main_block_returns_zero_net_fee_for_a_validation_failure() {
        // Wrong chain id -> validationFailure BEFORE any debit -> zero net fee, even though a status-0
        // receipt is recorded.
        Wei gasPrice = Wei.of(1_000L);
        EvmTransaction wrongChain = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID.add(BigInteger.ONE)).sign(key, algo);
        txStore.put(wrongChain);

        BigInteger netFee = processor.processMainBlock(List.of(ref(wrongChain)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("a pre-debit validation failure settles no fee", BigInteger.ZERO, netFee);
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

    /** A second node over its own stores, funding the same genesis sender so roots are comparable. */
    private static EvmBlockProcessor freshNode(EvmTxStore txs, EvmMetaStore meta, InMemoryKVSource state,
                                               Address sender) {
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txs, meta, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        proc.seedGenesisIfAbsent();
        return proc;
    }

    @Test
    public void a_skipped_height_waits_behind_a_blob_deferred_height() {
        // Audit round 2, C2 (lag=2): height 1 carries d1, height 2 carries d2. Block 3 commits daSkip
        // for height 2. Node B lacks d1's blob when height 1 matures, so height 1 is pending; the skip
        // of height 2 must NOT checkpoint ahead of it (that chained root(2) from root(0) and later
        // root(1) from root(2)). It must queue behind height 1 and drain in order.
        EvmTransaction d1 = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        EvmTransaction d2 = EvmTransaction.unsigned(1L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        long lag = 2L;

        // Node A: has both blobs; never stalls.
        txStore.put(d1);
        txStore.put(d2);
        processor.processConfirmedBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        processor.processConfirmedBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, false);
        processor.processConfirmedBlock(List.of(), 3L, 1003L, BLOCK_HASH_3, List.of(), lag, true);
        Bytes32 rootA1 = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();
        Bytes32 rootA2 = metaStore.getHeightRecord(2L).orElseThrow().stateRoot();
        assertTrue("A executed d1", metaStore.getReceipt(d1.getHash()).isPresent());
        assertTrue("A skipped d2", metaStore.getReceipt(d2.getHash()).isEmpty());

        // Node B: d1 withheld until after block 3.
        InMemoryKVSource stateB = new InMemoryKVSource();
        EvmTxStore txB = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaB = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor procB = freshNode(txB, metaB, stateB, sender);
        txB.put(d2);
        procB.processConfirmedBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        procB.processConfirmedBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, false);
        assertEquals("height 1 is blob-deferred on B", List.of(1L), metaB.pendingHeights());
        procB.processConfirmedBlock(List.of(), 3L, 1003L, BLOCK_HASH_3, List.of(), lag, true);

        assertTrue("the skip must not checkpoint ahead of the deferred height",
                metaB.getHeightRecord(2L).isEmpty());
        assertTrue("height 2 is skip-marked", metaB.isSkipped(2L));
        assertEquals("height 2 queues behind height 1", List.of(1L, 2L), metaB.pendingHeights());

        txB.put(d1);
        procB.onBlobsAvailable();

        assertTrue("both drained", metaB.pendingHeights().isEmpty());
        assertEquals(rootA1, metaB.getHeightRecord(1L).orElseThrow().stateRoot());
        assertEquals(rootA2, metaB.getHeightRecord(2L).orElseThrow().stateRoot());
        assertTrue(metaB.getReceipt(d2.getHash()).isEmpty());
    }

    @Test
    public void a_shallow_reorg_reopens_the_matured_height_for_the_replacement_block() {
        // Audit round 2, C1 (lag=2): block 3 decides height 2 (daSkip=true -> skipped). When block 3 is
        // unwound by a 1-deep reorg, height 2's outcome must be undone and re-decided by the replacement
        // block 3' (daSkip=false -> executed). Pre-fix, rollbackTo(2) kept the skip and the replacement's
        // bit was ignored, so nodes that saw block 3 diverged forever from nodes that did not.
        EvmTransaction d1 = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        EvmTransaction d2 = EvmTransaction.unsigned(1L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        long lag = 2L;
        txStore.put(d1);
        txStore.put(d2);
        processor.processConfirmedBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        processor.processConfirmedBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, false);
        processor.processConfirmedBlock(List.of(), 3L, 1003L, BLOCK_HASH_3, List.of(), lag, true);
        assertTrue("height 2 was skipped by block 3", metaStore.isSkipped(2L));
        assertTrue(metaStore.getHeightRecord(2L).isPresent());
        assertTrue(metaStore.getReceipt(d2.getHash()).isEmpty());
        assertTrue("matured entries leave the live buffer", metaStore.getMaturityEntry(2L).isEmpty());

        // Reorg: block 3 is unwound (lowest unwound main height = 3).
        processor.rollbackForReorg(3L, lag);

        assertTrue("height 1 (decided by canonical block 2) stays executed", metaStore.getHeightRecord(1L).isPresent());
        assertTrue(metaStore.getReceipt(d1.getHash()).isPresent());
        assertTrue("height 2's outcome is undone", metaStore.getHeightRecord(2L).isEmpty());
        assertFalse("its skip marker is gone", metaStore.isSkipped(2L));
        EvmMetaStore.MaturityEntry reopened = metaStore.getMaturityEntry(2L).orElseThrow();
        assertEquals("height 2 is buffered again with its original payload", List.of(ref(d2)), reopened.refs());
        assertEquals(BLOCK_HASH_2, reopened.blockHash());

        // The replacement block 3' commits daSkip=false -> height 2 executes.
        processor.processConfirmedBlock(List.of(), 3L, 1003L, BLOCK_HASH_3, List.of(), lag, false);
        assertEquals(1, (int) metaStore.getReceipt(d2.getHash()).orElseThrow().status());

        // Same roots as a node that only ever saw the replacement chain.
        InMemoryKVSource stateC = new InMemoryKVSource();
        EvmTxStore txC = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaC = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor procC = freshNode(txC, metaC, stateC, sender);
        txC.put(d1);
        txC.put(d2);
        procC.processConfirmedBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        procC.processConfirmedBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, false);
        procC.processConfirmedBlock(List.of(), 3L, 1003L, BLOCK_HASH_3, List.of(), lag, false);
        assertEquals(metaC.getHeightRecord(1L).orElseThrow().stateRoot(), metaStore.getHeightRecord(1L).orElseThrow().stateRoot());
        assertEquals(metaC.getHeightRecord(2L).orElseThrow().stateRoot(), metaStore.getHeightRecord(2L).orElseThrow().stateRoot());
        assertEquals(procC.chainedRootAt(2L), processor.chainedRootAt(2L));
    }

    @Test
    public void at_lag_one_the_reorg_rollback_is_the_legacy_rollbackTo() {
        EvmTransaction d1 = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        EvmTransaction d2 = storedTx(1, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(d1)), 1L, 1001L, BLOCK_HASH_1, List.of(), 1L, false);
        processor.processConfirmedBlock(List.of(ref(d2)), 2L, 1002L, BLOCK_HASH_2, List.of(), 1L, false);
        assertTrue(metaStore.getHeightRecord(2L).isPresent());

        processor.rollbackForReorg(2L, 1L); // == rollbackTo(1): the unwound block decided only itself

        assertTrue(metaStore.getHeightRecord(1L).isPresent());
        assertTrue(metaStore.getHeightRecord(2L).isEmpty());
        assertEquals("nothing is re-opened at lag 1", List.of(), metaStore.maturityHeights());
        assertTrue(metaStore.getReceipt(d2.getHash()).isEmpty());
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

    @Test
    public void chained_root_at_returns_the_root_as_of_a_height() {
        Bytes32 root5 = Bytes32.fromHexString("0x" + "aa".repeat(32));
        Bytes32 root9 = Bytes32.fromHexString("0x" + "bb".repeat(32));
        metaStore.putHeightRecord(5L, root5, Bytes32.ZERO, 1, 1L);
        metaStore.putHeightRecord(9L, root9, Bytes32.ZERO, 1, 2L);

        assertEquals(root9, processor.chainedRootAt(9L));   // exact top
        assertEquals(root9, processor.chainedRootAt(20L));  // above all -> latest
        assertEquals(root5, processor.chainedRootAt(8L));   // between -> floor is height 5
        assertEquals(root5, processor.chainedRootAt(5L));   // exact lower

        // Below the first checkpoint: the genesis origin root (deterministic, non-null, not a seeded root).
        Bytes32 belowAll = processor.chainedRootAt(4L);
        assertNotNull(belowAll);
        assertEquals("genesis default is stable", belowAll, processor.chainedRootAt(0L));
        assertNotEquals(root5, belowAll);
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

        // A second deposit-only height must chain onto the first: its root differs, checking that the
        // checkpoint chain advances height over height. (The byte-level delta coverage of the mint is
        // pinned by deposit_replay_is_byte_identical_after_rollback, not by this root inequality.)
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
    public void deferred_height_still_mints_its_deposits_when_it_drains() {
        // Deposits are persisted to EVM_META 0x06 BEFORE any defer (spec §2.2): a height stalled on a
        // missing blob mints nothing while pending, then mints when onBlobsAvailable drains it.
        Address a = Address.fromHexString("0x00000000000000000000000000000000000000a7");
        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo); // blob NOT stored -> the height defers
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1,
                List.of(new BridgeDeposit(a, 7L)));

        assertNull("no mint while the height is deferred", account(a));
        assertTrue("no checkpoint while the height is deferred", metaStore.getHeightRecord(1L).isEmpty());
        assertEquals("height is queued, not dropped", List.of(1L), metaStore.pendingHeights());

        // The blob arrives (as it would over P2P); draining executes the height incl. its mint.
        txStore.putRaw(deploy.getHash(), deploy.getRawRlp());
        processor.onBlobsAvailable();

        assertEquals("the drained height mints its persisted 7-nano deposit", Wei.of(7_000_000_000L),
                account(a).getBalance());
        assertTrue("the drained height checkpoints", metaStore.getHeightRecord(1L).isPresent());
        assertTrue("no longer pending", metaStore.pendingHeights().isEmpty());
    }

    @Test
    public void deposit_only_height_survives_replay_of_higher_rollback() {
        // Pins that a deposit-only height stays in the replay schedule: its putTxList(h, List.of())
        // checkpoint is an EMPTY-VALUE key, which must survive the KV layer, or rollbackTo would
        // silently drop the height (and its mint) from the wipe-and-replay.
        Address b = Address.fromHexString("0x00000000000000000000000000000000000000b2");
        processor.processMainBlock(List.of(), 1L, 1001L, BLOCK_HASH_1,
                List.of(new BridgeDeposit(b, 42L)));
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processMainBlock(List.of(ref(deploy)), 2L, 1002L, BLOCK_HASH_2);
        Bytes32 rootBefore = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();

        processor.rollbackTo(1L);

        assertEquals("height 1 must replay, re-minting its deposit from EVM_META 0x06",
                Wei.of(42_000_000_000L), account(b).getBalance());
        assertEquals("the height-1 checkpoint root is unchanged by the rollback", rootBefore,
                metaStore.getHeightRecord(1L).orElseThrow().stateRoot());
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

    // -------------------------------------------------------------------------
    // Phase 3b Task 4: bridge contract seeding + burn scan (spec §3.1 / §3.2)
    // -------------------------------------------------------------------------

    /** Bridge scheduled from height 0 (the devnet spec value); everything else = devnet defaults. */
    private static EvmConfig bridgeActiveConfig() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE,
                EvmConfig.DEFAULT_TYPE2_ACTIVATION_HEIGHT, 0L);
    }

    private static final Bytes NATIVE_TARGET_20 =
            Bytes.fromHexString("0x1111111111111111111111111111111111111111");

    /** withdraw(bytes20) calldata: selector ‖ target20 ‖ 12 zero bytes (bytes20 is left-aligned). */
    private static Bytes withdrawCalldata(Bytes target20) {
        return Bytes.concatenate(BridgeContract.WITHDRAW_SELECTOR, target20, Bytes.repeat((byte) 0, 12));
    }

    /** A bridge-active processor over {@code state}/{@code txs}/{@code meta} with the sender funded. */
    private EvmBlockProcessor seededBridgeProcessor(InMemoryKVSource state, EvmTxStore txs,
                                                    EvmMetaStore meta) {
        EvmBlockProcessor proc = new EvmBlockProcessor(bridgeActiveConfig(), state, txs, meta, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        proc.seedGenesisIfAbsent();
        proc.seedBridgeContractIfAbsent();
        return proc;
    }

    private EvmTransaction withdrawTx(long nonce, Wei value) {
        return EvmTransaction.unsigned(nonce, Wei.of(1), 100_000L, Optional.of(BridgeContract.ADDRESS),
                value, withdrawCalldata(NATIVE_TARGET_20), CHAIN_ID).sign(key, algo);
    }

    @Test
    public void bridge_contract_is_seeded_once_when_scheduled_and_never_when_not() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmBlockProcessor scheduled = new EvmBlockProcessor(bridgeActiveConfig(), state,
                new EvmTxStore(new InMemoryKVSource()), new EvmMetaStore(new InMemoryKVSource()));
        scheduled.seedBridgeContractIfAbsent();
        assertEquals("a scheduled bridge seeds the pinned runtime bytecode at the protocol address",
                BridgeContract.RUNTIME_BYTECODE,
                new RocksDbWorldUpdater(state).get(BridgeContract.ADDRESS).getCode());

        // No-op proof: remove the account record out-of-band; the marker must stop a re-seed.
        state.delete(EvmStateSchema.accountKey(BridgeContract.ADDRESS));
        scheduled.seedBridgeContractIfAbsent();
        assertNull("a second seed call must be a marker-guarded no-op",
                new RocksDbWorldUpdater(state).get(BridgeContract.ADDRESS));

        InMemoryKVSource unscheduledState = new InMemoryKVSource();
        EvmBlockProcessor unscheduled = new EvmBlockProcessor(EvmConfig.devnet(), unscheduledState,
                new EvmTxStore(new InMemoryKVSource()), new EvmMetaStore(new InMemoryKVSource()));
        unscheduled.seedBridgeContractIfAbsent();
        assertNull("an unscheduled bridge must not plant contract code into the world state",
                new RocksDbWorldUpdater(unscheduledState).get(BridgeContract.ADDRESS));
        assertNull("an unscheduled bridge must not write the seeded marker either",
                unscheduledState.get(EvmStateSchema.bridgeContractMarkerKey()));
    }

    @Test
    public void a_withdraw_call_records_the_burn_in_evm_meta() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = seededBridgeProcessor(state, txs, meta);

        EvmTransaction withdraw = withdrawTx(0L, Wei.of(5_000_000_000L)); // 5e9 wei = 5 nano exactly
        txs.put(withdraw);
        proc.processMainBlock(List.of(ref(withdraw)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("the withdraw call must execute cleanly on the pinned bytecode", 1,
                meta.getReceipt(withdraw.getHash()).orElseThrow().status());
        assertEquals("the burn scan must record (nativeTarget20, amountNano) from the Withdrawal event",
                List.of(new BridgeWithdrawal(NATIVE_TARGET_20, 5L)), meta.getWithdrawals(1L));
        assertEquals("the burned wei stays on the contract (burned-in-place audit balance)",
                Wei.of(5_000_000_000L),
                new RocksDbWorldUpdater(state).getAccount(BridgeContract.ADDRESS).getBalance());
    }

    @Test
    public void dust_and_zero_value_withdrawals_revert_and_record_nothing() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = seededBridgeProcessor(state, txs, meta);

        // 1_500_000_001 wei is not a whole number of nano and zero value trips the same require:
        // both revert (status 0), so there is no Withdrawal event and nothing to record. The dust
        // tx still burns the sender nonce (Ethereum revert semantics), so the second tx is nonce 1.
        EvmTransaction dust = withdrawTx(0L, Wei.of(1_500_000_001L));
        EvmTransaction zero = withdrawTx(1L, Wei.ZERO);
        txs.put(dust);
        txs.put(zero);
        proc.processMainBlock(List.of(ref(dust), ref(zero)), 1L, 1001L, BLOCK_HASH_1);

        assertEquals("an indivisible (dust) value must revert", 0,
                meta.getReceipt(dust.getHash()).orElseThrow().status());
        assertEquals("a zero value must revert", 0,
                meta.getReceipt(zero.getHash()).orElseThrow().status());
        assertTrue("reverted withdrawals must record no burns", meta.getWithdrawals(1L).isEmpty());
    }

    @Test
    public void withdrawal_records_regenerate_on_replay() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = seededBridgeProcessor(state, txs, meta);

        EvmTransaction withdraw = withdrawTx(0L, Wei.of(5_000_000_000L));
        txs.put(withdraw);
        proc.processMainBlock(List.of(ref(withdraw)), 1L, 1001L, BLOCK_HASH_1);
        List<BridgeWithdrawal> recorded = meta.getWithdrawals(1L);
        assertEquals("pre-condition: the live execution recorded the burn",
                List.of(new BridgeWithdrawal(NATIVE_TARGET_20, 5L)), recorded);

        proc.rollbackTo(0L);
        assertTrue("the reorg sweep must remove the height-1 burn record",
                meta.getWithdrawals(1L).isEmpty());

        // The blob is still stored, so re-confirming the same main block re-executes the withdraw
        // (its receipt was swept with the rollback) and must regenerate the identical record.
        proc.processMainBlock(List.of(ref(withdraw)), 1L, 1001L, BLOCK_HASH_1);
        assertEquals("re-execution must regenerate the identical burn record", recorded,
                meta.getWithdrawals(1L));
    }

    // -------------------------------------------------------------------------
    // EIP-3529 storage-refund cap helper (G3-T2)
    // -------------------------------------------------------------------------

    @Test
    public void eip3529_cap_binds_when_refund_exceeds_a_fifth_of_gas_used() {
        // rawRefund 50k > 100k/5 = 20k -> capped to 20k
        assertEquals(20_000L, EvmBlockProcessor.cappedStorageRefund(100_000L, 50_000L, 5L));
    }

    @Test
    public void eip3529_cap_passes_a_small_refund_through() {
        assertEquals(4_800L, EvmBlockProcessor.cappedStorageRefund(100_000L, 4_800L, 5L));
    }

    @Test
    public void eip3529_cap_is_zero_for_no_refund_or_no_gas() {
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(100_000L, 0L, 5L));
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(0L, 4_800L, 5L));
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(100_000L, -1L, 5L));
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(-100_000L, 4_800L, 5L)); // negative gross
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(100_000L, 4_800L, 0L));  // zero quotient, no div-by-zero
        assertEquals(0L, EvmBlockProcessor.cappedStorageRefund(100_000L, 4_800L, -1L)); // negative quotient
    }

    @Test
    public void eip3529_activation_height_defaults_active_on_devnet_and_roundtrips() {
        assertEquals("devnet factory activates EIP-3529 at height 0",
                0L, EvmConfig.devnet().eip3529ActivationHeight());
        EvmConfig scheduled = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                30_000_000L, BigInteger.ONE, 0L, Long.MAX_VALUE, 123L);
        assertEquals(123L, scheduled.eip3529ActivationHeight());
    }

    // -------------------------------------------------------------------------
    // EIP-3529 net-gas integration (G3-T4): gated refund in executeOne
    // -------------------------------------------------------------------------

    /**
     * Initcode: constructor SSTOREs slot0 = 42; runtime is {@code 6000600055} (SSTORE slot0 = 0).
     * Deployed at height 1 (slot set). Called at height 2: clears slot0, earning a storage refund.
     * The 7-byte runtime (0x60 0x00 0x60 0x00 0x55) is returned by the constructor via a
     * CODECOPY+RETURN sequence.
     * Bytecode breakdown:
     *   602a 6000 55  — constructor: PUSH1 42, PUSH1 0, SSTORE  (slot0 = 42)
     *   6005 6011 60 00 39 — PUSH1 5, PUSH1 17, PUSH1 0, CODECOPY  (copy 5 runtime bytes from offset 17)
     *   6005 6000 f3  — PUSH1 5, PUSH1 0, RETURN  (return 5 runtime bytes)
     *   60 00 60 00 55  — runtime: PUSH1 0, PUSH1 0, SSTORE  (slot0 = 0)
     */
    private static final Bytes INIT_SET_THEN_CLEAR =
            Bytes.fromHexString("0x602a6000556005601160003960056000f36000600055");

    /**
     * EIP-3529 active vs gated: clearing a storage slot earns a refund only when the activation
     * height gate is open. This test verifies the full integration: net gas &lt; gross gas when
     * active, the cap is respected (net &ge; gross - gross/5), and the sender is refunded the
     * wei difference between gross and net gas fees.
     */
    @Test
    public void eip3529_active_run_charges_net_gas_and_refunds_fee_delta_to_sender() {
        final BigInteger GAS_PRICE = BigInteger.valueOf(10);
        final long GAS_LIMIT = 300_000L;

        // --- Active config: EIP-3529 on from height 0 (devnet default). ---
        EvmConfig activeConfig = EvmConfig.devnet(); // eip3529ActivationHeight = 0
        InMemoryKVSource activeState = new InMemoryKVSource();
        EvmTxStore activeTxs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore activeMeta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor activeProc = new EvmBlockProcessor(activeConfig, activeState, activeTxs, activeMeta,
                0L, List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        activeProc.seedGenesisIfAbsent();

        // Deploy (height 1, nonce 0): constructor sets slot0 = 42 in the new contract.
        EvmTransaction activeDeploy = EvmTransaction.unsigned(0L, Wei.of(GAS_PRICE), GAS_LIMIT,
                Optional.empty(), Wei.ZERO, INIT_SET_THEN_CLEAR, CHAIN_ID).sign(key, algo);
        activeTxs.put(activeDeploy);
        activeProc.processMainBlock(List.of(ref(activeDeploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt activeDeployReceipt = activeMeta.getReceipt(activeDeploy.getHash()).orElseThrow();
        assertEquals("active deploy must succeed", 1, activeDeployReceipt.status());
        Address activeContract = activeDeployReceipt.contractAddress().orElseThrow();

        // Call (height 2, nonce 1): runtime clears slot0 from 42 -> 0, earning a storage refund.
        EvmTransaction activeCall = EvmTransaction.unsigned(1L, Wei.of(GAS_PRICE), GAS_LIMIT,
                Optional.of(activeContract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        activeTxs.put(activeCall);
        BigInteger balanceBeforeActiveCall =
                new RocksDbWorldUpdater(activeState).getAccount(sender).getBalance().getAsBigInteger();
        BigInteger activeCallNetFee =
                activeProc.processMainBlock(List.of(ref(activeCall)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt activeCallReceipt = activeMeta.getReceipt(activeCall.getHash()).orElseThrow();
        assertEquals("active call must succeed", 1, activeCallReceipt.status());
        long netGasUsed = activeCallReceipt.gasUsed();
        BigInteger balanceAfterActiveCall =
                new RocksDbWorldUpdater(activeState).getAccount(sender).getBalance().getAsBigInteger();

        // --- Gated config: EIP-3529 activation height = MAX (never active). ---
        EvmConfig gatedConfig = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE,
                EvmConfig.DEFAULT_TYPE2_ACTIVATION_HEIGHT, EvmConfig.DEFAULT_BRIDGE_ACTIVATION_HEIGHT,
                Long.MAX_VALUE); // eip3529 gated off
        InMemoryKVSource gatedState = new InMemoryKVSource();
        EvmTxStore gatedTxs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore gatedMeta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor gatedProc = new EvmBlockProcessor(gatedConfig, gatedState, gatedTxs, gatedMeta,
                0L, List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        gatedProc.seedGenesisIfAbsent();

        // Same deploy in gated world (height 1, nonce 0).
        EvmTransaction gatedDeploy = EvmTransaction.unsigned(0L, Wei.of(GAS_PRICE), GAS_LIMIT,
                Optional.empty(), Wei.ZERO, INIT_SET_THEN_CLEAR, CHAIN_ID).sign(key, algo);
        gatedTxs.put(gatedDeploy);
        gatedProc.processMainBlock(List.of(ref(gatedDeploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt gatedDeployReceipt = gatedMeta.getReceipt(gatedDeploy.getHash()).orElseThrow();
        assertEquals("gated deploy must succeed", 1, gatedDeployReceipt.status());
        Address gatedContract = gatedDeployReceipt.contractAddress().orElseThrow();

        // Same call in gated world (height 2, nonce 1).
        EvmTransaction gatedCall = EvmTransaction.unsigned(1L, Wei.of(GAS_PRICE), GAS_LIMIT,
                Optional.of(gatedContract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        gatedTxs.put(gatedCall);
        BigInteger balanceBeforeGatedCall =
                new RocksDbWorldUpdater(gatedState).getAccount(sender).getBalance().getAsBigInteger();
        gatedProc.processMainBlock(List.of(ref(gatedCall)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt gatedCallReceipt = gatedMeta.getReceipt(gatedCall.getHash()).orElseThrow();
        assertEquals("gated call must succeed", 1, gatedCallReceipt.status());
        long grossGasUsed = gatedCallReceipt.gasUsed();
        BigInteger balanceAfterGatedCall =
                new RocksDbWorldUpdater(gatedState).getAccount(sender).getBalance().getAsBigInteger();

        // --- Assertions ---
        assertTrue("EIP-3529 active run must charge strictly less net gas than the gated gross",
                netGasUsed < grossGasUsed);
        assertTrue("refund must not exceed gross/5 (the EIP-3529 /5 cap)",
                (grossGasUsed - netGasUsed) <= grossGasUsed / 5);

        // Active sender net cost = netGasUsed * gasPrice (upfront debit was GAS_LIMIT*price, then refunded unused+storage)
        BigInteger activeCost = balanceBeforeActiveCall.subtract(balanceAfterActiveCall);
        BigInteger gatedCost = balanceBeforeGatedCall.subtract(balanceAfterGatedCall);
        BigInteger expectedFeeSaving = BigInteger.valueOf(grossGasUsed - netGasUsed).multiply(GAS_PRICE);
        assertEquals("sender must receive exactly (gross-net)*gasPrice more in the active run",
                expectedFeeSaving, gatedCost.subtract(activeCost));

        // Absolute pin: each run's charged cost must equal exactly gasUsed * gasPrice.
        // Value is zero for both CALL txs so cost is purely the gas fee. A symmetric over-refund
        // bug (both runs refunded by the same wrong amount) would satisfy the relative assertion
        // above while violating these two independent anchors.
        assertEquals("active run: sender cost must equal netGasUsed * gasPrice",
                BigInteger.valueOf(netGasUsed).multiply(GAS_PRICE), activeCost);
        // G3-T1: the surfaced net fee must equal the wei actually removed from the sender ON THE REFUND
        // PATH (netGasUsed < grossGasUsed here) — pins that the fee is upfront-minus-refund-applied, not
        // a re-derivation, and that the refund subtraction is not dropped.
        assertEquals("active run: surfaced net fee == wei actually removed from the sender",
                activeCost, activeCallNetFee);
        assertEquals("gated run: sender cost must equal grossGasUsed * gasPrice",
                BigInteger.valueOf(grossGasUsed).multiply(GAS_PRICE), gatedCost);
    }

    // -------------------------------------------------------------------------
    // EIP-3529 edge cases (G3-T2 step 5): revert earns no refund; replay determinism
    // -------------------------------------------------------------------------

    /**
     * Initcode: constructor SSTOREs slot0 = 42; runtime clears slot0 (SSTORE slot0=0) then REVERTs.
     * Bytecode breakdown:
     *   602a 6000 55        — constructor: PUSH1 42, PUSH1 0, SSTORE  (slot0 = 42)
     *   600a 6011 6000 39   — PUSH1 10, PUSH1 17, PUSH1 0, CODECOPY  (copy 10 runtime bytes from offset 17)
     *   600a 6000 f3        — PUSH1 10, PUSH1 0, RETURN  (return 10 runtime bytes)
     *   6000 6000 55        — runtime: PUSH1 0, PUSH1 0, SSTORE  (slot0 = 0)
     *   6000 6000 fd        — runtime: PUSH1 0, PUSH1 0, REVERT
     */
    private static final Bytes INIT_CLEAR_THEN_REVERT =
            Bytes.fromHexString("0x602a600055600a6011600039600a6000f3600060005560006000fd");

    /**
     * A reverting tx earns NO storage refund even if it cleared a slot during execution.
     * The EIP-3529 gate is {@code status == 1}; a revert (status 0) bypasses the refund path,
     * so the call receipt must record GROSS gas in both the active and gated worlds — the two
     * gasUsed values must be equal.
     */
    @Test
    public void eip3529_reverting_tx_earns_no_storage_refund() {
        final long GAS_LIMIT = 300_000L;

        // --- Active config: EIP-3529 on from height 0 (devnet default). ---
        InMemoryKVSource activeState = new InMemoryKVSource();
        EvmTxStore activeTxs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore activeMeta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor activeProc = new EvmBlockProcessor(EvmConfig.devnet(), activeState, activeTxs,
                activeMeta, 0L, List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        activeProc.seedGenesisIfAbsent();

        // Deploy at height 1 (nonce 0): constructor sets slot0 = 42.
        EvmTransaction activeDeploy = EvmTransaction.unsigned(0L, Wei.of(1), GAS_LIMIT,
                Optional.empty(), Wei.ZERO, INIT_CLEAR_THEN_REVERT, CHAIN_ID).sign(key, algo);
        activeTxs.put(activeDeploy);
        activeProc.processMainBlock(List.of(ref(activeDeploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt activeDeployReceipt = activeMeta.getReceipt(activeDeploy.getHash()).orElseThrow();
        assertEquals("active deploy must succeed", 1, activeDeployReceipt.status());
        Address activeContract = activeDeployReceipt.contractAddress().orElseThrow();

        // Call at height 2 (nonce 1): runtime clears slot0 then REVERTs.
        EvmTransaction activeCall = EvmTransaction.unsigned(1L, Wei.of(1), GAS_LIMIT,
                Optional.of(activeContract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        activeTxs.put(activeCall);
        activeProc.processMainBlock(List.of(ref(activeCall)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt activeCallReceipt = activeMeta.getReceipt(activeCall.getHash()).orElseThrow();
        assertEquals("the reverting call must have status 0", 0, activeCallReceipt.status());
        long activeGasUsed = activeCallReceipt.gasUsed();

        // --- Gated config: EIP-3529 activation height = MAX (never active). ---
        EvmConfig gatedConfig = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE,
                EvmConfig.DEFAULT_TYPE2_ACTIVATION_HEIGHT, EvmConfig.DEFAULT_BRIDGE_ACTIVATION_HEIGHT,
                Long.MAX_VALUE); // eip3529 gated off
        InMemoryKVSource gatedState = new InMemoryKVSource();
        EvmTxStore gatedTxs = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore gatedMeta = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor gatedProc = new EvmBlockProcessor(gatedConfig, gatedState, gatedTxs, gatedMeta,
                0L, List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        gatedProc.seedGenesisIfAbsent();

        // Same deploy in gated world (height 1, nonce 0).
        EvmTransaction gatedDeploy = EvmTransaction.unsigned(0L, Wei.of(1), GAS_LIMIT,
                Optional.empty(), Wei.ZERO, INIT_CLEAR_THEN_REVERT, CHAIN_ID).sign(key, algo);
        gatedTxs.put(gatedDeploy);
        gatedProc.processMainBlock(List.of(ref(gatedDeploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt gatedDeployReceipt = gatedMeta.getReceipt(gatedDeploy.getHash()).orElseThrow();
        assertEquals("gated deploy must succeed", 1, gatedDeployReceipt.status());
        Address gatedContract = gatedDeployReceipt.contractAddress().orElseThrow();

        // Same call in gated world (height 2, nonce 1).
        EvmTransaction gatedCall = EvmTransaction.unsigned(1L, Wei.of(1), GAS_LIMIT,
                Optional.of(gatedContract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        gatedTxs.put(gatedCall);
        gatedProc.processMainBlock(List.of(ref(gatedCall)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt gatedCallReceipt = gatedMeta.getReceipt(gatedCall.getHash()).orElseThrow();
        assertEquals("gated reverting call must also have status 0", 0, gatedCallReceipt.status());
        long gatedGasUsed = gatedCallReceipt.gasUsed();

        // A reverting tx never enters the refund gate (status != 1), so both worlds charge gross.
        assertTrue("reverting call must have consumed non-trivial gas (> base 21000)",
                activeGasUsed > 21_000L);
        assertEquals("reverting tx earns no refund: active and gated gasUsed must be equal",
                gatedGasUsed, activeGasUsed);
    }

    /**
     * A refunded tx replays to an identical net gasUsed AND an identical chained state root after
     * a rollback. The EIP-3529 refund path is deterministic: re-executing the same height post-reorg
     * reproduces the same receipt and the same checkpoint that the chained root folds in.
     */
    @Test
    public void eip3529_refunded_tx_replays_deterministically_after_rollback() {
        final long GAS_LIMIT = 300_000L;

        // Use the shared processor (EvmConfig.devnet(), EIP-3529 active from height 0).
        // Deploy INIT_SET_THEN_CLEAR at height 1 (nonce 0): constructor sets slot0 = 42.
        EvmTransaction deploy = EvmTransaction.unsigned(0L, Wei.of(1), GAS_LIMIT,
                Optional.empty(), Wei.ZERO, INIT_SET_THEN_CLEAR, CHAIN_ID).sign(key, algo);
        txStore.put(deploy);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        EvmReceipt deployReceipt = metaStore.getReceipt(deploy.getHash()).orElseThrow();
        assertEquals("deploy must succeed", 1, deployReceipt.status());
        Address contract = deployReceipt.contractAddress().orElseThrow();

        // Call at height 2 (nonce 1): runtime clears slot0 (42 -> 0), earning an EIP-3529 refund.
        EvmTransaction callTx = EvmTransaction.unsigned(1L, Wei.of(1), GAS_LIMIT,
                Optional.of(contract), Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        txStore.put(callTx);
        processor.processMainBlock(List.of(ref(callTx)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt callReceipt = metaStore.getReceipt(callTx.getHash()).orElseThrow();
        assertEquals("call must succeed and earn a refund", 1, callReceipt.status());
        long netGasUsed = callReceipt.gasUsed();
        Bytes32 rootAtTwo = metaStore.getHeightRecord(2L).orElseThrow().stateRoot();

        // Reorg: roll back to height 1, then re-apply height 2 with the same tx.
        // rollbackTo(1) wipes state above height 1 and replays heights 1..1 from EVM_META.
        processor.rollbackTo(1L);

        // Re-apply height 2: callTx blob is still in txStore; the same signed tx object is reused.
        processor.processMainBlock(List.of(ref(callTx)), 2L, 1002L, BLOCK_HASH_2);
        EvmReceipt replayedReceipt = metaStore.getReceipt(callTx.getHash()).orElseThrow();
        assertEquals("replayed call must succeed", 1, replayedReceipt.status());

        assertEquals("replayed net gasUsed must be identical to the original (refund is deterministic)",
                netGasUsed, replayedReceipt.gasUsed());
        assertEquals("replayed chained state root must be byte-identical (reorg symmetry)",
                rootAtTwo, metaStore.getHeightRecord(2L).orElseThrow().stateRoot());
    }

    @Test
    public void chained_root_at_follows_a_reorg_unwind() {
        Bytes32 root3 = Bytes32.fromHexString("0x" + "33".repeat(32));
        Bytes32 root7 = Bytes32.fromHexString("0x" + "77".repeat(32));
        metaStore.putHeightRecord(3L, root3, Bytes32.ZERO, 1, 1L);
        metaStore.putHeightRecord(7L, root7, Bytes32.ZERO, 1, 2L);
        assertEquals(root7, processor.chainedRootAt(9L)); // top before the unwind

        metaStore.removeAbove(5L); // reorg: drop every checkpoint above height 5 (removes height 7)

        assertEquals("after unwinding past height 7, the as-of root falls back to the height-3 floor",
                root3, processor.chainedRootAt(9L));
    }

    // -------------------------------------------------------------------------
    // G2-T1a: maturedEvmHeight index for delta-lagged EVM execution
    // -------------------------------------------------------------------------

    @Test
    public void matured_evm_height_is_confirmed_minus_lag_plus_one() {
        // G2-T1a: setMain(N) executes the height that has just reached finality depth = N - lag + 1.
        assertEquals(10L, EvmBlockProcessor.maturedEvmHeight(10L, 1L));  // lag=1 -> immediate (today)
        assertEquals(9L, EvmBlockProcessor.maturedEvmHeight(10L, 2L));   // lag=2 -> one behind
        assertEquals(-5L, EvmBlockProcessor.maturedEvmHeight(10L, 16L)); // deep lag -> below genesis (caller guards)
        assertEquals(1L, EvmBlockProcessor.maturedEvmHeight(16L, 16L));  // first height that matures at lag=16
    }

    @Test(expected = IllegalArgumentException.class)
    public void matured_evm_height_rejects_lag_below_one() {
        EvmBlockProcessor.maturedEvmHeight(10L, 0L);
    }

    // -------------------------------------------------------------------------
    // G2-T1a step 3: processConfirmedBlock buffers + matures at finality depth
    // -------------------------------------------------------------------------

    @Test
    public void confirmed_block_at_lag_one_executes_the_confirmed_height_immediately() {
        // lag=1: matured = confirmedHeight, so execution is immediate (byte-identical to the old path).
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1, List.of(), 1L, false);
        assertTrue("height 1 checkpoints immediately at lag=1", metaStore.getHeightRecord(1L).isPresent());
    }

    @Test
    public void confirmed_block_at_lag_two_defers_execution_by_one_confirmed_block() {
        // lag=2: confirming height 1 buffers it (matured = 0, nothing matures yet).
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1, List.of(), 2L, false);
        assertTrue("height 1 not yet executed at lag=2", metaStore.getHeightRecord(1L).isEmpty());
        assertTrue("height 1 is buffered", metaStore.getMaturityEntry(1L).isPresent());

        // Confirming an empty height 2 matures height 1 (2 - 2 + 1 = 1).
        processor.processConfirmedBlock(List.of(), 2L, 1002L, BLOCK_HASH_2, List.of(), 2L, false);
        assertTrue("height 1 executes when height 2 confirms", metaStore.getHeightRecord(1L).isPresent());
        assertTrue("height 1 buffer entry consumed", metaStore.getMaturityEntry(1L).isEmpty());
    }

    @Test
    public void confirmed_block_defers_a_matured_height_whose_blob_is_missing() {
        // A ref whose blob was never stored: at maturity (lag=1) the height defers to the pending
        // queue (existing I4 path) rather than checkpointing. The BEHIND-verdict trap repair is G2-T1b;
        // here we only assert the deferral still composes.
        Bytes32 phantom = Bytes32.fromHexString("0x" + "ab".repeat(32));
        processor.processConfirmedBlock(List.of(phantom), 1L, 1001L, BLOCK_HASH_1, List.of(), 1L, false);
        assertTrue("missing-blob height does not checkpoint", metaStore.getHeightRecord(1L).isEmpty());
        assertTrue("missing-blob height defers to the pending queue",
                metaStore.pendingHeights().contains(1L));
    }

    @Test
    public void a_buffered_unmatured_height_truncated_by_a_reorg_leaves_no_execution_artifact() {
        // lag=2: confirm height 5 with a real deploy -> it is buffered, NOT executed (matured = 4,
        // and height 4 was never buffered here). A reorg that unwinds height 5 truncates the buffer;
        // because the height never executed, there is no checkpoint/receipt to roll back.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 5L, 1005L, BLOCK_HASH_1, List.of(), 2L, false);
        assertTrue("height 5 is buffered", metaStore.getMaturityEntry(5L).isPresent());
        assertTrue("height 5 never executed", metaStore.getHeightRecord(5L).isEmpty());

        // Simulate the reorg truncation setMain->rollbackTo would perform.
        metaStore.removeAbove(4L);

        assertTrue("buffer entry is swept", metaStore.getMaturityEntry(5L).isEmpty());
        assertTrue("no checkpoint existed to roll back", metaStore.getHeightRecord(5L).isEmpty());
        assertTrue("nothing left pending either", metaStore.pendingHeights().isEmpty());
    }

    // -------------------------------------------------------------------------
    // G2-T1b: committed-skip heights fold a canonical SKIP_SENTINEL (ADR-015)
    // -------------------------------------------------------------------------

    @Test
    public void a_marked_height_checkpoints_a_skip_without_executing_its_txs() {
        // Pre-mark height 1 as committed-skip, then feed it a real deploy ref via processMainBlock.
        // The deploy's blob is present, but the skip means it must NOT execute: no receipt, empty tx
        // list, yet the height still checkpoints (frontier must advance) with a SKIP_SENTINEL root.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        metaStore.putSkipMarker(1L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);

        assertTrue("skipped height still checkpoints", metaStore.getHeightRecord(1L).isPresent());
        assertTrue("skipped height has an empty tx list", metaStore.getTxList(1L).isEmpty());
        assertTrue("the skipped deploy never executed (no receipt)",
                metaStore.getReceipt(deploy.getHash()).isEmpty());
    }

    @Test
    public void a_skipped_height_has_a_distinct_root_from_an_empty_execution() {
        // Same height number, same inputs, but one is skip-marked and one is not: their checkpoint
        // roots must differ, proving the sentinel makes a skip distinct (not merely "no txs ran").
        EvmTransaction deployA = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        metaStore.putSkipMarker(1L);
        processor.processMainBlock(List.of(ref(deployA)), 1L, 1001L, BLOCK_HASH_1);
        Bytes32 skipRoot = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();

        // Fresh processor/stores, height 1 NOT skipped, deploy executes normally.
        InMemoryKVSource state2 = new InMemoryKVSource();
        EvmTxStore txs2 = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore meta2 = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor p2 = new EvmBlockProcessor(EvmConfig.devnet(), state2, txs2, meta2, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        p2.seedGenesisIfAbsent();
        EvmTransaction deployB = EvmTransaction.unsigned(0, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        txs2.put(deployB);
        p2.processMainBlock(List.of(Bytes32.wrap(deployB.getHash().getBytes())), 1L, 1001L, BLOCK_HASH_1);
        Bytes32 execRoot = meta2.getHeightRecord(1L).orElseThrow().stateRoot();

        assertNotEquals("a committed-skip root must differ from an executed root", execRoot, skipRoot);
    }

    @Test
    public void a_skipped_height_still_mints_its_bridge_deposits() {
        // Deposits are native facts, blob-independent, so a skip still mints them (spec 3.2).
        Address depositTarget = Address.fromHexString("0x00000000000000000000000000000000000000cc");
        metaStore.putDeposits(1L, List.of(new io.xdag.evm.bridge.BridgeDeposit(depositTarget, 5L)));
        metaStore.putSkipMarker(1L);
        processor.processMainBlock(List.of(), 1L, 1001L, BLOCK_HASH_1);

        assertTrue("skipped-but-deposit height checkpoints", metaStore.getHeightRecord(1L).isPresent());
        long minted = new RocksDbWorldUpdater(stateSource).getAccount(depositTarget)
                .getBalance().getAsBigInteger().longValueExact();
        assertEquals("deposit minted despite the skip",
                5L * io.xdag.evm.bridge.BridgeConstants.WEI_PER_NANO.longValueExact(), minted);
    }

    @Test
    public void a_skipped_height_root_survives_reorg_replay() {
        // rollbackTo replays from EVM_META tx lists with no block access, so the skip marker (0x0A)
        // is what makes replay fold the same SKIP_SENTINEL. Root must be byte-stable across replay.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        metaStore.putSkipMarker(1L);
        processor.processMainBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1);
        Bytes32 before = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();

        processor.rollbackTo(1L); // removeAbove(1) keeps height 1 + its skip marker, then replays it
        assertTrue("skip marker survives the reorg truncation at its own height", metaStore.isSkipped(1L));
        Bytes32 after = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();
        assertEquals("replay reproduces the skip root byte-for-byte", before, after);
    }

    // -------------------------------------------------------------------------
    // G2-T1b step 3: processConfirmedBlock honors the committed daSkip bit
    // -------------------------------------------------------------------------

    @Test
    public void a_committed_skip_is_honored_even_when_the_blob_is_present_at_lag_two() {
        // lag=2, daSkip=true for the matured height. Confirm height 1 (buffers the deploy, blob PRESENT),
        // then confirm height 2 with daSkip=true -> matures height 1 as a SKIP, not an execution, even
        // though this node holds the blob. The deploy must NOT execute; height 1 checkpoints as skipped.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1, List.of(), 2L, false);
        assertTrue("height 1 buffered", metaStore.getMaturityEntry(1L).isPresent());

        // block 2 commits daSkip=true for the height it matures (height 1).
        processor.processConfirmedBlock(List.of(), 2L, 1002L, BLOCK_HASH_2, List.of(), 2L, true);

        assertTrue("height 1 checkpointed", metaStore.getHeightRecord(1L).isPresent());
        assertTrue("height 1 recorded as skipped", metaStore.isSkipped(1L));
        assertTrue("height 1 has an empty tx list (skipped)", metaStore.getTxList(1L).isEmpty());
        assertTrue("the deploy did NOT execute despite its blob being present",
                metaStore.getReceipt(deploy.getHash()).isEmpty());
        assertTrue("buffer entry consumed", metaStore.getMaturityEntry(1L).isEmpty());
    }

    @Test
    public void a_committed_include_still_executes_at_lag_two() {
        // Control: daSkip=false matures height 1 by EXECUTING it (T1a behavior), no skip marker.
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1, List.of(), 2L, false);
        processor.processConfirmedBlock(List.of(), 2L, 1002L, BLOCK_HASH_2, List.of(), 2L, false);
        assertTrue("height 1 executed", metaStore.getHeightRecord(1L).isPresent());
        assertFalse("height 1 not marked skipped", metaStore.isSkipped(1L));
        assertTrue("the deploy executed", metaStore.getReceipt(deploy.getHash()).isPresent());
    }

    @Test
    public void a_committed_skip_yields_the_same_root_on_a_blob_holding_and_a_blob_lacking_node() {
        // Node A HAS the blob; node B does NOT. Both see block-2's daSkip=true for matured height 1.
        // They must reach the SAME checkpoint root for height 1 (that is the whole point of a skip).
        long lag = 2L;

        // Node A: blob present (this test's `processor`, whose txStore gets the deploy blob).
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        processor.processConfirmedBlock(List.of(ref(deploy)), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        processor.processConfirmedBlock(List.of(), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, true);
        Bytes32 rootA = metaStore.getHeightRecord(1L).orElseThrow().stateRoot();

        // Node B: same genesis alloc, but the deploy blob is NEVER stored in txsB.
        InMemoryKVSource stateB = new InMemoryKVSource();
        EvmTxStore txsB = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaB = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor procB = new EvmBlockProcessor(EvmConfig.devnet(), stateB, txsB, metaB, 0L,
                List.of(new GenesisAllocEntry(sender, Wei.fromEth(1))));
        procB.seedGenesisIfAbsent();
        Bytes32 sameRef = Bytes32.wrap(deploy.getHash().getBytes()); // ref only; blob absent in txsB
        procB.processConfirmedBlock(List.of(sameRef), 1L, 1001L, BLOCK_HASH_1, List.of(), lag, false);
        procB.processConfirmedBlock(List.of(), 2L, 1002L, BLOCK_HASH_2, List.of(), lag, true);
        Bytes32 rootB = metaB.getHeightRecord(1L).orElseThrow().stateRoot();

        assertEquals("a committed-skip converges both nodes to the same root", rootA, rootB);
        assertTrue(metaStore.isSkipped(1L));
        assertTrue(metaB.isSkipped(1L));
    }

    // -------------------------------------------------------------------------
    // G2-T1c: maturedPayloadAvailable pack-time DA check
    // -------------------------------------------------------------------------

    @Test
    public void matured_payload_available_true_when_the_buffered_refs_blob_is_present() {
        // lag=2: matured for confirmedHeight 3 is height 2. Buffer height 2 with a ref whose blob IS
        // in txStore -> the miner can make it available -> include (available == true).
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L); // stored in txStore
        metaStore.putMaturityEntry(2L, BLOCK_HASH_1, 1002L, List.of(ref(deploy)), List.of());
        assertTrue("all buffered blobs present -> available",
                processor.maturedPayloadAvailable(3L, 2L));
    }

    @Test
    public void matured_payload_available_false_when_a_buffered_refs_blob_is_missing() {
        // Buffer height 2 with a ref whose blob was NEVER stored -> unavailable -> the miner must skip.
        Bytes32 phantom = Bytes32.fromHexString("0x" + "ab".repeat(32));
        metaStore.putMaturityEntry(2L, BLOCK_HASH_1, 1002L, List.of(phantom), List.of());
        assertFalse("a missing blob -> not available",
                processor.maturedPayloadAvailable(3L, 2L));
    }

    @Test
    public void matured_payload_available_true_when_nothing_is_buffered() {
        // No buffer entry at the matured height (an empty height, or a height not yet confirmed):
        // nothing to skip -> include trivially.
        assertTrue("no buffered payload -> available (nothing to skip)",
                processor.maturedPayloadAvailable(9L, 2L)); // matured = 8, never buffered
    }

    @Test
    public void matured_payload_available_returns_true_for_present_blob_regardless_of_lag() {
        // The method's contract is purely "is the matured height's buffered payload available",
        // independent of lag. At lag=1, matured for confirmedHeight 5 is height 5; buffer it with a
        // PRESENT blob -> available. (The "lag=1 never skips" property comes from the caller, where the
        // matured height is the unconfirmed block being mined and is therefore never buffered.)
        EvmTransaction deploy = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        metaStore.putMaturityEntry(5L, BLOCK_HASH_1, 1005L, List.of(ref(deploy)), List.of());
        assertTrue("buffered present blob at lag 1 -> available",
                processor.maturedPayloadAvailable(5L, 1L)); // matured = 5
    }

    // -------------------------------------------------------------------------
    // G2-T2: buffered missing-blob accessors + buffered-aware ingest gate
    // -------------------------------------------------------------------------

    @Test
    public void buffered_missing_blob_is_enumerated_and_awaited() {
        // A buffered (not-yet-matured) height whose ref's blob is absent from txStore must be
        // enumerated by bufferedMissingBlobHashes AND accepted by the ingest gate isAwaitingBlob,
        // so a proactively-fetched reply is stored (G2-T2). Use a phantom ref (never stored).
        Bytes32 phantom = Bytes32.fromHexString("0x" + "ab".repeat(32));
        metaStore.putMaturityEntry(3L, BLOCK_HASH_1, 1003L, List.of(phantom), List.of());

        assertEquals("buffered missing blob enumerated", List.of(phantom),
                processor.bufferedMissingBlobHashes());
        assertEquals("ambiguous buffered ref also enumerated as a possible batch", List.of(phantom),
                processor.bufferedMissingBatchHashes());
        assertTrue("ingest gate awaits a buffered height's blob",
                processor.isAwaitingBlob(Hash.wrap(phantom)));
        assertTrue("ingest gate awaits it as a possible batch too",
                processor.isAwaitingBatch(Hash.wrap(phantom)));
    }

    @Test
    public void a_buffered_height_with_a_present_blob_has_nothing_missing() {
        // The buffered ref's blob IS in txStore -> nothing missing, not awaited.
        EvmTransaction present = storedTx(0, Optional.empty(), INIT_CODE, 200_000L);
        metaStore.putMaturityEntry(3L, BLOCK_HASH_1, 1003L, List.of(ref(present)), List.of());
        assertTrue("present blob -> not missing", processor.bufferedMissingBlobHashes().isEmpty());
        assertFalse("present blob -> not awaited", processor.isAwaitingBlob(present.getHash()));
    }

    @Test
    public void an_unreferenced_blob_is_not_awaited_by_any_buffered_or_pending_height() {
        // A hash referenced by NO buffered/pending height must not be awaited (ingest-gate DoS guard).
        Bytes32 unrelated = Bytes32.fromHexString("0x" + "cd".repeat(32));
        assertFalse(processor.isAwaitingBlob(Hash.wrap(unrelated)));
        assertFalse(processor.isAwaitingBatch(Hash.wrap(unrelated)));
        assertTrue(processor.bufferedMissingBlobHashes().isEmpty());
    }

    @Test
    public void a_ref_shared_by_two_buffered_heights_is_enumerated_once() {
        // The `seen` dedup: the same missing ref referenced by two buffered heights appears once.
        Bytes32 shared = Bytes32.fromHexString("0x" + "ab".repeat(32)); // never stored -> missing
        metaStore.putMaturityEntry(3L, BLOCK_HASH_1, 1003L, List.of(shared), List.of());
        metaStore.putMaturityEntry(4L, BLOCK_HASH_1, 1004L, List.of(shared), List.of());
        assertEquals("shared missing ref enumerated exactly once", List.of(shared),
                processor.bufferedMissingBlobHashes());
    }

    @Test
    public void on_blobs_available_returns_the_drained_height_and_its_net_fee() {
        Wei gasPrice = Wei.of(1_000L);
        long gasLimit = 200_000L;
        EvmTransaction deploy = EvmTransaction.unsigned(0L, gasPrice, gasLimit, Optional.empty(),
                Wei.ZERO, INIT_CODE, CHAIN_ID).sign(key, algo);
        Bytes32 ref = ref(deploy);
        // Defer height 1: its blob is NOT in the store yet, so processMainBlock queues it as pending.
        processor.processMainBlock(List.of(ref), 1L, 1001L, BLOCK_HASH_1);
        assertTrue("height must be deferred (no receipt yet)", metaStore.getReceipt(deploy.getHash()).isEmpty());
        assertTrue("returns nothing while the blob is still missing", processor.onBlobsAvailable().isEmpty());

        // Now the blob arrives: draining executes it and reports (height, netFeeWei).
        txStore.put(deploy);
        java.util.List<EvmBlockProcessor.DrainedHeight> drained = processor.onBlobsAvailable();
        assertEquals(1, drained.size());
        assertEquals("drained height is the payload height", 1L, drained.get(0).height());
        long gasUsed = metaStore.getReceipt(deploy.getHash()).orElseThrow().gasUsed();
        assertEquals("net fee is gasUsed * gasPrice",
                BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger()), drained.get(0).netFeeWei());
    }
}
