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

import static io.xdag.BlockBuilder.generateAddressBlock;
import static io.xdag.BlockBuilder.generateExtraBlock;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
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
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
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

/**
 * End-to-end B2 proof over a real RocksDB-backed BlockchainImpl: a signed EIP-155 deploy tx whose
 * hash rides an XDAG_FIELD_EVM_TX_REF field is executed exactly when its carrier block is confirmed
 * as a main block, leaving contract code in EVM_STATE and a receipt + checkpoint in EVM_META.
 */
public class EvmConsensusIntegrationTest {

    /** Initcode deploying runtime 0x602a600055 (SSTORE slot0 = 42 on every call). */
    private static final Bytes INIT_CODE = Bytes.fromHexString("0x64602a6000556000526005601bf3");
    private static final Bytes RUNTIME = Bytes.fromHexString("0x602a600055");

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
    private EvmMetaStore evmMetaStore;

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
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TXHISTORY));
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

        // EVM services exactly as Kernel.startComponents wires them for evm.enabled networks.
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
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmBlockProcessor(
                new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore));
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    @Test
    public void deploy_tx_executes_when_carrier_block_becomes_main() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        // The signed EVM transaction, stored as if the pool had accepted it (spec §4.4 step 5).
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        // Gas now settles in EVM wei (缺口2), so the deployer must be funded to cover gasLimit*gasPrice.
        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));

        List<io.xdag.core.Address> pending = new ArrayList<>();
        Bytes32 ref = addressBlock.getHashLow();
        Block carrier = null;

        for (int i = 1; i <= 12; i++) {
            generateTime += 64000L;
            pending.clear();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock;
            if (i == 3) {
                // The carrier: a standard main-candidate block plus the 32-byte EVM tx ref.
                extraBlock = new Block(config, xdagTime, null, pending, true, null, null, -1,
                        XAmount.ZERO, null, evmRef);
                extraBlock.signOut(poolKey);
                extraBlock.setNonce(HashUtils.sha256(Bytes.wrap(new byte[]{0x12, 0x34})));
                carrier = extraBlock;
            } else {
                extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
            }
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }

        Block storedCarrier = blockchain.getBlockByHash(carrier.getHashLow(), false);
        long carrierHeight = storedCarrier.getInfo().getHeight();
        assertTrue("carrier must have been confirmed as a main block", carrierHeight > 0);

        // Receipt: successful deployment with a contract address.
        EvmReceipt receipt = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow();
        assertEquals(1, receipt.status());
        Address contract = receipt.contractAddress().orElseThrow();

        // World state: runtime code deposited, deployer nonce bumped.
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(evmStateSource);
        assertEquals(RUNTIME, world.getAccount(contract).getCode());
        assertEquals(UInt256.ZERO, world.getAccount(contract).getStorageValue(UInt256.ZERO));
        assertEquals(1L, world.getAccount(deployTx.getSender()).getNonce());

        // EVM_META checkpoint at the carrier's main height.
        assertEquals(List.of(deployTx.getHash()), evmMetaStore.getTxList(carrierHeight));
        assertEquals(1, evmMetaStore.getHeightRecord(carrierHeight).orElseThrow().txCount());
    }
}
