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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.cli.XdagCli;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LaneL1SnapshotTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static final Bytes LANE = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();

    private Config configIn(Path storeDir) {
        Config c = new DevnetConfig();
        c.getNodeSpec().setStoreDir(storeDir.toString());
        c.getNodeSpec().setStoreBackupDir(storeDir.resolve("backup").toString());
        return c;
    }

    private LaneL1Store openStore(Config config) {
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        src.setConfig(config);
        LaneL1Store store = new LaneL1Store(src);
        store.start();
        return store;
    }

    private static LaneRecord lane(long height) {
        return new LaneRecord(height, Bytes32.random(), 5L, 32L, 10_000_000L, 1L);
    }

    /**
     * Ships SNAPSHOT/LANE_L1 from one node's store directory to another's, the way an operator
     * ships SNAPSHOT/BLOCKS today. {@link XdagCli#copyDir} only {@code mkdir}s a single level, so
     * the intermediate SNAPSHOT directory (created by RocksDB itself on a real node) is made here.
     */
    private static void ship(Path fromStoreDir, Path toStoreDir) throws IOException {
        Path target = toStoreDir.resolve(LaneSnapshotGate.SNAPSHOT_DB_NAME);
        Files.createDirectories(target);
        XdagCli.copyDir(fromStoreDir.resolve(LaneSnapshotGate.SNAPSHOT_DB_NAME).toString(), target.toString());
    }

    @Test
    public void exportThenImportOnAnotherNodeVerifiesTheHash() throws Exception {
        Path dirA = root.newFolder("a").toPath();
        Path dirB = root.newFolder("b").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        Bytes code = Bytes.random(700);
        LaneL1Store a = openStore(configA);
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, lane(7L));
        batch.putCode(CODE_HASH, 1L, code);
        a.commit(batch);
        Bytes32 expected = a.stateHash();
        LaneSnapshotGate.export(configA, a);
        a.stop();
        assertTrue(Files.isDirectory(dirA.resolve(LaneSnapshotGate.SNAPSHOT_DB_NAME)));

        // ship the snapshot directory to node B (what operators do with SNAPSHOT/BLOCKS today)
        ship(dirA, dirB);

        LaneL1Store b = openStore(configB);
        try {
            LaneSnapshotGate.checkAndImport(configB, 100L, b);
            assertEquals(expected, b.stateHash());
            assertTrue(b.hasLane(LANE));
            assertEquals(code, b.getCode(CODE_HASH));
            assertEquals(1L, b.getCodeRefCount(CODE_HASH));
        } finally {
            b.stop();
        }
    }

    @Test
    public void missingSnapshotIsFatalOnceActivated() throws Exception {
        Path dir = root.newFolder("c").toPath();
        Config config = configIn(dir);
        LaneL1Store store = openStore(config);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneSnapshotGate.checkAndImport(config, 100L, store));
            assertTrue(e.getMessage().contains("LANE_L1 snapshot required"));
        } finally {
            store.stop();
        }
    }

    @Test
    public void snapshotHeightEqualToActivationStillRequiresTheSnapshot() throws Exception {
        Path dir = root.newFolder("i").toPath();
        Config config = configIn(dir);
        config.getLaneSpec().setLaneActivationHeight(100L);
        LaneL1Store store = openStore(config);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneSnapshotGate.checkAndImport(config, 100L, store));
            assertTrue(e.getMessage().contains("LANE_L1 snapshot required"));
        } finally {
            store.stop();
        }
    }

    @Test
    public void snapshotBelowActivationIsIgnored() throws Exception {
        Path dir = root.newFolder("d").toPath();
        Config config = configIn(dir);
        config.getLaneSpec().setLaneActivationHeight(1_000L);
        LaneL1Store store = openStore(config);
        try {
            LaneSnapshotGate.checkAndImport(config, 999L, store); // no directory, no exception
            assertFalse(Files.exists(dir.resolve(LaneSnapshotGate.SNAPSHOT_DB_NAME)));
        } finally {
            store.stop();
        }
    }

    @Test
    public void tamperedSnapshotIsRejected() throws Exception {
        Path dir = root.newFolder("e").toPath();
        Config config = configIn(dir);
        LaneL1Store a = openStore(config);
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, lane(7L));
        a.commit(batch);
        LaneSnapshotGate.export(config, a);
        // a second export would silently merge into the first one: refuse it, naming the directory
        IllegalStateException reexport = assertThrows(IllegalStateException.class,
                () -> LaneSnapshotGate.export(config, a));
        assertTrue(reexport.getMessage().contains(LaneSnapshotGate.snapshotDir(config).toString()));
        a.stop();

        RocksdbKVSource snap = new RocksdbKVSource(LaneSnapshotGate.SNAPSHOT_DB_NAME);
        snap.setConfig(config);
        snap.init();
        try {
            snap.put(LaneL1Keys.lane(LANE), lane(8L).encode());
        } finally {
            snap.close();
        }

        Path freshDir = root.newFolder("f").toPath();
        Config fresh = configIn(freshDir);
        ship(dir, Paths.get(fresh.getNodeSpec().getStoreDir()));
        LaneL1Store b = openStore(fresh);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneSnapshotGate.checkAndImport(fresh, 100L, b));
            assertTrue(e.getMessage().contains("hash mismatch"));
        } finally {
            b.stop();
        }
    }

    @Test
    public void restartAfterImportVerifiesInsteadOfReimporting() throws Exception {
        Path dirA = root.newFolder("g").toPath();
        Path dirB = root.newFolder("h").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        LaneL1Store a = openStore(configA);
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, lane(7L));
        a.commit(batch);
        Bytes32 expected = a.stateHash();
        LaneSnapshotGate.export(configA, a);
        a.stop();
        ship(dirA, dirB);

        LaneL1Store b = openStore(configB);
        LaneSnapshotGate.checkAndImport(configB, 100L, b);
        b.stop();

        // second boot before the snapshot-boot flag was recorded: verify, never re-import
        LaneL1Store restarted = openStore(configB);
        try {
            LaneSnapshotGate.checkAndImport(configB, 100L, restarted);
            assertEquals(expected, restarted.stateHash());
            assertTrue(restarted.hasLane(LANE));
        } finally {
            restarted.stop();
        }

        // a LANE_L1 that drifted from the snapshot it claims to have booted from is refused
        LaneL1Store drifted = openStore(configB);
        try {
            LaneL1Batch extra = new LaneL1Batch();
            extra.putLane(Bytes.random(20), lane(9L));
            drifted.commit(extra);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneSnapshotGate.checkAndImport(configB, 100L, drifted));
            assertTrue(e.getMessage().contains("hash mismatch"));
        } finally {
            drifted.stop();
        }
    }
}
