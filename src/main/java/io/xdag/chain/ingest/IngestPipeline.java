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
package io.xdag.chain.ingest;

import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Parallel pre-validation, in-order commit. {@link #submit} assigns the next sequence number and
 * hands the block to the pool; results are re-ordered by sequence and one commit thread calls the
 * committer strictly in arrival order (P5). Backpressure: {@code queueCapacity} blocks may be in
 * flight; a further {@link #submit} blocks the caller, which is what the network thread did before
 * SP0b-2 when it called the synchronized import directly.
 *
 * <p>A block is <em>accepted</em> the moment {@link #submit} hands out its sequence number, and an
 * accepted block always reaches the committer: {@link #stop} stops accepting and then drains, a
 * pre-validation that blows up travels on as a {@link PreValidated} carrying the cause, and even a
 * rejected pool task is published straight to the commit queue rather than dropped.
 *
 * <p>Both the pool threads and the commit thread are daemons. Draining is {@link #stop}'s job, not
 * the JVM's: the commit loop never ends by itself while the pipeline runs, so a non-daemon commit
 * thread would turn every missed {@link #stop} — a failed start-up, an aborted test, a surefire
 * fork — into a process that never exits. Losing the un-committed tail at an abrupt exit costs
 * nothing: the store is always a prefix of the write stream and an unimported block is simply
 * requested from peers again.
 */
@Slf4j
public final class IngestPipeline {

    /** How long the commit loop sleeps between re-checks when it is waiting for the head block. */
    private static final long POLL_MILLIS = 50;

    /** How long {@link #stop} waits for the in-flight blocks to be committed. */
    private static final long DRAIN_SECONDS = 60;

    /** How long {@link #stop} waits for the commit thread to end once the pipeline is drained. */
    private static final long JOIN_MILLIS = 10_000;

    /** The lock-side import; must not throw for a block that would merely be rejected. */
    public interface Committer {
        ImportResult commit(PreValidated pv);
    }

    private final ExecutorService pool;
    private final Thread commitThread;
    private final Semaphore slots;
    private final Committer committer;
    private final Consumer<PreValidated> beforeReady; // test hook, may throw
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition headReady = lock.newCondition();
    private final PriorityQueue<PreValidated> ready =
            new PriorityQueue<>(Comparator.comparingLong(PreValidated::seq));

    /** Sequence numbers handed out so far; guarded by {@link #lock}. */
    private long accepted;

    /** Blocks the committer has returned for, and so the next sequence to commit; guarded by {@link #lock}. */
    private long nextToCommit;

    /** Whether {@link #submit} still accepts blocks; guarded by {@link #lock}. */
    private boolean running;

    public IngestPipeline(int threads, int queueCapacity, Committer committer) {
        this(threads, queueCapacity, committer, pv -> { });
    }

    IngestPipeline(int threads, int queueCapacity, Committer committer, Consumer<PreValidated> beforeReady) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threads and queueCapacity must be positive");
        }
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "xdag-ingest");
            t.setDaemon(true);
            return t;
        });
        this.slots = new Semaphore(queueCapacity);
        this.committer = Objects.requireNonNull(committer, "committer");
        this.beforeReady = Objects.requireNonNull(beforeReady, "beforeReady");
        this.commitThread = new Thread(this::commitLoop, "xdag-ingest-commit");
        this.commitThread.setDaemon(true);
    }

    public void start() {
        lock.lock();
        try {
            if (running) {
                throw new IllegalStateException("ingest pipeline is already running");
            }
            running = true;
        } finally {
            lock.unlock();
        }
        commitThread.start();
    }

    /** Queues a block; blocks the caller while {@code queueCapacity} blocks are in flight. */
    public void submit(BlockWrapper wrapper) {
        Objects.requireNonNull(wrapper, "wrapper");
        slots.acquireUninterruptibly();
        long seq = -1;
        lock.lock();
        try {
            if (running) {
                seq = accepted++;
            }
        } finally {
            lock.unlock();
        }
        if (seq < 0) {
            slots.release();
            throw new IllegalStateException("ingest pipeline is not running");
        }
        long assigned = seq;
        try {
            pool.execute(() -> preValidate(assigned, wrapper));
        } catch (RuntimeException e) {
            // The sequence number is already handed out, so the commit loop is waiting for this
            // block: hand it over unvalidated rather than stalling the pipeline behind it.
            publish(PreValidated.failed(assigned, wrapper, e));
        }
    }

    private void preValidate(long seq, BlockWrapper wrapper) {
        PreValidated pv;
        try {
            pv = PreValidator.compute(seq, wrapper, true);
            beforeReady.accept(pv);
        } catch (Throwable t) {
            pv = PreValidated.failed(seq, wrapper, t);
        }
        publish(pv);
    }

    private void publish(PreValidated pv) {
        lock.lock();
        try {
            ready.add(pv);
            headReady.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void commitLoop() {
        while (true) {
            PreValidated head;
            lock.lock();
            try {
                while (ready.isEmpty() || ready.peek().seq() != nextToCommit) {
                    if (!running && nextToCommit >= accepted) {
                        return;
                    }
                    try {
                        headReady.await(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                head = ready.poll();
            } finally {
                lock.unlock();
            }
            try {
                committer.commit(head);
            } catch (Throwable t) {
                log.error("ingest commit failed for block {}", head.hashLow(), t);
            } finally {
                // Only now is the block committed: advance the counter after the committer has
                // returned, so awaitIdle() cannot report idle while an import is still running.
                lock.lock();
                try {
                    nextToCommit = head.seq() + 1;
                    headReady.signalAll();
                } finally {
                    lock.unlock();
                }
                slots.release();
            }
        }
    }

    /** True once the committer has returned for every submitted block; false on timeout. */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (nextToCommit < accepted) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                headReady.awaitNanos(left);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public long submitted() {
        lock.lock();
        try {
            return accepted;
        } finally {
            lock.unlock();
        }
    }

    public long committed() {
        lock.lock();
        try {
            return nextToCommit;
        } finally {
            lock.unlock();
        }
    }

    /** Stops accepting, commits everything already submitted, then stops the threads. */
    public void stop() {
        lock.lock();
        try {
            running = false;
            headReady.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            if (!awaitIdle(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ingest pipeline drain timed out, {} block(s) not committed", submitted() - committed());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();
        try {
            commitThread.join(JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
