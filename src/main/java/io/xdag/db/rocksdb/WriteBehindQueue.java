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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * written in order and one group is in flight at a time, so the databases always hold a prefix of
 * the stream: a crash loses at most the entries the writer has not yet written (bounded by
 * {@code maxPending} plus one group). Entries are never coalesced per source or per key — that
 * would break the prefix property. The import pattern alternates sources per block (TIME, BLOCK,
 * INDEX, ...), so runs are typically short: the layer's win is asynchrony, not batching; measure
 * before tuning {@code flushEntries}.
 *
 * <p>"Written" means handed to the database (RocksDB WAL, not fsync'd; durability is unchanged
 * from before SP0b-2).
 *
 * <p>Keys and values are retained by reference until they are written (and in the sources' pending
 * maps and read caches); callers must not mutate them after {@code put}.
 *
 * <p>Concurrency: any number of producers may enqueue; the version, the source's pending-map entry
 * and the queue position are assigned in one critical section, so producers are totally ordered
 * by the queue lock. The import path is nevertheless single-writer (it runs under the blockchain
 * lock) and {@link #direct} relies on that: see {@link PersistControl#direct}.
 *
 * <p>Failure: any throwable out of a write, and an interrupt of the writer thread, marks the queue
 * failed: every waiter (producers parked on backpressure, flushers waiting on a barrier or on the
 * in-flight group) is woken, and every later put, flush, drain or start throws
 * {@link IllegalStateException} with that cause. Nothing is dropped silently.
 *
 * <p>Manual mode ({@code threaded == false}, tests and offline tools): there is no writer thread.
 * {@link #flushSync()} drains on the calling thread, a producer that hits {@code maxPending} drains
 * one group itself instead of parking, and tests step the queue with {@link #drainOnce()}. A
 * threaded queue behaves the same way while no writer is running (before {@link #start()} and
 * after {@link #stop()}).
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
    private final boolean threaded;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Condition idle = lock.newCondition();
    private final ThreadLocal<Boolean> bypass = new ThreadLocal<>();
    private volatile Throwable failure;
    private volatile boolean running;   // written under lock
    private volatile Thread writer;
    private long versions;              // guarded by lock
    private long enqueued;              // guarded by lock
    private long written;               // guarded by lock
    private int inFlight;               // guarded by lock: groups polled but not yet written (0 or 1)
    private int barriers;               // guarded by lock: barriers currently in the queue
    private long firstEnqueuedNanos;    // guarded by lock

    /**
     * @param maxPending   backpressure bound: while this many entries are queued a producer parks
     *                     (threaded, writer running) or drains one group itself (otherwise). Memory
     *                     is bounded by {@code maxPending} × (key + value): BLOCK values are 512 B
     *                     and INDEX values a few hundred bytes, so 4096 entries is a few MB. A
     *                     parked producer keeps whatever it holds — on the import path that is the
     *                     blockchain lock — so size it for the writer's burst capacity.
     * @param flushEntries the writer takes at most this many entries per group
     * @param flushMs      the writer writes a partial group once its oldest entry is this old
     * @param threaded     {@code true}: a writer thread drains after {@link #start()};
     *                     {@code false}: manual mode, see the class comment
     */
    public WriteBehindQueue(int maxPending, int flushEntries, long flushMs, boolean threaded) {
        if (maxPending < 1 || flushEntries < 1 || flushMs < 1) {
            throw new IllegalArgumentException("maxPending, flushEntries and flushMs must be positive");
        }
        this.maxPending = maxPending;
        this.flushEntries = flushEntries;
        this.flushNanos = TimeUnit.MILLISECONDS.toNanos(flushMs);
        this.threaded = threaded;
    }

    /**
     * Starts the writer thread. Not a daemon: a JVM must not exit with queued writes. Idempotent
     * while running; throws after a failure and on a manual-mode queue.
     */
    public synchronized void start() {
        if (!threaded) {
            throw new IllegalStateException("a manual-mode write-behind queue has no writer thread");
        }
        checkFailed();
        if (running) {
            return;
        }
        lock.lock();
        try {
            running = true;
        } finally {
            lock.unlock();
        }
        Thread t = new Thread(this::loop, "xdag-persist");
        t.setDaemon(false);
        writer = t;
        t.start();
        log.info("write-behind writer started (maxPending={}, flushEntries={}, flushMs={})",
                maxPending, flushEntries, TimeUnit.NANOSECONDS.toMillis(flushNanos));
    }

    /**
     * Flushes everything and stops the writer thread. Idempotent. Throws if the queue has failed,
     * so a caller that closes the databases afterwards must do so in a {@code finally}.
     */
    public synchronized void stop() {
        if (!running) {
            flushSync(); // no writer: drains on the caller; throws after a failure
            return;
        }
        Thread t = writer;
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
            if (t.isAlive()) {
                log.warn("write-behind writer did not stop within 30 s; {} entries still queued", pending());
            } else {
                log.info("write-behind writer stopped: {} entries enqueued, {} written", enqueuedCount(), writtenCount());
            }
        }
    }

    /** Test hook: true while the writer thread is alive. */
    boolean writerAlive() {
        Thread t = writer;
        return t != null && t.isAlive();
    }

    /**
     * Test hook: drop every queued write without writing it (a simulated crash), release every
     * waiter and let the writer thread exit.
     */
    public void abandon() {
        lock.lock();
        try {
            for (Entry e : queue) {
                if (e.barrier != null) {
                    e.barrier.countDown();
                }
            }
            queue.clear();
            barriers = 0;
            running = false;
            notEmpty.signalAll();
            notFull.signalAll();
            idle.signalAll();
        } finally {
            lock.unlock();
        }
        Thread t = writer;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

    /** Test hook: number of entries ever enqueued (barriers excluded). */
    public long enqueuedCount() {
        lock.lock();
        try {
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    /** Test hook: number of entries written to the databases so far (barriers excluded). */
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
        return Boolean.TRUE.equals(bypass.get());
    }

    @Override
    public <T> T direct(Supplier<T> body) {
        if (isBypass()) {
            return body.get();
        }
        flushSync();
        bypass.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            bypass.remove();
        }
    }

    private void checkFailed() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("write-behind persistence failed; the node must stop", f);
        }
    }

    /**
     * Marks the queue failed and wakes every waiter: producers parked on backpressure, flushers
     * waiting on a barrier (all queued barriers are released, not only the failing group's) and
     * anyone waiting for the in-flight group. The first failure is kept as the cause.
     */
    private void fail(Throwable t) {
        lock.lock();
        try {
            if (failure == null) {
                failure = t;
            }
            running = false;
            for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.barrier != null) {
                    e.barrier.countDown();
                    it.remove();
                }
            }
            barriers = 0;
            notFull.signalAll();
            notEmpty.signalAll();
            idle.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends one write. Under the queue lock, after the backpressure wait: assigns the version,
     * records it in the source's pending map and read cache, and appends the entry — one critical
     * section, so the pending map and the stream can never disagree about the order of two writes.
     */
    void enqueue(WriteBehindKVSource source, byte[] key, byte[] value) {
        lock.lock();
        try {
            checkFailed();
            while (queue.size() >= maxPending) {
                if (running) {
                    notFull.await();
                } else {
                    // No writer thread: drain one group on the caller instead of parking forever.
                    lock.unlock();
                    try {
                        drainOnce();
                    } finally {
                        lock.lock();
                    }
                }
                checkFailed();
            }
            long version = ++versions;
            source.recordPending(key, value, version);
            if (queue.isEmpty()) {
                firstEnqueuedNanos = System.nanoTime();
            }
            queue.addLast(new Entry(source, key, value, version));
            enqueued++;
            notEmpty.signal(); // only the writer thread waits on notEmpty
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
        CountDownLatch barrier;
        lock.lock();
        try {
            while (true) {
                checkFailed();
                if (queue.isEmpty()) {
                    if (inFlight == 0) {
                        return; // quiescent: everything queued before the call is written
                    }
                    idle.await(); // a polled group is still being written
                } else if (running) {
                    barrier = new CountDownLatch(1);
                    queue.addLast(new Entry(barrier));
                    barriers++;
                    notEmpty.signal();
                    break;
                } else {
                    lock.unlock();
                    try {
                        drainOnce();
                    } finally {
                        lock.lock();
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the writer", ie);
        } finally {
            lock.unlock();
        }
        try {
            barrier.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the writer", ie);
        }
        checkFailed();
    }

    private void loop() {
        try {
            while (true) {
                lock.lock();
                try {
                    while (queue.isEmpty() && running) {
                        notEmpty.await();
                    }
                    if (queue.isEmpty()) {
                        return; // stopped and drained
                    }
                    // Wait for a full group, a barrier anywhere in the queue, the flush timer or
                    // stop(). A loop rather than a single await: every enqueue signals notEmpty,
                    // and an early wake-up must not cut the group short before the timer runs out.
                    while (running && barriers == 0 && queue.size() < flushEntries) {
                        long remaining = flushNanos - (System.nanoTime() - firstEnqueuedNanos);
                        if (remaining <= 0) {
                            break;
                        }
                        notEmpty.awaitNanos(remaining);
                    }
                } finally {
                    lock.unlock();
                }
                if (!drainOnce() && failure != null) {
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail(new IllegalStateException("write-behind writer interrupted with " + pending() + " entries queued", ie));
        } catch (Throwable t) {
            fail(t); // nothing may kill the writer silently
        }
    }

    /**
     * Test hook and the writer's step: writes one group (up to {@code flushEntries} entries, or up
     * to and including the first barrier). One group is in flight at a time — a second drainer
     * waits — so groups reach the databases in stream order. Returns false when there was nothing
     * to write or the write failed.
     */
    public boolean drainOnce() {
        List<Entry> group = new ArrayList<>();
        lock.lock();
        try {
            while (inFlight > 0 && failure == null) {
                idle.await();
            }
            if (failure != null) {
                return false;
            }
            while (!queue.isEmpty() && group.size() < flushEntries) {
                Entry e = queue.pollFirst();
                group.add(e);
                if (e.barrier != null) {
                    barriers--;
                    break;
                }
            }
            if (group.isEmpty()) {
                return false;
            }
            inFlight++;
            if (!queue.isEmpty()) {
                // The timer restarts for the entries left behind: a partial remainder waits up to
                // another flushMs (or until it fills up) before it is written.
                firstEnqueuedNanos = System.nanoTime();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the in-flight group", ie);
        } finally {
            lock.unlock();
        }
        boolean ok = false;
        try {
            writeRuns(group);
            ok = true;
        } catch (Throwable t) {
            log.error("write-behind persistence failed after {} written entries; the node must stop", writtenCount(), t);
            fail(t);
        } finally {
            finish(group, ok);
        }
        return ok;
    }

    /** Retires a written group: pending entries first, then the counters, then the barriers. */
    private void finish(List<Entry> group, boolean ok) {
        int entries = 0;
        for (Entry e : group) {
            if (e.barrier == null) {
                entries++;
                if (ok) {
                    // Before any waiter can observe the group as written: a flusher released by the
                    // barrier or by idle must never see a stale pending entry.
                    e.source.completed(e.key, e.version);
                }
            }
        }
        lock.lock();
        try {
            if (ok) {
                written += entries;
                notFull.signalAll();
            }
            inFlight--;
            idle.signalAll();
        } finally {
            lock.unlock();
        }
        for (Entry e : group) {
            if (e.barrier != null) {
                e.barrier.countDown();
            }
        }
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
