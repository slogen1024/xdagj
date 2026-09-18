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

import io.xdag.db.PersistControl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * One ordered write stream shared by every {@link WriteBehindKVSource} of a node, drained by a
 * single writer thread into per-database {@code batchWrite} calls.
 *
 * <p>Ordering: entries are written in enqueue order. A group taken by the writer is cut into runs
 * of consecutive entries of the same source; a run is also cut before a put whose key was deleted
 * earlier in the same run, because {@code batchWrite} applies its deletes after its puts. Runs are
 * written in order, so the databases always hold a prefix of the stream: a crash loses at most the
 * entries the writer has not yet written (bounded by {@code maxPending} plus one group).
 *
 * <p>Failure: any throwable out of a write marks the queue failed; every later put or flush throws
 * {@link IllegalStateException} with that cause. Nothing is dropped silently.
 *
 * <p>Manual mode: a queue that was never {@link #start()}ed is drained by {@link #flushSync()} on the
 * calling thread and by tests through {@link #drainOnce()}.
 */
@Slf4j
public final class WriteBehindQueue implements PersistControl {

    static final class Entry {
        final WriteBehindKVSource source; // null for a barrier
        final byte[] key;
        final byte[] value;               // null = delete
        final long version;
        final CountDownLatch barrier;

        Entry(WriteBehindKVSource source, byte[] key, byte[] value, long version) {
            this.source = source;
            this.key = key;
            this.value = value;
            this.version = version;
            this.barrier = null;
        }

        Entry(CountDownLatch barrier) {
            this.source = null;
            this.key = null;
            this.value = null;
            this.version = -1;
            this.barrier = barrier;
        }
    }

    private final int maxPending;
    private final int flushEntries;
    private final long flushNanos;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final AtomicLong versions = new AtomicLong();
    private final ThreadLocal<Integer> bypassDepth = ThreadLocal.withInitial(() -> 0);
    private volatile Throwable failure;
    private volatile boolean running;
    private Thread writer;
    private long enqueued;
    private long written;
    private long firstEnqueuedNanos;

    public WriteBehindQueue(int maxPending, int flushEntries, long flushMs) {
        if (maxPending < 1 || flushEntries < 1 || flushMs < 1) {
            throw new IllegalArgumentException("maxPending, flushEntries and flushMs must be positive");
        }
        this.maxPending = maxPending;
        this.flushEntries = flushEntries;
        this.flushNanos = TimeUnit.MILLISECONDS.toNanos(flushMs);
    }

    /** Starts the writer thread. Not a daemon: a JVM must not exit with queued writes. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        writer = new Thread(this::loop, "xdag-persist");
        writer.setDaemon(false);
        writer.start();
    }

    /** Flushes everything and stops the writer thread. Idempotent. */
    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running) {
                if (failure == null) {
                    drainAll();
                }
                return;
            }
            t = writer;
        }
        try {
            flushSync();
        } finally {
            lock.lock();
            try {
                running = false;
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
            try {
                t.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Test hook: drop every queued write without writing it (a simulated crash). */
    public void abandon() {
        lock.lock();
        try {
            for (Entry e : queue) {
                if (e.barrier != null) {
                    e.barrier.countDown();
                }
            }
            queue.clear();
            running = false;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    long nextVersion() {
        return versions.incrementAndGet();
    }

    public Throwable failure() {
        return failure;
    }

    public int pending() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Number of entries ever enqueued (barriers excluded). */
    public long enqueuedCount() {
        lock.lock();
        try {
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    /** Number of entries written to the databases so far (barriers excluded). */
    public long writtenCount() {
        lock.lock();
        try {
            return written;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isBypass() {
        return bypassDepth.get() > 0;
    }

    @Override
    public <T> T direct(Supplier<T> body) {
        if (isBypass()) {
            return body.get();
        }
        flushSync();
        bypassDepth.set(1);
        try {
            return body.get();
        } finally {
            bypassDepth.set(0);
        }
    }

    private void checkFailed() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("write-behind persistence failed; the node must stop", f);
        }
    }

    void enqueue(Entry e) {
        checkFailed();
        lock.lock();
        try {
            while (queue.size() >= maxPending && failure == null) {
                notFull.await();
            }
            checkFailed();
            if (queue.isEmpty()) {
                firstEnqueuedNanos = System.nanoTime();
            }
            queue.addLast(e);
            enqueued++;
            notEmpty.signalAll();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while queuing a write", ie);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flushSync() {
        checkFailed();
        if (isBypass()) {
            return;
        }
        CountDownLatch barrier = new CountDownLatch(1);
        boolean threaded;
        lock.lock();
        try {
            if (queue.isEmpty()) {
                return;
            }
            queue.addLast(new Entry(barrier));
            threaded = running;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        if (!threaded) {
            drainAll();
            checkFailed();
            return;
        }
        try {
            barrier.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the writer", ie);
        }
        checkFailed();
    }

    private void drainAll() {
        while (drainOnce()) {
            // until empty
        }
    }

    private void loop() {
        while (true) {
            lock.lock();
            try {
                while (queue.isEmpty() && running) {
                    notEmpty.await();
                }
                if (queue.isEmpty() && !running) {
                    return;
                }
                // Wait for a full group, a barrier, the flush timer or stop(). A loop rather than a
                // single await: every enqueue signals notEmpty, and an early wake-up must not cut
                // the group short of flushEntries before the timer has run out.
                while (running && !queue.isEmpty() && queue.size() < flushEntries
                        && queue.peekLast().barrier == null) {
                    long remaining = flushNanos - (System.nanoTime() - firstEnqueuedNanos);
                    if (remaining <= 0) {
                        break;
                    }
                    notEmpty.awaitNanos(remaining);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            if (!drainOnce() && failure != null) {
                return;
            }
        }
    }

    /**
     * Writes one group (up to {@code flushEntries} entries, or up to and including the first
     * barrier). Returns false when there was nothing to write or the write failed.
     */
    public boolean drainOnce() {
        List<Entry> group = new ArrayList<>();
        lock.lock();
        try {
            while (!queue.isEmpty() && group.size() < flushEntries) {
                Entry e = queue.pollFirst();
                group.add(e);
                if (e.barrier != null) {
                    break;
                }
            }
            if (!queue.isEmpty()) {
                firstEnqueuedNanos = System.nanoTime();
            }
        } finally {
            lock.unlock();
        }
        if (group.isEmpty()) {
            return false;
        }
        try {
            writeRuns(group);
        } catch (Throwable t) {
            failure = t;
            log.error("write-behind persistence failed after {} entries; the node must stop", written, t);
            lock.lock();
            try {
                running = false;
                notFull.signalAll();
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
            for (Entry e : group) {
                if (e.barrier != null) {
                    e.barrier.countDown();
                }
            }
            return false;
        }
        lock.lock();
        try {
            for (Entry e : group) {
                if (e.barrier == null) {
                    written++;
                }
            }
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
        for (Entry e : group) {
            if (e.barrier != null) {
                e.barrier.countDown();
            } else {
                e.source.completed(e.key, e.version);
            }
        }
        return true;
    }

    private static void writeRuns(List<Entry> group) {
        WriteBehindKVSource runSource = null;
        List<Pair<byte[], byte[]>> puts = new ArrayList<>();
        List<byte[]> deletes = new ArrayList<>();
        Set<Bytes> deletedInRun = new HashSet<>();
        for (Entry e : group) {
            if (e.barrier != null) {
                continue;
            }
            boolean sourceChanges = runSource != null && runSource != e.source;
            boolean putAfterDelete = e.value != null && deletedInRun.contains(Bytes.wrap(e.key));
            if (sourceChanges || putAfterDelete) {
                flushRun(runSource, puts, deletes);
                puts = new ArrayList<>();
                deletes = new ArrayList<>();
                deletedInRun = new HashSet<>();
            }
            runSource = e.source;
            if (e.value == null) {
                deletes.add(e.key);
                deletedInRun.add(Bytes.wrap(e.key));
            } else {
                puts.add(Pair.of(e.key, e.value));
            }
        }
        flushRun(runSource, puts, deletes);
    }

    private static void flushRun(WriteBehindKVSource source, List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (source == null || (puts.isEmpty() && deletes.isEmpty())) {
            return;
        }
        source.delegate().batchWrite(puts, deletes);
    }
}
