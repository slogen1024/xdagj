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
import io.xdag.evm.bridge.BridgeConstants;
import io.xdag.evm.bridge.BridgeWithdrawal;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * End-to-end Phase-3b withdrawal-release proof over a real RocksDB-backed {@link BlockchainImpl}:
 * a burn record at height {@code H} (INJECTED into EVM_META 0x07 here — Task 6 produces it
 * end-to-end via the executor's burn scan) releases native nano from the bridge lock address to
 * its native target when the chain confirms {@code H + N} (devnet {@code N = 2}), journals what
 * was actually released (EVM_META 0x08), refuses to release while immature or when the lock
 * cannot cover the whole height, and reverses exactly on unwind.
 *
 * <p>Scaffold reuses the Phase-3a {@link BridgeDepositIntegrationTest} harness (same store/EVM
 * wiring and {@code MockBlockchain}). Main blocks are driven ONE epoch at a time — a single
 * {@code tryToConnect} runs {@code checkNewMain} once, promoting at most one main block — so
 * tests can land on an EXACT confirmed height (the immature test depends on not overshooting).
 */
public class BridgeWithdrawalIntegrationTest {

    /** Arbitrary 20-byte native release targets; distinct from the pool key and the lock address. */
    private static final Bytes TARGET_1 = Bytes.fromHexString("0x4444444444444444444444444444444444444444");
    private static final Bytes TARGET_2 = Bytes.fromHexString("0x5555555555555555555555555555555555555555");

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private EvmMetaStore evmMetaStore;

    private BridgeDepositIntegrationTest.MockBlockchain blockchain;
    private ECKeyPair poolKey;
    private long generateTime = 1600616700000L;
    private Bytes32 ref;

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
        KVSource<byte[], byte[]> evmStateSource = dbFactory.getDB(DatabaseName.EVM_STATE);
        evmStateSource.init();
        evmStateSource.reset();
        KVSource<byte[], byte[]> evmTxSource = dbFactory.getDB(DatabaseName.EVM_TX);
        evmTxSource.init();
        evmTxSource.reset();
        KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
        evmMetaSource.init();
        evmMetaSource.reset();
        EvmTxStore evmTxStore = new EvmTxStore(evmTxSource);
        evmMetaStore = new EvmMetaStore(evmMetaSource);
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmBlockProcessor(
                new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore));

        blockchain = new BridgeDepositIntegrationTest.MockBlockchain(kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    @Test
    public void matured_withdrawal_releases_native_funds_after_n_blocks() {
        seedChain();
        long delay = pinnedDevnetDelay();
        long burnHeight = injectBurns(100L, List.of(new BridgeWithdrawal(TARGET_1, 40L)));

        driveToConfirmedHeight(burnHeight + delay);

        AddressStore addressStore = blockchain.getAddressStore();
        assertEquals("the matured burn must credit its native target",
                XAmount.of(40L), addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("the lock must be debited by exactly the released amount",
                XAmount.of(60L), addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertEquals("the release height must journal what was actually released",
                List.of(new BridgeWithdrawal(TARGET_1, 40L)), evmMetaStore.getReleases(burnHeight + delay));
    }

    @Test
    public void immature_withdrawal_does_not_release() {
        seedChain();
        long delay = pinnedDevnetDelay();
        long burnHeight = injectBurns(100L, List.of(new BridgeWithdrawal(TARGET_1, 40L)));

        driveToConfirmedHeight(burnHeight + delay - 1);

        AddressStore addressStore = blockchain.getAddressStore();
        assertEquals("an immature burn must not touch the target",
                XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("an immature burn must not touch the lock",
                XAmount.of(100L), addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertNoReleaseJournalAnywhere();
    }

    @Test
    public void insufficient_lock_skips_all_releases_deterministically() {
        seedChain();
        long delay = pinnedDevnetDelay();
        // Lock holds 10 nano but the height wants 16: the FIRST shortfall must skip the whole
        // height (deterministic skip-all), not release entry one and fail on entry two.
        long burnHeight = injectBurns(10L, List.of(
                new BridgeWithdrawal(TARGET_1, 8L),
                new BridgeWithdrawal(TARGET_2, 8L)));

        driveToConfirmedHeight(burnHeight + delay);

        AddressStore addressStore = blockchain.getAddressStore();
        assertEquals(XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals(XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_2.toArray()));
        assertEquals("the lock must be untouched by a skipped height",
                XAmount.of(10L), addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertNoReleaseJournalAnywhere();
    }

    @Test
    public void unwound_release_is_reversed_exactly() {
        // First a test-1-style release...
        seedChain();
        long delay = pinnedDevnetDelay();
        long burnHeight = injectBurns(100L, List.of(new BridgeWithdrawal(TARGET_1, 40L)));
        long releaseHeight = burnHeight + delay;
        driveToConfirmedHeight(releaseHeight);
        AddressStore addressStore = blockchain.getAddressStore();
        assertEquals(XAmount.of(40L), addressStore.getBalanceByAddress(TARGET_1.toArray()));

        // ...then reverse it. Direct-call route: this harness drives a single canonical chain and
        // has no fork scaffold, so the reversal method is package-private and invoked directly with
        // the 0x08 journal present — exactly the state unWindMain would call it in.
        blockchain.reverseReleasedWithdrawals(releaseHeight);

        assertEquals("the reversal must take back exactly what was released",
                XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("the reversal must restore the lock exactly",
                XAmount.of(100L), addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertEquals("the consumed journal must be deleted",
                List.of(), evmMetaStore.getReleases(releaseHeight));
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /** Seeds the chain (address block + 10 epochs + checkMain) so main promotion is rolling. */
    private void seedChain() {
        poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        ref = addressBlock.getHashLow();
        for (int i = 1; i <= 10; i++) {
            addOneExtraBlock();
        }
        blockchain.checkMain();
        assertTrue("the seed must confirm at least one main block", blockchain.getXdagStats().nmain > 0);
    }

    /**
     * Pre-funds the lock address with {@code lockNano} (direct AddressStore write — protocol
     * balances live there) and injects {@code burns} as the 0x07 record of a burn height chosen
     * strictly in the future, so its release height cannot already have passed.
     */
    private long injectBurns(long lockNano, List<BridgeWithdrawal> burns) {
        blockchain.getAddressStore().updateBalance(BridgeConstants.LOCK_ADDRESS_20.toArray(),
                XAmount.of(lockNano));
        long burnHeight = blockchain.getXdagStats().nmain + 3;
        evmMetaStore.putWithdrawals(burnHeight, burns);
        return burnHeight;
    }

    /** Devnet pins N = 2; the maturity tests are meaningless if config drift changes that silently. */
    private long pinnedDevnetDelay() {
        long delay = config.getEvmSpec().getEvmBridgeWithdrawalDelay();
        assertEquals("devnet evm.bridgeWithdrawalDelay must be 2 for these vectors", 2L, delay);
        return delay;
    }

    /**
     * Adds extra blocks one epoch at a time until EXACTLY {@code target} main blocks are confirmed.
     * Cannot overshoot: each import runs checkNewMain once, which calls setMain at most once.
     */
    private void driveToConfirmedHeight(long target) {
        int guard = 0;
        while (blockchain.getXdagStats().nmain < target) {
            assertTrue("chain failed to reach confirmed height " + target, ++guard <= 300);
            addOneExtraBlock();
        }
        assertEquals(target, blockchain.getXdagStats().nmain);
    }

    private void addOneExtraBlock() {
        generateTime += 64000L;
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(ref, XDAG_FIELD_OUT, false));
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
        ref = extraBlock.getHashLow();
    }

    private void assertNoReleaseJournalAnywhere() {
        long horizon = blockchain.getXdagStats().nmain + 3;
        for (long h = 0; h <= horizon; h++) {
            assertEquals("no 0x08 journal may exist at height " + h,
                    List.of(), evmMetaStore.getReleases(h));
        }
    }
}
