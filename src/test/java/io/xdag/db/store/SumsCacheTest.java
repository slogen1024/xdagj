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

package io.xdag.db.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.xdag.BlockBuilder;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FileUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SumsCacheTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private Config config;
    private RocksdbFactory factory;
    private BlockStoreImpl store;
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        factory = new RocksdbFactory(config);
        store = BlockStoreImpl.forNode(factory);
        store.start();
    }

    private List<Block> blocks(int n, long t0) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block b = BlockBuilder.generateAddressBlock(config, key, t0 + i * 65536L * 3);
            out.add(new Block(new XdagBlock(b.toBytes())));
        }
        return out;
    }

    /** The definition: per block, per file key, sum += block sum and size += 512 at the byte index of that level. */
    private static Map<String, MutableBytes> expected(List<Block> blocks) {
        Map<String, MutableBytes> m = new HashMap<>();
        for (Block b : blocks) {
            long time = b.getTimestamp();
            List<String> names = FileUtils.getFileName(time);
            for (int i = 0; i < names.size(); i++) {
                MutableBytes sums = m.computeIfAbsent(names.get(i), k -> MutableBytes.create(4096));
                int index = (int) ((time >> (40 - 8 * i)) & 0xff);
                long sum = sums.getLong(16 * index, java.nio.ByteOrder.LITTLE_ENDIAN) + b.getXdagBlock().getSum();
                long size = sums.getLong(16 * index + 8, java.nio.ByteOrder.LITTLE_ENDIAN) + 512;
                sums.set(16 * index, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
                sums.set(16 * index + 8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
            }
        }
        return m;
    }

    private byte[] rawSums(String name) {
        KVSource<byte[], byte[]> index = factory.getDB(DatabaseName.INDEX);
        return index.get(BytesUtils.merge(BlockStore.SUMS_BLOCK_INFO, name.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sumsAreUpdatedInMemoryAndWrittenOnlyOnFlush() {
        List<Block> bs = blocks(5, 1_700_000_000_000L);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        Map<String, MutableBytes> want = expected(bs);
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("cache reflects every update", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNull("nothing written before the flush", rawSums(e.getKey()));
        }
        store.flushSums();
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("flushed value equals the cache", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNotNull("the index now holds the key", rawSums(e.getKey()));
            BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
            assertArrayEquals("a fresh store reads the flushed array back", e.getValue().toArray(), fresh.getSums(e.getKey()).toArray());
        }
    }

    @Test
    public void loadSumIsIdenticalBeforeAndAfterAReopen() {
        List<Block> bs = blocks(12, 1_700_000_000_000L);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        long start = bs.get(0).getTimestamp() & 0xffffff000000L;
        MutableBytes fromCache = MutableBytes.create(4096);
        int rc1 = store.loadSum(start, start + (1L << 24), fromCache);
        store.saveXdagStatus(new io.xdag.core.XdagStats()); // flushes the dirty sums first
        BlockStoreImpl reopened = BlockStoreImpl.forNode(factory);
        reopened.start();
        MutableBytes fromDisk = MutableBytes.create(4096);
        int rc2 = reopened.loadSum(start, start + (1L << 24), fromDisk);
        assertEquals(rc1, rc2);
        assertArrayEquals(fromCache.toArray(), fromDisk.toArray());
    }

    @Test
    public void everyTwoHundredFiftySixBlocksFlushOnTheirOwn() {
        List<Block> bs = blocks(BlockStoreImpl.SUMS_FLUSH_EVERY, 1_700_000_000_000L);
        for (int i = 0; i < bs.size() - 1; i++) {
            store.saveBlock(bs.get(i));
        }
        String any = FileUtils.getFileName(bs.get(0).getTimestamp()).get(0);
        assertNull(rawSums(any));
        store.saveBlock(bs.get(bs.size() - 1));
        assertNotNull("the 256th block flushed", rawSums(any));
    }
}
