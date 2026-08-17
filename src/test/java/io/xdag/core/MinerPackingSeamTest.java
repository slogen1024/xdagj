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
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.HistoricalStateReader;
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
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/** The seam: createMainBlock() packs a pool tx, and that ref executes through the real processor. */
public class MinerPackingSeamTest {

    private static final Bytes INIT_CODE = Bytes.fromHexString("0x64602a6000556000526005601bf3");
    private static final Bytes RUNTIME = Bytes.fromHexString("0x602a600055");

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final Address evmSender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
    private EvmMetaStore evmMetaStore;
    private EvmTxPool evmTxPool;
    private EvmBlockProcessor evmBlockProcessor;
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
        KVSource<byte[], byte[]> evmTxSource = dbFactory.getDB(DatabaseName.EVM_TX);
        evmTxSource.init();
        evmTxSource.reset();
        KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
        evmMetaSource.init();
        evmMetaSource.reset();
        evmTxStore = new EvmTxStore(evmTxSource);
        evmMetaStore = new EvmMetaStore(evmMetaSource);
        evmTxPool = new EvmTxPool(evmTxStore, evmStateSource, BigInteger.valueOf(0xCAFE),
                30_000_000L, Wei.ONE, 3600L, () -> 1000L);
        evmBlockProcessor = new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore);
        kernel.setEvmStateStore(evmStateSource);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmTxPool(evmTxPool);
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmBlockProcessor(evmBlockProcessor);

        RocksDbWorldUpdater w = new RocksDbWorldUpdater(evmStateSource);
        w.createAccount(evmSender, 0L, Wei.fromEth(1));
        w.commit();

        blockchain = new BlockchainImpl(kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    @Test
    public void miner_packed_ref_executes_through_the_processor() {
        // Submit exactly as eth_sendRawTransaction would: into the tx store + pool.
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, Wei.ONE, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        assertEquals(EvmTxPool.AddResult.ADDED, evmTxPool.add(deployTx.getRawRlp()));

        // The miner builds a main block; with the batch fork active it must carry a batch commitment
        // (not the bare tx hash — the 0x0F field now points to an EvmTxStore batch record).
        Block mainBlock = blockchain.createMainBlock();
        assertNotNull("main block must carry a ref", mainBlock.getEvmTxRef());
        // The ref must be a batch commitment whose single member is the deploy tx.
        var batchOpt = evmTxStore.getBatch(org.hyperledger.besu.datatypes.Hash.wrap(mainBlock.getEvmTxRef()));
        assertTrue("ref must resolve as a batch", batchOpt.isPresent());
        assertEquals("batch must contain exactly one tx", 1, batchOpt.get().size());
        assertEquals("batch member must be the deploy tx hash",
                Bytes32.wrap(deployTx.getHash().getBytes()), batchOpt.get().get(0));

        // Execute the block's ref through the real setMain path (EvmBlockProcessor.processMainBlock).
        // The processor dual-looks-up the batch commitment and expands it to the tx list.
        // getHashLow() forces hash calculation (an unmined block's info hash is otherwise unset).
        evmBlockProcessor.processMainBlock(List.of(mainBlock.getEvmTxRef()), 1L, 1001L,
                Bytes32.wrap(mainBlock.getHashLow()));

        var receipt = evmMetaStore.getReceipt(deployTx.getHash());
        assertTrue("the miner-packed tx must execute", receipt.isPresent());
        assertEquals(1, receipt.orElseThrow().status());
        Address contract = receipt.orElseThrow().contractAddress().orElseThrow();
        assertNotNull(new RocksDbWorldUpdater(evmStateSource).getAccount(contract));
        assertEquals(RUNTIME, new RocksDbWorldUpdater(evmStateSource).getAccount(contract).getCode());
        assertEquals(UInt256.ZERO,
                new RocksDbWorldUpdater(evmStateSource).getAccount(contract).getStorageValue(UInt256.ZERO));
    }

    @Test
    public void a_batched_main_block_confirms_multiple_txs_end_to_end() {
        // Second sender: private key = 2. Its Ethereum address is derived from the public key.
        KeyPair evmKey2 = algo.createKeyPair(algo.createPrivateKey(BigInteger.TWO));
        Address evmSender2 = Address.extract(evmKey2.getPublicKey());

        // Fund both senders: sender1 needs enough for 3 txs (200_000 gas * Wei.ONE * 3), sender2 for 1.
        // Wei.fromEth(1) covers any reasonable gas cost in these tests.
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(evmStateSource);
        w.createAccount(evmSender2, 0L, Wei.fromEth(1));
        w.commit();

        // Three consecutive txs from sender1 (nonces 0, 1, 2) — value transfers to a dummy address.
        Address dummy = Address.fromHexString("0x1111111111111111111111111111111111111111");
        for (long nonce = 0; nonce <= 2; nonce++) {
            EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 200_000L,
                    Optional.of(dummy), Wei.ZERO, Bytes.EMPTY, BigInteger.valueOf(0xCAFE))
                    .sign(evmKey, algo);
            evmTxStore.put(tx);
            assertEquals("sender1 nonce " + nonce + " must be ADDED",
                    EvmTxPool.AddResult.ADDED, evmTxPool.add(tx.getRawRlp()));
        }
        // One tx from sender2 (nonce 0).
        EvmTransaction tx2 = EvmTransaction.unsigned(0L, Wei.ONE, 200_000L,
                Optional.of(dummy), Wei.ZERO, Bytes.EMPTY, BigInteger.valueOf(0xCAFE))
                .sign(evmKey2, algo);
        evmTxStore.put(tx2);
        assertEquals("sender2 nonce 0 must be ADDED",
                EvmTxPool.AddResult.ADDED, evmTxPool.add(tx2.getRawRlp()));

        // The miner builds a main block — with batchActivationHeight=0, it must produce a batch commitment.
        Block mainBlock = blockchain.createMainBlock();
        assertNotNull("main block must carry an evmTxRef", mainBlock.getEvmTxRef());
        var batchOpt = evmTxStore.getBatch(org.hyperledger.besu.datatypes.Hash.wrap(mainBlock.getEvmTxRef()));
        assertTrue("ref must resolve as a batch", batchOpt.isPresent());
        assertEquals("batch must contain exactly 4 txs", 4, batchOpt.get().size());

        // Execute the batch through the real processor (mirrors what setMain calls).
        evmBlockProcessor.processMainBlock(List.of(mainBlock.getEvmTxRef()), 1L, 1001L,
                Bytes32.wrap(mainBlock.getHashLow()));

        // All 4 receipts must exist with status 1 (value transfers succeed).
        List<Bytes32> batchMembers = batchOpt.get();
        for (Bytes32 memberHash : batchMembers) {
            org.hyperledger.besu.datatypes.Hash h = org.hyperledger.besu.datatypes.Hash.wrap(memberHash);
            var receipt = evmMetaStore.getReceipt(h);
            assertTrue("receipt must be present for " + h, receipt.isPresent());
            assertEquals("receipt must be success for " + h, 1, receipt.orElseThrow().status());
        }

        // getTxList(height=1) must return exactly the 4 hashes in batch order.
        List<org.hyperledger.besu.datatypes.Hash> txList = evmMetaStore.getTxList(1L);
        assertEquals("tx list must have 4 entries", 4, txList.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("tx list entry " + i + " must match batch member " + i,
                    batchMembers.get(i), Bytes32.wrap(txList.get(i).getBytes()));
        }

        // Sender1's nonce must have advanced to 3 (all three txs executed).
        RocksDbWorldUpdater snapshot = new RocksDbWorldUpdater(evmStateSource);
        assertEquals("sender1 account nonce must be 3 after 3 executed txs",
                3L, snapshot.getAccount(evmSender).getNonce());
    }

    @Test
    public void receipt_is_queryable_after_execution() throws Exception {
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, Wei.ONE, 200_000L, java.util.Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        evmTxPool.add(deployTx.getRawRlp());
        Block mainBlock = blockchain.createMainBlock();
        evmBlockProcessor.processMainBlock(List.of(mainBlock.getEvmTxRef()), 1L, 1001L,
                Bytes32.wrap(mainBlock.getHashLow()));

        // Query the receipt through the eth handler over the same stores (C3 write->query loop).
        io.xdag.rpc.server.handler.EthRequestHandler h = new io.xdag.rpc.server.handler.EthRequestHandler(
                EvmConfig.devnet(), BigInteger.ONE, blockchain, evmTxPool, null,
                evmTxStore, evmMetaStore, 1024L,
                new HistoricalStateReader(evmStateSource, new EvmStateJournal(new InMemoryKVSource()), 128));
        io.xdag.rpc.server.protocol.JsonRpcRequest req = new io.xdag.rpc.server.protocol.JsonRpcRequest();
        req.setMethod("eth_getTransactionReceipt");
        req.setParams(new Object[]{deployTx.getHash().getBytes().toHexString()});
        req.setId(1);

        java.util.Map<?, ?> receipt = (java.util.Map<?, ?>) h.handle(req);
        assertEquals("0x1", receipt.get("status"));
        assertNotNull(receipt.get("contractAddress"));
    }
}
