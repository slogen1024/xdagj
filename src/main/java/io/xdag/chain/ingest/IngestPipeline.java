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
import java.util.concurrent.atomic.AtomicInteger;
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
 * pre-validation that blows up travels on as a {@link PreValidated} carrying the cause, and a pool
 * task that cannot even be scheduled is published straight to the commit queue rather than dropped.
 *
 * <p>The pipeline is single-use: {@link #start} once, {@link #stop} once. {@link #submit} throws
 * {@link IllegalStateException} before the start and after the stop, so the caller has to handle
 * that on the shutdown path. {@link #stop} is best-effort with a bound: it waits {@value
 * #DRAIN_SECONDS}s for the drain and {@value #JOIN_MILLIS}ms for the commit thread, then logs and
 * interrupts rather than hanging the shutdown.
 *
 * <p>Both the pool threads and the commit thread are daemons. Draining is {@link #stop}'s job, not
 * the JVM's: a non-daemon commit thread would turn every missed {@link #stop} — a failed start-up,
 * an aborted test, a surefire fork — into a process that never exits, and it would buy nothing,
 * because an abrupt exit is indistinguishable from {@code kill -9}, which the store already
 * tolerates (it is always a prefix of the write stream, and an unimported block is simply requested
 * from peers again).
 */
@Slf4j
public final class IngestPipeline {

    /**
     * How long the commit loop waits for a signal before re-checking. Every change to its condition
     * signals it, so this bound is only a safety net against a signal path being missed.
     */
    private static final long POLL_MILLIS = 50;

    /** How long {@link #stop} waits for the in-flight blocks to be committed. */
    private static final long DRAIN_SECONDS = 15;

    /** How long {@link #stop} waits for the commit thread to end once the pipeline is drained. */
    private static final long JOIN_MILLIS = 5_000;

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

    /** Whether {@link #start} has run; guarded by {@link #lock}. A pipeline is not restartable. */
    private boolean started;

    public IngestPipeline(int threads, int queueCapacity, Committer committer) {
        this(threads, queueCapacity, committer, pv -> { });
    }

    IngestPipeline(int threads, int queueCapacity, Committer committer, Consumer<PreValidated> beforeReady) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threads and queueCapacity must be positive");
        }
        AtomicInteger poolThreadNumber = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "xdag-ingest-" + poolThreadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.slots = new Semaphore(queueCapacity);
        this.committer = Objects.requireNonNull(committer, "committer");
        this.beforeReady = Objects.requireNonNull(beforeReady, "beforeReady");
        this.commitThread = new Thread(this::commitLoop, "xdag-ingest-commit");
        this.commitThread.setDaemon(true);
        this.commitThread.setUncaughtExceptionHandler(
                (t, e) -> log.error("ingest commit thread {} died, the pipeline no longer commits", t.getName(), e));
    }

    /**
     * Starts the commit thread. A pipeline is single-use: starting one that has already been started
     * — including one that has been stopped — throws, rather than leaving {@code running} true with
     * no commit thread behind it.
     */
    public void start() {
        lock.lock();
        try {
            if (started) {
                throw new IllegalStateException("ingest pipeline is not restartable");
            }
            started = true;
            // Started under the lock so that no submit can ever observe running == true with a
            // commit thread that has not been started yet.
            commitThread.start();
            running = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues a block; blocks the caller while {@code queueCapacity} blocks are in flight.
     *
     * <p>Two rules the caller must obey:
     *
     * <ul>
     * <li><b>Hold no monitor the committer needs.</b> The slot is held from {@code submit} until the
     * commit of that block returns, so a caller that blocks here at capacity while holding lock L
     * deadlocks the moment the committer also needs L. Concretely: {@code
     * SyncManager.validateAndAddNewBlock} is {@code synchronized} today, so the entry point that
     * calls {@code submit} must not be — the committer runs {@code tryToConnect} on the commit
     * thread and would be blocked out by the caller's own monitor.
     * <li><b>Do not touch the block afterwards.</b> The {@link io.xdag.core.Block} is handed to the
     * pool; {@code Block.parse()} and {@code Block.getHashLow()} are unsynchronized lazy mutators
     * guarded by a plain {@code boolean}, so a {@code parse()} on the caller thread racing the
     * pool's can duplicate the block's inputs/outputs — silent, consensus-visible corruption rather
     * than a crash.
     * </ul>
     *
     * @throws IllegalStateException before {@link #start}, after {@link #stop}, or if the commit
     *                               thread has died — the caller's shutdown path must expect it
     */
    public void submit(BlockWrapper wrapper) {
        Objects.requireNonNull(wrapper, "wrapper");
        slots.acquireUninterruptibly();
        long seq = -1;
        boolean commitThreadDead = false;
        lock.lock();
        try {
            if (running) {
                if (commitThread.isAlive()) {
                    seq = accepted++;
                } else {
                    commitThreadDead = true;
                }
            }
        } finally {
            lock.unlock();
        }
        if (seq < 0) {
            slots.release();
            throw new IllegalStateException(commitThreadDead
                    ? "ingest pipeline commit thread is dead"
                    : "ingest pipeline is not running");
        }
        long assigned = seq;
        try {
            pool.execute(() -> preValidate(assigned, wrapper));
        } catch (Throwable t) {
            // The sequence number is already handed out, so the commit loop is waiting for this
            // block: hand it over unvalidated rather than stalling the pipeline behind it forever.
            // Throwable, not RuntimeException: an Error out of the pool's thread creation would
            // otherwise strand the sequence and park every later submit at capacity.
            publish(PreValidated.failed(assigned, wrapper, t));
            if (t instanceof Error error) {
                throw error;
            }
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
        try {
            while (true) {
                PreValidated head = takeHead();
                if (head == null) {
                    return;
                }
                commit(head);
            }
        } finally {
            long outstanding;
            boolean wasRunning;
            lock.lock();
            try {
                outstanding = accepted - nextToCommit;
                wasRunning = running;
            } finally {
                lock.unlock();
            }
            if (wasRunning || outstanding > 0) {
                log.error("ingest commit loop exited while the pipeline was {}, {} block(s) not committed",
                        wasRunning ? "running" : "stopping", outstanding);
            }
        }
    }

    /** Waits for the block whose turn it is; null when the pipeline is stopped and drained. */
    private PreValidated takeHead() {
        lock.lock();
        try {
            while (true) {
                if (!ready.isEmpty()) {
                    long headSeq = ready.peek().seq();
                    if (headSeq == nextToCommit) {
                        return ready.poll();
                    }
                    if (headSeq < nextToCommit) {
                        // Cannot happen: a sequence is published once and committed once. Drop it
                        // rather than stall the whole pipeline behind a head that never matches.
                        PreValidated stale = ready.poll();
                        log.error("ingest dropped a duplicate pre-validated block {}, seq {} already committed ({})",
                                stale.hashLow(), stale.seq(), nextToCommit);
                        slots.release();
                        continue;
                    }
                }
                if (!running && nextToCommit >= accepted) {
                    return null;
                }
                try {
                    headReady.await(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void commit(PreValidated head) {
        try {
            ImportResult result = committer.commit(head);
            if (result == ImportResult.ERROR || result == ImportResult.INVALID_BLOCK) {
                log.debug("ingest rejected block {}: {}", head.hashLow(), result);
            }
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

    /** Blocks accepted but not yet committed, read as one consistent pair. */
    public long inFlight() {
        lock.lock();
        try {
            return accepted - nextToCommit;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting, commits everything already submitted, then stops the threads. Best-effort
     * with a bound: if the drain or the join times out the commit thread is interrupted, so it
     * cannot keep importing into a store the kernel is already closing.
     */
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
                log.error("ingest pipeline drain timed out after {}s, {} block(s) not committed",
                        DRAIN_SECONDS, inFlight());
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
        if (commitThread.isAlive()) {
            log.error("ingest commit thread still running after {}ms, interrupting it to keep it out of a closing store",
                    JOIN_MILLIS);
            commitThread.interrupt();
        }
    }
}
