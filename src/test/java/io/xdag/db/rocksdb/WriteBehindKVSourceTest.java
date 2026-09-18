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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        queue = new WriteBehindQueue(8, 4, 1_000_000L, false); // manual: drained by drainOnce()/flushSync()
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
    public void batchWriteTreatsANullValuedPairAsADeleteOnBothPaths() {
        raw.put(b("q"), b("1"));
        raw.put(b("d"), b("1"));
        assertArrayEquals(b("1"), source.get(b("q")));
        assertArrayEquals(b("1"), source.get(b("d")));
        source.batchWrite(List.of(Pair.of(b("q"), null)), List.of());
        assertNull("queued: tombstone, cache evicted", source.get(b("q")));
        queue.flushSync();
        assertNull(raw.get(b("q")));
        queue.direct(() -> source.batchWrite(List.of(Pair.of(b("d"), null)), List.of()));
        assertNull("direct: delegate delete, cache evicted", source.get(b("d")));
        assertNull(raw.get(b("d")));
    }

    @Test
    public void backpressureInManualModeDrainsAGroupOnTheCallerInsteadOfParking() {
        for (int i = 0; i < 8; i++) {
            source.put(b("k" + i), b("v"));
        }
        assertEquals(8, queue.pending());
        assertEquals(0, queue.writtenCount());
        source.put(b("k8"), b("v")); // full: this thread writes one group, then appends
        assertEquals(5, queue.pending());
        assertEquals(4, queue.writtenCount());
        assertArrayEquals(b("v"), raw.get(b("k0")));
        assertArrayEquals(b("v"), raw.get(b("k3")));
        assertNull("only one group was drained", raw.get(b("k4")));
        assertArrayEquals(b("v"), source.get(b("k8")));
        queue.flushSync();
        assertEquals(0, queue.pending());
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
        assertThrows("a manual queue has no writer to start", IllegalStateException.class, queue::start);
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

    /** A delegate whose {@code get} reads the value, then parks until released: a read miss caught mid-flight. */
    private static final class ParkingReadDelegate extends ForwardingKVSource {
        final CountDownLatch inRead = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ParkingReadDelegate(KVSource<byte[], byte[]> delegate) {
            super(delegate);
        }

        @Override
        public byte[] get(byte[] key) {
            byte[] value = super.get(key);
            inRead.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return value;
        }
    }

    @Test(timeout = 30_000)
    public void aReadMissThatRacesAQueuedWriteDoesNotPoisonTheReadCache() throws Exception {
        raw.put(b("k"), b("old"));
        ParkingReadDelegate parking = new ParkingReadDelegate(raw);
        WriteBehindKVSource cached = new WriteBehindKVSource(parking, queue, 16);
        AtomicReference<byte[]> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(cached.get(b("k"))), "reader");
        reader.start();
        assertTrue(parking.inRead.await(10, TimeUnit.SECONDS)); // missed pending and cache, holds "old"
        cached.put(b("k"), b("new")); // queued: pending map and cache now say "new"
        parking.release.countDown();
        reader.join(10_000);
        assertFalse(reader.isAlive());
        assertArrayEquals("the reader itself saw the pre-write value", b("old"), seen.get());
        queue.flushSync(); // the write lands and the pending entry is retired: only the cache is left
        assertArrayEquals("a stale read miss must not repopulate the cache over a newer write", b("new"), cached.get(b("k")));
        assertArrayEquals(b("new"), raw.get(b("k")));
    }

    @Test(timeout = 30_000)
    public void aReadMissThatRacesADirectDeleteDoesNotPoisonTheReadCache() throws Exception {
        raw.put(b("k"), b("old"));
        ParkingReadDelegate parking = new ParkingReadDelegate(raw);
        WriteBehindKVSource cached = new WriteBehindKVSource(parking, queue, 16);
        AtomicReference<byte[]> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(cached.get(b("k"))), "reader");
        reader.start();
        assertTrue(parking.inRead.await(10, TimeUnit.SECONDS));
        queue.direct(() -> cached.delete(b("k"))); // no pending entry: only the write epoch can catch this
        parking.release.countDown();
        reader.join(10_000);
        assertFalse(reader.isAlive());
        assertArrayEquals(b("old"), seen.get());
        assertNull("a stale read miss must not resurrect a directly deleted key", cached.get(b("k")));
        assertNull(raw.get(b("k")));
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
    public void twoSourcesOnOneQueueAreWrittenInStreamOrderAsSeparateRuns() {
        RocksdbKVSource raw2 = new RocksdbKVSource("WB2");
        raw2.setConfig(config);
        raw2.init();
        try {
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            KVSource<byte[], byte[]> a = new ForwardingKVSource(raw) {
                @Override
                public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                    calls.add("A:" + puts.size() + "/" + deletes.size());
                    super.batchWrite(puts, deletes);
                }
            };
            KVSource<byte[], byte[]> bb = new ForwardingKVSource(raw2) {
                @Override
                public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                    calls.add("B:" + puts.size() + "/" + deletes.size());
                    super.batchWrite(puts, deletes);
                }
            };
            WriteBehindKVSource sa = new WriteBehindKVSource(a, queue, 0);
            WriteBehindKVSource sb = new WriteBehindKVSource(bb, queue, 0);
            sa.put(b("k"), b("a1"));
            sb.put(b("k"), b("b1"));
            sa.delete(b("k"));
            sb.put(b("k"), b("b2"));
            assertNull(sa.get(b("k")));
            assertArrayEquals(b("b2"), sb.get(b("k")));
            assertTrue(queue.drainOnce()); // one group of four entries
            assertEquals("a run ends whenever the source changes", List.of("A:1/0", "B:1/0", "A:0/1", "B:1/0"), calls);
            assertNull(raw.get(b("k")));
            assertArrayEquals(b("b2"), raw2.get(b("k")));
            assertEquals(0, queue.pending());
            assertNull(sa.get(b("k")));
            assertArrayEquals(b("b2"), sb.get(b("k")));
        } finally {
            raw2.close();
        }
    }

    @Test
    public void resetFlushesThenClearsPendingAndCacheAndResetsTheDelegate() {
        raw.put(b("c"), b("disk"));
        assertArrayEquals(b("disk"), source.get(b("c"))); // now in the read cache
        source.put(b("q"), b("v"));
        source.reset();
        assertEquals(0, queue.pending());
        assertTrue(raw.isAlive());
        assertNull("the delegate was wiped", raw.get(b("c")));
        assertNull("the read cache was cleared", source.get(b("c")));
        assertNull("the pending map was cleared", source.get(b("q")));
    }

    @Test
    public void closeFlushesThenClosesTheDelegate() {
        source.put(b("k"), b("v"));
        source.close();
        assertFalse(raw.isAlive());
        assertEquals(0, queue.pending());
        raw.init(); // reopen to see what was flushed before the close
        assertArrayEquals(b("v"), raw.get(b("k")));
    }

    @Test
    public void closeAfterAFailureStillClosesTheDelegate() {
        AtomicBoolean closed = new AtomicBoolean();
        KVSource<byte[], byte[]> dead = new ForwardingKVSource(raw) {
            @Override
            public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                throw new RuntimeException("boom");
            }

            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        WriteBehindKVSource s2 = new WriteBehindKVSource(dead, queue, 0);
        s2.put(b("k"), b("v"));
        assertFalse(queue.drainOnce());
        assertNotNull(queue.failure());
        s2.close(); // must not throw
        assertTrue("the delegate is closed even though the flush failed", closed.get());
        assertFalse(raw.isAlive());
    }

    @Test
    public void factoryWrapsOnlyTheFourImportDatabases() {
        RocksdbFactory rawFactory = new RocksdbFactory(config);
        WriteBehindQueue q = new WriteBehindQueue(8, 4, 1_000_000L, false);
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

    @Test
    public void factoryCloseClosesTheDelegateEvenWhenTheQueueHasFailed() {
        AtomicBoolean closed = new AtomicBoolean();
        DatabaseFactory rawFactory = new DatabaseFactory() {
            @Override
            public KVSource<byte[], byte[]> getDB(DatabaseName name) {
                return new ForwardingKVSource(raw) {
                    @Override
                    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                        throw new RuntimeException("boom");
                    }
                };
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        WriteBehindQueue q = new WriteBehindQueue(8, 4, 1_000_000L, false);
        WriteBehindFactory factory = new WriteBehindFactory(rawFactory, q, 0);
        factory.getDB(DatabaseName.BLOCK).put(b("k"), b("v"));
        assertFalse(q.drainOnce());
        factory.close(); // stop() throws after a failure; the delegate must still be closed
        assertTrue(closed.get());
        assertNotNull(q.failure());
    }
}
