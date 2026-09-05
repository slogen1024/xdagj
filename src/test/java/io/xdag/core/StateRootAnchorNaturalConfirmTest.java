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
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * Audit round 2, finding C3 regression: blocks anchored the way the miner anchors them (settle ->
 * predict position -> root(N - lag - 1)) must confirm through the NATURAL tryToConnect -> checkNewMain
 * path under hard-reject. Under the pre-fix rule (nmain+1, root(N - lag)) every such block was a
 * MISMATCH and setMain froze the chain.
 */
public class StateRootAnchorNaturalConfirmTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    // Devnet + hard-reject forced on, so a MISMATCH freezes nmain instead of merely logging.
    private final Config config = new DevnetConfig() {
        @Override
        public boolean isEvmStateRootHardReject() {
            return true;
        }
    };
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private EvmMetaStore evmMetaStore;
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
        evmMetaStore = new EvmMetaStore(evmMetaSource);
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

    /** A main-candidate block linking {@code pretop}, anchored exactly as createMainBlock anchors. */
    private Block minedBlock(Bytes32 pretop, long xdagTime, int seed) {
        EvmStateAnchor anchor = blockchain.prepareStateRootAnchor(pretop);
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(pretop, XDAG_FIELD_OUT, false));
        Block b = new Block(config, xdagTime, null, pending, true, null, null, -1, XAmount.ZERO, null, null,
                anchor);
        b.signOut(poolKey);
        b.setNonce(HashUtils.sha256(Bytes.of((byte) seed, (byte) (seed >> 8))));
        return b;
    }

    @Test
    public void miner_anchored_blocks_confirm_through_checkNewMain_under_hard_reject() {
        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        Bytes32 pretop = addressBlock.getHashLow();

        int blocks = 12;
        List<Block> mined = new ArrayList<>();
        for (int i = 1; i <= blocks; i++) {
            generateTime += 64000L;
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block b = minedBlock(pretop, xdagTime, i);
            assertSame("block " + i + " must import (an import-time MISMATCH would be INVALID_BLOCK)",
                    IMPORTED_BEST, blockchain.tryToConnect(b));
            mined.add(b);
            pretop = b.getHashLow();
        }
        // Settle everything that can be confirmed: the top candidate stays unconfirmed by design.
        blockchain.checkNewMain();
        blockchain.checkNewMain();

        long nmain = blockchain.getXdagStats().nmain;
        assertTrue("hard-reject must not have frozen the chain: nmain=" + nmain, nmain >= blocks - 1);

        long lag = config.getEvmSpec().getEvmStateRootLag();
        int anchored = 0;
        for (Block b : mined) {
            Block stored = blockchain.getBlockByHash(b.getHashLow(), false);
            long height = stored.getInfo().getHeight();
            if (height == 0) {
                continue; // the still-unconfirmed top
            }
            EvmStateAnchor anchor = b.getEvmStateAnchor();
            if (height - lag - 1 < 0) {
                assertNull("no anchorable height yet at confirmed height " + height, anchor);
            } else {
                assertNotNull("confirmed height " + height + " must carry an anchor", anchor);
                assertEquals("anchor must commit root(height - lag - 1) for confirmed height " + height,
                        height - lag - 1, anchor.height());
                anchored++;
            }
        }
        assertTrue("the scenario must exercise real anchors", anchored >= blocks - 3);
    }
}
