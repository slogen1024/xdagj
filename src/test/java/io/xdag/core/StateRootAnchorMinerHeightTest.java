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
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * Audit round 2, finding C3: the miner must predict the height at which its block will be CONFIRMED
 * (its main-chain position), not {@code nmain + 1}. checkNewMain confirms a candidate only once
 * another candidate sits above it, so at template time the pretop -- and, because tryToConnect runs
 * checkNewMain before the top update, usually its predecessor too -- is still unconfirmed.
 */
public class StateRootAnchorMinerHeightTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private BlockchainImpl blockchain;
    private ECKeyPair poolKey;

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
        poolKey = key;

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

        KVSource<byte[], byte[]> evmStateSource = dbFactory.getDB(DatabaseName.EVM_STATE);
        evmStateSource.init();
        evmStateSource.reset();
        KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
        evmMetaSource.init();
        evmMetaSource.reset();
        EvmMetaStore evmMetaStore = new EvmMetaStore(evmMetaSource);
        EvmTxStore evmTxStore = new EvmTxStore(new InMemoryKVSource());
        EvmTxPool evmTxPool = new EvmTxPool(evmTxStore, evmStateSource,
                BigInteger.valueOf(0xCAFE), 30_000_000L, Wei.ONE, 3600L, () -> 1000L);
        kernel.setEvmStateStore(evmStateSource);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmTxPool(evmTxPool);
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmBlockProcessor(new EvmBlockProcessor(
                EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore));

        blockchain = new BlockchainImpl(kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    /** Connects {@code count} main-candidate blocks at successive past epochs; returns the last hashlow. */
    private Bytes32 buildChain(int count, long[] generateTime, Bytes32 ref) {
        List<Address> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            generateTime[0] += 64000L;
            pending.clear();
            pending.add(new Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime[0]));
            Block extra = generateExtraBlock(config, poolKey, xdagTime, pending);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extra));
            ref = extra.getHashLow();
        }
        return ref;
    }

    @Test
    public void predicts_the_height_at_which_the_next_block_is_confirmed() {
        long[] generateTime = {1600616700000L};
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime[0]);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        Bytes32 pretop = buildChain(6, generateTime, addressBlock.getHashLow());

        // Template time for the next block: the miner links the current top as its pretop.
        long nmainAtTemplate = blockchain.getXdagStats().nmain;
        long predicted = blockchain.predictNextMainHeight(pretop);

        // Connect the block that the template stands for, then enough candidates above it to get it
        // confirmed through the natural tryToConnect -> checkNewMain path.
        List<Address> pending = new ArrayList<>();
        generateTime[0] += 64000L;
        pending.add(new Address(pretop, XDAG_FIELD_OUT, false));
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime[0]));
        Block next = generateExtraBlock(config, poolKey, xdagTime, pending);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(next));
        buildChain(4, generateTime, next.getHashLow());

        long actual = blockchain.getBlockByHash(next.getHashLow(), false).getInfo().getHeight();
        assertTrue("the block must have been confirmed as a main block", actual > 0);
        assertTrue("the scenario must exhibit unconfirmed candidates at template time (else it proves "
                + "nothing): nmain+1=" + (nmainAtTemplate + 1) + " actual=" + actual,
                nmainAtTemplate + 1 < actual);
        assertEquals("predicted confirm height must equal the actual confirm height", actual, predicted);
    }
}
