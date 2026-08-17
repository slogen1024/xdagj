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
package io.xdag.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * createMainBlock() produces a batch commitment in the 0x0F field (batch D2 fork active on
 * devnet from genesis). Legacy tests that asserted bare-tx-hash semantics are updated here:
 * "main_block_packs_top_pool_tx" intent was "packing works" → updated to batch semantics below.
 */
public class MainBlockEvmPackingTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    // DevnetConfig has batchActivationHeight = 0, so the batch fork is always active.
    private final Config config = new DevnetConfig();
    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final KeyPair evmKey2 = algo.createKeyPair(algo.createPrivateKey(BigInteger.TWO));
    private final Address evmSender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    private final Address evmSender2 = Address.fromHexString("0x2b5ad5c4795c026514f8317c7a215e218dccd6cf");

    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
    private EvmTxPool evmTxPool;
    private EvmMetaStore evmMetaStore;
    private BlockchainImpl blockchain;

    @Before
    public void setUp() throws Exception {
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        wallet = new Wallet(config);
        if (wallet.exists()) {
            wallet.delete();
        }
        assertTrue(wallet.unlock("password"));
        ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        wallet.setAccounts(Collections.singletonList(key));
        wallet.flush();

        kernel = new Kernel(config, key);
        dbFactory = new RocksdbFactory(config);
        BlockStore blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX), dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.BLOCK), dbFactory.getDB(DatabaseName.TXHISTORY));
        blockStore.reset();
        OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphanBlockStore.reset();
        AddressStore addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.reset();
        TransactionHistoryStore txHistoryStore = Mockito.mock(TransactionHistoryStore.class);
        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setAddressStore(addressStore);
        kernel.setTxHistoryStore(txHistoryStore);
        kernel.setWallet(wallet);

        evmStateSource = dbFactory.getDB(DatabaseName.EVM_STATE);
        evmStateSource.init();
        evmStateSource.reset();
        KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
        evmMetaSource.init();
        evmMetaSource.reset();
        evmMetaStore = new EvmMetaStore(evmMetaSource);
        // A single shared EvmTxStore — the pool's add() writes txs to it, and selectEvmBatch
        // calls putBatch() on the same instance so getBatch() resolves correctly in assertions.
        evmTxStore = new EvmTxStore(new InMemoryKVSource());
        evmTxPool = new EvmTxPool(evmTxStore, evmStateSource,
                BigInteger.valueOf(0xCAFE), 30_000_000L, Wei.ONE, 3600L, () -> 1000L);
        kernel.setEvmStateStore(evmStateSource);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmTxPool(evmTxPool);
        kernel.setEvmTxStore(evmTxStore);

        RocksDbWorldUpdater w = new RocksDbWorldUpdater(evmStateSource);
        w.createAccount(evmSender, 0L, Wei.fromEth(1));
        w.createAccount(evmSender2, 0L, Wei.fromEth(1));
        w.commit();

        blockchain = new BlockchainImpl(kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    private EvmTransaction pooledTx(KeyPair key, long nonce) {
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 100_000L, Optional.empty(),
                Wei.ZERO, Bytes.fromHexString("0x6001600155"), BigInteger.valueOf(0xCAFE)).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.ADDED, evmTxPool.add(tx.getRawRlp()));
        return tx;
    }

    // ---------------------------------------------------------------------------
    // Batch-path tests (batchActivationHeight = 0 on devnet, active from genesis)
    // ---------------------------------------------------------------------------

    /**
     * Replaces the original "main_block_packs_top_pool_tx" (which asserted bare-tx-hash semantics).
     * After the batch fork a single pool tx is packed as a batch commitment — NOT the bare hash.
     */
    @Test
    public void a_single_pool_tx_is_still_packed_as_a_batch_commitment() {
        EvmTransaction tx = pooledTx(evmKey, 0);
        Block main = blockchain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("batch ref must be non-null", ref);

        // Ref must resolve via getBatch, not be the bare tx hash.
        Optional<List<Bytes32>> batch = evmTxStore.getBatch(Hash.wrap(ref));
        assertTrue("ref must be a stored batch", batch.isPresent());
        assertEquals("batch must contain exactly one tx", 1, batch.get().size());
        assertEquals("batch member must be the tx hash",
                Bytes32.wrap(tx.getHash().getBytes()), batch.get().get(0));
    }

    /**
     * Multiple pool txs (two senders) are packed into one batch commitment. Both tx hashes appear
     * in the decoded member list.
     */
    @Test
    public void a_mined_main_block_carries_a_batch_commitment_covering_the_pool() {
        EvmTransaction tx1 = pooledTx(evmKey, 0);
        EvmTransaction tx2 = pooledTx(evmKey2, 0);
        Block main = blockchain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("batch ref must be non-null with pool txs", ref);

        Optional<List<Bytes32>> batch = evmTxStore.getBatch(Hash.wrap(ref));
        assertTrue("ref must be a stored batch", batch.isPresent());
        assertEquals("batch must contain both txs", 2, batch.get().size());

        Bytes32 h1 = Bytes32.wrap(tx1.getHash().getBytes());
        Bytes32 h2 = Bytes32.wrap(tx2.getHash().getBytes());
        assertTrue("batch must contain tx1", batch.get().contains(h1));
        assertTrue("batch must contain tx2", batch.get().contains(h2));
    }

    // ---------------------------------------------------------------------------
    // Shared invariants — both batch and legacy paths agree on these
    // ---------------------------------------------------------------------------

    @Test
    public void empty_pool_packs_no_ref() {
        assertNull(blockchain.createMainBlock().getEvmTxRef());
    }

    @Test
    public void already_executed_tx_is_not_packed() {
        EvmTransaction tx = pooledTx(evmKey, 0);
        evmMetaStore.putReceipt(tx.getHash(), new EvmReceipt(1, 21_000L, Optional.empty(), List.of()));
        assertNull("a tx with a receipt must not be re-packed", blockchain.createMainBlock().getEvmTxRef());
    }
}
