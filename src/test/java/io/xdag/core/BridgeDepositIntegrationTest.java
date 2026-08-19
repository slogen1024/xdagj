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
import io.xdag.evm.bridge.BridgeRemark;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.hyperledger.besu.evm.account.Account;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * End-to-end Phase-3a deposit proof over a real RocksDB-backed {@link BlockchainImpl}: a NATIVE
 * transfer to the bridge lock address, confirmed through {@code setMain}, mints wrapped funds in
 * the EVM world state — to the remark-encoded target when the remark decodes, to the network
 * recovery address when it does not, and to NOBODY before the bridge activation height (a
 * pre-activation deposit is a plain transfer, spec §1/§2.2).
 *
 * <p>Transfer/confirmation scaffolding follows {@code BlockchainTest.testNew2NewTransactionBlock};
 * the EVM store wiring follows {@code EvmConsensusIntegrationTest}. The expected mint is pinned to
 * the ACTUAL native credit of the lock address (its AddressStore balance delta) so the test never
 * re-derives the fee rule.
 */
public class BridgeDepositIntegrationTest {

    /** Remark vector for EVM target 0x3535…35 (spec §2.1); deliberately NOT the recovery address. */
    private static final String VALID_REMARK = "2RueRbXvwjnXoWxfFFesBWpWV3P8h6BK";
    private static final org.hyperledger.besu.datatypes.Address REMARK_TARGET =
            org.hyperledger.besu.datatypes.Address.fromHexString(
                    "0x3535353535353535353535353535353535353535");
    /** Devnet evm.bridgeRecoveryAddress. */
    private static final org.hyperledger.besu.datatypes.Address RECOVERY_ADDRESS =
            org.hyperledger.besu.datatypes.Address.fromHexString(
                    "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

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
    public void deposit_with_valid_remark_mints_to_the_remark_target() {
        // The vector itself: the remark string IS the encoding of the 0x3535…35 target.
        assertEquals(VALID_REMARK, BridgeRemark.encode(REMARK_TARGET));

        MockBlockchain blockchain = new MockBlockchain(kernel);
        XAmount lockDelta = runDepositFlow(blockchain, config, VALID_REMARK);

        long lockDeltaNano = lockDelta.toDecimal(0, XUnit.NANO_XDAG).longValueExact();
        BigInteger expectedWei = BigInteger.valueOf(lockDeltaNano).multiply(BridgeConstants.WEI_PER_NANO);
        assertEquals("mint must equal the lock's native credit at 1 nano = 10^9 wei",
                expectedWei, evmBalance(REMARK_TARGET));
        assertEquals("the recovery address must NOT receive a valid-remark deposit",
                BigInteger.ZERO, evmBalance(RECOVERY_ADDRESS));
    }

    @Test
    public void deposit_with_undecodable_remark_mints_to_the_recovery_address() {
        MockBlockchain blockchain = new MockBlockchain(kernel);
        XAmount lockDelta = runDepositFlow(blockchain, config, "hello");

        long lockDeltaNano = lockDelta.toDecimal(0, XUnit.NANO_XDAG).longValueExact();
        BigInteger expectedWei = BigInteger.valueOf(lockDeltaNano).multiply(BridgeConstants.WEI_PER_NANO);
        assertEquals("an undecodable remark must mint to the recovery address",
                expectedWei, evmBalance(RECOVERY_ADDRESS));
        assertEquals("no other account may be minted to", BigInteger.ZERO, evmBalance(REMARK_TARGET));
    }

    @Test
    public void pre_activation_deposit_does_not_mint() throws Exception {
        // A finite future activation height: deposits ARE collected in applyBlock (the bridge is
        // scheduled) but must be dropped by the exact-height consensus gate in setMain.
        Config preActivationConfig = new DevnetConfig() {
            @Override
            public long getEvmBridgeActivationHeight() {
                return 1_000_000L;
            }
        };
        MockBlockchain blockchain = newBlockchain(preActivationConfig);
        XAmount lockDelta = runDepositFlow(blockchain, preActivationConfig, VALID_REMARK);

        // Native semantics unchanged (plain transfer, asserted inside runDepositFlow) but NO mint.
        assertTrue(lockDelta.isPositive());
        assertEquals("no mint to the remark target before activation",
                BigInteger.ZERO, evmBalance(REMARK_TARGET));
        assertEquals("no mint to the recovery address before activation",
                BigInteger.ZERO, evmBalance(RECOVERY_ADDRESS));
    }

    @Test
    public void unscheduled_bridge_ignores_deposits_even_with_undecodable_remark() throws Exception {
        // Mainnet-like defaults: bridge never scheduled, NO recovery address configured. An
        // undecodable remark must not crash applyBlock (the recovery address is null here) and
        // nothing may mint; the native transfer still settles at the lock address.
        Config unscheduledConfig = new DevnetConfig() {
            @Override
            public long getEvmBridgeActivationHeight() {
                return Long.MAX_VALUE;
            }

            @Override
            public String getEvmBridgeRecoveryAddress() {
                return null;
            }
        };
        MockBlockchain blockchain = newBlockchain(unscheduledConfig);
        XAmount lockDelta = runDepositFlow(blockchain, unscheduledConfig, "hello");

        assertTrue(lockDelta.isPositive());
        assertEquals(BigInteger.ZERO, evmBalance(REMARK_TARGET));
        assertEquals(BigInteger.ZERO, evmBalance(RECOVERY_ADDRESS));
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /**
     * Funds the pool key, seeds 10 main blocks, sends ONE native transfer of 100 XDAG to the bridge
     * lock address carrying {@code remark}, confirms it with 16 more main blocks, and returns the
     * lock address's native balance delta (it starts at zero) — asserted positive so no test can
     * pass vacuously. Mirrors {@code BlockchainTest.testNew2NewTransactionBlock}.
     */
    private XAmount runDepositFlow(BlockchainImpl blockchain, Config cfg, String remark) {
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        blockchain.getAddressStore().updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(cfg, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));

        List<Address> pending = new ArrayList<>();
        Bytes32 ref = addressBlock.getHashLow();
        for (int i = 1; i <= 10; i++) {
            generateTime += 64000L;
            pending.clear();
            pending.add(new Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock = generateExtraBlock(cfg, poolKey, xdagTime, pending);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }
        blockchain.checkMain();

        // The deposit: 100 XDAG from the pool key to the lock address, remark riding the tx block.
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(BridgeConstants.LOCK_ADDRESS_20.toArray()),
                XDAG_FIELD_OUTPUT, true);
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block txBlock = generateTransactionWithRemark(cfg, poolKey, xdagTime - 1, from, to,
                XAmount.of(100, XUnit.XDAG), remark, UInt64.ONE);
        ImportResult result = blockchain.tryToConnect(txBlock);
        assertTrue(result == IMPORTED_NOT_BEST || result == IMPORTED_BEST);

        // Confirm the transfer through main blocks (the first extra block links the tx).
        pending.clear();
        pending.add(new Address(txBlock.getHashLow(), false));
        for (int i = 1; i <= 16; i++) {
            generateTime += 64000L;
            pending.add(new Address(ref, XDAG_FIELD_OUT, false));
            xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock = generateExtraBlock(cfg, poolKey, xdagTime, pending);
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
            pending.clear();
        }

        XAmount lockBalance = blockchain.getAddressStore()
                .getBalanceByAddress(BridgeConstants.LOCK_ADDRESS_20.toArray());
        assertTrue("the lock address must have been credited natively", lockBalance.isPositive());
        return lockBalance;
    }

    /**
     * {@code BlockBuilder.generateNewTransactionBlock} plus the remark parameter the deposit
     * protocol rides on ({@code Block}'s constructor threads it into the 32-byte remark field,
     * exactly as the wallet's {@code createNewBlock} does).
     */
    private static Block generateTransactionWithRemark(Config config, ECKeyPair key, long xdagTime,
            Address from, Address to, XAmount amount, String remark, UInt64 nonce) {
        List<Address> refs = new ArrayList<>();
        List<ECKeyPair> keys = new ArrayList<>();
        refs.add(new Address(from.getAddress(), XDAG_FIELD_INPUT, amount, true));
        refs.add(new Address(to.getAddress(), XDAG_FIELD_OUTPUT, amount, true));
        keys.add(key);
        Block b = new Block(config, xdagTime, refs, null, false, keys, remark, 0,
                XAmount.of(100, XUnit.MILLI_XDAG), nonce);
        b.signOut(key);
        return b;
    }

    /** Kernel sharing this test's stores/wallet/EVM processor but running an overridden config. */
    private MockBlockchain newBlockchain(Config cfg) throws Exception {
        cfg.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        cfg.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        Kernel k = new Kernel(cfg, wallet.getDefKey());
        k.setBlockStore(kernel.getBlockStore());
        k.setOrphanBlockStore(kernel.getOrphanBlockStore());
        k.setAddressStore(kernel.getAddressStore());
        k.setTxHistoryStore(kernel.getTxHistoryStore());
        k.setWallet(wallet);
        k.setEvmTxStore(evmTxStore);
        k.setEvmMetaStore(evmMetaStore);
        k.setEvmBlockProcessor(kernel.getEvmBlockProcessor());
        return new MockBlockchain(k);
    }

    private BigInteger evmBalance(org.hyperledger.besu.datatypes.Address address) {
        Account account = new RocksDbWorldUpdater(evmStateSource).getAccount(address);
        return account == null ? BigInteger.ZERO : account.getBalance().getAsBigInteger();
    }

    static class MockBlockchain extends BlockchainImpl {

        public MockBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
        }

        @Override
        public void addOurBlock(int keyIndex, Block block) {
        }
    }
}
