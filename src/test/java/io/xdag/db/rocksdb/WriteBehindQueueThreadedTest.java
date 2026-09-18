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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The writer thread: timer, barriers, the in-flight window, failure wake-ups, stop, contention. */
public class WriteBehindQueueThreadedTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private RocksdbKVSource raw;
    private WriteBehindQueue queue;
    private WriteBehindKVSource source;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder("store").getAbsolutePath());
        raw = new RocksdbKVSource("WBT");
        raw.setConfig(config);
        raw.init();
    }

    @After
    public void tearDown() {
        if (queue != null) {
            queue.abandon();
        }
        raw.close();
    }

    private void start(KVSource<byte[], byte[]> delegate, int maxPending, int flushEntries, long flushMs) {
        queue = new WriteBehindQueue(maxPending, flushEntries, flushMs, true);
        source = new WriteBehindKVSource(delegate, queue, 16);
        queue.start();
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            Thread.sleep(5);
        }
    }

    private static boolean parked(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    /** batchWrite signals {@code entered}, then waits for {@code release} (if given), then sleeps {@code sleepMs}. */
    private static final class SlowDelegate extends ForwardingKVSource {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        final long sleepMs;
        final boolean throwAfter;

        SlowDelegate(KVSource<byte[], byte[]> delegate, CountDownLatch release, long sleepMs, boolean throwAfter) {
            super(delegate);
            this.release = release;
            this.sleepMs = sleepMs;
            this.throwAfter = throwAfter;
        }

        @Override
        public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
            entered.countDown();
            try {
                if (release != null) {
                    release.await();
                }
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (throwAfter) {
                throw new RuntimeException("boom");
            }
            super.batchWrite(puts, deletes);
        }
    }

    @Test(timeout = 30_000)
    public void writerFlushesOnTheTimer() throws Exception {
        start(raw, 64, 4, 50);
        assertTrue(queue.writerAlive());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("xdag-persist".equals(t.getName()) && t.isAlive()) {
                assertFalse("the writer must not be a daemon", t.isDaemon());
            }
        }
        source.put(b("a"), b("1"));
        source.put(b("b"), b("2"));
        source.put(b("c"), b("3")); // three entries: below flushEntries, so only the timer flushes them
        awaitUntil("the timer flush", () -> queue.writtenCount() == 3);
        assertEquals(0, queue.pending());
        assertArrayEquals(b("3"), raw.get(b("c")));
        assertArrayEquals(b("3"), source.get(b("c")));
    }

    @Test(timeout = 30_000)
    public void flushSyncBlocksUntilTheGroupIsInTheDatabase() throws Exception {
        SlowDelegate slow = new SlowDelegate(raw, null, 200, false);
        start(slow, 64, 4, 10);
        source.put(b("k"), b("v"));
        long t0 = System.nanoTime();
        queue.flushSync();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("flushSync returned after " + elapsedMs + " ms, before the 200 ms write finished", elapsedMs >= 150);
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertEquals(0, queue.pending());
        assertEquals(1, queue.writtenCount());
    }

    @Test(timeout = 30_000)
    public void directSeesEveryQueuedWriteAndNothingRacesIt() throws Exception {
        SlowDelegate slow = new SlowDelegate(raw, null, 300, false);
        start(slow, 64, 1, 10);
        source.put(b("k"), b("v1"));
        assertTrue(slow.entered.await(10, TimeUnit.SECONDS)); // the group is polled and in flight: queue empty
        assertEquals(0, queue.pending());
        long t0 = System.nanoTime();
        queue.direct(() -> source.put(b("k"), b("v2")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("direct must wait for the in-flight group (" + elapsedMs + " ms)", elapsedMs >= 100);
        awaitUntil("the in-flight group", () -> queue.writtenCount() == 1);
        assertArrayEquals("the direct write must land after the in-flight group", b("v2"), raw.get(b("k")));
        assertArrayEquals(b("v2"), source.get(b("k")));
        assertEquals(0, queue.pending());
        assertFalse(queue.isBypass());
    }

    @Test(timeout = 30_000)
    public void aFailedWriteWakesEveryWaiter() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SlowDelegate failing = new SlowDelegate(raw, release, 0, true);
        start(failing, 2, 1, 10);
        source.put(b("k0"), b("v")); // polled by the writer, which now blocks inside batchWrite
        assertTrue(failing.entered.await(10, TimeUnit.SECONDS));
        source.put(b("k1"), b("v"));
        source.put(b("k2"), b("v")); // queue == maxPending
        AtomicReference<Throwable> flusher = new AtomicReference<>();
        AtomicReference<Throwable> producer = new AtomicReference<>();
        Thread a = new Thread(() -> {
            try {
                queue.flushSync(); // barrier queued behind k1, k2 — not in the failing group
            } catch (Throwable t) {
                flusher.set(t);
            }
        }, "flusher");
        Thread bThread = new Thread(() -> {
            try {
                source.put(b("k3"), b("v")); // parks on backpressure
            } catch (Throwable t) {
                producer.set(t);
            }
        }, "producer");
        a.start();
        bThread.start();
        awaitUntil("both waiters to park", () -> parked(a) && parked(bThread));
        release.countDown(); // the in-flight write now throws
        a.join(5_000);
        bThread.join(5_000);
        assertFalse("flusher still waiting", a.isAlive());
        assertFalse("producer still waiting", bThread.isAlive());
        assertTrue(String.valueOf(flusher.get()), flusher.get() instanceof IllegalStateException);
        assertTrue(String.valueOf(producer.get()), producer.get() instanceof IllegalStateException);
        assertNotNull(queue.failure());
        assertEquals("boom", queue.failure().getMessage());
        awaitUntil("the writer to exit", () -> !queue.writerAlive());
        assertThrows("start after a failure", IllegalStateException.class, queue::start);
        assertThrows("stop after a failure", IllegalStateException.class, queue::stop);
    }

    @Test(timeout = 30_000)
    public void backpressureParksTheProducerUntilTheWriterDrains() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SlowDelegate slow = new SlowDelegate(raw, release, 0, false);
        start(slow, 2, 1, 10);
        source.put(b("a"), b("v"));
        assertTrue(slow.entered.await(10, TimeUnit.SECONDS)); // writer stuck on "a"
        source.put(b("b"), b("v"));
        source.put(b("c"), b("v")); // queue == maxPending
        AtomicBoolean unblocked = new AtomicBoolean();
        Thread t = new Thread(() -> {
            source.put(b("d"), b("v"));
            unblocked.set(true);
        }, "producer");
        t.start();
        awaitUntil("the producer to park", () -> parked(t));
        Thread.sleep(100);
        assertFalse("put must park while pending >= maxPending and a writer is running", unblocked.get());
        release.countDown();
        t.join(10_000);
        assertTrue(unblocked.get());
        queue.flushSync();
        assertArrayEquals(b("v"), raw.get(b("d")));
        assertEquals(4, queue.writtenCount());
    }

    @Test(timeout = 30_000)
    public void stopFlushesEverythingAndJoinsTheWriter() throws Exception {
        start(raw, 64, 4, 60_000); // a long timer: only stop() can flush a partial group
        source.put(b("k"), b("v"));
        queue.stop();
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertFalse(queue.writerAlive());
        assertEquals(0, queue.pending());
        queue.stop(); // idempotent
        assertFalse(queue.writerAlive());
        source.put(b("after"), b("w")); // no writer: flushSync drains on the caller
        queue.flushSync();
        assertArrayEquals(b("w"), raw.get(b("after")));
    }

    @Test(timeout = 30_000)
    public void concurrentWritersKeepPendingAndDiskInAgreement() throws Exception {
        start(raw, 64, 16, 5);
        int threads = 4;
        int puts = 500;
        int keys = 50;
        List<Thread> workers = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int w = 0; w < threads; w++) {
            int id = w;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < puts; i++) {
                        source.put(b("k" + (i % keys)), b(id + ":" + i));
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            }, "writer-" + w);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            t.join(20_000);
            assertFalse(t.isAlive());
        }
        assertNull(error.get());
        queue.flushSync();
        assertEquals(0, queue.pending());
        assertEquals(threads * puts, queue.enqueuedCount());
        assertEquals(threads * puts, queue.writtenCount());
        for (int k = 0; k < keys; k++) {
            byte[] key = b("k" + k);
            assertNotNull(raw.get(key));
            assertArrayEquals("key " + k, raw.get(key), source.get(key));
        }
    }
}
