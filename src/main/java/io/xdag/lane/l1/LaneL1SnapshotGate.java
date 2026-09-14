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
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Snapshot bootstrap rule for LANE_L1: once the snapshot height is at or past the lane activation
 * height, a node booting from a snapshot must also import {@code SNAPSHOT/LANE_L1} (hash-verified).
 * Below activation the directory is ignored, present or not.
 *
 * <p>The snapshot database sits next to {@code SNAPSHOT/BLOCKS} and {@code SNAPSHOT/ADDRESS} under
 * the node's store directory and is produced by {@code XdagCli.makeSnapshot}; it holds every
 * LANE_L1 key plus the state hash recorded under {@link LaneL1Keys#SNAPSHOT_HASH_KEY} (see
 * {@link LaneL1Store#exportSnapshot}). {@code makeSnapshot} always writes it, even on a network
 * where the lane protocol is still unscheduled: such a snapshot is one {@code META} key plus the
 * hash, and a consumer below activation ignores it anyway.
 *
 * <p><b>The import marker is a crash-window retry, not a licence to skip verification.</b>
 * {@link LaneL1Store#importSnapshot} copies the snapshot's recorded hash into the local store under
 * {@link LaneL1Keys#SNAPSHOT_HASH_KEY}, atomically with the imported keys, as the durable record
 * that this LANE_L1 <em>is</em> that snapshot. The gate is only ever reached when the block store
 * is about to be (re)seeded from a snapshot (see "Where the gate runs" below), so a marker found
 * there means one thing only: an earlier attempt at <em>this same</em> re-seed already imported the
 * lane state and the node died before it finished the rest. Such a retry must be a no-op, so
 * {@link #checkAndImport} returns — but only after proving both halves of that claim: that
 * {@code SNAPSHOT/LANE_L1} is present for this boot and records the very hash the marker names (so
 * the operator is not re-bootstrapping from a <em>newer</em> snapshot while keeping a LANE_L1 that
 * stops at the older one), and that the local {@link LaneL1Store#stateHash()} still equals the
 * marker (so a LANE_L1 that has since applied blocks is not re-seeded under, and double-counted
 * against, a block store that is about to replay them). Either mismatch is refused, naming the
 * local LANE_L1 directory to delete. A node that simply restarts never reaches any of this: the
 * snapshot-boot branch is entered only while {@code blockStore.isSnapshotBoot()} is still false.
 *
 * <p><b>Where the gate runs.</b> {@code BlockchainImpl}'s constructor calls it as the very first
 * statement of its snapshot-boot branch — before any stats are built and before the
 * {@code isSnapshotJ()} decision — so every snapshot boot passes through it, and it fails fast
 * ahead of the expensive block/address imports. That branch is guarded by
 * {@code isSnapshotEnabled() && getSnapshotHeight() > 0 && !blockStore.isSnapshotBoot()}, and
 * {@code Kernel} sets {@code setSnapshotBoot()} right after construction, so the gate runs on
 * re-seed boots and on those only.
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
 *   <li>{@code BlockchainImpl.tryToConnect} has no rejection keyed on the snapshot height or on
 *       block age beyond {@code xdagEra} (its only time checks are
 *       {@code timestamp > now + MAIN_CHAIN_PERIOD/4} and {@code timestamp < xdagEra}). Its
 *       {@code NO_PARENT} verdict fires when even the {@code BlockInfo} of a referenced block is
 *       absent ({@code getBlockByHash(ref, false) == null}) — which for pre-snapshot chunk blocks
 *       is guaranteed by bullet 1, since those blocks are absent entirely, BlockInfo included.
 *       {@code SyncManager} answers NO_PARENT by requesting the missing parent from every active
 *       channel ({@code sendGetBlock}), recursively; the fetched block imports through the normal
 *       path, so {@code BlockStoreImpl.saveBlock} writes its raw bytes, its BlockInfo and its time
 *       index. (The age rule itself — why a chunk chain may not reach further back than that — is
 *       documented in {@code ChunkChain}'s "Age rule ({@code minEpoch})" paragraph.)</li>
 *   <li>Therefore such a paying block cannot connect until its whole chunk chain has been fetched
 *       and stored raw; by the time a main block confirms it, {@code getBlockByHash(h, true)}
 *       succeeds for every chunk on the snapshot node exactly as on a full node, and the LANE_L1
 *       verdict is identical on both.</li>
 * </ul>
 *
 * <p>Residual exposure (accepted; node-local liveness only, never a state divergence): if no peer
 * can serve the old chunks, the paying block never connects on that node and the main block linking
 * it stays NO_PARENT too — the same liveness dependency every snapshot node already has for any
 * post-snapshot block linking a dropped pre-snapshot block. Recovery is best-effort:
 * {@code SyncManager.syncPushBlock} re-requests a given missing parent at most once every 64
 * seconds, and evicts random pending entries once {@code syncMap} reaches {@code MAX_SIZE}, so a
 * stalled block may have to be re-offered by a peer before it is retried at all.
 */
@Slf4j
public final class LaneL1SnapshotGate {

    public static final String SNAPSHOT_DB_NAME = "SNAPSHOT/LANE_L1";

    private LaneL1SnapshotGate() {
    }

    /** The {@code SNAPSHOT/LANE_L1} directory under this node's store directory. */
    public static Path snapshotDir(Config config) {
        return Paths.get(config.getNodeSpec().getStoreDir(), SNAPSHOT_DB_NAME);
    }

    /** The live {@code LANE_L1} directory under this node's store directory. */
    public static Path laneDir(Config config) {
        return Paths.get(config.getNodeSpec().getStoreDir(), DatabaseName.LANE_L1.toString());
    }

    /**
     * Writes the whole LANE_L1 state plus its hash into {@code SNAPSHOT/LANE_L1} under the node's
     * store directory. Always called by {@code makeSnapshot}, whatever the network's lane
     * activation height and whatever LANE_L1 holds: an "empty" export is one {@code META} key plus
     * the hash, which a consumer below activation ignores.
     *
     * @throws IllegalStateException if that directory already holds a snapshot (a second export
     *                               would merge into the first one and record a hash for content
     *                               that is no longer what is stored there)
     */
    public static void export(Config config, LaneL1Store store) {
        RocksdbKVSource target = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        target.setConfig(config);
        try {
            target.init();
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
     * Called as the first step of snapshot bootstrap. Imports {@code SNAPSHOT/LANE_L1} into
     * {@code store} exactly once, when the snapshot height is at or past the lane activation
     * height. The decision table:
     *
     * <ol>
     *   <li>{@code snapshotHeight < activation}: no-op, directory present or not.</li>
     *   <li>{@code store == null}: {@link IllegalStateException} — past activation the kernel must
     *       have wired LANE_L1 before {@code BlockchainImpl} runs.</li>
     *   <li>the store carries the import marker ({@link LaneL1Store#importedSnapshotHash()}): a
     *       previous attempt at this same re-seed already imported the lane state. Return only if
     *       {@code SNAPSHOT/LANE_L1} is present and records exactly that hash, and the local state
     *       hash still equals the marker; otherwise refuse (re-bootstrap from a different snapshot,
     *       or a LANE_L1 that has moved on since the import).</li>
     *   <li>the store carries state but no marker ({@link LaneL1Store#hasState()}): refuse — that
     *       state cannot be verified against anything.</li>
     *   <li>the snapshot directory is missing: refuse, naming what to fetch.</li>
     *   <li>otherwise: import (hash- and schema-verified by {@link LaneL1Store#importSnapshot}).</li>
     * </ol>
     *
     * @throws IllegalStateException in cases 2, 3 (on either mismatch), 4 and 5 above, and when the
     *                               snapshot itself is rejected (no recorded hash, wrong schema,
     *                               hash mismatch)
     */
    public static void checkAndImport(Config config, long snapshotHeight, LaneL1Store store) {
        long activation = config.getLaneSpec().getLaneActivationHeight();
        if (snapshotHeight < activation) {
            report("LANE_L1 snapshot not required: snapshot height " + snapshotHeight
                    + " is below the lane activation height " + activation);
            return;
        }
        if (store == null) {
            throw new IllegalStateException("LANE_L1 store not wired at snapshot height " + snapshotHeight
                    + " (lane activation height " + activation + "); the kernel must create it before "
                    + "BlockchainImpl");
        }
        Optional<Bytes32> alreadyImported = store.importedSnapshotHash();
        if (alreadyImported.isPresent()) {
            verifyMarker(config, alreadyImported.get(), store);
            return;
        }
        if (store.hasState()) {
            throw new IllegalStateException("LANE_L1 already holds state that was not imported from a snapshot "
                    + "(cannot be verified) at snapshot height " + snapshotHeight + " (lane activation height "
                    + activation + "); remove the local LANE_L1 directory (" + laneDir(config)
                    + ") and restart, or boot from the matching snapshot");
        }
        Path dir = snapshotDir(config);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("LANE_L1 snapshot required at height " + snapshotHeight
                    + " (lane activation height " + activation + ") but " + dir + " is missing; obtain "
                    + "SNAPSHOT/LANE_L1 from the same publisher as SNAPSHOT/BLOCKS and place it beside it, "
                    + "then restart");
        }
        RocksdbKVSource source = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        source.setConfig(config);
        try {
            source.init();
            int keyCount = Math.max(source.keys().size() - 1, 0); // the recorded hash is not state
            try {
                store.importSnapshot(source);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("LANE_L1 snapshot at " + dir + " was refused: " + e.getMessage(), e);
            }
            report("imported " + keyCount + " LANE_L1 keys from " + dir + ", state hash "
                    + store.stateHash().toHexString());
        } finally {
            source.close();
        }
    }

    /**
     * The marker path: this LANE_L1 already claims to be a snapshot import, and the gate only runs
     * when the block store is about to be re-seeded, so the only benign reading is "the previous
     * attempt at this very re-seed got as far as the lane import". Accept that — idempotently, the
     * store is left untouched — but only once both halves are proven; refuse anything else with the
     * directory to delete.
     *
     * @throws IllegalStateException if {@code SNAPSHOT/LANE_L1} is absent for this boot, records a
     *                               different hash (re-bootstrap from another snapshot) or records
     *                               none at all, or if the local state has drifted since the import
     */
    private static void verifyMarker(Config config, Bytes32 marker, LaneL1Store store) {
        Path dir = snapshotDir(config);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("LANE_L1 was imported from a snapshot with hash "
                    + marker.toHexString() + " but SNAPSHOT/LANE_L1 is missing for this boot (" + dir
                    + "), which re-seeds the block store; place the same snapshot beside SNAPSHOT/BLOCKS "
                    + "or remove the local LANE_L1 directory (" + laneDir(config) + ") and restart");
        }
        Optional<Bytes32> recorded = recordedSnapshotHash(config);
        if (recorded.isEmpty()) {
            throw new IllegalStateException("LANE_L1 was imported from a snapshot with hash "
                    + marker.toHexString() + " but the snapshot at " + dir + " records no state hash; "
                    + "place the same snapshot beside SNAPSHOT/BLOCKS or remove the local LANE_L1 directory ("
                    + laneDir(config) + ") and restart");
        }
        if (!recorded.get().equals(marker)) {
            throw new IllegalStateException("LANE_L1 was imported from a snapshot with hash "
                    + marker.toHexString() + " but this boot seeds from a snapshot with hash "
                    + recorded.get().toHexString() + "; remove the local LANE_L1 directory ("
                    + laneDir(config) + ") and restart");
        }
        Bytes32 local = store.stateHash();
        if (!local.equals(marker)) {
            throw new IllegalStateException("LANE_L1 has moved past the snapshot it was imported from (hash "
                    + local.toHexString() + " vs imported " + marker.toHexString() + "); this boot re-seeds "
                    + "the block store, so remove the local LANE_L1 directory (" + laneDir(config)
                    + ") and restart");
        }
        report("LANE_L1 already matches the snapshot at " + dir + " (state hash " + marker.toHexString()
                + "); nothing to import");
    }

    /**
     * The state hash recorded under {@link LaneL1Keys#SNAPSHOT_HASH_KEY} in {@code SNAPSHOT/LANE_L1},
     * or empty if that snapshot records none (or a value that is not a 32-byte hash). Only called
     * once the directory is known to exist, so opening it cannot create one.
     */
    private static Optional<Bytes32> recordedSnapshotHash(Config config) {
        RocksdbKVSource source = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        source.setConfig(config);
        try {
            source.init();
            byte[] v = source.get(LaneL1Keys.SNAPSHOT_HASH_KEY);
            return v == null || v.length != Bytes32.SIZE ? Optional.empty() : Optional.of(Bytes32.wrap(v));
        } finally {
            source.close();
        }
    }

    /** Snapshot bootstrap reports on stdout (like the rest of it) as well as to the log. */
    private static void report(String message) {
        System.out.println(message);
        log.info(message);
    }
}
