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

import io.xdag.config.Config;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Snapshot bootstrap rule for LANE_L1: once the snapshot height is at or past the lane activation
 * height, a node booting from a snapshot must also import {@code SNAPSHOT/LANE_L1} (hash-verified).
 * Below activation the directory is ignored, present or not.
 *
 * <p>The snapshot database sits next to {@code SNAPSHOT/BLOCKS} and {@code SNAPSHOT/ADDRESS} under
 * the node's store directory and is produced by {@code XdagCli.makeSnapshot}; it holds every
 * LANE_L1 key plus the state hash recorded under {@link LaneL1Keys#SNAPSHOT_HASH_KEY} (see
 * {@link LaneL1Store#exportSnapshot}).
 *
 * <p><b>Import vs verify.</b> {@link #checkAndImport} imports only into an empty LANE_L1 (META
 * only, which is all {@link LaneL1Store#start()} writes); a store that already carries state is
 * <em>verified</em> instead — its {@link LaneL1Store#stateHash()} must equal the hash recorded in
 * the snapshot. That is the idempotent-restart path: {@code BlockchainImpl}'s constructor only
 * reaches {@code initSnapshotJ} while {@code blockStore.isSnapshotBoot()} is still false, so the
 * only way to re-enter the gate with a populated LANE_L1 is a crash between this import and the
 * snapshot-boot flag being recorded — at which point LANE_L1 holds exactly the snapshot's state and
 * the hashes match. Anything else (a LANE_L1 left over from a different chain or a partially
 * written import) is a mismatch and is refused rather than silently merged.
 *
 * <h3>Why no raw chunk bytes travel in the snapshot</h3>
 *
 * <p>A lane input's verdict depends on the CHUNK blocks its code/args chain links, and the age rule
 * {@code epoch(chunk) >= epoch(paying) - 1} lets a post-snapshot paying block reach at most two
 * epochs back — possibly to before the snapshot time. Those chunk blocks are <em>not</em> in the
 * snapshot, and they do not need to be, for three verified reasons:
 *
 * <ul>
 *   <li>{@code BlockStoreImpl.getRawBlockByHash} returns {@code null} (not a BlockInfo-only block)
 *       when the 512 raw bytes are absent, and {@code SnapshotStoreImpl.makeSnapshot} keeps only
 *       blocks that have a public key ({@code snapshotInfo != null}) or a non-zero balance — so
 *       pre-snapshot CHUNK blocks (zero amount, no key) are absent from a snapshot-booted node
 *       entirely.</li>
 *   <li>{@code BlockchainImpl.tryToConnect} rejects a block only for
 *       {@code timestamp > now + MAIN_CHAIN_PERIOD/4} or {@code timestamp < xdagEra}; there is no
 *       "older than the snapshot" rejection. A link to an absent block yields
 *       {@code ImportResult.NO_PARENT}, and {@code SyncManager} answers NO_PARENT by requesting the
 *       missing parent from every active channel ({@code sendGetBlock}), recursively; the fetched
 *       block imports through the normal path, so {@code BlockStoreImpl.saveBlock} writes its raw
 *       bytes, its BlockInfo and its time index.</li>
 *   <li>Therefore such a paying block cannot connect until its whole chunk chain has been fetched
 *       and stored raw; by the time a main block confirms it, {@code getBlockByHash(h, true)}
 *       succeeds for every chunk on the snapshot node exactly as on a full node, and the LANE_L1
 *       verdict is identical on both.</li>
 * </ul>
 *
 * <p>Residual exposure (accepted; node-local liveness only, never a state divergence): if no peer
 * can serve the old chunks, the paying block never connects on that node and the main block linking
 * it stays NO_PARENT too — the same liveness dependency every snapshot node already has for any
 * post-snapshot block linking a dropped pre-snapshot block.
 */
public final class LaneSnapshotGate {

    public static final String SNAPSHOT_DB_NAME = "SNAPSHOT/LANE_L1";

    private LaneSnapshotGate() {
    }

    /** The {@code SNAPSHOT/LANE_L1} directory under this node's store directory. */
    public static Path snapshotDir(Config config) {
        return Paths.get(config.getNodeSpec().getStoreDir(), SNAPSHOT_DB_NAME);
    }

    /**
     * Writes the whole LANE_L1 state plus its hash into {@code SNAPSHOT/LANE_L1} under the node's
     * store directory.
     *
     * @throws IllegalStateException if that directory already holds a snapshot (a second export
     *                               would merge into the first one and record a hash for content
     *                               that is no longer what is stored there)
     */
    public static void export(Config config, LaneL1Store store) {
        RocksdbKVSource target = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        target.setConfig(config);
        target.init();
        try {
            if (!target.keys().isEmpty()) {
                throw new IllegalStateException("LANE_L1 snapshot target " + snapshotDir(config)
                        + " is not empty; remove it before exporting again");
            }
            store.exportSnapshot(target);
        } finally {
            target.close();
        }
    }

    /**
     * Returns true if a LANE_L1 snapshot is worth writing: either the lane protocol has a scheduled
     * activation height on this network, or LANE_L1 already holds state (any key beyond
     * {@code META}). A shared network whose activation is still {@link Long#MAX_VALUE} and whose
     * LANE_L1 was never written has nothing to carry.
     */
    public static boolean shouldExport(Config config, LaneL1Store store) {
        if (store == null) {
            return false;
        }
        return config.getLaneSpec().getLaneActivationHeight() != Long.MAX_VALUE || store.sortedKeys().size() > 1;
    }

    /**
     * Called from snapshot bootstrap. Imports {@code SNAPSHOT/LANE_L1} into {@code store} when the
     * snapshot height is at or past the lane activation height; below activation this is a no-op
     * even if the directory exists. A store that already carries state is verified against the
     * snapshot's recorded hash instead of re-imported (see the class Javadoc).
     *
     * @throws IllegalStateException when the snapshot is required but missing, when it carries no
     *                               recorded hash, or when its content (or the store's existing
     *                               content) does not match that hash
     */
    public static void checkAndImport(Config config, long snapshotHeight, LaneL1Store store) {
        long activation = config.getLaneSpec().getLaneActivationHeight();
        if (snapshotHeight < activation || store == null) {
            return;
        }
        Path dir = snapshotDir(config);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("LANE_L1 snapshot required at height " + snapshotHeight
                    + " (lane activation height " + activation + ") but " + dir + " is missing");
        }
        RocksdbKVSource source = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        source.setConfig(config);
        source.init();
        try {
            if (store.sortedKeys().size() > 1) {
                verifyAgainst(source, store, dir);
            } else {
                store.importSnapshot(source);
            }
        } finally {
            source.close();
        }
    }

    /** Restart path: LANE_L1 already holds state, so it must already equal the snapshot's. */
    private static void verifyAgainst(RocksdbKVSource source, LaneL1Store store, Path dir) {
        byte[] expected = source.get(LaneL1Keys.SNAPSHOT_HASH_KEY);
        if (expected == null) {
            throw new IllegalStateException("LANE_L1 snapshot at " + dir + " has no state hash");
        }
        Bytes32 actual = store.stateHash();
        if (!actual.equals(Bytes32.wrap(expected))) {
            throw new IllegalStateException("LANE_L1 already holds state that does not match the snapshot at "
                    + dir + ": snapshot hash mismatch, expected " + Bytes32.wrap(expected) + " actual " + actual);
        }
    }
}
