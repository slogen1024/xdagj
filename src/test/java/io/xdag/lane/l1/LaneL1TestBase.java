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

package io.xdag.lane.l1;

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
import io.xdag.config.Config;
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
import io.xdag.lane.ext.ExtCodec;
import io.xdag.lane.ext.LaneBlockBuilder;
import io.xdag.lane.ext.LaneConfigExt;
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
 * Real BlockchainImpl + RocksDB fixture for lane hook tests. Main blocks are "mined" by searching a nonce whose
 * raw-hash difficulty lies in [2^46, 2^47), so chain weight grows predictably and chunk blocks (difficulty ~2^33)
 * can never hijack the top (see plan §0.5).
 */
public abstract class LaneL1TestBase {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    protected static final XAmount ONE_XDAG = XAmount.of(1, XUnit.XDAG);
    protected static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    protected static final LaneConfigExt CFG = new LaneConfigExt(1L, 32L, 10_000_000L);
    private static final BigInteger DIFF_LO = BigInteger.ONE.shiftLeft(46);
    private static final BigInteger DIFF_HI = BigInteger.ONE.shiftLeft(47);

    protected Config config = new DevnetConfig();
    protected Wallet wallet;
    protected Kernel kernel;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected LaneL1Store laneStore;
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

        public void checkMain() {
            checkNewMain();
        }
    }

    @Before
    public void setUpLane() throws Exception {
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        // Explicit, not inherited from the devnet default: another test in this JVM may have left a
        // lane.activation.height system-property override behind (LaneSpecTest), and Task 16 flips it.
        config.getLaneSpec().setLaneActivationHeight(0);
        wallet = new Wallet(config);
        wallet.unlock("password");
        wallet.setAccounts(Collections.singletonList(poolKey));
        wallet.flush();

        kernel = new Kernel(config, poolKey);
        dbFactory = new RocksdbFactory(config);
        BlockStore blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        blockStore.reset();
        OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphanBlockStore.reset();
        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.reset();
        laneStore = new LaneL1Store(dbFactory.getDB(DatabaseName.LANE_L1));
        laneStore.start();
        laneStore.reset();

        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setAddressStore(addressStore);
        kernel.setTxHistoryStore(Mockito.mock(TransactionHistoryStore.class));
        kernel.setWallet(wallet);
        kernel.setLaneL1Store(laneStore);

        // No setLaneHooks() here: kernel.setLaneL1Store(laneStore) above precedes construction, and
        // BlockchainImpl's constructor installs the LaneL1Processor from the kernel's store itself.
        blockchain = new MockBlockchain(kernel);

        Block addressBlock = BlockBuilder.generateAddressBlock(config, poolKey, generateTime);
        addressStore.updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));
        assertSame(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(new Block(new XdagBlock(addressBlock.toBytes()))));
        topRef = hashLow(addressBlock);
    }

    @After
    public void tearDownLane() throws IOException {
        if (wallet != null) {
            try {
                wallet.delete();
            } catch (NoSuchFileException e) {
                // already gone
            }
        }
        if (laneStore != null) {
            laneStore.stop();
        }
        if (dbFactory != null) {
            dbFactory.close();
        }
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

    protected LaneBlockBuilder.Built deployNewLane(Bytes wasm, Bytes initArgs) {
        // Both chains count: init args above the inline capacity ride a second chain of their own.
        int chunks = LaneBlockBuilder.deployNewLaneChunks(wasm.size(), initArgs.size());
        XAmount fee = LaneBlockBuilder.minHeaderFee(config.getLaneSpec().getLaneChunkFee(), chunks);
        return LaneBlockBuilder.deployNewLane(config, txTime(), poolKey, nextNonce(), fee, wasm, CFG, initArgs, 1000L).value();
    }

    protected LaneBlockBuilder.Built call(Bytes laneId, Bytes contract, Bytes args, XAmount headerFee) {
        return LaneBlockBuilder.call(config, txTime(), poolKey, nextNonce(), laneId, contract, 1, 100L, ONE_XDAG, headerFee, args).value();
    }

    /** Imports chunks tail-first, then the paying block. */
    protected void importBuilt(LaneBlockBuilder.Built built) {
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
                ImportResult r = blockchain.tryToConnect(candidate);
                if (expectBest) {
                    assertSame("main block must extend the best chain: " + r.getErrorInfo(), ImportResult.IMPORTED_BEST, r);
                } else {
                    assertTrue("fork block rejected: " + r.getErrorInfo(),
                            r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
                }
                topRef = hashLow(candidate);
                blockchain.checkMain();
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
