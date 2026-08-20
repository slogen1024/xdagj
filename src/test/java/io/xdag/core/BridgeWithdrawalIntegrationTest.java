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
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
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
import io.xdag.evm.bridge.BridgeConstants;
import io.xdag.evm.bridge.BridgeContract;
import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.bridge.BridgeRemark;
import io.xdag.evm.bridge.BridgeWithdrawal;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * End-to-end Phase-3b withdrawal-release proof over a real RocksDB-backed {@link BlockchainImpl}:
 * a burn record at height {@code H} releases native nano from the bridge lock address to its
 * native target when the chain confirms {@code H + N} (devnet {@code N = 2}), journals what was
 * actually released (EVM_META 0x08), refuses to release while immature or when the lock cannot
 * cover the whole height, and reverses exactly on unwind. Tests 1-4 INJECT the 0x07 record to
 * isolate the release rule; the Task 6 capstone below produces it end-to-end — a real native
 * deposit mints the EVM signer, whose real {@code withdraw(T)} tx burns through a real
 * 0x0F-carrier main block, and maturation releases with NO injected records.
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

    /**
     * Fixed EVM signer E for the full-cycle tests (SECP256K1 private key 2). Key 1 is deliberately
     * AVOIDED: its address is exactly the devnet bridgeRecoveryAddress, which would make "minted to
     * the remark target" indistinguishable from the undecodable-remark recovery fallback.
     */
    private static final SECP256K1 EVM_ALGO = new SECP256K1();
    private static final KeyPair EVM_KEY = EVM_ALGO.createKeyPair(EVM_ALGO.createPrivateKey(BigInteger.TWO));
    private static final org.hyperledger.besu.datatypes.Address EVM_SENDER =
            org.hyperledger.besu.datatypes.Address.extract(EVM_KEY.getPublicKey());

    /** The full-cycle burn: 40 XDAG expressed in nano and in wei (an exact nano multiple). */
    private static final long BURN_NANO = 40_000_000_000L;
    private static final BigInteger BURN_WEI =
            BigInteger.valueOf(BURN_NANO).multiply(BridgeConstants.WEI_PER_NANO);

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
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

        // EVM services exactly as Kernel.startComponents wires them for evm.enabled networks. The
        // EvmConfig mirrors Kernel's 6-arg construction: the PROCESSOR must carry the devnet spec
        // bridgeActivationHeight (0) or the bridge contract never seeds and the burn scan is dead
        // code — the EvmConfig.devnet() factory alone keeps the bridge unscheduled (MAX_VALUE).
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
        EvmConfig evmConfig = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE,
                EvmConfig.DEFAULT_TYPE2_ACTIVATION_HEIGHT,
                config.getEvmSpec().getEvmBridgeActivationHeight());
        EvmBlockProcessor evmBlockProcessor =
                new EvmBlockProcessor(evmConfig, evmStateSource, evmTxStore, evmMetaStore);
        evmBlockProcessor.seedBridgeContractIfAbsent(); // Kernel invokes this at startup; mirror it
        kernel.setEvmBlockProcessor(evmBlockProcessor);

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
        assertEquals("a skipped height must not credit target 1",
                XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("a skipped height must not credit target 2",
                XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_2.toArray()));
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
    // Task 6 capstone: the whole pipeline with NO injected records — deposit mints E, E's real
    // withdraw(T) burns through a real 0x0F-carrier main block, maturation releases to T.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void full_cycle_deposit_burn_and_release() {
        seedChain();
        long delay = pinnedDevnetDelay();
        AddressStore addressStore = blockchain.getAddressStore();
        addressStore.updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));

        // 1. The native deposit: 100 XDAG pool -> lock, remark = encode(E) (the 3a pattern).
        Block depositTx = connectLockTransfer(BridgeRemark.encode(EVM_SENDER), UInt64.ONE);

        // 2. E's burn, signed and stored up-front (blob-first): a legacy withdraw(TARGET_1) call of
        // exactly BURN_WEI at gasPrice 1 / gasLimit 100k. Carrier route: the crafted-main-candidate
        // pattern of EvmConsensusIntegrationTest — an extra block whose 0x0F field is the tx hash.
        EvmTransaction burnTx = EvmTransaction.unsigned(0L, Wei.of(1), 100_000L,
                Optional.of(BridgeContract.ADDRESS), Wei.of(BURN_WEI), withdrawCalldata(TARGET_1),
                EvmConfig.DEVNET_CHAIN_ID).sign(EVM_KEY, EVM_ALGO);
        evmTxStore.put(burnTx);

        Block depositLink = addLinkingExtraBlock(depositTx, null);
        Block burnCarrier = addLinkingExtraBlock(null, Bytes32.wrap(burnTx.getHash().getBytes()));
        long depositHeight = driveUntilMainConfirmed(depositLink);
        long burnHeight = driveUntilMainConfirmed(burnCarrier);
        assertTrue("the mint height must precede the burn height", depositHeight < burnHeight);

        // The mint is pinned to the lock's ACTUAL native credit (3a rule: never re-derive the fee).
        long depositNano = lockBalanceNano();
        assertTrue("the deposit must out-fund the burn", depositNano > BURN_NANO);
        assertEquals("the deposit height must carry E's mint in EVM_META 0x06",
                List.of(new BridgeDeposit(EVM_SENDER, depositNano)),
                evmMetaStore.getDeposits(depositHeight));
        EvmReceipt burnReceipt = evmMetaStore.getReceipt(burnTx.getHash()).orElseThrow();
        assertEquals("the withdraw call must execute successfully at the carrier height",
                1, burnReceipt.status());
        assertEquals("the burn scan must record (T, nano) at the burn height",
                List.of(new BridgeWithdrawal(TARGET_1, BURN_NANO)), evmMetaStore.getWithdrawals(burnHeight));

        // 3. Maturation: exactly N = 2 more confirmed main blocks.
        driveToConfirmedHeight(burnHeight + delay);

        // 4. The release and every conservation figure of the cycle.
        assertEquals("T must receive exactly the burned nano",
                XAmount.of(BURN_NANO), addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("the lock must hold deposit minus burn",
                XAmount.of(depositNano - BURN_NANO),
                addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertEquals("the release journal must record what was released",
                List.of(new BridgeWithdrawal(TARGET_1, BURN_NANO)),
                evmMetaStore.getReleases(burnHeight + delay));
        assertEquals("the burned wei stays on the contract (audit balance)",
                BURN_WEI, evmBalance(BridgeContract.ADDRESS));
        assertEquals("E must hold minted minus burned minus gas (gasPrice 1)",
                BigInteger.valueOf(depositNano).multiply(BridgeConstants.WEI_PER_NANO)
                        .subtract(BURN_WEI).subtract(BigInteger.valueOf(burnReceipt.gasUsed())),
                evmBalance(EVM_SENDER));
    }

    @Test
    public void reorg_before_maturation_cancels_the_release() {
        seedChain();
        long delay = pinnedDevnetDelay();
        long burnHeight = injectBurns(100L, List.of(new BridgeWithdrawal(TARGET_1, 40L)));
        driveToConfirmedHeight(burnHeight); // the burn is canonical; its release is still N away

        // Route (stated honestly): this harness drives one canonical chain and cannot grow a
        // heavier competing branch, so the unwind is exercised the way test 4 sanctioned — by
        // invoking exactly what unWindMain runs for an unwind past the burn height: the per-height
        // 0x08 reversal (provably a no-op here: nothing has been released yet) and the trailing
        // EVM rollback whose removeAbove sweep cancels the immature 0x07 record.
        blockchain.reverseReleasedWithdrawals(burnHeight);
        kernel.getEvmBlockProcessor().rollbackTo(burnHeight - 1);
        assertEquals("the unwound burn record must be swept from 0x07",
                List.of(), evmMetaStore.getWithdrawals(burnHeight));

        driveToConfirmedHeight(burnHeight + delay);

        AddressStore addressStore = blockchain.getAddressStore();
        assertEquals("a cancelled burn must never credit its target",
                XAmount.ZERO, addressStore.getBalanceByAddress(TARGET_1.toArray()));
        assertEquals("the lock must be untouched after the cancellation",
                XAmount.of(100L), addressStore.getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray()));
        assertNoReleaseJournalAnywhere();
    }

    @Test
    public void mixed_height_deposit_and_burn_order() {
        seedChain();
        AddressStore addressStore = blockchain.getAddressStore();
        addressStore.updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));

        Block depositTx = connectLockTransfer(BridgeRemark.encode(EVM_SENDER), UInt64.ONE);
        EvmTransaction burnTx = EvmTransaction.unsigned(0L, Wei.of(1), 100_000L,
                Optional.of(BridgeContract.ADDRESS), Wei.of(BURN_WEI), withdrawCalldata(TARGET_2),
                EvmConfig.DEVNET_CHAIN_ID).sign(EVM_KEY, EVM_ALGO);
        evmTxStore.put(burnTx);

        // ONE main block links the deposit AND carries E's burn as its 0x0F ref. E holds NOTHING
        // before this height, so the burn can only afford its value if the same-height mint
        // executes first — the spec §3.2 order pin (mint -> execute -> scan).
        Block mixed = addLinkingExtraBlock(depositTx, Bytes32.wrap(burnTx.getHash().getBytes()));
        long height = driveUntilMainConfirmed(mixed);

        long depositNano = lockBalanceNano();
        assertEquals("0x06 must carry the height's mint",
                List.of(new BridgeDeposit(EVM_SENDER, depositNano)), evmMetaStore.getDeposits(height));
        assertEquals("0x07 must carry the height's burn",
                List.of(new BridgeWithdrawal(TARGET_2, BURN_NANO)), evmMetaStore.getWithdrawals(height));
        EvmReceipt receipt = evmMetaStore.getReceipt(burnTx.getHash()).orElseThrow();
        assertEquals("the same-height burn must execute successfully on the fresh mint",
                1, receipt.status());
        assertEquals("the burned wei must sit on the contract",
                BURN_WEI, evmBalance(BridgeContract.ADDRESS));
        assertEquals("E must hold minted minus burned minus gas (gasPrice 1)",
                BigInteger.valueOf(depositNano).multiply(BridgeConstants.WEI_PER_NANO)
                        .subtract(BURN_WEI).subtract(BigInteger.valueOf(receipt.gasUsed())),
                evmBalance(EVM_SENDER));
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

    /**
     * withdraw(bytes20) calldata: selector ‖ target20 ‖ 12 zero bytes (bytes20 is left-aligned).
     * The ONE core-package copy of the Task 4 shape (mirrors EvmBlockProcessorTest#withdrawCalldata,
     * which is private to the io.xdag.evm test package).
     */
    private static Bytes withdrawCalldata(Bytes target20) {
        return Bytes.concatenate(BridgeContract.WITHDRAW_SELECTOR, target20, Bytes.repeat((byte) 0, 12));
    }

    /** Connects a 100-XDAG pool-to-lock transfer carrying {@code remark} in the CURRENT epoch. */
    private Block connectLockTransfer(String remark, UInt64 nonce) {
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()),
                XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(BridgeConstants.LOCK_ADDRESS_20.toArray()),
                XDAG_FIELD_OUTPUT, true);
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block txBlock = BridgeDepositIntegrationTest.generateTransactionWithRemark(config, poolKey,
                xdagTime - 1, from, to, XAmount.of(100, XUnit.XDAG), remark, nonce);
        ImportResult result = blockchain.tryToConnect(txBlock);
        assertTrue(result == IMPORTED_NOT_BEST || result == IMPORTED_BEST);
        return txBlock;
    }

    /**
     * Adds the next epoch's main-candidate extra block, optionally linking {@code txBlock} (tx link
     * FIRST, chain ref second — the 3a linking order) and/or carrying {@code evmRef} as its 0x0F
     * field (the EvmConsensusIntegrationTest carrier shape).
     */
    private Block addLinkingExtraBlock(Block txBlock, Bytes32 evmRef) {
        generateTime += 64000L;
        List<Address> pending = new ArrayList<>();
        if (txBlock != null) {
            pending.add(new Address(txBlock.getHashLow(), false));
        }
        pending.add(new Address(ref, XDAG_FIELD_OUT, false));
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block extraBlock = new Block(config, xdagTime, null, pending, true, null, null, -1,
                XAmount.ZERO, null, evmRef);
        extraBlock.signOut(poolKey);
        extraBlock.setNonce(HashUtils.sha256(Bytes.wrap(new byte[]{0x12, 0x34})));
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
        ref = extraBlock.getHashLow();
        return extraBlock;
    }

    /** Drives one epoch at a time until {@code block} is promoted to main; returns its height. */
    private long driveUntilMainConfirmed(Block block) {
        int guard = 0;
        while (blockchain.getBlockByHash(block.getHashLow(), false).getInfo().getHeight() == 0) {
            assertTrue("block " + block.getHashLow().toHexString() + " was never confirmed as main",
                    ++guard <= 300);
            addOneExtraBlock();
        }
        return blockchain.getBlockByHash(block.getHashLow(), false).getInfo().getHeight();
    }

    private long lockBalanceNano() {
        return blockchain.getAddressStore()
                .getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray())
                .toDecimal(0, XUnit.NANO_XDAG).longValueExact();
    }

    private BigInteger evmBalance(org.hyperledger.besu.datatypes.Address address) {
        Account account = new RocksDbWorldUpdater(evmStateSource).getAccount(address);
        return account == null ? BigInteger.ZERO : account.getBalance().getAsBigInteger();
    }

    private void assertNoReleaseJournalAnywhere() {
        long horizon = blockchain.getXdagStats().nmain + 3;
        for (long h = 0; h <= horizon; h++) {
            assertEquals("no 0x08 journal may exist at height " + h,
                    List.of(), evmMetaStore.getReleases(h));
        }
    }
}
