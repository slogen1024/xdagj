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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WriteBehindKVSourceTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private Config config;
    private RocksdbKVSource raw;
    private WriteBehindQueue queue;
    private WriteBehindKVSource source;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder("store").getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder("backup").getAbsolutePath());
        raw = new RocksdbKVSource("WB");
        raw.setConfig(config);
        raw.init();
        queue = new WriteBehindQueue(8, 4, 1_000_000L); // manual: never started, flushed by drainOnce()/flushSync()
        source = new WriteBehindKVSource(raw, queue, 16);
    }

    @After
    public void tearDown() {
        queue.abandon();
        raw.close();
    }

    @Test
    public void putIsVisibleBeforeItIsWrittenAndAfter() {
        source.put(b("k"), b("v"));
        assertArrayEquals(b("v"), source.get(b("k")));
        assertNull("not on disk yet", raw.get(b("k")));
        assertTrue(queue.drainOnce());
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertArrayEquals(b("v"), source.get(b("k")));
        assertEquals(0, queue.pending());
    }

    @Test
    public void deleteIsATombstoneUntilWritten() {
        raw.put(b("k"), b("old"));
        source.delete(b("k"));
        assertNull(source.get(b("k")));
        assertArrayEquals("still on disk until the writer runs", b("old"), raw.get(b("k")));
        queue.flushSync();
        assertNull(raw.get(b("k")));
        assertNull(source.get(b("k")));
    }

    @Test
    public void laterPutWinsOverEarlierDeleteOfTheSameKeyInOneGroup() {
        source.put(b("k"), b("v1"));
        source.delete(b("k"));
        source.put(b("k"), b("v2"));
        assertArrayEquals(b("v2"), source.get(b("k")));
        queue.flushSync();
        assertArrayEquals("batchWrite applies deletes after puts; the queue must split the group", b("v2"), raw.get(b("k")));
    }

    @Test
    public void overwriteWhileAGroupIsInFlightKeepsTheNewestValue() {
        source.put(b("k"), b("v1"));
        // Simulate the writer taking the group but a newer put landing before completion is recorded.
        source.put(b("k"), b("v2"));
        assertTrue(queue.drainOnce());
        assertArrayEquals(b("v2"), source.get(b("k")));
        assertArrayEquals(b("v2"), raw.get(b("k")));
    }

    @Test
    public void iterationFlushesFirst() {
        source.put(b("p1"), b("a"));
        source.put(b("p2"), b("b"));
        List<byte[]> keys = source.prefixKeyLookup(b("p"));
        assertEquals(2, keys.size());
        assertEquals(0, queue.pending());
        assertArrayEquals(b("a"), raw.get(b("p1")));
    }

    @Test
    public void bypassWritesStraightThroughAndUpdatesTheReadCache() {
        source.put(b("k"), b("queued"));
        queue.flushSync();
        queue.direct(() -> {
            source.put(b("k"), b("direct"));
            source.put(b("d"), b("x"));
            source.delete(b("d"));
        });
        assertEquals("nothing queued", 0, queue.pending());
        assertArrayEquals(b("direct"), raw.get(b("k")));
        assertArrayEquals(b("direct"), source.get(b("k")));
        assertNull(source.get(b("d")));
    }

    @Test
    public void directDrainsPendingWritesFirst() {
        source.put(b("k"), b("queued"));
        assertEquals(1, queue.pending());
        queue.direct(() -> assertArrayEquals("drained before the body runs", b("queued"), raw.get(b("k"))));
        assertEquals(0, queue.pending());
    }

    @Test
    public void directIsReentrant() {
        queue.direct(() -> queue.direct(() -> source.put(b("k"), b("v"))));
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertFalse(queue.isBypass());
    }

    @Test
    public void batchWriteQueuesPutsThenDeletesInOrder() {
        raw.put(b("gone"), b("1"));
        source.batchWrite(List.of(Pair.of(b("a"), b("1")), Pair.of(b("b"), b("2"))), List.of(b("gone")));
        assertArrayEquals(b("1"), source.get(b("a")));
        assertNull(source.get(b("gone")));
        queue.flushSync();
        assertArrayEquals(b("2"), raw.get(b("b")));
        assertNull(raw.get(b("gone")));
    }

    @Test
    public void backpressureBlocksAtMaxPendingAndWakesAfterADrain() throws Exception {
        for (int i = 0; i < 8; i++) {
            source.put(b("k" + i), b("v"));
        }
        AtomicBoolean unblocked = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            source.put(b("k8"), b("v"));
            unblocked.set(true);
        });
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse("put must block while pending >= maxPending", unblocked.get());
        assertTrue(queue.drainOnce());
        t.join(5_000);
        assertTrue(unblocked.get());
        queue.flushSync();
        assertArrayEquals(b("v"), raw.get(b("k8")));
    }

    @Test
    public void aFailedWriteIsFatalAndNeverSilent() {
        raw.close(); // the delegate is dead: the next group write must fail
        source.put(b("k"), b("v"));
        assertFalse(queue.drainOnce());
        assertNotNull(queue.failure());
        assertThrows(IllegalStateException.class, () -> source.put(b("k2"), b("v")));
        assertThrows(IllegalStateException.class, queue::flushSync);
    }

    @Test
    public void flushSyncIsIdempotentAndCheapWhenEmpty() {
        queue.flushSync();
        queue.flushSync();
        assertEquals(0, queue.pending());
    }

    @Test
    public void readCacheServesRepeatedGetsWithoutTheDelegate() {
        raw.put(b("k"), b("disk"));
        assertArrayEquals(b("disk"), source.get(b("k")));
        raw.delete(b("k")); // behind the wrapper's back: only a cache hit can still return it
        assertArrayEquals("second get is served by the read cache", b("disk"), source.get(b("k")));
        source.delete(b("k"));
        assertNull("a delete evicts the cache entry", source.get(b("k")));
    }

    @Test
    public void everyReadMethodOfTheInterfaceIsCoveredByTheWrapper() {
        List<String> missing = new ArrayList<>();
        for (Method m : KVSource.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                Method impl = WriteBehindKVSource.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (Modifier.isAbstract(impl.getModifiers())) {
                    missing.add(m.getName());
                }
            } catch (NoSuchMethodException e) {
                missing.add(m.getName());
            }
        }
        assertTrue("KVSource methods not overridden by WriteBehindKVSource (would bypass the pending map): " + missing,
                missing.isEmpty());
    }

    @Test
    public void factoryWrapsOnlyTheFourImportDatabases() {
        RocksdbFactory rawFactory = new RocksdbFactory(config);
        WriteBehindQueue q = new WriteBehindQueue(8, 4, 1_000_000L);
        WriteBehindFactory factory = new WriteBehindFactory(rawFactory, q, 16);
        try {
            assertTrue(factory.getDB(DatabaseName.INDEX) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.BLOCK) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.TIME) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.ORPHANIND) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.ADDRESS) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.CHAIN_L1) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.TXHISTORY) instanceof WriteBehindKVSource);
            assertTrue("same instance on repeated getDB", factory.getDB(DatabaseName.INDEX) == factory.getDB(DatabaseName.INDEX));
        } finally {
            q.abandon();
            factory.close();
        }
    }
}
