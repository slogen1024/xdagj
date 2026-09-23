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

package io.xdag.chain.l1;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_COINBASE;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.BlockBuilder;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.XdagTime;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * Real BlockchainImpl + RocksDB fixture for chain hook tests. Main blocks are "mined" by searching a nonce whose
 * raw-hash difficulty lies in [2^46, 2^47), so chain weight grows predictably and chunk blocks (difficulty ~2^33)
 * can never hijack the top (see plan §0.5).
 */
public abstract class ChainL1TestBase {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    protected static final XAmount ONE_XDAG = XAmount.of(1, XUnit.XDAG);
    protected static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    protected static final ChainConfigExt CFG = new ChainConfigExt(1L, 32L, 10_000_000L);
    private static final BigInteger DIFF_LO = BigInteger.ONE.shiftLeft(46);
    private static final BigInteger DIFF_HI = BigInteger.ONE.shiftLeft(47);

    protected Config config;
    protected Wallet wallet;
    protected Kernel kernel;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected ChainL1Store chainStore;
    protected MockBlockchain blockchain;
    protected final ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    protected long generateTime = 1600616700000L;
    protected Bytes32 topRef;
    private long nonce = 1;
    /** Mixed into every mined nonce; bumped by rewindTo() so a competing branch never reproduces an existing block byte for byte. */
    private long forkSalt = 0;

    public static class MockBlockchain extends BlockchainImpl {
        public MockBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
        }

        @Override
        public void addOurBlock(int keyIndex, Block block) {
        }

