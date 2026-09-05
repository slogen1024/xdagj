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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

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
import io.xdag.evm.bridge.BridgeConstants;
import io.xdag.evm.bridge.GenesisLockSeeder;
import io.xdag.evm.state.EvmMetaStore;
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
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * Audit round 2, C1 at the BlockchainImpl level (lag=2): unwinding the block that DECIDED a matured
 * height must undo that height's EVM outcome and its native fee credit, and re-buffer its payload so
 * the replacement block re-decides it. Pre-fix, unWindMain rolled the EVM back only above the lowest
 * unwound height, leaving the orphaned decision (and its fee credit) in place forever.
 */
public class EvmReorgReopenIntegrationTest {

    /** Initcode deploying runtime 0x602a600055 (SSTORE slot0 = 42 on every call). */
    private static final Bytes INIT_CODE = Bytes.fromHexString("0x64602a6000556000526005601bf3");

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig() {
        @Override
        public long getEvmStateRootLag() {
            return 2L;
        }
    };
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
    private EvmMetaStore evmMetaStore;
    private AddressStore addressStore;

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
        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
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
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmBlockProcessor(
                new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore));
        // The genesis alloc backs the lock so the fee credit debits a funded lock (A4).
        GenesisLockSeeder.seedIfAbsent(addressStore, config.getEvmSpec());
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    private XAmount lockBalance() {
        return addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray());
    }

    @Test
    public void unwinding_the_deciding_block_reopens_the_matured_height_and_reverses_its_fee_credit() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // 1000 nano per gas unit
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());
        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        Bytes32 ref = addressBlock.getHashLow();
        Block carrier = null;
        long k = -1L;
        for (int i = 1; i <= 12 && k < 0; i++) {
            generateTime += 64000L;
            List<Address> pending = new ArrayList<>();
            pending.add(new Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock;
            if (i == 3) {
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
            if (carrier != null && evmMetaStore.getReceipt(deployTx.getHash()).isPresent()) {
                k = blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getHeight();
            }
        }
        assertTrue("the carrier's height K must have matured and executed", k > 0);
        assertEquals("the deciding block K+1 must be confirmed", k + 1, blockchain.getXdagStats().nmain);

        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long feeNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue(feeNano > 0);
        Block blockK = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertEquals("fee was credited to the payload block K", XAmount.of(feeNano), blockK.getInfo().getFee());
        assertEquals("fee debit journaled at K", feeNano, evmMetaStore.getFeeDebit(k));
        XAmount amountK = blockK.getInfo().getAmount();
        XAmount lockAfterCredit = lockBalance();

        // Reorg: unwind everything above K (i.e. block K+1, which DECIDED height K under lag=2).
        blockchain.unWindMain(blockK);

        assertEquals("K stays main", k, blockchain.getXdagStats().nmain);
        assertTrue("K's EVM outcome is undone", evmMetaStore.getHeightRecord(k).isEmpty());
        assertTrue(evmMetaStore.getReceipt(deployTx.getHash()).isEmpty());
        EvmMetaStore.MaturityEntry reopened = evmMetaStore.getMaturityEntry(k).orElseThrow();
        assertEquals("K is buffered again with its payload", List.of(evmRef), reopened.refs());
        assertEquals("K's fee debit is reversed on the lock", lockAfterCredit.add(XAmount.of(feeNano)), lockBalance());
        assertEquals("K's fee debit journal is consumed", 0L, evmMetaStore.getFeeDebit(k));
        Block blockKAfter = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertEquals("K's fee credit is un-credited", XAmount.ZERO, blockKAfter.getInfo().getFee());
        assertEquals(amountK.subtract(XAmount.of(feeNano)), blockKAfter.getInfo().getAmount());
        assertNotNull(blockKAfter);
    }
}
