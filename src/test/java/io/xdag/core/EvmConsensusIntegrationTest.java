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
}
