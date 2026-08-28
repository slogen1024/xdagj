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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * createMainBlock() produces a batch commitment in the 0x0F field (batch D2 fork active on
 * devnet from genesis). Legacy tests that asserted bare-tx-hash semantics are updated here:
 * "main_block_packs_top_pool_tx" intent was "packing works" → updated to batch semantics below.
 */
public class MainBlockEvmPackingTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    // DevnetConfig has batchActivationHeight = 0, so the batch fork is always active.
    private final Config config = new DevnetConfig();
    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final KeyPair evmKey2 = algo.createKeyPair(algo.createPrivateKey(BigInteger.TWO));
    private final Address evmSender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    private final Address evmSender2 = Address.fromHexString("0x2b5ad5c4795c026514f8317c7a215e218dccd6cf");

    private Wallet wallet;
    private Kernel kernel;
    private RocksdbFactory dbFactory;
    private KVSource<byte[], byte[]> evmStateSource;
    private EvmTxStore evmTxStore;
    private EvmTxPool evmTxPool;
    private EvmMetaStore evmMetaStore;
    private EvmBlockProcessor evmBlockProcessor;
    private BlockchainImpl blockchain;

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
        AddressStore addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
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
        KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
        evmMetaSource.init();
        evmMetaSource.reset();
        evmMetaStore = new EvmMetaStore(evmMetaSource);
        // A single shared EvmTxStore — the pool's add() writes txs to it, and selectEvmBatch
        // calls putBatch() on the same instance so getBatch() resolves correctly in assertions.
        evmTxStore = new EvmTxStore(new InMemoryKVSource());
        evmTxPool = new EvmTxPool(evmTxStore, evmStateSource,
                BigInteger.valueOf(0xCAFE), 30_000_000L, Wei.ONE, 3600L, () -> 1000L);
        // A processor backed by the same stores, so createMainBlock's state-root anchor (G1-T2)
        // reads chainedRootAt from the very checkpoints this harness seeds.
        evmBlockProcessor = new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore);
        kernel.setEvmStateStore(evmStateSource);
        kernel.setEvmMetaStore(evmMetaStore);
        kernel.setEvmTxPool(evmTxPool);
        kernel.setEvmTxStore(evmTxStore);
        kernel.setEvmBlockProcessor(evmBlockProcessor);

        RocksDbWorldUpdater w = new RocksDbWorldUpdater(evmStateSource);
        w.createAccount(evmSender, 0L, Wei.fromEth(1));
        w.createAccount(evmSender2, 0L, Wei.fromEth(1));
        w.commit();

        blockchain = new BlockchainImpl(kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
        dbFactory.close();
    }

    private EvmTransaction pooledTx(KeyPair key, long nonce) {
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 100_000L, Optional.empty(),
                Wei.ZERO, Bytes.fromHexString("0x6001600155"), BigInteger.valueOf(0xCAFE)).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.ADDED, evmTxPool.add(tx.getRawRlp()));
        return tx;
    }

    // ---------------------------------------------------------------------------
    // Batch-path tests (batchActivationHeight = 0 on devnet, active from genesis)
    // ---------------------------------------------------------------------------

    /**
     * Replaces the original "main_block_packs_top_pool_tx" (which asserted bare-tx-hash semantics).
     * After the batch fork a single pool tx is packed as a batch commitment — NOT the bare hash.
     */
    @Test
    public void a_single_pool_tx_is_still_packed_as_a_batch_commitment() {
        EvmTransaction tx = pooledTx(evmKey, 0);
        Block main = blockchain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("batch ref must be non-null", ref);

        // Ref must resolve via getBatch, not be the bare tx hash.
        Optional<List<Bytes32>> batch = evmTxStore.getBatch(Hash.wrap(ref));
        assertTrue("ref must be a stored batch", batch.isPresent());
        assertEquals("batch must contain exactly one tx", 1, batch.get().size());
        assertEquals("batch member must be the tx hash",
                Bytes32.wrap(tx.getHash().getBytes()), batch.get().get(0));
    }

    /**
     * Before the batch fork the miner falls back to the legacy selectEvmTxRef path: the 0x0F field
     * carries the bare tx hash, NOT a batch commitment. We verify this with a DevnetConfig that
     * reports batchActivationHeight = Long.MAX_VALUE (the AbstractConfig default), overriding the
     * devnet conf-file value of 0.
     */
    @Test
    public void pre_fork_main_block_still_packs_a_bare_tx_hash() throws Exception {
        // Anonymous DevnetConfig subclass keeps everything identical to the class-level `config`
        // except getEvmBatchActivationHeight() returns Long.MAX_VALUE, so nextHeight (1) < MAX_VALUE
        // and createMainBlock() takes the legacy selectEvmTxRef branch.
        Config preForkConfig = new DevnetConfig() {
            @Override
            public long getEvmBatchActivationHeight() {
                return Long.MAX_VALUE;
            }
        };
        preForkConfig.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        preForkConfig.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        // Reuse the same wallet key and EVM state already set up in @Before.
        Kernel preForkKernel = new Kernel(preForkConfig, wallet.getDefKey());
        preForkKernel.setBlockStore(kernel.getBlockStore());
        preForkKernel.setOrphanBlockStore(kernel.getOrphanBlockStore());
        preForkKernel.setAddressStore(kernel.getAddressStore());
        preForkKernel.setTxHistoryStore(kernel.getTxHistoryStore());
        preForkKernel.setWallet(wallet);
        preForkKernel.setEvmStateStore(evmStateSource);
        preForkKernel.setEvmMetaStore(evmMetaStore);
        preForkKernel.setEvmTxPool(evmTxPool);
        preForkKernel.setEvmTxStore(evmTxStore);

        EvmTransaction tx = pooledTx(evmKey, 0);
        BlockchainImpl preForkChain = new BlockchainImpl(preForkKernel);
        Block main = preForkChain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("legacy ref must be non-null", ref);

        // Legacy path: ref IS the bare tx hash, not a batch commitment.
        assertEquals("legacy ref must equal bare tx hash",
                Bytes32.wrap(tx.getHash().getBytes()), ref);

        // No batch record should exist for this ref in the store.
        assertTrue("no batch record must exist for a legacy ref",
                evmTxStore.getBatch(Hash.wrap(ref)).isEmpty());
    }

    /**
     * Multiple pool txs (two senders) are packed into one batch commitment. Both tx hashes appear
     * in the decoded member list.
     */
    @Test
    public void a_mined_main_block_carries_a_batch_commitment_covering_the_pool() {
        EvmTransaction tx1 = pooledTx(evmKey, 0);
        EvmTransaction tx2 = pooledTx(evmKey2, 0);
        Block main = blockchain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("batch ref must be non-null with pool txs", ref);

        Optional<List<Bytes32>> batch = evmTxStore.getBatch(Hash.wrap(ref));
        assertTrue("ref must be a stored batch", batch.isPresent());
        assertEquals("batch must contain both txs", 2, batch.get().size());

        Bytes32 h1 = Bytes32.wrap(tx1.getHash().getBytes());
        Bytes32 h2 = Bytes32.wrap(tx2.getHash().getBytes());
        // Both txs share gasPrice=Wei.ONE; bySender is a LinkedHashMap (insertion-ordered),
        // so selectBatch picks tx1's sender run first (strict > comparison keeps first best),
        // then tx2's. Expected order: [tx1, tx2].
        assertEquals("batch must contain txs in exact priority order",
                List.of(h1, h2), batch.get());
    }

    /**
     * Defect-1 upgrade window: while type-2 is not yet active, the miner must not pack a gossiped
     * type-2 tx. Executing it to a status-0 receipt would permanently burn its hash (the
     * receipt-presence dedup skips it forever, even post-activation — re-signing the same fields
     * yields the same hash) and stall the sender's nonce chain. The batch must carry ONLY the
     * legacy tx.
     */
    @Test
    public void pre_fork_miner_does_not_pack_type2() throws Exception {
        // Anonymous DevnetConfig subclass keeps everything identical to the class-level `config`
        // except getEvmType2ActivationHeight() returns Long.MAX_VALUE, so nextHeight (1) is below
        // the type-2 activation while the batch fork stays active (devnet batch height = 0).
        Config preType2Config = new DevnetConfig() {
            @Override
            public long getEvmType2ActivationHeight() {
                return Long.MAX_VALUE;
            }
        };
        preType2Config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        preType2Config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        // Reuse the same wallet key and EVM state already set up in @Before.
        Kernel preType2Kernel = new Kernel(preType2Config, wallet.getDefKey());
        preType2Kernel.setBlockStore(kernel.getBlockStore());
        preType2Kernel.setOrphanBlockStore(kernel.getOrphanBlockStore());
        preType2Kernel.setAddressStore(kernel.getAddressStore());
        preType2Kernel.setTxHistoryStore(kernel.getTxHistoryStore());
        preType2Kernel.setWallet(wallet);
        preType2Kernel.setEvmStateStore(evmStateSource);
        preType2Kernel.setEvmMetaStore(evmMetaStore);
        preType2Kernel.setEvmTxPool(evmTxPool);
        preType2Kernel.setEvmTxStore(evmTxStore);

        EvmTransaction legacy = pooledTx(evmKey, 0);
        EvmTransaction type2 = EvmTransaction.unsignedType2(0L, Wei.ONE, Wei.of(3), 100_000L,
                Optional.empty(), Wei.ZERO, Bytes.fromHexString("0x6001600155"), List.of(),
                BigInteger.valueOf(0xCAFE)).sign(evmKey2, algo);
        assertEquals(EvmTxPool.AddResult.ADDED, evmTxPool.add(type2.getRawRlp()));

        BlockchainImpl preType2Chain = new BlockchainImpl(preType2Kernel);
        Block main = preType2Chain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("batch ref must be non-null — the legacy tx is still packable", ref);
        Optional<List<Bytes32>> batch = evmTxStore.getBatch(Hash.wrap(ref));
        assertTrue("ref must be a stored batch", batch.isPresent());
        assertEquals("pre-activation type-2 must be filtered out of the batch",
                List.of(Bytes32.wrap(legacy.getHash().getBytes())), batch.get());
    }

    /**
     * Legacy single-ref path with a type-2 candidate in the pool: when BOTH the batch fork and
     * type-2 activation are in the future, the miner takes the selectEvmTxRef branch and must skip
     * the type-2 candidate, packing the LEGACY tx's bare 32-byte hash as the 0x0F ref (no batch
     * record). Covers the type-2 skip inside selectEvmTxRef, which the batch tests never reach.
     */
    @Test
    public void pre_fork_and_pre_type2_miner_packs_only_the_bare_legacy_hash() throws Exception {
        // Anonymous DevnetConfig subclass keeps everything identical to the class-level `config`
        // except BOTH activation heights return Long.MAX_VALUE: nextHeight (1) is below the batch
        // fork (legacy selectEvmTxRef branch) AND below the type-2 activation (type-2 skipped).
        Config preForkConfig = new DevnetConfig() {
            @Override
            public long getEvmBatchActivationHeight() {
                return Long.MAX_VALUE;
            }

            @Override
            public long getEvmType2ActivationHeight() {
                return Long.MAX_VALUE;
            }
        };
        preForkConfig.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        preForkConfig.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        // Reuse the same wallet key and EVM state already set up in @Before.
        Kernel preForkKernel = new Kernel(preForkConfig, wallet.getDefKey());
        preForkKernel.setBlockStore(kernel.getBlockStore());
        preForkKernel.setOrphanBlockStore(kernel.getOrphanBlockStore());
        preForkKernel.setAddressStore(kernel.getAddressStore());
        preForkKernel.setTxHistoryStore(kernel.getTxHistoryStore());
        preForkKernel.setWallet(wallet);
        preForkKernel.setEvmStateStore(evmStateSource);
        preForkKernel.setEvmMetaStore(evmMetaStore);
        preForkKernel.setEvmTxPool(evmTxPool);
        preForkKernel.setEvmTxStore(evmTxStore);

        EvmTransaction legacy = pooledTx(evmKey, 0);
        EvmTransaction type2 = EvmTransaction.unsignedType2(0L, Wei.ONE, Wei.of(3), 100_000L,
                Optional.empty(), Wei.ZERO, Bytes.fromHexString("0x6001600155"), List.of(),
                BigInteger.valueOf(0xCAFE)).sign(evmKey2, algo);
        assertEquals(EvmTxPool.AddResult.ADDED, evmTxPool.add(type2.getRawRlp()));

        BlockchainImpl preForkChain = new BlockchainImpl(preForkKernel);
        Block main = preForkChain.createMainBlock();

        Bytes32 ref = main.getEvmTxRef();
        assertNotNull("legacy ref must be non-null", ref);

        // Legacy path: ref IS the bare LEGACY tx hash — the type-2 candidate is skipped.
        assertEquals("legacy ref must equal bare tx hash",
                Bytes32.wrap(legacy.getHash().getBytes()), ref);

        // No batch record should exist for this ref in the store.
        assertTrue("no batch record must exist for a legacy ref",
                evmTxStore.getBatch(Hash.wrap(ref)).isEmpty());
    }

    // ---------------------------------------------------------------------------
    // Shared invariants — both batch and legacy paths agree on these
    // ---------------------------------------------------------------------------

    @Test
    public void empty_pool_packs_no_ref() {
        assertNull(blockchain.createMainBlock().getEvmTxRef());
    }

    @Test
    public void already_executed_tx_is_not_packed() {
        // Exercises the batch-fork path: stopped-set drains the only tx so selectEvmBatch returns null.
        EvmTransaction tx = pooledTx(evmKey, 0);
        evmMetaStore.putReceipt(tx.getHash(), new EvmReceipt(1, 21_000L, Optional.empty(), List.of()));
        assertNull("a tx with a receipt must not be re-packed", blockchain.createMainBlock().getEvmTxRef());
    }

    /**
     * End-to-end G2-T1c capstone at lag=2: the miner's daSkip decision is committed correctly into
     * the block's EvmStateAnchor depending on whether the matured height's buffered blob is present.
     *
     * <p>At lag=2 with nmain=1: nextHeight=2, anchorHeight=2-2=0 (non-null anchor),
     * maturedEvmHeight=2-2+1=1. Seeding a checkpoint at h=0 lets chainedRootAt(0) resolve.
     * Case A (missing blob): putMaturityEntry at h=1 with a phantom hash not in evmTxStore →
     * expandRefs returns it as unknown → maturedPayloadAvailable=false → daSkip=true.
     * Case B (present blob): putMaturityEntry at h=1 with the hash of a pooled tx that IS in
     * evmTxStore → expandRefs finds it → maturedPayloadAvailable=true → daSkip=false.
     */
    @Test
    public void createMainBlock_commits_daskip_when_the_matured_height_blob_is_missing_at_lag_two()
            throws Exception {
        // Lag-2 DevnetConfig: override only the stateRootLag; all other fork heights remain devnet
        // defaults (activation=0, batchFork=0, type2=0). A fresh kernel is wired over the same shared
        // EVM stores so the maturity/tx lookups hit the same data this test seeds below.
        Config lag2 = new DevnetConfig() {
            @Override
            public long getEvmStateRootLag() {
                return 2L;
            }
        };
        lag2.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        lag2.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        Kernel lag2Kernel = new Kernel(lag2, wallet.getDefKey());
        lag2Kernel.setBlockStore(kernel.getBlockStore());
        lag2Kernel.setOrphanBlockStore(kernel.getOrphanBlockStore());
        lag2Kernel.setAddressStore(kernel.getAddressStore());
        lag2Kernel.setTxHistoryStore(kernel.getTxHistoryStore());
        lag2Kernel.setWallet(wallet);
        lag2Kernel.setEvmStateStore(evmStateSource);
        lag2Kernel.setEvmMetaStore(evmMetaStore);
        lag2Kernel.setEvmTxPool(evmTxPool);
        lag2Kernel.setEvmTxStore(evmTxStore);
        // A processor over the shared stores so maturedPayloadAvailable reads the maturity entries
        // and tx blobs that this test seeds below.
        lag2Kernel.setEvmBlockProcessor(
                new EvmBlockProcessor(EvmConfig.devnet(), evmStateSource, evmTxStore, evmMetaStore));

        BlockchainImpl lag2Chain = new BlockchainImpl(lag2Kernel);
        // nmain=1 → nextHeight=2 → anchorHeight=2-2=0 (non-null) → maturedEvmHeight=1
        lag2Chain.getXdagStats().nmain = 1;

        // Seed a checkpoint at h=0 so chainedRootAt(0) resolves (required for a non-null anchor).
        Bytes32 seeded = Bytes32.fromHexString("0x" + "11".repeat(32));
        evmMetaStore.putHeightRecord(0L, seeded, Bytes32.ZERO, 0, 1000L);

        // ── Case A: matured height 1 has a ref whose blob is NOT in evmTxStore ──────────────────
        // phantom is unknown to txStore → expandRefs → unknownRefs non-empty → complete()=false
        // → maturedPayloadAvailable=false → daSkip=true
        Bytes32 phantom = Bytes32.fromHexString("0x" + "ab".repeat(32));
        evmMetaStore.putMaturityEntry(1L,
                Bytes32.fromHexString("0x" + "cc".repeat(32)), 1001L,
                List.of(phantom), List.of());
        EvmStateAnchor skipAnchor = lag2Chain.createMainBlock().getEvmStateAnchor();
        assertNotNull("lag-2 block at nextHeight>=2 must carry a state-root anchor", skipAnchor);
        assertTrue("missing matured-height blob => miner commits daSkip=true", skipAnchor.daSkip());

        // ── Case B: same height buffered with a blob that IS present in evmTxStore ─────────────
        // pooledTx stores the tx blob via evmTxPool.add → evmTxStore; use its hash as the ref.
        EvmTransaction present = pooledTx(evmKey, 0);
        Bytes32 presentRef = Bytes32.wrap(present.getHash().getBytes());
        evmMetaStore.putMaturityEntry(1L,
                Bytes32.fromHexString("0x" + "cc".repeat(32)), 1001L,
                List.of(presentRef), List.of());
        EvmStateAnchor includeAnchor = lag2Chain.createMainBlock().getEvmStateAnchor();
        assertNotNull("lag-2 block at nextHeight>=2 must carry a state-root anchor", includeAnchor);
        assertFalse("present matured-height blob => miner commits daSkip=false",
                includeAnchor.daSkip());
    }

    @Test
    public void createMainBlock_attaches_the_state_root_anchor_when_active() {
        // devnet: activation=0, lag=1 => the mined block carries an anchor of the chained root
        // as-of H-1. Seed a run of checkpoints all with the SAME root so the assertion is robust to
        // the exact next height on a fresh harness (the floor checkpoint <= any anchorHeight>=0 is
        // still `seeded`), and assert on rootLow rather than a hardcoded height.
        Bytes32 seeded = Bytes32.fromHexString("0x" + "11".repeat(32));
        for (long h = 0; h <= 4; h++) {
            evmMetaStore.putHeightRecord(h, seeded, Bytes32.ZERO, 0, 1000L);
        }

        Block main = blockchain.createMainBlock();

        EvmStateAnchor anchor = main.getEvmStateAnchor();
        assertNotNull("an active devnet main block must carry a state-root anchor", anchor);
        assertEquals(EvmStateAnchor.rootLowOf(seeded), anchor.rootLow());
        assertFalse("devnet lag=1: matured height is the unconfirmed block, so daSkip stays false",
                anchor.daSkip());
        assertEquals("anchored main block still serializes to 512 bytes",
                512, main.getXdagBlock().getData().size());
    }
}
