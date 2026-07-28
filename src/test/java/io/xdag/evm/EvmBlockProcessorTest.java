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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
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
        processor = new EvmBlockProcessor(EvmConfig.devnet(), stateSource, txStore, metaStore);

        RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateSource);
        world.createAccount(sender, 0L, Wei.fromEth(1));
        world.commit();
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
        RocksDbWorldUpdater seed = new RocksDbWorldUpdater(stateB);
        seed.createAccount(sender, 0L, Wei.fromEth(1));
        seed.commit();
        EvmBlockProcessor procB = new EvmBlockProcessor(EvmConfig.devnet(), stateB, txB, metaB);
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
}
