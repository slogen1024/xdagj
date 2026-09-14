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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.commons.io.FileUtils;
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
     * ships SNAPSHOT/BLOCKS today ({@link XdagCli#copyDir} creates the whole target path).
     */
    private static void ship(Path fromStoreDir, Path toStoreDir) {
        XdagCli.copyDir(fromStoreDir.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME).toString(),
                toStoreDir.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME).toString());
    }

    @Test
    public void exportThenImportOnAnotherNodeVerifiesTheHash() throws Exception {
        Path dirA = root.newFolder("a").toPath();
        Path dirB = root.newFolder("b").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        Bytes code = Bytes.random(700);
        Bytes32 expected;
        LaneL1Store a = openStore(configA);
        try {
            LaneL1Batch batch = new LaneL1Batch();
            batch.putLane(LANE, lane(7L));
            batch.putCode(CODE_HASH, 1L, code);
            a.commit(batch);
            expected = a.stateHash();
            LaneL1SnapshotGate.export(configA, a);
        } finally {
            a.stop();
        }
        assertTrue(Files.isDirectory(dirA.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME)));

        // ship the snapshot directory to node B (what operators do with SNAPSHOT/BLOCKS today)
        ship(dirA, dirB);

        LaneL1Store b = openStore(configB);
        try {
            LaneL1SnapshotGate.checkAndImport(configB, 100L, b);
            assertEquals(expected, b.stateHash());
            assertTrue(b.hasLane(LANE));
            assertEquals(code, b.getCode(CODE_HASH));
            assertEquals(1L, b.getCodeRefCount(CODE_HASH));
            // the import left a durable marker carrying the snapshot's hash
            assertEquals(Optional.of(expected), b.importedSnapshotHash());
            assertTrue(b.hasState());
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
                    () -> LaneL1SnapshotGate.checkAndImport(config, 100L, store));
            assertTrue(e.getMessage().startsWith("LANE_L1 snapshot required"));
            assertTrue(e.getMessage().contains("obtain SNAPSHOT/LANE_L1 from the same publisher"));
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
                    () -> LaneL1SnapshotGate.checkAndImport(config, 100L, store));
            assertTrue(e.getMessage().startsWith("LANE_L1 snapshot required"));
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
            LaneL1SnapshotGate.checkAndImport(config, 999L, store); // no directory, no exception
            assertFalse(Files.exists(dir.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME)));
            assertEquals(Optional.empty(), store.importedSnapshotHash());
        } finally {
            store.stop();
        }
    }

    /** A store that is not wired at all past activation is a wiring bug, not a silent skip. */
    @Test
    public void missingStorePastActivationIsFatal() throws Exception {
        Path dir = root.newFolder("j").toPath();
        Config config = configIn(dir);
        config.getLaneSpec().setLaneActivationHeight(50L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LaneL1SnapshotGate.checkAndImport(config, 100L, null));
        assertTrue(e.getMessage().startsWith("LANE_L1 store not wired"));
        assertTrue(e.getMessage().contains("the kernel must create it before BlockchainImpl"));

        // below activation a missing store is still fine
        config.getLaneSpec().setLaneActivationHeight(1_000L);
        LaneL1SnapshotGate.checkAndImport(config, 999L, null);
    }

    @Test
    public void tamperedSnapshotIsRejected() throws Exception {
        Path dir = root.newFolder("e").toPath();
        Config config = configIn(dir);
        LaneL1Store a = openStore(config);
        try {
            LaneL1Batch batch = new LaneL1Batch();
            batch.putLane(LANE, lane(7L));
            a.commit(batch);
            LaneL1SnapshotGate.export(config, a);
            // a second export would silently merge into the first one: refuse it, naming the directory
            IllegalStateException reexport = assertThrows(IllegalStateException.class,
                    () -> LaneL1SnapshotGate.export(config, a));
            assertTrue(reexport.getMessage().contains(LaneL1SnapshotGate.snapshotDir(config).toString()));
        } finally {
            a.stop();
        }

        RocksdbKVSource snap = new RocksdbKVSource(LaneL1SnapshotGate.SNAPSHOT_DB_NAME);
        snap.setConfig(config);
        snap.init();
        try {
            snap.put(LaneL1Keys.lane(LANE), lane(8L).encode());
        } finally {
            snap.close();
        }

        Path freshDir = root.newFolder("f").toPath();
        Config fresh = configIn(freshDir);
        ship(dir, freshDir);
        LaneL1Store b = openStore(fresh);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneL1SnapshotGate.checkAndImport(fresh, 100L, b));
            assertTrue(e.getMessage().startsWith("LANE_L1 snapshot at "));
            assertTrue(e.getMessage().contains(LaneL1SnapshotGate.snapshotDir(fresh).toString()));
            assertTrue(e.getMessage().contains("hash mismatch"));
            // nothing was written: the store is still importable once a good snapshot arrives
            assertFalse(b.hasState());
            assertEquals(Optional.empty(), b.importedSnapshotHash());
        } finally {
            b.stop();
        }
    }

    /**
     * The marker makes the import one-shot: a later boot returns without touching the snapshot
     * directory. It records where this LANE_L1 came from, not what it holds now — so a store that
     * has since applied blocks (here, simulated by an extra lane) is still accepted. That is the
     * contract: verifying the hash again would refuse every node that ever applied a block.
     */
    @Test
    public void restartAfterImportIsIdempotentAndDoesNotNeedTheSnapshot() throws Exception {
        Path dirA = root.newFolder("g").toPath();
        Path dirB = root.newFolder("h").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        Bytes32 expected;
        LaneL1Store a = openStore(configA);
        try {
            LaneL1Batch batch = new LaneL1Batch();
            batch.putLane(LANE, lane(7L));
            a.commit(batch);
            expected = a.stateHash();
            LaneL1SnapshotGate.export(configA, a);
        } finally {
            a.stop();
        }
        ship(dirA, dirB);

        LaneL1Store b = openStore(configB);
        try {
            LaneL1SnapshotGate.checkAndImport(configB, 100L, b);
        } finally {
            b.stop();
        }

        // the operator may now delete the snapshot directory: the marker answers on its own
        FileUtils.deleteDirectory(new File(dirB.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME).toString()));
        assertFalse(Files.exists(dirB.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME)));

        LaneL1Store restarted = openStore(configB);
        try {
            LaneL1SnapshotGate.checkAndImport(configB, 100L, restarted);
            assertEquals(expected, restarted.stateHash());
            assertTrue(restarted.hasLane(LANE));
            assertEquals(Optional.of(expected), restarted.importedSnapshotHash());
        } finally {
            restarted.stop();
        }

        // a LANE_L1 that has moved on from the snapshot it booted from is still accepted
        LaneL1Store moved = openStore(configB);
        try {
            LaneL1Batch extra = new LaneL1Batch();
            extra.putLane(Bytes.random(20), lane(9L));
            moved.commit(extra);
            LaneL1SnapshotGate.checkAndImport(configB, 100L, moved);
            assertEquals(Optional.of(expected), moved.importedSnapshotHash());
            assertFalse(expected.equals(moved.stateHash()));
        } finally {
            moved.stop();
        }
    }

    /** State that never came from a snapshot cannot be verified against one, so it is refused. */
    @Test
    public void localStateWithoutTheMarkerIsRefused() throws Exception {
        Path dirA = root.newFolder("k").toPath();
        Path dirB = root.newFolder("l").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        LaneL1Store a = openStore(configA);
        try {
            LaneL1Batch batch = new LaneL1Batch();
            batch.putLane(LANE, lane(7L));
            a.commit(batch);
            LaneL1SnapshotGate.export(configA, a);
        } finally {
            a.stop();
        }
        ship(dirA, dirB);
        assertTrue(Files.isDirectory(dirB.resolve(LaneL1SnapshotGate.SNAPSHOT_DB_NAME)));

        LaneL1Store b = openStore(configB);
        try {
            LaneL1Batch leftover = new LaneL1Batch();
            leftover.putLane(Bytes.random(20), lane(3L)); // e.g. a LANE_L1 from another chain
            b.commit(leftover);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LaneL1SnapshotGate.checkAndImport(configB, 100L, b));
            assertTrue(e.getMessage().startsWith("LANE_L1 already holds state that was not imported"));
            assertTrue(e.getMessage().contains(LaneL1SnapshotGate.laneDir(configB).toString()));
            assertTrue(e.getMessage().contains("remove the local LANE_L1 directory"));
        } finally {
            b.stop();
        }
    }

    /**
     * Export is unconditional, so a network whose lane protocol is still unscheduled ships a
     * META-only SNAPSHOT/LANE_L1. A consumer below activation ignores it; one past activation
     * imports it and ends up with an empty-but-marked LANE_L1.
     */
    @Test
    public void emptyLaneStateIsStillExportedAndHarmless() throws Exception {
        Path dirA = root.newFolder("m").toPath();
        Path dirB = root.newFolder("n").toPath();
        Config configA = configIn(dirA);
        configA.getLaneSpec().setLaneActivationHeight(Long.MAX_VALUE); // lane protocol unscheduled

        LaneL1Store a = openStore(configA);
        try {
            assertFalse(a.hasState());
            LaneL1SnapshotGate.export(configA, a);
        } finally {
            a.stop();
        }
        RocksdbKVSource exported = new RocksdbKVSource(LaneL1SnapshotGate.SNAPSHOT_DB_NAME);
        exported.setConfig(configA);
        exported.init();
        try {
            assertEquals(2, exported.keys().size()); // META plus the recorded hash
        } finally {
            exported.close();
        }

        ship(dirA, dirB);
        Config below = configIn(dirB);
        below.getLaneSpec().setLaneActivationHeight(1_000L);
        LaneL1Store b = openStore(below);
        try {
            LaneL1SnapshotGate.checkAndImport(below, 999L, b); // directory present but ignored
            assertEquals(Optional.empty(), b.importedSnapshotHash());
        } finally {
            b.stop();
        }

        Config past = configIn(dirB);
        past.getLaneSpec().setLaneActivationHeight(100L);
        LaneL1Store c = openStore(past);
        try {
            LaneL1SnapshotGate.checkAndImport(past, 100L, c);
            assertTrue(c.importedSnapshotHash().isPresent());
            assertFalse(c.hasState());
            assertEquals(LaneL1Store.SCHEMA_VERSION, c.schemaVersion());
        } finally {
            c.stop();
        }
    }
}
