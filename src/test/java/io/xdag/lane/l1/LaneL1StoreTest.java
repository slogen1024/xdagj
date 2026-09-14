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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.lane.InMemoryKVSource;
import io.xdag.lane.ext.ExtCodec;
import io.xdag.lane.ext.ExtKind;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LaneL1StoreTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static final Bytes LANE = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final Bytes32 BLOCK = Bytes32.random();

    private static LaneL1Store memStore() {
        LaneL1Store s = new LaneL1Store(new InMemoryKVSource());
        s.start();
        return s;
    }

    @Test
    public void startWritesSchemaVersionOnce() {
        InMemoryKVSource src = new InMemoryKVSource();
        LaneL1Store s = new LaneL1Store(src);
        s.start();
        assertEquals(1, src.keys().size());
        assertEquals(LaneL1Store.SCHEMA_VERSION, s.schemaVersion());
        s.start();
        assertEquals(1, src.keys().size());
    }

    @Test
    public void recordsRoundTrip() {
        LaneRecord lane = new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 2L);
        assertEquals(lane, LaneRecord.decode(lane.encode()));
        assertEquals(3L, lane.withContractCount(3L).contractCount());
        ContractRecord contract = new ContractRecord(LANE, CODE_HASH, 7L, BLOCK);
        assertEquals(contract, ContractRecord.decode(contract.encode()));
        InputRecord in = new InputRecord(BLOCK, ExtKind.CALL, InputStatus.INVALID_FEE, CONTRACT);
        assertEquals(in, InputRecord.decode(in.encode()));
        InputRecord none = new InputRecord(BLOCK, null, InputStatus.INVALID_FORMAT, Bytes.wrap(new byte[20]));
        assertEquals(none, InputRecord.decode(none.encode()));
        List<InputRef> refs = List.of(new InputRef(LANE, 7L, 0L), new InputRef(LANE, 7L, 1L));
        assertEquals(refs, InputRef.decodeList(InputRef.encodeList(refs)));
        assertEquals(InputStatus.CODE_TOO_LARGE, InputStatus.fromCode(3));
    }

    @Test
    public void batchCommitWritesEverythingInOneCall() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryKVSource src = new InMemoryKVSource() {
            @Override
            public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                calls.incrementAndGet();
                super.batchWrite(puts, deletes);
            }
        };
        LaneL1Store s = new LaneL1Store(src);
        s.start();

        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putContract(CONTRACT, new ContractRecord(LANE, CODE_HASH, 7L, BLOCK));
        batch.putCode(CODE_HASH, 1L, Bytes.of((byte) 1, (byte) 2, (byte) 3));
        batch.putCallCount(LANE, 7L, 1L);
        batch.putInput(LANE, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
        batch.putReverse(BLOCK, List.of(new InputRef(LANE, 7L, 0L)));
        s.commit(batch);

        assertEquals(1, calls.get());
        assertTrue(s.hasLane(LANE));
        assertEquals(1L, s.getLane(LANE).contractCount());
        assertEquals(LANE, s.getContract(CONTRACT).laneId());
        assertTrue(s.hasCode(CODE_HASH));
        assertEquals(1L, s.getCodeRefCount(CODE_HASH));
        assertEquals(Bytes.of((byte) 1, (byte) 2, (byte) 3), s.getCode(CODE_HASH));
        assertEquals(1L, s.getCallCount(LANE, 7L));
        assertEquals(InputStatus.OK, s.getInput(LANE, 7L, 0L).status());
        assertEquals(1, s.getReverse(BLOCK).size());

        LaneL1Batch undo = new LaneL1Batch();
        undo.deleteLane(LANE);
        undo.deleteContract(CONTRACT);
        undo.deleteCode(CODE_HASH);
        undo.deleteCallCount(LANE, 7L);
        undo.deleteInput(LANE, 7L, 0L);
        undo.deleteReverse(BLOCK);
        s.commit(undo);
        assertEquals(2, calls.get());
        assertFalse(s.hasLane(LANE));
        assertNull(s.getContract(CONTRACT));
        assertFalse(s.hasCode(CODE_HASH));
        assertEquals(0L, s.getCodeRefCount(CODE_HASH));
        assertEquals(0L, s.getCallCount(LANE, 7L));
        assertNull(s.getInput(LANE, 7L, 0L));
        assertTrue(s.getReverse(BLOCK).isEmpty());
        assertEquals(1, src.keys().size()); // only META remains

        s.commit(new LaneL1Batch());
        assertEquals(2, calls.get()); // empty batch does not touch the store
    }

    @Test
    public void snapshotExportImportAndHash() {
        LaneL1Store a = memStore();
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putCode(CODE_HASH, 2L, Bytes.random(1000));
        a.commit(batch);
        Bytes32 hashA = a.stateHash();

        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);
        assertEquals(hashA, Bytes32.wrap(snap.get(new byte[]{LaneL1Keys.SNAPSHOT_HASH})));

        LaneL1Store b = memStore();
        b.importSnapshot(snap);
        assertEquals(hashA, b.stateHash());
        assertTrue(b.hasLane(LANE));
        assertEquals(2L, b.getCodeRefCount(CODE_HASH));

        // tampering is detected
        snap.put(LaneL1Keys.code(CODE_HASH), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        LaneL1Store c = memStore();
        assertThrows(IllegalStateException.class, () -> c.importSnapshot(snap));

        // a snapshot without a hash is refused
        InMemoryKVSource noHash = new InMemoryKVSource();
        a.exportSnapshot(noHash);
        noHash.delete(new byte[]{LaneL1Keys.SNAPSHOT_HASH});
        assertThrows(IllegalStateException.class, () -> memStore().importSnapshot(noHash));

        // hash depends on content
        LaneL1Batch more = new LaneL1Batch();
        more.putCallCount(LANE, 8L, 1L);
        a.commit(more);
        assertNotEquals(hashA, a.stateHash());
    }

    @Test
    public void rocksAndMemoryProduceTheSameHashForTheSameContent() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource rocks = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        rocks.setConfig(config);
        LaneL1Store onRocks = new LaneL1Store(rocks);
        onRocks.start();
        LaneL1Store inMem = memStore();
        try {
            for (LaneL1Store s : List.of(onRocks, inMem)) {
                LaneL1Batch batch = new LaneL1Batch();
                batch.putLane(LANE, new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
                batch.putContract(CONTRACT, new ContractRecord(LANE, CODE_HASH, 7L, BLOCK));
                batch.putInput(LANE, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
                s.commit(batch);
            }
            assertEquals(inMem.stateHash(), onRocks.stateHash());
            assertEquals(4, onRocks.sortedKeys().size());
        } finally {
            onRocks.stop();
        }
    }

    @Test
    public void stateHashFollowsTheDocumentedDefinition() throws Exception {
        LaneL1Store s = memStore();
        LaneRecord lane = new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L);
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, lane);
        s.commit(batch);

        byte[] metaKey = LaneL1Keys.META_KEY;
        byte[] metaValue = new byte[4];
        ExtCodec.putU32(metaValue, 0, LaneL1Store.SCHEMA_VERSION);
        byte[] laneKey = LaneL1Keys.lane(LANE);
        byte[] laneValue = lane.encode();

        // META (prefix 0x00) always sorts before LANE (prefix 0x01) in unsigned lexicographic order.
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Pair<byte[], byte[]> e : List.of(Pair.of(metaKey, metaValue), Pair.of(laneKey, laneValue))) {
            byte[] len = new byte[4];
            ExtCodec.putU32(len, 0, e.getKey().length);
            md.update(len);
            md.update(e.getKey());
            ExtCodec.putU32(len, 0, e.getValue().length);
            md.update(len);
            md.update(e.getValue());
        }
        Bytes32 expected = Bytes32.wrap(md.digest());
        assertEquals(expected, s.stateHash());
    }

    @Test
    public void importRefusesNonEmptyStore() {
        LaneL1Store nonEmpty = memStore();
        LaneL1Batch batch = new LaneL1Batch();
        batch.putLane(LANE, new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        nonEmpty.commit(batch);

        LaneL1Store other = memStore();
        InMemoryKVSource snap = new InMemoryKVSource();
        other.exportSnapshot(snap);

        assertThrows(IllegalStateException.class, () -> nonEmpty.importSnapshot(snap));
    }

    @Test
    public void recordsRejectMalformedComponents() {
        Bytes shortLane = Bytes.wrap(new byte[19]);
        assertThrows(IllegalArgumentException.class, () -> new ContractRecord(shortLane, CODE_HASH, 7L, BLOCK));
        assertThrows(IllegalArgumentException.class, () -> new InputRef(shortLane, 7L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new LaneRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L << 32));
        assertThrows(NullPointerException.class, () -> new InputRecord(null, ExtKind.CALL, InputStatus.OK, CONTRACT));
    }

    @Test
    public void corruptCodeRecordIsReported() {
        InMemoryKVSource src = new InMemoryKVSource();
        LaneL1Store s = new LaneL1Store(src);
        s.start();
        byte[] v = new byte[8 + 4];
        ExtCodec.putU32(v, 0, 1);
        ExtCodec.putU32(v, 4, 1000); // declares 1000 bytes but only 4 are actually stored
        src.put(LaneL1Keys.code(CODE_HASH), v);

        assertEquals(1L, s.getCodeRefCount(CODE_HASH));
        assertThrows(IllegalStateException.class, () -> s.getCode(CODE_HASH));
    }
}
