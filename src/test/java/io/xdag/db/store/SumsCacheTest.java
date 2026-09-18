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
import io.xdag.core.XdagStats;
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

    /** One deepest sums bucket: the level-3 key changes every 2^24 XDAG ticks (1/1024 s each, about 4.55 h). */
    private static final long BUCKET = 1L << 24;
    /** A bucket-aligned start, so a run of blocks with a small stride stays in one deepest bucket. */
    private static final long T0 = 1_700_000_000_000L & ~(BUCKET - 1);
    private static final long EPOCH = 65536L;

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

    private List<Block> blocks(int n, long t0, long stride) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block b = BlockBuilder.generateAddressBlock(config, key, t0 + i * stride);
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

    private static String rootKey(Block b) {
        return FileUtils.getFileName(b.getTimestamp()).get(0);
    }

    private static String deepestKey(Block b) {
        return FileUtils.getFileName(b.getTimestamp()).get(3);
    }

    @Test
    public void sumsAreUpdatedInMemoryAndWrittenOnlyOnFlush() {
        List<Block> bs = blocks(5, T0, EPOCH);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        Map<String, MutableBytes> want = expected(bs);
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("cache reflects every update", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNull("nothing written before the flush", rawSums(e.getKey()));
        }
        store.flushSums();
        BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("flushed value equals the cache", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNotNull("the index now holds the key", rawSums(e.getKey()));
            assertArrayEquals("a store with an empty cache reads the flushed array back",
                    e.getValue().toArray(), fresh.getSums(e.getKey()).toArray());
        }
    }

    @Test
    public void loadSumIsIdenticalBeforeAndAfterAReopen() {
        List<Block> bs = blocks(12, T0, EPOCH);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        long start = bs.get(0).getTimestamp() & 0xffffff000000L;
        MutableBytes fromCache = MutableBytes.create(4096);
        int rc1 = store.loadSum(start, start + (1L << 24), fromCache);
        store.flushSums();
        BlockStoreImpl reopened = BlockStoreImpl.forNode(factory);
        // The factory hands back the already-open sources; start() relies on RocksdbKVSource.init() being idempotent.
        reopened.start();
        MutableBytes fromDisk = MutableBytes.create(4096);
        int rc2 = reopened.loadSum(start, start + (1L << 24), fromDisk);
        assertEquals(rc1, rc2);
        assertArrayEquals(fromCache.toArray(), fromDisk.toArray());
    }

    /**
     * The production shape: BlockchainImpl.tryToConnect saves the stats after every import. That
     * must not flush the sums, or the batching never happens; only the 256th save writes them.
     */
    @Test
    public void aStatsSavePerImportDoesNotFlushBeforeTheTwoHundredFiftySixthBlock() {
        List<Block> bs = blocks(300, T0, EPOCH / 2); // 300 * 32768 ticks stays inside one deepest bucket
        XdagStats stats = new XdagStats();
        String rootKey = rootKey(bs.get(0));
        for (int i = 0; i < bs.size(); i++) {
            store.saveBlock(bs.get(i));
            store.saveXdagStatus(stats);
            if (i + 1 < BlockStoreImpl.SUMS_FLUSH_EVERY) {
                assertNull("no sums written after save " + (i + 1), rawSums(rootKey));
            } else {
                assertNotNull("sums written from save " + BlockStoreImpl.SUMS_FLUSH_EVERY + " on", rawSums(rootKey));
            }
        }
        BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
        assertArrayEquals("the index holds exactly the first 256 blocks' contributions",
                expected(bs.subList(0, BlockStoreImpl.SUMS_FLUSH_EVERY)).get(rootKey).toArray(),
                fresh.getSums(rootKey).toArray());
        assertArrayEquals("the cache holds all 300",
                expected(bs).get(rootKey).toArray(), store.getSums(rootKey).toArray());
    }

    @Test
    public void rollingIntoANewDeepestBucketFlushesTheOneLeftBehind() {
        Block first = blocks(1, T0, EPOCH).get(0);
        Block next = blocks(1, T0 + BUCKET, EPOCH).get(0);
        store.saveBlock(first);
        assertNull(rawSums(deepestKey(first)));
        store.saveBlock(next);
        assertNotNull("the bucket left behind was written at the roll", rawSums(deepestKey(first)));
        assertNull("the bucket being grown stays in memory", rawSums(deepestKey(next)));
        BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
        assertArrayEquals(expected(List.of(first)).get(deepestKey(first)).toArray(), fresh.getSums(deepestKey(first)).toArray());
        assertArrayEquals("the shared root key was written with only the first block in it",
                expected(List.of(first)).get(rootKey(first)).toArray(), fresh.getSums(rootKey(first)).toArray());
        assertArrayEquals("while the cache already has both",
                expected(List.of(first, next)).get(rootKey(first)).toArray(), store.getSums(rootKey(first)).toArray());
    }

    /** The eviction's one load-bearing invariant: a bucket that was flushed and evicted accumulates on top of the disk value. */
    @Test
    public void aBlockReturningToAnEvictedBucketAccumulatesOnTheDiskValue() {
        Block first = blocks(1, T0, EPOCH).get(0);
        Block other = blocks(1, T0 + BUCKET, EPOCH).get(0);
        Block third = blocks(1, T0 + EPOCH, EPOCH).get(0);
        store.saveBlock(first);
        store.saveBlock(other);   // rolls: flushes and evicts first's bucket (getSums cannot observe that: it reads disk on a miss)
        store.saveBlock(third);   // back in first's bucket: must reload from disk, not start from zero
        store.flushSums();
        BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
        assertArrayEquals(expected(List.of(first, third)).get(deepestKey(first)).toArray(),
                fresh.getSums(deepestKey(first)).toArray());
    }

    @Test
    public void resetClearsTheCacheTheDirtySetAndTheCounter() {
        List<Block> bs = blocks(200, T0, EPOCH / 2);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        String rootKey = rootKey(bs.get(0));
        assertNotNull(store.getSums(rootKey));
        store.reset();
        assertNull("the cache is empty after a reset", store.getSums(rootKey));
        for (Block b : bs) {
            store.saveBlock(b);
        }
        assertNull("the counter restarted at zero: 200 + 200 saves did not reach the flush", rawSums(rootKey));
        store.flushSums();
        BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
        assertArrayEquals("only the saves after the reset were written",
                expected(bs).get(rootKey).toArray(), fresh.getSums(rootKey).toArray());
    }
}
