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
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.evm.state.InMemoryKVSource;
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
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
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
        // The shared fixture runs at devnet lag=1 (immediate execution). The lag-2 test builds its own
        // kernel/stores over a lag-2 config via #buildFixture, so setUp's wiring must not diverge.
        buildFixture(config);
    }

    /**
     * Wires a fresh RocksDB-backed kernel + EVM stores over {@code cfg}, exactly as
     * {@code Kernel.startComponents} does for evm.enabled networks, and stashes the results in the
     * instance fields ({@link #kernel}, {@link #dbFactory}, {@link #evmStateSource}, {@link #evmTxStore},
     * {@link #evmMetaStore}). Called by {@link #setUp} for the shared devnet (lag=1) fixture and by the
     * lag-2 test for its own lag-2 fixture; @After tears down whichever fixture is live.
     */
    private void buildFixture(Config cfg) throws Exception {
        cfg.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        cfg.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        wallet = new Wallet(cfg);
        if (wallet.exists()) {
            wallet.delete();
        }
        assertTrue(wallet.unlock("password"));
        ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        wallet.setAccounts(Collections.singletonList(key));
        wallet.flush();

        kernel = new Kernel(cfg, key);
        dbFactory = new RocksdbFactory(cfg);

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
        // A4: mirror Kernel.startComponents — the genesis alloc backs the lock (genesis deposit),
        // so fee-credit tests exercise the real transfer-from-lock path against a funded lock.
        io.xdag.evm.bridge.GenesisLockSeeder.seedIfAbsent(addressStore, cfg.getEvmSpec());
    }

    @After
    public void tearDown() throws Exception {
        // Idempotent: a test that rebuilds the fixture (e.g. the lag-2 test) calls this to release the
        // devnet fixture before rebuilding, and @After then runs it a second time on the live fixture.
        if (wallet != null) {
            wallet.delete();
            wallet = null;
        }
        if (dbFactory != null) {
            dbFactory.close();
            dbFactory = null;
        }
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

    @Test
    public void evmExecutionLagsMainConfirmationByOneHeightAtLagTwo() throws Exception {
        // ── Fixture at lag=2 ─────────────────────────────────────────────────────────────────────
        // setUp built the shared fixture at devnet lag=1 (immediate execution). Tear it down and
        // rebuild over a lag=2 config: setMain(N) must now mature height N-lag+1 == N-1, so a payload
        // height K is BUFFERED when its own main block confirms and EXECUTES only when K+1 confirms.
        // This is precisely what the setMain wiring (processConfirmedBlock passing stateRootLag) buys;
        // at lag=1 the very same drive executes K immediately (no buffer, no lag), so every assertion
        // below is chosen to FAIL under immediate execution — that is what proves the lag is honoured.
        tearDown();
        Config lag2 = new DevnetConfig() {
            @Override
            public long getEvmStateRootLag() {
                return 2L;
            }
        };
        buildFixture(lag2);
        assertEquals(2L, kernel.getConfig().getEvmSpec().getEvmStateRootLag());

        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        // A deploy tx riding the carrier block's EVM ref (same shape as the lag-1 deploy test).
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, Wei.of(1), 200_000L, Optional.empty(),
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
        long carrierEvmHeight = -1; // set once the carrier is buried into a confirmed main block

        // ── Drive the chain until the carrier's EVM height K first confirms, then assert the window ──
        // We advance one block at a time and detect the exact iteration where setMain(K) has just run
        // (K == the carrier's confirmed height, first observed as nmain reaching K). Detecting the
        // window empirically instead of hard-coding it keeps the test robust to tryToConnect's burial
        // timing (the block-I -> matured-height mapping is not 1:1 and must not be assumed).
        for (int i = 1; i <= 20; i++) {
            generateTime += 64000L;
            List<io.xdag.core.Address> pending = new ArrayList<>();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
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

            if (carrier == null) {
                continue;
            }
            long confirmedHeight = blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getHeight();
            if (confirmedHeight <= 0) {
                continue; // carrier not yet a confirmed main block
            }
            if (carrierEvmHeight < 0) {
                // ── Phase 1: the block that confirms K (nmain has just reached K) ──────────────────
                carrierEvmHeight = confirmedHeight;
                long nmain = blockchain.getXdagStats().nmain;
                assertEquals("setMain(K) must have just run: nmain == carrier's confirmed height K",
                        carrierEvmHeight, nmain);

                // Under lag=2, setMain(K) BUFFERS height K (matures K-1, which carries nothing) and does
                // NOT execute it. Under lag=1 / the pre-Gate-2 immediate path, height K would already be
                // checkpointed here with no buffer — so BOTH of these assertions fail at lag=1.
                assertTrue("K must be buffered in the maturity buffer at the block that confirms it",
                        evmMetaStore.getMaturityEntry(carrierEvmHeight).isPresent());
                assertTrue("K must NOT yet be executed (no height checkpoint) when it merely confirms",
                        evmMetaStore.getHeightRecord(carrierEvmHeight).isEmpty());
                assertTrue("the carrier's deploy receipt must not exist before K matures",
                        evmMetaStore.getReceipt(deployTx.getHash()).isEmpty());
                // Execution demonstrably LAGS the native head: nothing is checkpointed at the head yet.
                assertTrue("no EVM checkpoint may exist at or above the confirmed head under lag=2",
                        evmMetaStore.highestHeight().isEmpty()
                                || evmMetaStore.highestHeight().orElseThrow() < carrierEvmHeight);

                // ── Phase 2: drive exactly ONE more main confirmation so setMain(K+1) runs ─────────
                long targetNmain = nmain + 1;
                do {
                    generateTime += 64000L;
                    List<io.xdag.core.Address> nextPending = new ArrayList<>();
                    nextPending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
                    long nextXdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
                    Block next = generateExtraBlock(config, poolKey, nextXdagTime, nextPending);
                    assertSame(IMPORTED_BEST, blockchain.tryToConnect(next));
                    ref = next.getHashLow();
                } while (blockchain.getXdagStats().nmain < targetNmain);

                // setMain(K+1) matured K == (K+1)-lag+1: it is now executed and the buffer is drained.
                assertTrue("K must be executed (height checkpoint present) once K+1 confirms",
                        evmMetaStore.getHeightRecord(carrierEvmHeight).isPresent());
                assertTrue("K's maturity buffer entry must be consumed after it matures",
                        evmMetaStore.getMaturityEntry(carrierEvmHeight).isEmpty());
                // The deploy actually executed at K (not at K+1): its receipt exists and its tx list is
                // checkpointed at K, exactly one height behind the block (K+1) that triggered execution.
                assertEquals(1, evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
                assertEquals(List.of(deployTx.getHash()), evmMetaStore.getTxList(carrierEvmHeight));
                return;
            }
        }
        throw new AssertionError("carrier's EVM height K never confirmed within the drive window");
    }

    // ---------------------------------------------------------------------------------------------
    // C4 capstone: historical state must survive across heights AND a reorg, driving the REAL
    // EvmBlockProcessor (with a journal) and reading through HistoricalStateReader.
    // ---------------------------------------------------------------------------------------------

    /** Fixed EVM signer whose address is both funded at genesis and used to sign every transfer. */
    private static final SECP256K1 EVM_ALGO = new SECP256K1();
    private static final KeyPair SENDER_KEY = EVM_ALGO.createKeyPair(EVM_ALGO.createPrivateKey(BigInteger.ONE));
    private static final Address SENDER = Address.extract(SENDER_KEY.getPublicKey());
    /** Codeless EOA (not in the genesis alloc) paid by the canonical branch, so a zero-gas transfer
     *  settles. The reorg branch pays RECIPIENT_B instead, giving that tx a genuinely different hash. */
    private static final Address RECIPIENT_A = Address.fromHexString("0x00000000000000000000000000000000000000aa");
    private static final Address RECIPIENT_B = Address.fromHexString("0x00000000000000000000000000000000000000bb");
    private static final long HISTORY_WINDOW = 128;
    private static final long TRANSFER_VALUE = 1_000L;
    private static final long TRANSFER_GAS_LIMIT = 21_000L; // exactly intrinsic: messageGas == 0
    private static final long TRANSFER_GAS_PRICE = 1L;
    /** Net debit per transfer = value + gasUsed * gasPrice; gasUsed == gasLimit here ONLY because the
     *  gas limit is exactly intrinsic (21000), so nothing is refunded. Raising the limit would break this. */
    private static final long TRANSFER_DEBIT = TRANSFER_VALUE + TRANSFER_GAS_LIMIT * TRANSFER_GAS_PRICE;

    @Test
    public void historicalStateSurvivesAcrossHeightsAndReorg() {
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        EvmBlockProcessor proc = newProcessorWithJournal(journal, HISTORY_WINDOW);
        HistoricalStateReader reader = new HistoricalStateReader(evmStateSource, journal, (int) HISTORY_WINDOW);

        // Two heights on the canonical branch: nonce n-1 pays RECIPIENT_A, each debiting SENDER.
        long b1 = executeTransferBlock(proc, 1, 0L, RECIPIENT_A);
        long b2 = executeTransferBlock(proc, 2, 1L, RECIPIENT_A);
        assertTrue("each transfer must debit the sender", b2 < b1);
        assertEquals(b1 - TRANSFER_DEBIT, b2);

        // Historical read: at height 1 the sender still shows its post-height-1 balance, reconstructed
        // by overlaying height 2's reverse-delta journal onto the live store (head == 2, target == 1).
        assertEquals(b1, senderBalance(reader.worldAt(1, 2)));

        // Reorg to height 1: rollbackTo wipes EVM_STATE, clears the journal, re-seeds the genesis
        // funding, and replays height 1 from the immutable EVM_TX blob (regenerating its journal).
        proc.rollbackTo(1);
        assertEquals("post-rollback live state must be back at the height-1 balance",
                b1, senderBalance(reader.worldAt(1, 1)));

        // A genuinely different height-2 transfer (same nonce 1, different recipient => different tx
        // hash) forms the second branch. History at head must reflect it: worldAt(2, 2) short-circuits
        // to the live store, so b2b == the reconstructed head balance, proving the stale journal is gone.
        long b2b = executeTransferBlock(proc, 2, 1L, RECIPIENT_B);
        assertEquals(b1 - TRANSFER_DEBIT, b2b);
        assertEquals(b2b, senderBalance(reader.worldAt(2, 2)));

        // The reorg genuinely SWITCHED branches (not merely "didn't crash"): RECIPIENT_B, absent before
        // the reorg, now holds exactly one transfer, while RECIPIENT_A holds only its height-1 transfer —
        // the abandoned first-branch height-2 payment to A was reverted by rollbackTo(1) (else A would
        // show 2 * TRANSFER_VALUE). This is the assertion that proves the second branch actually took.
        RocksDbWorldUpdater live = new RocksDbWorldUpdater(evmStateSource);
        assertEquals(TRANSFER_VALUE,
                live.getAccount(RECIPIENT_B).getBalance().getAsBigInteger().longValueExact());
        assertEquals(TRANSFER_VALUE,
                live.getAccount(RECIPIENT_A).getBalance().getAsBigInteger().longValueExact());
    }

    /**
     * Builds the SAME processor {@link #setUp} wires (devnet config over the shared {@code evmStateSource}
     * / {@code evmTxStore} / {@code evmMetaStore}), but via the 8-arg journal ctor: activation height 0,
     * a genesis allocation that funds {@link #SENDER}, the supplied reverse-delta journal, and the C4
     * window. The processor and {@link HistoricalStateReader} therefore read the exact same
     * {@code evmStateSource} instance.
     */
    private EvmBlockProcessor newProcessorWithJournal(EvmStateJournal journal, long window) {
        return new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore,
                0L, List.of(new GenesisAllocEntry(SENDER, Wei.fromEth(1))), journal, (int) window);
    }

    /**
     * Signs one value transfer from {@link #SENDER} (empty payload, gas limit == intrinsic so the
     * message runs zero gas and settles as a bare transfer), stores its blob, then drives the REAL
     * processor at {@code height} exactly as {@code BlockchainImpl.setMain} would — packing the tx
     * hash as the sole EVM ref of a confirmed main block. Returns SENDER's post-execution balance read
     * from the live {@code evmStateSource}.
     */
    private long executeTransferBlock(EvmBlockProcessor proc, long height, long nonce, Address recipient) {
        EvmTransaction transfer = EvmTransaction.unsigned(nonce, Wei.of(TRANSFER_GAS_PRICE),
                TRANSFER_GAS_LIMIT, Optional.of(recipient), Wei.of(TRANSFER_VALUE), Bytes.EMPTY,
                EvmConfig.DEVNET_CHAIN_ID).sign(SENDER_KEY, EVM_ALGO);
        evmTxStore.put(transfer);
        Bytes32 ref = Bytes32.wrap(transfer.getHash().getBytes());
        Bytes32 blockHash = HashUtils.sha256(Bytes.ofUnsignedLong(height));
        proc.processMainBlock(List.of(ref), height, height * 64L, blockHash);

        EvmReceipt receipt = evmMetaStore.getReceipt(transfer.getHash()).orElseThrow();
        assertEquals("transfer must execute successfully", 1, receipt.status());
        return senderBalance(new RocksDbWorldUpdater(evmStateSource));
    }

    private static long senderBalance(WorldUpdater world) {
        return world.getAccount(SENDER).getBalance().getAsBigInteger().longValueExact();
    }

    @Test
    public void confirming_block_is_credited_the_floored_evm_fee() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        // gasPrice chosen so gasUsed * gasPrice clears many nano (devnet fee routing is active at height 0).
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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue("test must credit a non-zero nano fee to be meaningful", expectedNano > 0);
        // A bare main-candidate carrier collects no native tx fee, so info.fee is exactly the EVM fee.
        assertEquals("confirming block's fee == floor(gasUsed*gasPrice / 1e9) nano",
                XAmount.of(expectedNano), storedCarrier.getInfo().getFee());
    }

    // -----------------------------------------------------------------------------------------
    // G3-T1 Test B: below activation — fee is burned, not credited
    // -----------------------------------------------------------------------------------------

    @Test
    public void below_activation_height_evm_fee_is_burned_not_credited() throws Exception {
        // Rebuild the fixture with feeRewardActivationHeight = MAX_VALUE so the EVM fee is NOT
        // routed to the confirming block (it is burned in wei, native accounting unchanged).
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public long getEvmFeeRewardActivationHeight() {
                return Long.MAX_VALUE;
            }
        });

        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // would produce significant nano if routed
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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        // The EVM tx must still have executed (the gate only blocks fee ROUTING, not execution).
        assertEquals("deploy must still execute", 1,
                evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
        // The carrier is a bare main-candidate with no native tx inputs, so its fee is zero when
        // fee routing is disabled (wei burned, not converted to nano).
        assertEquals("below activation the EVM fee is burned, not credited",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
    }

    // -----------------------------------------------------------------------------------------
    // G3-T1 Test C: dust burned, not rounded up
    // -----------------------------------------------------------------------------------------

    @Test
    public void dust_is_burned_not_rounded_up() {
        // gasPrice = 1e9+1 wei => floor((gasUsed*(1e9+1))/1e9) == gasUsed (the integer quotient),
        // and the remainder (gasUsed wei) is always positive sub-nano dust burned on the EVM side.
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_001L); // 1e9 + 1 wei
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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        assertEquals("deploy must succeed", 1,
                evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
        // floor(gasUsed*(1e9+1) / 1e9) == gasUsed; remainder == gasUsed wei (burned, never rounded up).
        assertEquals("dust is dropped: credited nano == floor(fee/1e9) == gasUsed",
                XAmount.of(gasUsed), storedCarrier.getInfo().getFee());
    }

    // -----------------------------------------------------------------------------------------
    // G3-T1 Test D: delta=2 — fee credits the PAYLOAD block K (the matured height), not K+1
    // -----------------------------------------------------------------------------------------

    @Test
    public void under_lag_2_the_evm_fee_credits_the_block_that_executed_the_payload() throws Exception {
        // Under lag=2, setMain(K) buffers the carrier's payload (height K) without executing it.
        // setMain(K+1) matures K and executes it, returning the fee; that fee is credited to block K
        // (the payload block), not K+1 — so the credit unwinds with the execution and both paths agree.
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public long getEvmStateRootLag() {
                return 2L;
            }
        });
        assertEquals(2L, kernel.getConfig().getEvmSpec().getEvmStateRootLag());

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
        long carrierEvmHeight = -1L;

        for (int i = 1; i <= 20; i++) {
            generateTime += 64000L;
            List<io.xdag.core.Address> pending = new ArrayList<>();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
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

            if (carrier == null) {
                continue;
            }
            long confirmedHeight = blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getHeight();
            if (confirmedHeight <= 0) {
                continue; // carrier not yet a confirmed main block
            }
            if (carrierEvmHeight < 0) {
                carrierEvmHeight = confirmedHeight;
                // Under lag=2, K is buffered when it first confirms — not yet executed.
                assertTrue("K must be buffered, not executed yet",
                        evmMetaStore.getHeightRecord(carrierEvmHeight).isEmpty());

                // Drive one more main confirmation so setMain(K+1) executes K.
                long targetNmain = blockchain.getXdagStats().nmain + 1;
                do {
                    generateTime += 64000L;
                    List<io.xdag.core.Address> nextPending = new ArrayList<>();
                    nextPending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
                    long nextXdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
                    Block next = generateExtraBlock(config, poolKey, nextXdagTime, nextPending);
                    assertSame(IMPORTED_BEST, blockchain.tryToConnect(next));
                    ref = next.getHashLow();
                } while (blockchain.getXdagStats().nmain < targetNmain);

                // K must be executed now and its receipt present.
                assertEquals("deploy must execute once K matures", 1,
                        evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());

                long k = carrierEvmHeight;
                long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
                long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                        .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
                assertTrue("meaningful fee", expectedNano > 0);

                // K1: the fee credits the PAYLOAD block K (the height whose txs executed), not the
                // confirming block K+1 — so the credit unwinds with the execution and both paths agree.
                Block blockK = blockchain.getBlockByHash(carrier.getHashLow(), false);
                assertEquals("under lag=2 the fee credits the payload block K",
                        XAmount.of(expectedNano), blockK.getInfo().getFee());

                Block blockKplus1 = blockchain.getBlockByHeight(k + 1);
                assertNotNull("block K+1 must exist and be confirmed", blockKplus1);
                assertEquals("block K+1 (the confirming block) is NOT credited under lag=2",
                        XAmount.ZERO, blockKplus1.getInfo().getFee());
                return;
            }
        }
        throw new AssertionError("carrier EVM height K never confirmed within drive window");
    }

    // -----------------------------------------------------------------------------------------
    // G3-T1 Test E: reorg symmetry — unSetMain reverses the fee credit
    // -----------------------------------------------------------------------------------------

    @Test
    public void unSetMain_reverses_the_evm_fee_credit() {
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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue("test must credit a non-zero nano fee to be meaningful", expectedNano > 0);

        // Verify the fee is credited into BOTH the fee field and the distributable amount (the amount is
        // what PoolAwardManager pays out). A bare main-candidate carrier's amount is exactly
        // reward + evmFee, so it must exceed the fee credit before the unwind.
        XAmount feeBefore = storedCarrier.getInfo().getFee();
        assertEquals("fee must be credited before unSetMain", XAmount.of(expectedNano), feeBefore);
        assertTrue("amount must carry reward + the fee credit before unwind",
                storedCarrier.getInfo().getAmount().greaterThan(XAmount.of(expectedNano)));

        // unSetMain reverses reward + fee: the fee field zeroes AND the credit is clawed back out of the
        // distributable amount (for a bare carrier the whole amount reverses to ZERO). Asserting the
        // amount (not only the fee field) catches a bug where setFee(ZERO) runs but the amount reversal
        // is skipped — leaving the credit spendable by PoolAwardManager after a reorg.
        blockchain.unSetMain(storedCarrier);
        assertEquals("unSetMain zeroes the fee field",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
        assertEquals("unSetMain reverses the fee credit from the distributable amount too",
                XAmount.ZERO, storedCarrier.getInfo().getAmount());
    }

    // -----------------------------------------------------------------------------------------
    // G3-T1 Test F: conservation — EVM wei destroyed == native nano credited * 1e9 + dust
    // -----------------------------------------------------------------------------------------

    @Test
    public void evm_wei_destroyed_equals_nano_credited_scaled_plus_dust() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // 1000 nano per gas unit
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        // Capture EVM sender balance BEFORE funding/execution.
        // Fund the sender so execution can proceed.
        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        BigInteger balBefore = new RocksDbWorldUpdater(evmStateSource)
                .getAccount(deployTx.getSender()).getBalance().getAsBigInteger();

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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        assertEquals("deploy must succeed", 1,
                evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());

        BigInteger balAfter = new RocksDbWorldUpdater(evmStateSource)
                .getAccount(deployTx.getSender()).getBalance().getAsBigInteger();
        BigInteger netFeeWei = balBefore.subtract(balAfter); // wei the sender lost (value=0, all gas)

        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        assertEquals("sender's EVM wei loss == gasUsed * gasPrice",
                BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger()), netFeeWei);

        BigInteger divisor = BigInteger.valueOf(1_000_000_000L);
        long creditedNano = netFeeWei.divide(divisor).longValueExact();
        long dustWei = netFeeWei.mod(divisor).longValueExact();

        assertEquals("native fee credit == floor(netFeeWei/1e9)",
                XAmount.of(creditedNano), storedCarrier.getInfo().getFee());

        // amount includes the credited fee (amount - fee == block reward, i.e. pure mining reward).
        XAmount blockReward = blockchain.getReward(storedCarrier.getInfo().getHeight());
        assertEquals("amount includes the credited fee (amount - fee == block reward)",
                blockReward,
                storedCarrier.getInfo().getAmount().subtract(storedCarrier.getInfo().getFee()));

        // Conservation: wei destroyed on EVM side == nano credited (scaled back to wei) + sub-nano dust.
        assertEquals("EVM wei destroyed == native nano credited (scaled) + sub-nano dust",
                netFeeWei,
                BigInteger.valueOf(creditedNano).multiply(divisor)
                        .add(BigInteger.valueOf(dustWei)));
    }

    // -----------------------------------------------------------------------------------------
    // K1 step 3: async blob-drain credits the payload block after a deferred blob arrives
    // -----------------------------------------------------------------------------------------

    @Test
    public void async_drain_credits_the_payload_block_after_a_deferred_blob_arrives() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // 1000 nano per gas unit
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        // NOTE: the blob is deliberately NOT put into evmTxStore yet — the carrier's payload defers.
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

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

        // The carrier confirmed but its payload is DEFERRED (blob missing), so it is not yet credited.
        Block carrierBefore = blockchain.getBlockByHash(carrier.getHashLow(), false);
        long m = carrierBefore.getInfo().getHeight();
        assertTrue("carrier confirmed", m > 0);
        assertTrue("payload deferred — not executed yet", evmMetaStore.getReceipt(deployTx.getHash()).isEmpty());
        assertEquals("no credit while deferred", XAmount.ZERO, carrierBefore.getInfo().getFee());

        // The blob arrives -> the async drain executes the payload and credits its OWN block M.
        evmTxStore.put(deployTx);
        blockchain.onEvmBlobsAvailable();

        assertEquals("the deferred payload executed on drain",
                1, evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue("meaningful fee", expectedNano > 0);
        Block carrierAfter = blockchain.getBlockByHeight(m);
        assertEquals("the async drain credited the payload block M with the same fee the sync path would",
                XAmount.of(expectedNano), carrierAfter.getInfo().getFee());
    }

    // -----------------------------------------------------------------------------------------
    // K1 step 3: reorg reversal — unSetMain reverses an async fee credit
    // -----------------------------------------------------------------------------------------

    @Test
    public void unsetmain_reverses_an_async_fee_credit() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L);
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());
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
        long m = blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getHeight();

        evmTxStore.put(deployTx);
        blockchain.onEvmBlobsAvailable(); // async-credit block M

        Block credited = blockchain.getBlockByHeight(m);
        assertTrue("async credit landed", credited.getInfo().getFee().greaterThan(XAmount.ZERO));

        blockchain.unSetMain(credited);
        assertEquals("unSetMain reverses the async-credited fee to zero",
                XAmount.ZERO, blockchain.getBlockByHeight(m).getInfo().getFee());
    }

    @Test
    public void async_drain_credits_the_past_payload_block_under_lag_2() throws Exception {
        // The scenario K1 exists for: at lag=2 a blob-behind node defers the PAST payload height K
        // (setMain(K+1) matures K but the blob is missing), then drains it later and must credit block
        // K (strictly behind the top), not the confirming block K+1 — converging with a never-behind node.
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public long getEvmStateRootLag() {
                return 2L;
            }
        });
        assertEquals(2L, kernel.getConfig().getEvmSpec().getEvmStateRootLag());

        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L);
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        // Blob WITHHELD: the payload defers at maturity instead of executing synchronously.
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
        for (int i = 1; i <= 20; i++) {
            generateTime += 64000L;
            List<io.xdag.core.Address> pending = new ArrayList<>();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
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
            if (carrier == null) {
                continue;
            }
            long confirmedHeight = blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getHeight();
            if (confirmedHeight <= 0) {
                continue;
            }
            if (k < 0) {
                k = confirmedHeight;
                // Drive one more main confirmation so setMain(K+1) matures K — but the blob is missing,
                // so K DEFERS to the pending queue (not executed, not credited).
                long targetNmain = blockchain.getXdagStats().nmain + 1;
                do {
                    generateTime += 64000L;
                    List<io.xdag.core.Address> nextPending = new ArrayList<>();
                    nextPending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
                    long nextXdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
                    Block next = generateExtraBlock(config, poolKey, nextXdagTime, nextPending);
                    assertSame(IMPORTED_BEST, blockchain.tryToConnect(next));
                    ref = next.getHashLow();
                } while (blockchain.getXdagStats().nmain < targetNmain);

                assertTrue("K must be deferred (blob missing), not executed",
                        evmMetaStore.getReceipt(deployTx.getHash()).isEmpty());
                assertEquals("payload block K not yet credited", XAmount.ZERO,
                        blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getFee());
                assertEquals("confirming block K+1 not credited", XAmount.ZERO,
                        blockchain.getBlockByHeight(k + 1).getInfo().getFee());

                // The blob arrives -> the async drain executes the PAST payload height K and credits block K.
                evmTxStore.put(deployTx);
                blockchain.onEvmBlobsAvailable();

                assertEquals("the deferred payload executed on drain", 1,
                        evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
                long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
                long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                        .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
                assertTrue("meaningful fee", expectedNano > 0);
                assertEquals("the async drain credits the PAST payload block K (not the confirming K+1)",
                        XAmount.of(expectedNano),
                        blockchain.getBlockByHash(carrier.getHashLow(), false).getInfo().getFee());
                assertEquals("confirming block K+1 remains NOT credited", XAmount.ZERO,
                        blockchain.getBlockByHeight(k + 1).getInfo().getFee());
                return;
            }
        }
        throw new AssertionError("carrier EVM height K never confirmed within the drive window");
    }

    // -----------------------------------------------------------------------------------------
    // A1 guard: overflowing fee is skipped, not credited, and setMain survives
    // -----------------------------------------------------------------------------------------

    @Test
    public void an_overflowing_fee_is_skipped_not_credited_and_setMain_survives() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        // gasPrice so large that gasUsed * gasPrice / 1e9 exceeds Long.MAX (nano): the credit conversion
        // would throw ArithmeticException into setMain without the overflow guard.
        Wei gasPrice = Wei.of(new BigInteger("200000000000000000000000")); // 2e23 wei
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L,
                Wei.of(new BigInteger("50000000000000000000000000000"))); // 5e28 wei — affords the gas
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

        // setMain must NOT have aborted on the overflowing fee: the carrier confirmed as main...
        Block storedCarrier = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertTrue("carrier confirmed — setMain survived the overflowing fee",
                storedCarrier.getInfo().getHeight() > 0);
        // ...and the overflowing fee was skipped (not credited): a bare carrier's fee stays ZERO.
        assertEquals("the overflowing fee is skipped, not credited",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
        // (sanity) the deploy executed and settled the huge fee on the EVM side.
        assertEquals(1, evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
    }

    // -----------------------------------------------------------------------------------------
    // A4 Test A: the fee credit is a TRANSFER from the lock, not a mint
    // -----------------------------------------------------------------------------------------

    @Test
    public void fee_credit_debits_the_lock_by_exactly_the_credited_nano() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        XAmount lockBefore = kernel.getAddressStore().getBalanceByAddress(lockKey);
        assertTrue("fixture must have seeded the lock (genesis deposit)",
                lockBefore.greaterThan(XAmount.ZERO));

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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue("test needs a non-zero fee", expectedNano > 0);
        assertEquals("fee credited to the carrier",
                XAmount.of(expectedNano), storedCarrier.getInfo().getFee());

        // Transfer, not mint: the lock lost exactly what the carrier gained.
        XAmount lockAfter = kernel.getAddressStore().getBalanceByAddress(lockKey);
        assertEquals("lock debited by exactly the credited nano",
                lockBefore.subtract(XAmount.of(expectedNano)), lockAfter);
        // And the debit is journaled for the unwind reversal.
        assertEquals("0x0B journal records the debit at the credited height", expectedNano,
                evmMetaStore.getFeeDebit(storedCarrier.getInfo().getHeight()));
    }

    // -----------------------------------------------------------------------------------------
    // A4 Test B: a short lock deterministically skips the WHOLE credit (no debit, no credit)
    // -----------------------------------------------------------------------------------------

    @Test
    public void a_short_lock_skips_the_fee_credit_entirely() throws Exception {
        // Rebuild with an EMPTY alloc: the bridge stays scheduled but nothing seeds the lock,
        // so the lock cannot cover any fee. (This is the broken-invariant shape the skip guards.)
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public java.util.List<io.xdag.evm.GenesisAllocEntry> getEvmGenesisAlloc() {
                return java.util.List.of();
            }
        });

        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        assertEquals("empty alloc -> unfunded lock",
                XAmount.ZERO, kernel.getAddressStore().getBalanceByAddress(lockKey));

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L);
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
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        // Execution itself is unaffected — only the credit is skipped.
        assertEquals("deploy must still execute", 1,
                evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
        assertEquals("credit skipped: no fee on the carrier",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
        assertEquals("no debit: lock untouched",
                XAmount.ZERO, kernel.getAddressStore().getBalanceByAddress(lockKey));
        assertEquals("no journal entry", 0L,
                evmMetaStore.getFeeDebit(storedCarrier.getInfo().getHeight()));
    }
}
