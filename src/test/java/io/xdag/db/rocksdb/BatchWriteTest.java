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

package io.xdag.db.rocksdb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.lane.InMemoryKVSource;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BatchWriteTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    @Test
    public void defaultBatchWriteAppliesPutsThenDeletes() {
        InMemoryKVSource src = new InMemoryKVSource();
        src.init();
        src.put(b(9), b(9));
        src.batchWrite(List.of(Pair.of(b(1), b(11)), Pair.of(b(2), b(22)), Pair.of(b(3), null)), List.of(b(9), b(2)));
        assertArrayEquals(b(11), src.get(b(1)));
        assertNull(src.get(b(2)));
        assertNull(src.get(b(3)));
        assertNull(src.get(b(9)));
    }

    @Test
    public void rocksBatchWriteUsesWriteBatch() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        src.setConfig(config);
        src.init();
        try {
            src.put(b(9), b(9));
            src.batchWrite(List.of(Pair.of(b(1), b(11)), Pair.of(b(2), b(22)), Pair.of(b(3), null)), List.of(b(9)));
            assertArrayEquals(b(11), src.get(b(1)));
            assertArrayEquals(b(22), src.get(b(2)));
            assertNull(src.get(b(3)));
            assertNull(src.get(b(9)));
            assertEquals(2, src.keys().size());
        } finally {
            src.close();
        }
    }

    @Test
    public void rocksBatchWriteAppliesPutsThenDeletesInOrder() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        src.setConfig(config);
        src.init();
        try {
            byte[] v1 = b(1);
            byte[] v2 = b(2);

            src.put(b(5), b(5));

            // put then delete on the same key within one batch: delete wins (applied after puts).
            src.batchWrite(List.of(Pair.of(b(5), v1)), List.of(b(5)));
            assertNull(src.get(b(5)));

            // two puts on the same key within one batch, no delete: last put wins (list order).
            src.batchWrite(List.of(Pair.of(b(5), v1), Pair.of(b(5), v2)), List.of());
            assertArrayEquals(v2, src.get(b(5)));

            // an empty batch is a no-op.
            src.batchWrite(List.of(), List.of());
            assertArrayEquals(v2, src.get(b(5)));

            // a deletes-only batch removes the key.
            src.batchWrite(List.of(), List.of(b(5)));
            assertNull(src.get(b(5)));
        } finally {
            src.close();
        }
    }

    @Test
    public void factoryKnowsLaneL1() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbFactory factory = new RocksdbFactory(config);
        KVSource<byte[], byte[]> db = factory.getDB(DatabaseName.LANE_L1);
        assertNotNull(db);
        assertEquals("LANE_L1", db.getName());
        factory.close();
    }

    private static byte[] keyOf(int marker, int totalLen) {
        byte[] k = new byte[totalLen];
        k[0] = (byte) marker;
        return k;
    }

    /**
     * Tuweni {@code Bytes.compareTo} ranks by bit length first and aliases all-zero keys of
     * different lengths, so a naive {@code TreeMap<Bytes, byte[]>} test double does not model
     * RocksDB's plain bytewise comparator. This keeps {@link InMemoryKVSource} and a real
     * {@link RocksdbKVSource} in lock-step over a set of keys chosen to expose exactly that gap:
     * a 1-byte all-zero key vs. a 2-byte all-zero key, and same-length keys distinguished only by
     * their leading byte.
     */
    @Test
    public void memoryAndRocksAgreeOnOrderAndDistinctness() throws Exception {
        List<byte[]> keys = List.of(
                keyOf(0x00, 1),
                keyOf(0x01, 21),
                keyOf(0x02, 21),
                keyOf(0x03, 33),
                keyOf(0x07, 29),
                keyOf(0x0c, 33),
                keyOf(0x0e, 33),
                keyOf(0x00, 2));

        InMemoryKVSource memSrc = new InMemoryKVSource();
        memSrc.init();

        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource rocksSrc = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        rocksSrc.setConfig(config);
        rocksSrc.init();
        try {
            for (int i = 0; i < keys.size(); i++) {
                byte[] key = keys.get(i);
                byte[] value = b(i);
                memSrc.put(key, value);
                rocksSrc.put(key, value);
            }

            assertEquals(8, memSrc.keys().size());
            assertEquals(8, rocksSrc.keys().size());

            for (byte[] key : keys) {
                assertArrayEquals(rocksSrc.get(key), memSrc.get(key));
            }

            List<Pair<byte[], byte[]>> memList = memSrc.prefixKeyAndValueLookup(new byte[0]);
            List<Pair<byte[], byte[]>> rocksList = rocksSrc.prefixKeyAndValueLookup(new byte[0]);
            assertEquals(hexKeys(rocksList), hexKeys(memList));
        } finally {
            rocksSrc.close();
        }
    }

    private static List<String> hexKeys(List<Pair<byte[], byte[]>> entries) {
        List<String> out = new ArrayList<>();
        for (Pair<byte[], byte[]> e : entries) {
            out.add(Hex.encodeHexString(e.getKey()));
        }
        return out;
    }

    @Test
    public void batchWriteAfterCloseThrows() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.LANE_L1.toString());
        src.setConfig(config);
        src.init();
        src.close();

        assertThrows(IllegalStateException.class,
                () -> src.batchWrite(List.of(Pair.of(b(1), b(1))), List.of()));
    }
}
