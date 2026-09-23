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
 * The one exception is the commit loop itself ending — an {@code Error}, an interrupt: what was in
 * flight is then lost, but the loop hands those blocks' backpressure permits back on its way out and
 * every later {@link #submit} is refused with {@code isCommitterDead()}, so the node reports the
 * death instead of parking its I/O threads on a semaphore nothing can release any more.
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

    /**
     * {@link #submit} refusing a block. The two causes need different handling by the caller, so
     * they are distinguishable rather than one opaque {@code IllegalStateException}: a refusal
     * during shutdown is routine, while a dead commit thread means the node has silently stopped
     * importing anything and an operator has to be told.
     */
    public static final class SubmitRejectedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final boolean committerDead;

        SubmitRejectedException(boolean committerDead) {
            super(committerDead ? "ingest pipeline commit thread is dead" : "ingest pipeline is not running");
            this.committerDead = committerDead;
        }

        /** True when the commit thread has died: every further block is dropped until a restart. */
        public boolean isCommitterDead() {
            return committerDead;
        }
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

    /**
     * Set by the commit loop on its way out, guarded by {@link #lock}. From that moment nothing will
     * ever release a backpressure permit again, so {@link #submit} has to refuse rather than park.
     */
    private boolean committerDead;

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
     * deadlocks the moment the committer also needs L. Concretely: the import inside {@code
     * SyncManager.validateAndAddNewBlock} is {@code synchronized} on the {@code SyncManager}, so the
     * entry point that calls {@code submit} must not be — the committer runs {@code tryToConnect} on
     * the commit thread and would be blocked out by the caller's own monitor.
     * <li><b>Do not touch the block afterwards.</b> The {@link io.xdag.core.Block} is handed to the
     * pool; {@code Block.parse()} and {@code Block.getHashLow()} are unsynchronized lazy mutators
     * guarded by a plain {@code boolean}, so a {@code parse()} on the caller thread racing the
     * pool's can duplicate the block's inputs/outputs — silent, consensus-visible corruption rather
     * than a crash.
     * </ul>
     *
     * <p>A dead commit thread is refused, never waited on: the refusal is decided before the permit
     * is taken, so this returns (by throwing) instead of parking on a semaphore nothing can release
     * any more.
     *
     * @throws SubmitRejectedException before {@link #start}, after {@link #stop}, or if the commit
     *                                 thread has died — the caller's shutdown path must expect it,
     *                                 and must tell the two apart via {@code isCommitterDead()}
     */
    public void submit(BlockWrapper wrapper) {
        Objects.requireNonNull(wrapper, "wrapper");
        // Tested BEFORE the acquire, which is the whole point of committerDead: once the commit
        // loop has exited nothing releases a permit ever again, so on a saturated pipeline the
        // acquire below would park this thread -- a netty I/O thread -- for good, and the
        // dead-committer warning the caller writes on this very exception would never be reached.
        SubmitRejectedException rejected = rejection();
        if (rejected != null) {
            throw rejected;
        }
        // Uninterruptibly on purpose: this is the netty I/O thread's own backpressure, exactly as
        // the synchronized import blocked it before SP0b-2, and an interrupt here would have to
        // drop the block. Nothing unsticks it but a commit or the commit loop handing the permits
        // back on its way out -- netty's own shutdown will not -- so stop() is what has to be
        // reachable, and it is: the commit thread never calls submit.
        slots.acquireUninterruptibly();
        long seq = -1;
        lock.lock();
        try {
            rejected = rejectionLocked();
            if (rejected == null) {
                seq = accepted++;
            }
        } finally {
            lock.unlock();
        }
        if (seq < 0) {
            // A stop(), or the commit loop's own exit, raced the acquire above.
            slots.release();
            throw rejected;
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

    /** {@link #rejectionLocked} for a caller that does not already hold {@link #lock}. */
    private SubmitRejectedException rejection() {
        lock.lock();
        try {
            return rejectionLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The refusal this {@link #submit} deserves, or null while the pipeline still accepts blocks.
     * Must be called holding {@link #lock}.
     *
     * <p>The two causes are told apart by {@code running}: a dead committer is only news while the
     * pipeline is supposed to be importing, whereas after {@link #stop} the refusal is the routine
     * shutdown one the caller must not warn an operator about.
     */
    private SubmitRejectedException rejectionLocked() {
        if (running && !committerDead && commitThread.isAlive()) {
            return null;
        }
        return new SubmitRejectedException(running);
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
                // Nothing will commit anything after this, so nothing will release a permit after
                // this either: refuse every later submit rather than let it park forever on the
                // acquire, which on a saturated pipeline is every netty I/O thread at once.
                committerDead = true;
            } finally {
                lock.unlock();
            }
            // The permits of the blocks that were in flight, handed back for the submitters already
            // parked on the acquire: they wake, see committerDead and are refused. Safe to release
            // beyond the capacity only because no permit is ever taken again.
            if (outstanding > 0) {
                slots.release((int) outstanding);
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
            // CHAIN_FEE_POLICY is listed although this branch cannot reach it today: the chunk fee
            // gate sits in front of submit(), as SP0b-3 §5.3 requires, so a refused block is never
            // handed to the pipeline at all and the refusal is logged there instead. It is listed so
            // that a gate moved inside the commit later is logged rather than silently swallowed by
            // a condition that only ever knew about two of the three ways a block can be turned away.
            if (result == ImportResult.ERROR || result == ImportResult.INVALID_BLOCK
                    || result == ImportResult.CHAIN_FEE_POLICY) {
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
            // The interrupt can land inside a setMain. commit() catches the Throwable that comes
            // back, so the import is abandoned rather than propagated: on disk that leaves the
            // store a prefix of the write stream with an incomplete transition at the tip, which is
            // what SP0b-1's start-up gate and --repairchain exist to find and unwind. It is still
            // better than never closing the store at all.
            log.error("ingest commit thread still running after {}ms, interrupting it to keep it out of a closing "
                    + "store; an import in flight is abandoned and may leave an incomplete main-block transition "
                    + "for the start-up check to unwind", JOIN_MILLIS);
            commitThread.interrupt();
        }
    }
}
