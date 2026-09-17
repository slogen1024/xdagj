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

package io.xdag.cli;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static uk.org.webcompere.systemstubs.SystemStubs.tapSystemOut;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.db.rocksdb.SnapshotStoreImpl;
import io.xdag.utils.XdagTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * G9 (SP0b-1), end to end: {@code xdag.sh --makesnapshot} on a real RocksDB store that a real chain
 * was mined into. Nothing below {@link XdagCli#makeSnapshot(boolean)} is mocked: the command opens
 * the directories the fixture wrote, and each of the three directories it ships is then
 * content-verified by replaying what the bootstrap ({@code BlockchainImpl.initSnapshotJ}) reads:
 * <ul>
 *   <li>{@code SNAPSHOT/BLOCKS} — imported into a second node's block store with the same
 *       {@link SnapshotStoreImpl#saveSnapshotToIndex} call the bootstrap makes, after which the
 *       tip main block must be readable by height: that is the lookup the bootstrap dereferences
 *       unguarded, so a snapshot without its tip is one that NPEs on boot.</li>
 *   <li>{@code SNAPSHOT/ADDRESS} — opened through {@link AddressStoreImpl}, the way
 *       {@code saveAddress} reads it; the miner's balance must be what the live store held.</li>
 *   <li>{@code SNAPSHOT/CHAIN_L1} — its recorded state hash must be the live store's, and copied
 *       to the second node it must pass {@link ChainL1SnapshotGate#checkAndImport}, whose hash
 *       check is the verification.</li>
 * </ul>
 * BLOCKS and ADDRESS are opened in place, in the very directories the command wrote, which also
 * proves the command released its handles: RocksDB refuses a second open of a database this
 * process still holds.
 */
public class MakeSnapshotEndToEndTest extends ChainL1TestBase {

    /** The names {@code XdagCli.makeSnapshot} and {@code BlockchainImpl.initSnapshotJ} agree on. */
    private static final String SNAPSHOT_BLOCKS = "SNAPSHOT/BLOCKS";
    private static final String SNAPSHOT_ADDRESS = "SNAPSHOT/ADDRESS";

    @Test
    public void makeSnapshotWritesThreeDirectoriesThatBootAnotherNode() throws Exception {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        ChainBlockBuilder.Built deploy = deployNewChain(payload(700, 61), payload(10, 62));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertTrue(chainStore.hasChain(chainId));
        Bytes32 expectedHash = chainStore.stateHash();
        long height = blockchain.getXdagStats().nmain;
        Block tip = blockchain.getBlockByHeight(height);
        assertNotNull("the tip main block is readable by height on the live store", tip);
        Bytes32 tipHash = hashLow(tip);
        // The pool key is the miner: it was seeded with 1000 XDAG and paid for every main block.
        XAmount minerBefore = balanceOf(poolKey.toAddress());
        assertTrue("the miner holds a balance worth checking: " + minerBefore, minerBefore.isPositive());

        releaseStores();

        XdagCli cli = new XdagCli();
        cli.setConfig(config);
        boolean[] bootable = new boolean[1];
        String printed = tapSystemOut(() -> bootable[0] = cli.makeSnapshot(false));
        assertTrue("all three directories were written, so the snapshot is bootable:\n" + printed, bootable[0]);

        // Anchored on the line end: "snapshot height: 7" must not pass on a printed 70.
        assertTrue(printed, printed.contains("snapshot height: " + height + System.lineSeparator()));
        // Mirrors the command's own formula over the tip's timestamp, since the snapshot's nextTime is
        // the timestamp of the highest block it walked.
        assertTrue(printed, printed.contains("next start frame: "
                + Long.toHexString(XdagTime.getEndOfEpoch(tip.getInfo().getTimestamp()) + 1) + System.lineSeparator()));
        assertTrue(printed, printed.contains("chain state snapshot written to"));

        Path snap = Paths.get(config.getNodeSpec().getStoreDir(), "SNAPSHOT");
        assertTrue(Files.isDirectory(snap.resolve("BLOCKS")));
        assertTrue(Files.isDirectory(snap.resolve("ADDRESS")));
        assertTrue(Files.isDirectory(snap.resolve("CHAIN_L1")));

        // ADDRESS: read the way saveAddress reads it, through an AddressStoreImpl over the shipped directory.
        RocksdbKVSource addressSrc = new RocksdbKVSource(SNAPSHOT_ADDRESS);
        addressSrc.setConfig(config);
        AddressStore snapAddress = new AddressStoreImpl(addressSrc);
        snapAddress.start();
        try {
            assertEquals("the miner's balance survived the copy", minerBefore,
                    snapAddress.getBalanceByAddress(poolKey.toAddress().toArray()));
        } finally {
            snapAddress.stop();
        }

        // CHAIN_L1: the recorded hash is the live store's.
        RocksdbKVSource snapChain = new RocksdbKVSource(ChainL1SnapshotGate.SNAPSHOT_DB_NAME);
        snapChain.setConfig(config);
        snapChain.init();
        try {
            byte[] recorded = snapChain.get(ChainL1Keys.SNAPSHOT_HASH_KEY);
            assertNotNull("the CHAIN_L1 snapshot records its state hash", recorded);
            assertEquals(expectedHash, Bytes32.wrap(recorded));
        } finally {
            snapChain.close();
        }

        // A second node bootstraps from the shipped directories.
        DevnetConfig other = otherNode();

        // BLOCKS: the bootstrap's own import into the other node's block store, then the lookup
        // initSnapshotJ makes right after it. Opened in place: the command closed its handle.
        DatabaseFactory otherFactory = new RocksdbFactory(other);
        BlockStore otherBlockStore = BlockStoreImpl.forNode(otherFactory);
        otherBlockStore.start();
        RocksdbKVSource blocksSrc = new RocksdbKVSource(SNAPSHOT_BLOCKS);
        blocksSrc.setConfig(config);
        blocksSrc.init();
        try {
            // (blockStore, txHistoryStore, keys, snapshotTime): the history store is optional and
            // the time only stamps history rows, so neither matters to what is asserted.
            new SnapshotStoreImpl(blocksSrc).saveSnapshotToIndex(otherBlockStore, null, List.of(poolKey), 0L);
            Block booted = otherBlockStore.getBlockByHeight(height);
            assertNotNull("SNAPSHOT/BLOCKS carries the tip main block (the bootstrap dereferences it)", booted);
            assertEquals("and it is the tip the live store had", tipHash, hashLow(booted));
        } finally {
            blocksSrc.close();
            otherFactory.close();
        }

        // CHAIN_L1: shipped by copying the directory, imported by the gate the boot runs.
        XdagCli.copyDir(snap.resolve("CHAIN_L1").toString(),
                Paths.get(other.getNodeSpec().getStoreDir(), "SNAPSHOT", "CHAIN_L1").toString());
        RocksdbKVSource otherSrc = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        otherSrc.setConfig(other);
        ChainL1Store otherStore = new ChainL1Store(otherSrc);
        otherStore.start();
        try {
            ChainL1SnapshotGate.checkAndImport(other, height, otherStore);
            assertEquals(expectedHash, otherStore.stateHash());
            assertTrue(otherStore.hasChain(chainId));
        } finally {
            otherStore.stop();
        }
    }

    /**
     * A copy failure inside {@code SNAPSHOT/ADDRESS} must not abort the command half-way: it is
     * reported, CHAIN_L1 is still exported, the height and frame are still printed and the last
     * line says what to delete — and the command returns false, which the dispatch turns into exit
     * 1, because a snapshot without ADDRESS cannot boot a node.
     */
    @Test
    public void anAddressCopyFailureIsReportedAndMakesTheSnapshotNotBootable() throws Exception {
        for (int i = 0; i < 3; i++) {
            mineMain(List.of());
        }
        long height = blockchain.getXdagStats().nmain;
        releaseStores();
        // RocksDB always writes a CURRENT file; a directory in its place makes copyFile fail on it.
        Path clash = Paths.get(config.getNodeSpec().getStoreDir(), "SNAPSHOT", "ADDRESS", "CURRENT");
        Files.createDirectories(clash);

        XdagCli cli = new XdagCli();
        cli.setConfig(config);
        boolean[] bootable = new boolean[1];
        String printed = tapSystemOut(() -> bootable[0] = cli.makeSnapshot(false));

        assertFalse("a snapshot without ADDRESS is not bootable:\n" + printed, bootable[0]);
        assertTrue(printed, printed.contains("address snapshot NOT written"));
        assertTrue("the failing file is named:\n" + printed, printed.contains(clash.toString()));
        assertTrue("CHAIN_L1 is still exported:\n" + printed, printed.contains("chain state snapshot written to"));
        assertTrue("the height is still printed:\n" + printed,
                printed.contains("snapshot height: " + height + System.lineSeparator()));
        // The whole SNAPSHOT directory, not just ADDRESS: the CHAIN_L1 export refuses a non-empty
        // target on the rerun, and neither the block scan nor copyDir clears stale rows first.
        assertTrue("the operator is told what to delete and rerun:\n" + printed, printed.contains(
                "this snapshot cannot boot a node; delete " + clash.getParent().getParent()
                        + " and run --makesnapshot again"));
    }

    /** A second node's config over its own temp root, past the chain activation height. */
    private DevnetConfig otherNode() throws IOException {
        String otherRoot = root.newFolder("other").getAbsolutePath();
        // DevnetConfig, not Config: setRootDir/setDir are on AbstractConfig, not part of the interface.
        DevnetConfig other = new DevnetConfig();
        other.setRootDir(otherRoot);
        other.setDir();
        other.getChainSpec().setChainActivationHeight(0);
        return other;
    }
}
