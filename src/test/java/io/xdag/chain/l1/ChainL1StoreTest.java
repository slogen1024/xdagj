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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.ext.ExtKind;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChainL1StoreTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final Bytes32 BLOCK = Bytes32.random();

    private static ChainL1Store memStore() {
        ChainL1Store s = new ChainL1Store(new InMemoryKVSource());
        s.start();
        return s;
    }

    @Test
    public void startWritesSchemaVersionOnce() {
        InMemoryKVSource src = new InMemoryKVSource();
        ChainL1Store s = new ChainL1Store(src);
        s.start();
        assertEquals(1, src.keys().size());
        assertEquals(ChainL1Store.SCHEMA_VERSION, s.schemaVersion());
        s.start();
        assertEquals(1, src.keys().size());
    }

    @Test
    public void recordsRoundTrip() {
        ChainRecord chain = new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 2L);
        assertEquals(chain, ChainRecord.decode(chain.encode()));
        assertEquals(3L, chain.withContractCount(3L).contractCount());
        ContractRecord contract = new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK);
        assertEquals(contract, ContractRecord.decode(contract.encode()));
        InputRecord in = new InputRecord(BLOCK, ExtKind.CALL, InputStatus.INVALID_FEE, CONTRACT);
        assertEquals(in, InputRecord.decode(in.encode()));
        InputRecord none = new InputRecord(BLOCK, null, InputStatus.INVALID_FORMAT, Bytes.wrap(new byte[20]));
        assertEquals(none, InputRecord.decode(none.encode()));
        List<InputRef> refs = List.of(new InputRef(CHAIN, 7L, 0L), new InputRef(CHAIN, 7L, 1L));
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
        ChainL1Store s = new ChainL1Store(src);
        s.start();

        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putContract(CONTRACT, new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK));
        batch.putCode(CODE_HASH, 1L, Bytes.of((byte) 1, (byte) 2, (byte) 3));
        batch.putCallCount(CHAIN, 7L, 1L);
        batch.putInput(CHAIN, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
        batch.putReverse(BLOCK, List.of(new InputRef(CHAIN, 7L, 0L)));
        s.commit(batch);

        assertEquals(1, calls.get());
        assertTrue(s.hasChain(CHAIN));
        assertEquals(1L, s.getChain(CHAIN).contractCount());
        assertEquals(CHAIN, s.getContract(CONTRACT).chainId());
        assertTrue(s.hasCode(CODE_HASH));
        assertEquals(1L, s.getCodeRefCount(CODE_HASH));
        assertEquals(Bytes.of((byte) 1, (byte) 2, (byte) 3), s.getCode(CODE_HASH));
        assertEquals(1L, s.getCallCount(CHAIN, 7L));
        assertEquals(InputStatus.OK, s.getInput(CHAIN, 7L, 0L).status());
        assertEquals(1, s.getReverse(BLOCK).size());

        ChainL1Batch undo = new ChainL1Batch();
        undo.deleteChain(CHAIN);
        undo.deleteContract(CONTRACT);
        undo.deleteCode(CODE_HASH);
        undo.deleteCallCount(CHAIN, 7L);
        undo.deleteInput(CHAIN, 7L, 0L);
        undo.deleteReverse(BLOCK);
        s.commit(undo);
        assertEquals(2, calls.get());
        assertFalse(s.hasChain(CHAIN));
        assertNull(s.getContract(CONTRACT));
        assertFalse(s.hasCode(CODE_HASH));
        assertEquals(0L, s.getCodeRefCount(CODE_HASH));
        assertEquals(0L, s.getCallCount(CHAIN, 7L));
        assertNull(s.getInput(CHAIN, 7L, 0L));
        assertTrue(s.getReverse(BLOCK).isEmpty());
        assertEquals(1, src.keys().size()); // only META remains

        s.commit(new ChainL1Batch());
        assertEquals(2, calls.get()); // empty batch does not touch the store
    }

    @Test
    public void snapshotExportImportAndHash() {
        ChainL1Store a = memStore();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putCode(CODE_HASH, 2L, Bytes.random(1000));
        a.commit(batch);
        Bytes32 hashA = a.stateHash();

        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);
        assertEquals(hashA, Bytes32.wrap(snap.get(new byte[]{ChainL1Keys.SNAPSHOT_HASH})));

        ChainL1Store b = memStore();
        b.importSnapshot(snap);
        assertEquals(hashA, b.stateHash());
        assertTrue(b.hasChain(CHAIN));
        assertEquals(2L, b.getCodeRefCount(CODE_HASH));
        // the import records the snapshot's hash as a durable marker, outside the state hash
        assertEquals(Optional.of(hashA), b.importedSnapshotHash());
        assertTrue(b.hasState());

        // tampering is detected
        snap.put(ChainL1Keys.code(CODE_HASH), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        ChainL1Store c = memStore();
        assertThrows(IllegalStateException.class, () -> c.importSnapshot(snap));

        // a snapshot without a hash is refused
        InMemoryKVSource noHash = new InMemoryKVSource();
        a.exportSnapshot(noHash);
        noHash.delete(new byte[]{ChainL1Keys.SNAPSHOT_HASH});
        assertThrows(IllegalStateException.class, () -> memStore().importSnapshot(noHash));

        // hash depends on content
        ChainL1Batch more = new ChainL1Batch();
        more.putCallCount(CHAIN, 8L, 1L);
        a.commit(more);
        assertNotEquals(hashA, a.stateHash());
    }

    @Test
    public void snapshotWithATruncatedStateHashIsRefused() {
        ChainL1Store a = memStore();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        a.commit(batch);
        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);

        // A value that is not a 32-byte hash must be rejected the same way a missing one is - as an
        // IllegalStateException naming CHAIN_L1 - not as an IllegalArgumentException out of Bytes32.wrap.
        snap.put(new byte[]{ChainL1Keys.SNAPSHOT_HASH}, new byte[]{-1, -1, -1, -1, -1});
        ChainL1Store b = memStore();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.importSnapshot(snap));
        assertTrue(e.getMessage(), e.getMessage().contains("no valid state hash"));
        assertFalse("a refused import must leave the store untouched", b.hasState());
    }

    @Test
    public void rocksAndMemoryProduceTheSameHashForTheSameContent() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource rocks = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        rocks.setConfig(config);
        ChainL1Store onRocks = new ChainL1Store(rocks);
        onRocks.start();
        ChainL1Store inMem = memStore();
        try {
            for (ChainL1Store s : List.of(onRocks, inMem)) {
                ChainL1Batch batch = new ChainL1Batch();
                batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
                batch.putContract(CONTRACT, new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK));
                batch.putInput(CHAIN, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
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
        ChainL1Store s = memStore();
        ChainRecord chain = new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, chain);
        s.commit(batch);

        byte[] metaKey = ChainL1Keys.META_KEY;
        byte[] metaValue = new byte[4];
        ExtCodec.putU32(metaValue, 0, ChainL1Store.SCHEMA_VERSION);
        byte[] chainKey = ChainL1Keys.chain(CHAIN);
        byte[] chainValue = chain.encode();

        // META (prefix 0x00) always sorts before CHAIN (prefix 0x01) in unsigned lexicographic order.
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Pair<byte[], byte[]> e : List.of(Pair.of(metaKey, metaValue), Pair.of(chainKey, chainValue))) {
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
        ChainL1Store nonEmpty = memStore();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        nonEmpty.commit(batch);

        ChainL1Store other = memStore();
        InMemoryKVSource snap = new InMemoryKVSource();
        other.exportSnapshot(snap);

        assertThrows(IllegalStateException.class, () -> nonEmpty.importSnapshot(snap));
    }

    @Test
    public void recordsRejectMalformedComponents() {
        Bytes shortChain = Bytes.wrap(new byte[19]);
        assertThrows(IllegalArgumentException.class, () -> new ContractRecord(shortChain, CODE_HASH, 7L, BLOCK));
        assertThrows(IllegalArgumentException.class, () -> new InputRef(shortChain, 7L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L << 32));
        assertThrows(NullPointerException.class, () -> new InputRecord(null, ExtKind.CALL, InputStatus.OK, CONTRACT));
    }

    @Test
    public void corruptCodeRecordIsReported() {
        InMemoryKVSource src = new InMemoryKVSource();
        ChainL1Store s = new ChainL1Store(src);
        s.start();
        src.put(ChainL1Keys.codeRef(CODE_HASH), new byte[]{1, 2, 3}); // must be exactly 4 bytes
        src.put(ChainL1Keys.code(CODE_HASH), new byte[]{9, 9, 9}); // blob key is independent of the ref

        assertThrows(IllegalStateException.class, () -> s.getCodeRefCount(CODE_HASH));
        assertEquals(Bytes.of((byte) 9, (byte) 9, (byte) 9), s.getCode(CODE_HASH));
    }

    @Test
    public void lastWritePerKeyWinsInABatch() {
        ChainL1Store s = memStore();

        ChainL1Batch deleteThenPut = new ChainL1Batch();
        deleteThenPut.deleteCallCount(CHAIN, 7L);
        deleteThenPut.putCallCount(CHAIN, 7L, 9L);
        s.commit(deleteThenPut);
        assertEquals(9L, s.getCallCount(CHAIN, 7L));

        ChainL1Batch putThenDelete = new ChainL1Batch();
        putThenDelete.putCallCount(CHAIN, 7L, 9L);
        putThenDelete.deleteCallCount(CHAIN, 7L);
        s.commit(putThenDelete);
        assertEquals(0L, s.getCallCount(CHAIN, 7L));

        ChainL1Batch codeThenRef = new ChainL1Batch();
        codeThenRef.putCode(CODE_HASH, 1L, Bytes.of((byte) 1, (byte) 2, (byte) 3));
        codeThenRef.putCodeRef(CODE_HASH, 2L);
        s.commit(codeThenRef);
        assertEquals(2L, s.getCodeRefCount(CODE_HASH));
        assertEquals(Bytes.of((byte) 1, (byte) 2, (byte) 3), s.getCode(CODE_HASH));

        ChainL1Batch collapsed = new ChainL1Batch();
        collapsed.putCallCount(CHAIN, 8L, 1L);
        collapsed.putCallCount(CHAIN, 8L, 2L);
        collapsed.deleteInput(CHAIN, 8L, 0L);
        assertEquals(1, collapsed.puts().size());
        assertEquals(1, collapsed.deletes().size());
    }

    @Test
    public void snapshotWithWrongSchemaIsRefused() {
        ChainL1Store a = memStore();
        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);

        byte[] wrongMeta = new byte[4];
        ExtCodec.putU32(wrongMeta, 0, 99);
        snap.put(ChainL1Keys.META_KEY, wrongMeta);
        snap.put(ChainL1Keys.SNAPSHOT_HASH_KEY, ChainL1Store.stateHashOf(snap).toArray());

        ChainL1Store b = memStore();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.importSnapshot(snap));
        assertTrue(e.getMessage().contains("schema"));
    }

    @Test
    public void resetLeavesOnlyMeta() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource rocks = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        rocks.setConfig(config);
        ChainL1Store s = new ChainL1Store(rocks);
        s.start();
        try {
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
            s.commit(batch);

            s.reset();

            assertEquals(1, s.sortedKeys().size());
            assertEquals(ChainL1Store.SCHEMA_VERSION, s.schemaVersion());
            assertFalse(s.hasChain(CHAIN));
        } finally {
            s.stop();
        }
    }

    @Test
    public void emptyStoreSnapshotRoundTrip() {
        ChainL1Store a = memStore();
        assertEquals(Optional.empty(), a.importedSnapshotHash());
        assertFalse(a.hasState());

        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);

        ChainL1Store b = memStore();
        b.importSnapshot(snap);

        assertEquals(a.stateHash(), b.stateHash());
        assertEquals(2, b.sortedKeys().size()); // META plus the import marker
        assertEquals(Optional.of(a.stateHash()), b.importedSnapshotHash());
        assertFalse(b.hasState()); // the marker is not state
    }

    @Test
    public void absentCodeIsNull() {
        ChainL1Store s = memStore();
        Bytes32 randomHash = Bytes32.random();
        assertNull(s.getCode(randomHash));
        assertEquals(0L, s.getCodeRefCount(randomHash));
        assertFalse(s.hasCode(randomHash));

        InMemoryKVSource nonEmptyTarget = new InMemoryKVSource();
        nonEmptyTarget.put(new byte[]{0x7F}, new byte[]{1});
        assertThrows(IllegalStateException.class, () -> s.exportSnapshot(nonEmptyTarget));
    }

    @Test
    public void truncatedReverseIndexIsReported() {
        InMemoryKVSource src = new InMemoryKVSource();
        ChainL1Store s = new ChainL1Store(src);
        s.start();

        src.put(ChainL1Keys.reverse(BLOCK), new byte[61]); // not a multiple of InputRef.SIZE (32)
        assertThrows(IllegalStateException.class, () -> s.getReverse(BLOCK));

        src.put(ChainL1Keys.chain(CHAIN), new byte[59]); // ChainRecord.SIZE is 60
        assertThrows(IllegalStateException.class, () -> s.getChain(CHAIN));
    }
}