        /**
         * Deliberately without the {@code try/catch} of the real {@link BlockchainImpl#checkMain()}:
         * an exception out of {@code setMain} must reach the test (plan §0.5). Everything else is
         * the production shape, monitor included. The stats save in particular: C1 (SP0b-2) is a
         * bug that only exists because that save runs after {@code checkNewMain} — under the
         * write-behind layer it is a QUEUED write that must not be allowed to land after a
         * transition's DIRECT one — and an override that dropped it would hide exactly that from
         * every fixture test. It is still redundant as far as the completion marker is concerned:
         * since SP0b-1 {@code setMain} and {@code unSetMain} persist the stats themselves,
         * immediately before the marker, so a "restart" in a test loads stats that already agree
         * with it either way.
         */
        @Override
        public synchronized void checkMain() {
            checkNewMain();
            // xdagStats state will change after checkNewMain
            getBlockStore().saveXdagStatus(getXdagStats());
        }
    }

    /**
     * The configuration this fixture runs on, built before anything else in {@link #setUpChain}
     * touches it.
     *
     * <p><b>Override this rather than assigning {@link #config}.</b> Several chain settings have no
     * setter on purpose, so a subclass that wants one changed has to supply a whole {@code Config};
     * doing that from a constructor or an instance initialiser depends on a JLS ordering rule
     * (superclass field initialisers, then subclass initialisers and constructor, then
     * {@code @Before}) and {@link #setUpChain} now overwrites whatever it finds, so an assignment
     * made that way is simply lost.
     */
    protected Config newConfig() {
        return new DevnetConfig();
    }

    @Before
    public void setUpChain() throws Exception {
        config = newConfig();
        String rootDir = root.newFolder("node").getAbsolutePath();
        // Cast: these are Lombok setters on AbstractConfig, not part of the Config interface.
        AbstractConfig paths = (AbstractConfig) config;
        paths.setRootDir(rootDir); // XdagCli.makeSnapshot derives its paths from it
        // setDir() re-derives storeDir (what RocksdbKVSource opens) and storeBackupDir from rootDir,
        // exactly as the config constructor did from the default root, so the two can never drift.
        paths.setDir();
        // The wallet too: DevnetConfig fixed walletFilePath from the DEFAULT root in its constructor,
        // so without this every fixture wallet lands in <cwd>/devnet/wallet instead of the temp root.
        paths.setWalletFilePath(rootDir + "/wallet/" + Constants.WALLET_FILE_NAME);
        // Explicit, not inherited from the devnet default: another test in this JVM may have left a
        // chain.activation.height system-property override behind (ChainSpecTest), and Task 16 flips it.
        config.getChainSpec().setChainActivationHeight(0);
        wallet = new Wallet(config);
        wallet.unlock("password");
        wallet.setAccounts(Collections.singletonList(poolKey));
        wallet.flush();

        kernel = new Kernel(config, poolKey);
        dbFactory = wrapFactory(new RocksdbFactory(config));
        if (dbFactory instanceof WriteBehindFactory wb) {
            kernel.setPersist(wb.queue()); // BlockchainImpl reads it once, in its constructor
        }
        BlockStore blockStore = BlockStoreImpl.forNode(dbFactory);
        blockStore.reset();
        OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphanBlockStore.reset();
        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.reset();
        chainStore = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
        chainStore.start();
        chainStore.reset();

        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setAddressStore(addressStore);
        kernel.setTxHistoryStore(Mockito.mock(TransactionHistoryStore.class));
        kernel.setWallet(wallet);
        kernel.setChainL1Store(chainStore);

        // The kernel is fully wired before construction: BlockchainImpl's constructor installs the
        // ChainL1Processor from kernel.getChainL1Store() itself and drains kernel.getChainKindHandlers()
        // into it, and neither is consulted again afterwards.
        beforeBlockchain(kernel);
        blockchain = new MockBlockchain(kernel);

        Block addressBlock = BlockBuilder.generateAddressBlock(config, poolKey, generateTime);
        addressStore.updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));
        assertSame(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(new Block(new XdagBlock(addressBlock.toBytes()))));
        topRef = hashLow(addressBlock);
    }

    @After
    public void tearDownChain() throws IOException {
        // BlockchainImpl's constructor starts a per-instance cleaner on a non-daemon scheduler
        // (rollBackLoop); only stopCheckMain() -> stopCleaner() ends it. Idempotent, so a test that
        // already went through releaseStores() is unaffected; startCheckMain is a no-op on the mock,
        // so nothing else is running.
        if (blockchain != null) {
            blockchain.stopCheckMain();
        }
        if (wallet != null) {
            try {
                wallet.delete();
            } catch (NoSuchFileException e) {
                // already gone
            }
        }
        if (chainStore != null) {
            chainStore.stop();
        }
        if (dbFactory != null) {
            dbFactory.close();
        }
    }

    /**
     * Hands the store directory over to a command that opens it itself ({@code --repairchain},
     * {@code --makesnapshot}): RocksDB is single-writer per directory, so the fixture's own handles
     * have to be gone first. Stops the blockchain's threads as well, since nothing may confirm a
     * block on a store the command is working on. Nulled out because {@link #tearDownChain} closes
     * whatever is left and tolerates nulls.
     */
    protected void releaseStores() {
        if (blockchain != null) {
            blockchain.stopCheckMain();
        }
        if (chainStore != null) {
            chainStore.stop();
            chainStore = null;
        }
        if (dbFactory != null) {
            dbFactory.close();
            dbFactory = null;
        }
    }

    /**
     * Lets a subclass put the node's write-behind layer (SP0b-2) under the block, time, index and
     * orphan databases. The default is the raw factory: every SP0a/SP0b-1 test keeps running on
     * synchronous writes, which is also what {@code Kernel} does when {@code chain.persist.maxPending}
     * is 0.
     */
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        return raw;
    }

    /**
     * Last chance to touch the kernel before {@code new MockBlockchain(kernel)}. Overridden by a
     * subclass that needs a {@link ChainKindHandler} in {@code kernel.getChainKindHandlers()}: that
     * map is read once, by the constructor, and a handler put there later never reaches the
     * processor at all.
     */
    protected void beforeBlockchain(Kernel kernel) {
    }

    protected UInt64 nextNonce() {
        return UInt64.valueOf(nonce++);
    }

    protected static Bytes32 hashLow(Block b) {
        return Bytes32.wrap(b.getHashLow().toArray());
    }

    /**
     * Timestamp for a transaction or chunk block: a few seconds into the epoch the next mined main
     * block closes. Only valid for a block that the very next {@link #mineMain} links: the chunks
     * and the block paying for them must share that one epoch, and every {@link #confirm} advances
     * {@code generateTime} (it mines main blocks), so a timestamp taken before a confirm belongs to
     * an epoch that has already closed.
     */
    protected long txTime() {
        return XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime + 64000L)) - 60000L;
    }

    protected ChainBlockBuilder.Built deployNewChain(Bytes wasm, Bytes initArgs) {
        // Both chains count: init args above the inline capacity ride a second chain of their own.
        int chunks = ChainBlockBuilder.deployNewChainChunks(wasm.size(), initArgs.size());
        XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
        return ChainBlockBuilder.deployNewChain(config, txTime(), poolKey, nextNonce(), fee, wasm, CFG, initArgs, 1000L).value();
    }

    protected ChainBlockBuilder.Built call(Bytes chainId, Bytes contract, Bytes args, XAmount headerFee) {
        return ChainBlockBuilder.call(config, txTime(), poolKey, nextNonce(), chainId, contract, 1, 100L, ONE_XDAG, headerFee, args).value();
    }

    /** Imports chunks tail-first, then the paying block. */
    protected void importBuilt(ChainBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            assertImported(built.chunks().get(i));
        }
        assertImported(built.block());
    }

    protected void assertImported(Block b) {
        ImportResult r = blockchain.tryToConnect(b);
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        assertArrayEquals("a non-main block hijacked the chain top; change the payload seed (plan §0.5)",
                topRef.toArray(), blockchain.getXdagTopStatus().getTop());
    }

    protected Block mineMain(List<Bytes32> extraRefs) {
        return mineMain(extraRefs, true);
    }

    /**
     * Fake PoW: a main block linking topRef, the coinbase and extraRefs, with nonce searched until the raw-hash
     * difficulty is in [2^46, 2^47). expectBest=false is for competing branches that have not overtaken yet.
     *
     * <p>{@code extraRefs} must be in ascending tx-nonce order: {@code applyBlock} walks the links in
     * list order and enforces strict nonce sequencing per sender, so a link whose nonce arrives out
     * of order is rejected rather than applied. That failure is silent here and surfaces later as
     * {@code confirm()}'s "block not applied after 6 main blocks".
     */
    protected Block mineMain(List<Bytes32> extraRefs, boolean expectBest) {
        Block candidate = buildMain(extraRefs);
        ImportResult r = blockchain.tryToConnect(candidate);
        if (expectBest) {
            assertSame("main block must extend the best chain: " + r.getErrorInfo(), ImportResult.IMPORTED_BEST, r);
        } else {
            assertTrue("fork block rejected: " + r + " " + r.getErrorInfo(),
                    r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        }
        topRef = hashLow(candidate);
        blockchain.checkMain();
        return candidate;
    }

    /**
     * The mining half of {@link #mineMain(List, boolean)}: advances the mining clock one epoch and
     * searches a nonce, but imports nothing and leaves {@code topRef} where it is.
     *
     * <p>For a test that has to hand a main block to something other than {@code tryToConnect} — the
     * network entry point, say, where it may legitimately come back {@code NO_PARENT} because one of
     * its links has not been admitted yet. {@link #mineMain(List, boolean)} asserts the import
     * outcome itself and so cannot be used for that. A caller that does import the block is
     * responsible for {@code topRef} and {@code checkMain()} afterwards.
     */
    protected Block buildMain(List<Bytes32> extraRefs) {
        generateTime += 64000L;
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(topRef, XDAG_FIELD_OUT, false));
        pending.add(new Address(BasicUtils.keyPair2Hash(poolKey), XDAG_FIELD_COINBASE, true));
        for (Bytes32 r : extraRefs) {
            pending.add(new Address(r, XDAG_FIELD_OUT, false));
        }
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block template = new Block(config, xdagTime, null, pending, true, null, null, -1, XAmount.ZERO, null);
        template.signOut(poolKey);
        for (long n = 1; ; n++) {
            byte[] nonceBytes = new byte[32];
            ExtCodec.putU64(nonceBytes, 0, n);
            ExtCodec.putU64(nonceBytes, 8, generateTime);
            ExtCodec.putU64(nonceBytes, 16, forkSalt);
            template.setNonce(Bytes32.wrap(nonceBytes));
            Block candidate = new Block(new XdagBlock(template.toBytes()));
            BigInteger diff = blockchain.calculateCurrentBlockDiff(candidate);
            if (diff.compareTo(DIFF_LO) >= 0 && diff.compareTo(DIFF_HI) < 0) {
                return candidate;
            }
        }
    }

    /** Points the mining cursor at an earlier main block so the next mineMain() calls build a competing branch. */
    protected void rewindTo(Block main, long timeWhenMined) {
        topRef = hashLow(main);
        generateTime = timeWhenMined;
        forkSalt++;
    }

    /** Mines empty main blocks until the block has been applied (settled by a confirmed main block). */
    protected void confirm(Block block) {
        for (int i = 0; i < 6; i++) {
            Block stored = blockchain.getBlockByHash(block.getHashLow(), false);
            assertNotNull("block was never stored: " + block.getHashLow(), stored);
            if ((stored.getInfo().getFlags() & BI_APPLIED) != 0) {
                return;
            }
            mineMain(List.of());
        }
        fail("block not applied after 6 main blocks: " + block.getHashLow());
    }

    protected long heightOf(Block main) {
        Block stored = blockchain.getBlockByHash(main.getHashLow(), false);
        assertNotNull("main block was never stored: " + main.getHashLow(), stored);
        return stored.getInfo().getHeight();
    }

    protected XAmount balanceOf(Bytes address20) {
        return addressStore.getBalanceByAddress(address20.toArray());
    }
}
