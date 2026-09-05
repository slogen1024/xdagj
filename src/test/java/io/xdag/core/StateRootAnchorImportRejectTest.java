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
import static io.xdag.core.ImportResult.INVALID_BLOCK;
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
 * Audit round 2, finding C4: a main candidate whose state-root anchor is verifiably wrong must be
 * rejected AT IMPORT (never stored, never a pretop) on a hard-reject network, so one crafted block
 * cannot park the whole network in setMain's freeze. A node that cannot verify yet (BEHIND) accepts.
 */
public class StateRootAnchorImportRejectTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

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
    private long generateTime = 1600616700000L;

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

    private long nextEpochTime() {
        generateTime += 64000L;
        return XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
    }

    /** A main candidate linking {@code pretop} carrying {@code anchor} (null = anchorless). */
    private Block candidate(Bytes32 pretop, long xdagTime, EvmStateAnchor anchor, int seed) {
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(pretop, XDAG_FIELD_OUT, false));
        Block b = new Block(config, xdagTime, null, pending, true, null, null, -1, XAmount.ZERO, null, null,
                anchor);
        b.signOut(poolKey);
        b.setNonce(HashUtils.sha256(Bytes.of((byte) seed, (byte) (seed >> 8))));
        return b;
    }

    /** Address block + {@code count} honestly anchored candidates; returns the top. */
    private Bytes32 buildAnchoredChain(int count) {
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        Bytes32 pretop = addressBlock.getHashLow();
        for (int i = 1; i <= count; i++) {
            long xdagTime = nextEpochTime();
            Block b = candidate(pretop, xdagTime, blockchain.prepareStateRootAnchor(pretop), i);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(b));
            pretop = b.getHashLow();
        }
        return pretop;
    }

    private EvmStateAnchor wrongRootAnchor(EvmStateAnchor honest) {
        assertNotNull("fixture must be at an anchorable height", honest);
        Bytes wrongLow = Bytes.repeat((byte) 0xFF, EvmStateAnchor.ROOT_LOW_LENGTH);
        return new EvmStateAnchor(honest.height(), wrongLow, honest.daSkip());
    }

    @Test
    public void wrong_root_anchor_is_rejected_at_import_and_a_sibling_continues_the_chain() {
        Bytes32 pretop = buildAnchoredChain(5);
        long nmainBefore = blockchain.getXdagStats().nmain;
        long xdagTime = nextEpochTime();

        Block bad = candidate(pretop, xdagTime, wrongRootAnchor(blockchain.prepareStateRootAnchor(pretop)), 100);
        assertSame("a verifiably divergent anchor must be INVALID at import",
                INVALID_BLOCK, blockchain.tryToConnect(bad));
        assertNull("a rejected block must not be stored", blockchain.getBlockByHash(bad.getHashLow(), false));

        // An honest sibling for the same epoch takes the slot and the chain keeps confirming.
        Block good = candidate(pretop, xdagTime, blockchain.prepareStateRootAnchor(pretop), 101);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(good));
        Bytes32 top = good.getHashLow();
        for (int i = 0; i < 3; i++) {
            Block b = candidate(top, nextEpochTime(), blockchain.prepareStateRootAnchor(top), 200 + i);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(b));
            top = b.getHashLow();
        }
        blockchain.settleMainChainConfirmations();
        assertTrue("the chain must have advanced past the rejected block's slot",
                blockchain.getXdagStats().nmain > nmainBefore + 1);
        assertTrue("the honest sibling must be confirmed as main",
                blockchain.getBlockByHash(good.getHashLow(), false).getInfo().getHeight() > 0);
    }

    @Test
    public void anchorless_mined_candidate_is_rejected_at_import_once_anchoring_is_active() {
        Bytes32 pretop = buildAnchoredChain(5);
        Block bare = candidate(pretop, nextEpochTime(), null, 100);
        assertSame(INVALID_BLOCK, blockchain.tryToConnect(bare));
        assertNull(blockchain.getBlockByHash(bare.getHashLow(), false));
    }

    @Test
    public void wrong_height_anchor_is_rejected_at_import() {
        Bytes32 pretop = buildAnchoredChain(5);
        EvmStateAnchor honest = blockchain.prepareStateRootAnchor(pretop);
        // The pre-fix index (one higher) is exactly what an un-upgraded miner would commit.
        EvmStateAnchor wrongHeight = new EvmStateAnchor(honest.height() + 1, honest.rootLow(), honest.daSkip());
        Block bad = candidate(pretop, nextEpochTime(), wrongHeight, 100);
        assertSame(INVALID_BLOCK, blockchain.tryToConnect(bad));
    }

    @Test
    public void a_node_that_is_behind_on_evm_execution_accepts_and_defers() {
        Bytes32 pretop = buildAnchoredChain(5);
        EvmStateAnchor honest = blockchain.prepareStateRootAnchor(pretop);
        // Simulate a blob-deferred (pending) height at/below the anchored height: this node cannot
        // verify the root yet -> BEHIND -> accept (setMain re-verifies later), never INVALID.
        evmMetaStore.putPending(honest.height(), Bytes32.ZERO, 1L, List.of(Bytes32.ZERO));
        Block unverifiable = candidate(pretop, nextEpochTime(), wrongRootAnchor(honest), 100);
        ImportResult result = blockchain.tryToConnect(unverifiable);
        assertTrue("BEHIND must not reject at import, got " + result,
                result == IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST);
        assertNotNull(blockchain.getBlockByHash(unverifiable.getHashLow(), false));
    }

    @Test
    public void honest_anchor_still_imports() {
        Bytes32 pretop = buildAnchoredChain(5);
        Block good = candidate(pretop, nextEpochTime(), blockchain.prepareStateRootAnchor(pretop), 100);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(good));
        assertEquals(IMPORTED_BEST, ImportResult.IMPORTED_BEST);
    }
}
