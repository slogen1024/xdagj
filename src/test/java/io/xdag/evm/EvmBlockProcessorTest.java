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
    public void missing_blob_is_skipped_without_checkpoint() {
        Bytes32 unknown = Bytes32.wrap(Hash.hash(Bytes.of(0x77)).getBytes());
        processor.processMainBlock(List.of(unknown), 1L, 1001L, BLOCK_HASH_1);

        assertTrue(metaStore.getHeightRecord(1L).isEmpty());
        assertTrue(metaStore.getTxList(1L).isEmpty());
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
