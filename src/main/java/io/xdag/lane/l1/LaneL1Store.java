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

import com.google.common.primitives.UnsignedBytes;
import io.xdag.core.XdagLifecycle;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.lane.ext.ExtCodec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Global (every node) lane state: registry, contracts, code store, input index. Backed by a single
 * {@link KVSource} (RocksDB in production, an in-memory implementation in tests) keyed per
 * {@link LaneL1Keys}.
 *
 * <p><b>Writes.</b> All writes go through a {@link LaneL1Batch} built by the caller and applied by
 * {@link #commit(LaneL1Batch)} as a single atomic {@link KVSource#batchWrite}; there is no other
 * write path. An empty batch does not touch the store (no-op, no underlying call).
 *
 * <p><b>Reads.</b> Every read ({@link #getLane}, {@link #getContract}, {@link #getCode} and
 * friends) is a point lookup by exact key; there is no range scan or iteration in the read API
 * (snapshot export is the only place that walks every key, see below). {@link #getCallCount} and
 * {@link #getCodeRefCount} return {@code 0} for a key that was never written (or was deleted); a
 * caller wanting to represent "no calls" or "no references" again must issue a delete, not a put
 * of an explicit zero, since the two are otherwise indistinguishable to these readers.
 *
 * <p><b>State hash.</b> {@link #stateHash()} is defined as the SHA-256 digest of every key/value
 * pair in the store (the {@code META} entry included, the reserved
 * {@link LaneL1Keys#SNAPSHOT_HASH} entry excluded), visited in ascending unsigned lexicographic
 * byte order of the key, each pair serialized as {@code u32(len(key)) || key || u32(len(value)) ||
 * value} (little-endian u32, per {@link ExtCodec#putU32}) and fed to the digest in that order. This
 * is a pure function of content: two stores with the same key/value pairs — RocksDB or in-memory —
 * always produce the same hash, and it changes whenever any pair is added, removed or modified.
 * {@link #exportSnapshot} and {@link #importSnapshot} use this hash to detect corruption or
 * mismatch between a snapshot and the state it claims to represent.
 *
 * <p><b>Snapshot cost.</b> {@link #sortedKeys()}, {@link #stateHash()}, {@link #exportSnapshot} and
 * {@link #importSnapshot} are O(N) in both time and heap, where N is the number of keys in the
 * store: each materializes every key (and, for the hash and the snapshot copy, every value) into
 * memory at once. These are snapshot-boundary operations only — never invoked per block.
 *
 * <p><b>Concurrency.</b> Not thread-safe beyond the consensus lock: callers (the SP0a hooks in
 * {@code BlockchainImpl}) must serialize all access the same way they serialize block application.
 */
public final class LaneL1Store implements XdagLifecycle {

    public static final int SCHEMA_VERSION = 1;

    private final KVSource<byte[], byte[]> source;
    private volatile boolean running;

    public LaneL1Store(KVSource<byte[], byte[]> source) {
        this.source = source;
    }

    // ---- lifecycle ----

    @Override
    public void start() {
        source.init();
        ensureMeta();
        running = true;
    }

    @Override
    public void stop() {
        source.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Wipes the underlying source and re-writes the schema version; for tests only. */
    public void reset() {
        source.reset();
        ensureMeta();
    }

    // ---- schema metadata ----

    private void ensureMeta() {
        if (source.get(LaneL1Keys.META_KEY) == null) {
            byte[] v = new byte[4];
            ExtCodec.putU32(v, 0, SCHEMA_VERSION);
            source.put(LaneL1Keys.META_KEY, v);
        }
    }

    public int schemaVersion() {
        byte[] v = source.get(LaneL1Keys.META_KEY);
        return v == null ? 0 : (int) ExtCodec.u32(v, 0);
    }

    // ---- lane registry reads ----

    public boolean hasLane(Bytes laneId) {
        return source.get(LaneL1Keys.lane(laneId)) != null;
    }

    public LaneRecord getLane(Bytes laneId) {
        byte[] v = source.get(LaneL1Keys.lane(laneId));
        return v == null ? null : LaneRecord.decode(v);
    }

    // ---- contract registry reads ----

    public ContractRecord getContract(Bytes contract) {
        byte[] v = source.get(LaneL1Keys.contract(contract));
        return v == null ? null : ContractRecord.decode(v);
    }

    // ---- code store reads ----

    /** Cheap presence check: looks at the (4-byte) reference count, never the blob. */
    public boolean hasCode(Bytes32 codeHash) {
        return source.get(LaneL1Keys.codeRef(codeHash)) != null;
    }

    /**
     * Returns the code's reference count, or 0 if it was never written (or was deleted).
     *
     * @throws IllegalStateException if the stored value is not exactly 4 bytes (a corrupt record)
     */
    public long getCodeRefCount(Bytes32 codeHash) {
        byte[] v = source.get(LaneL1Keys.codeRef(codeHash));
        if (v == null) {
            return 0;
        }
        if (v.length != 4) {
            throw new IllegalStateException("corrupt code ref record");
        }
        return ExtCodec.u32(v, 0);
    }

    /** Returns the stored raw code bytes, or null if there is no blob under this hash. */
    public Bytes getCode(Bytes32 codeHash) {
        byte[] v = source.get(LaneL1Keys.code(codeHash));
        return v == null ? null : Bytes.wrap(v);
    }

    // ---- call count / input index reads ----

    /** Returns the call count for (laneId, height), or 0 if it was never written (or was deleted). */
    public long getCallCount(Bytes laneId, long height) {
        byte[] v = source.get(LaneL1Keys.callCount(laneId, height));
        return v == null ? 0 : ExtCodec.u32(v, 0);
    }

    public InputRecord getInput(Bytes laneId, long height, long index) {
        byte[] v = source.get(LaneL1Keys.input(laneId, height, index));
        return v == null ? null : InputRecord.decode(v);
    }

    // ---- reverse index reads ----

    public List<InputRef> getReverse(Bytes32 blockHash) {
        byte[] v = source.get(LaneL1Keys.reverse(blockHash));
        return v == null ? List.of() : InputRef.decodeList(v);
    }

    // ---- batched writes ----

    /** Applies every put and delete in {@code batch} as one atomic write; a no-op for an empty batch. */
    public void commit(LaneL1Batch batch) {
        if (!batch.isEmpty()) {
            source.batchWrite(batch.puts(), batch.deletes());
        }
    }

    // ---- snapshot support ----

    /** All keys in unsigned lexicographic byte order (RocksDB's own key order). O(N); snapshot-only. */
    public List<byte[]> sortedKeys() {
        return sortedKeysOf(source);
    }

    private static List<byte[]> sortedKeysOf(KVSource<byte[], byte[]> src) {
        List<byte[]> keys = new ArrayList<>(src.keys());
        keys.sort(UnsignedBytes.lexicographicalComparator());
        return keys;
    }

    private static boolean isSnapshotHashKey(byte[] key) {
        return key.length == 1 && key[0] == LaneL1Keys.SNAPSHOT_HASH;
    }

    private static boolean isMetaKey(byte[] key) {
        return key.length == 1 && key[0] == LaneL1Keys.META;
    }

    /** See the class-level "State hash" section for the exact definition. O(N); snapshot-only. */
    public Bytes32 stateHash() {
        return stateHashOf(source);
    }

    /** sha256 over sorted (len(key) u32 | key | len(value) u32 | value), skipping the snapshot hash key itself. */
    public static Bytes32 stateHashOf(KVSource<byte[], byte[]> src) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] len = new byte[4];
        for (byte[] k : sortedKeysOf(src)) {
            if (isSnapshotHashKey(k)) {
                continue;
            }
            byte[] v = src.get(k);
            ExtCodec.putU32(len, 0, k.length);
            md.update(len);
            md.update(k);
            ExtCodec.putU32(len, 0, v.length);
            md.update(len);
            md.update(v);
        }
        return Bytes32.wrap(md.digest());
    }

    /**
     * Copies every key (this store's own state hash included) into {@code target}. O(N);
     * snapshot-only.
     *
     * @throws IllegalStateException if {@code target} already holds any key
     */
    public void exportSnapshot(KVSource<byte[], byte[]> target) {
        if (!sortedKeysOf(target).isEmpty()) {
            throw new IllegalStateException("snapshot target must be empty");
        }
        for (byte[] k : sortedKeys()) {
            target.put(k, source.get(k));
        }
        target.put(LaneL1Keys.SNAPSHOT_HASH_KEY, stateHash().toArray());
    }

    /**
     * Copies every key from the snapshot and verifies the recorded state hash. O(N); snapshot-only.
     *
     * @throws IllegalStateException if the snapshot carries no recorded hash, if this store is not
     *                                empty (any key other than {@code META}), if the snapshot's
     *                                schema version does not match {@link #SCHEMA_VERSION}, or if
     *                                the hash of the copied content does not match the recorded one
     */
    public void importSnapshot(KVSource<byte[], byte[]> from) {
        byte[] expected = from.get(LaneL1Keys.SNAPSHOT_HASH_KEY);
        if (expected == null) {
            throw new IllegalStateException("LANE_L1 snapshot has no state hash");
        }
        for (byte[] k : sortedKeys()) {
            if (!isMetaKey(k)) {
                throw new IllegalStateException("LANE_L1 must be empty before import");
            }
        }
        byte[] metaValue = from.get(LaneL1Keys.META_KEY);
        int snapshotSchema = metaValue == null ? -1 : (int) ExtCodec.u32(metaValue, 0);
        if (snapshotSchema != SCHEMA_VERSION) {
            throw new IllegalStateException("LANE_L1 snapshot schema " + snapshotSchema + " != " + SCHEMA_VERSION);
        }
        for (byte[] k : sortedKeysOf(from)) {
            if (isSnapshotHashKey(k)) {
                continue;
            }
            source.put(k, from.get(k));
        }
        Bytes32 actual = stateHash();
        if (!actual.equals(Bytes32.wrap(expected))) {
            throw new IllegalStateException("LANE_L1 snapshot hash mismatch: expected " + Bytes32.wrap(expected)
                    + " actual " + actual);
        }
    }
}
