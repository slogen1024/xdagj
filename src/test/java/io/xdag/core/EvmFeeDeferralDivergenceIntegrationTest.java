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
import static io.xdag.BlockBuilder.generateOldTransactionBlock;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.Constants;
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
 * Audit round 2, B1 (lag=2): a node that had to DEFER a matured height (blob missing) has not yet
 * credited that height's EVM fee to its payload block K. If a spend from K -- valid on every
 * never-behind node -- is confirmed before the blobs arrive, this node rejects it for insufficient
 * balance and never re-evaluates it, so its native state silently forks. The fix makes that event
 * loud and observable (CRITICAL + a divergence marker); the K1 drain credit still converges K's
 * amount so the common no-intermediate-spend case stays silent and correct.
 */
public class EvmFeeDeferralDivergenceIntegrationTest {

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
        GenesisLockSeeder.seedIfAbsent(addressStore, config.getEvmSpec());
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    @Test
    public void a_rejected_spend_from_a_fee_deferred_block_is_flagged_and_the_late_credit_still_lands() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // 1000 nano per gas unit
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        // The blob is WITHHELD: this node will have to defer height K when it matures.
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
            if (carrier != null && !evmMetaStore.pendingHeights().isEmpty()) {
                k = evmMetaStore.pendingHeights().get(0);
            }
        }
        assertTrue("height K must have matured and been DEFERRED (blob missing)", k > 0);
        Block blockK = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertEquals(k, blockK.getInfo().getHeight());
        assertEquals("no divergence flagged yet", -1L, blockchain.getEvmFeeDivergenceHeight());
        XAmount amountBeforeCredit = blockK.getInfo().getAmount();

        // A spend from K that a never-behind node accepts (K's amount there already includes the fee)
        // but that exceeds K's not-yet-credited amount here by one nano.
        XAmount spend = amountBeforeCredit.add(XAmount.ONE);
        generateTime += 64000L;
        long spendTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block payout = generateOldTransactionBlock(config, poolKey, spendTime,
                new Address(carrier.getHashLow(), false), new Address(addressBlock.getHashLow(), true), spend);
        ImportResult payoutImport = blockchain.tryToConnect(payout);
        assertTrue("tx block imports (not a main candidate)", payoutImport == IMPORTED_BEST
                || payoutImport == ImportResult.IMPORTED_NOT_BEST);
        for (int i = 0; i < 4; i++) {
            generateTime += 64000L;
            List<Address> pending = new ArrayList<>();
            pending.add(new Address(ref, XDAG_FIELD_OUT, false)); // extend the main chain tip ...
            if (i == 0) {
                pending.add(new Address(payout.getHashLow(), XDAG_FIELD_OUT, false)); // ... and pull in the payout
            }
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }
        assertTrue("the payout must have been applied (and rejected) by now",
                (blockchain.getBlockByHash(payout.getHashLow(), false).getInfo().flags & Constants.BI_MAIN_REF) != 0);
        assertEquals("K's amount is untouched: the spend was rejected on this node", amountBeforeCredit,
                blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getAmount());
        long flagged = blockchain.getEvmFeeDivergenceHeight();
        assertTrue("B1: rejecting a spend from a fee-deferred block must be flagged as a divergence", flagged > k);

        // The blob arrives: the K1 drain credits K late. The amount converges (and now covers the
        // spend), but the flag stays -- the rejected payout is never re-evaluated; only a re-sync heals.
        evmTxStore.put(deployTx);
        blockchain.onEvmBlobsAvailable();
        assertTrue(evmMetaStore.getReceipt(deployTx.getHash()).isPresent());
        assertTrue(evmMetaStore.pendingHeights().isEmpty());
        Block blockKAfter = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertTrue("fee credited on drain", blockKAfter.getInfo().getFee().isPositive());
        assertTrue("with the fee credited the spend would have been affordable",
                blockKAfter.getInfo().getAmount().greaterThanOrEqual(spend));
        assertEquals("the divergence marker survives the late credit", flagged, blockchain.getEvmFeeDivergenceHeight());
        assertFalse("payout stays unapplied on this node",
                (blockchain.getBlockByHash(payout.getHashLow(), false).getInfo().flags & Constants.BI_APPLIED) != 0);
        assertNotNull(blockKAfter);
    }
}
