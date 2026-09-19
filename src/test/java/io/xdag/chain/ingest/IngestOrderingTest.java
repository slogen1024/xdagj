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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class IngestOrderingTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    /**
     * A signed block that carries its own public key, so {@code verifiedKeys()} has real ECDSA work
     * to do — that work is what the pipeline moves off the blockchain lock. Returned re-parsed from
     * its 512 bytes, the way an arriving block reaches the node.
     */
    private Block block(int i) {
        Block b = new Block(config, 1_700_000_000_000L + i * 65536L, null, null, false,
                List.of(key), null, 0, XAmount.ZERO, null);
        b.signOut(key);
        return new Block(new XdagBlock(b.toBytes()));
    }

    /**
     * One block, parsed and hashed up front, so that handing it to several pre-validations at once
     * is safe: {@code Block.parse()} and {@code getHashLow()} are unsynchronized lazy mutators, and
     * from here on both are no-ops. {@code verifiedKeys()} is read-only and still does its real
     * ECDSA work on every pre-validation, which is what the many-round tests below want to exercise
     * without paying to build a fresh block each time.
     */
    private final Block shared = preParsed(block(0));

    private static Block preParsed(Block b) {
        b.parse();
        b.getHashLow();
        return b;
    }

    private BlockWrapper wrapShared() {
        return new BlockWrapper(shared, 0);
    }

    @Test(timeout = 30_000)
    public void commitOrderIsArrivalOrderEvenWhenPreValidationFinishesOutOfOrder() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        AtomicInteger slow = new AtomicInteger();
        IngestPipeline pipeline = new IngestPipeline(4, 64, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> {
            // every third block pre-validates slowly, so completion order != arrival order
            if (pv.seq() % 3 == 0 && slow.getAndIncrement() < 20) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) { }
            }
        });
        pipeline.start();
        try {
            for (int i = 0; i < 60; i++) {
                pipeline.submit(new BlockWrapper(block(i), 0));
            }
            assertTrue(pipeline.awaitIdle(30, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < 60; i++) {
            expected.add(i);
        }
        assertEquals(expected, committed);
    }

    @Test(timeout = 30_000)
    public void preValidationCarriesKeysAndNeverRejects() {
        PreValidated pv = PreValidator.inline(block(1));
        assertNull(pv.error());
        assertNotNull(pv.hashLow());
        assertEquals("an address block is signed by its own key", 1, pv.keys().size());
        assertTrue(pv.hasKeys());
    }

    @Test(timeout = 30_000)
    public void aThrowingPreValidationIsCarriedNotDropped() throws Exception {
        List<PreValidated> committed = Collections.synchronizedList(new ArrayList<>());
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            committed.add(pv);
            return ImportResult.INVALID_BLOCK;
        }, pv -> {
            if (pv.seq() == 1) {
                throw new IllegalStateException("boom");
            }
        });
        pipeline.start();
        try {
            for (int i = 0; i < 3; i++) {
                pipeline.submit(new BlockWrapper(block(i), 0));
            }
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        assertEquals(3, committed.size());
        assertNotNull("the failure travels with the block", committed.get(1).error());
        assertNull(committed.get(1).keys());
        assertNull(committed.get(0).error());
    }

    @Test(timeout = 30_000)
    public void stopCommitsEverythingAlreadySubmitted() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> { });
        pipeline.start();
        for (int i = 0; i < 20; i++) {
            pipeline.submit(new BlockWrapper(block(i), 0));
        }
        pipeline.stop();
        assertEquals(20, committed.size());
    }

    /**
     * {@code awaitIdle} must mean "the committer has returned for every submitted block", not "the
     * commit thread has dequeued them": a committer that blocks until released keeps the pipeline
     * busy, so an {@code awaitIdle} with a short timeout has to report false.
     */
    @Test(timeout = 30_000)
    public void awaitIdleWaitsForTheCommitterToReturn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> { });
        pipeline.start();
        try {
            pipeline.submit(new BlockWrapper(block(0), 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse("the committer has not returned yet",
                    pipeline.awaitIdle(200, TimeUnit.MILLISECONDS));
            assertEquals(0, pipeline.committed());
            release.countDown();
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
            assertEquals(1, committed.size());
            assertEquals(1, pipeline.committed());
        } finally {
            release.countDown();
            pipeline.stop();
        }
    }

    /**
     * A block {@code submit} accepted is committed even if {@code stop} runs concurrently: the
     * pipeline either refuses the submit outright or carries it all the way to the committer. The
     * window between "submit decided to accept" and "the pool task is queued" is a handful of
     * instructions, so the round is repeated until hitting it is near-certain rather than lucky.
     */
    @Test(timeout = 30_000)
    public void stopNeverLosesAnAcceptedBlock() throws Exception {
        for (int round = 0; round < 50; round++) {
            stopRaceRound(round);
        }
    }

    private void stopRaceRound(int round) throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(3, 4, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        });
        pipeline.start();
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch flowing = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            while (true) {
                try {
                    pipeline.submit(wrapShared());
                } catch (IllegalStateException stopped) {
                    return;
                }
                accepted.incrementAndGet();
                flowing.countDown();
            }
        }, "producer-" + round);
        producer.start();
        assertTrue(flowing.await(10, TimeUnit.SECONDS));
        pipeline.stop();
        producer.join(10_000);
        assertFalse("round " + round + ": the producer stopped once the pipeline did", producer.isAlive());
        assertTrue("round " + round + ": the race needs blocks in flight", accepted.get() > 0);
        assertEquals("round " + round + ": every accepted block reached the committer",
                accepted.get(), committed.size());
        assertEquals(accepted.get(), pipeline.submitted());
        assertEquals(accepted.get(), pipeline.committed());
        assertEquals(0, pipeline.inFlight());
    }

    /**
     * A committer that throws is the lock-side import blowing up: the pipeline logs it, counts the
     * block as committed, releases its slot and carries on with the next one. Without the slot
     * release the sixth submit below would never return.
     */
    @Test(timeout = 30_000)
    public void aThrowingCommitterDoesNotStallThePipeline() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(2, 4, pv -> {
            if (pv.seq() == 1) {
                throw new IllegalStateException("committer boom");
            }
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        });
        pipeline.start();
        try {
            for (int i = 0; i < 6; i++) {
                pipeline.submit(wrapShared());
            }
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        assertEquals(List.of(0L, 2L, 3L, 4L, 5L), committed);
        assertEquals(6, pipeline.committed());
    }

    /**
     * Permit conservation on the error path, with no slack to hide a leak: one slot, ten blocks,
     * every pre-validation failing. A slot lost on the failure path stalls the second submit.
     */
    @Test(timeout = 30_000)
    public void everyPreValidationFailingAtCapacityOneStillCommitsThemAll() throws Exception {
        List<PreValidated> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(2, 1, pv -> {
            committed.add(pv);
            return ImportResult.INVALID_BLOCK;
        }, pv -> {
            throw new IllegalStateException("boom " + pv.seq());
        });
        pipeline.start();
        try {
            for (int i = 0; i < 10; i++) {
                pipeline.submit(wrapShared());
            }
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        assertEquals(10, committed.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, committed.get(i).seq());
            assertNotNull(committed.get(i).error());
            assertNull(committed.get(i).keys());
        }
        assertEquals(0, pipeline.inFlight());
    }

    /**
     * Backpressure is real: with every slot held by a committer that will not return, the next
     * submit parks until one is released — which is exactly what makes the network thread wait
     * instead of queueing the whole world.
     */
    @Test(timeout = 30_000)
    public void submitBlocksWhileEverySlotIsInFlight() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch committing = new CountDownLatch(1);
        IngestPipeline pipeline = new IngestPipeline(2, 2, pv -> {
            committing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ImportResult.IMPORTED_BEST;
        });
        pipeline.start();
        CountDownLatch returned = new CountDownLatch(1);
        Thread third = null;
        try {
            pipeline.submit(wrapShared());
            pipeline.submit(wrapShared());
            assertTrue(committing.await(10, TimeUnit.SECONDS));
            third = new Thread(() -> {
                pipeline.submit(wrapShared());
                returned.countDown();
            }, "third-submit");
            third.start();
            assertFalse("the third submit must wait for a slot",
                    returned.await(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue("and return once one is released", returned.await(10, TimeUnit.SECONDS));
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            if (third != null) {
                third.join(10_000);
            }
            pipeline.stop();
        }
        assertEquals(3, pipeline.committed());
    }

    /** Many submitters, one commit order: the committed sequence is exactly 0..399, no gaps. */
    @Test(timeout = 30_000)
    public void concurrentSubmittersStillCommitOneContiguousSequence() throws Exception {
        int submitters = 4;
        int each = 100;
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(4, 32, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        });
        pipeline.start();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int t = 0; t < submitters; t++) {
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < each; i++) {
                        pipeline.submit(wrapShared());
                    }
                }, "submitter-" + t);
                threads.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : threads) {
                th.join(20_000);
                assertFalse("submitter finished", th.isAlive());
            }
            assertTrue(pipeline.awaitIdle(20, TimeUnit.SECONDS));
        } finally {
            go.countDown();
            pipeline.stop();
        }
        assertEquals(submitters * each, committed.size());
        for (int i = 0; i < committed.size(); i++) {
            assertEquals(i, (long) committed.get(i));
        }
    }

    /** A pipeline is single-use: a restart must be refused, not leave it accepting with no committer. */
    @Test(timeout = 30_000)
    public void aStoppedPipelineIsNotRestartable() {
        IngestPipeline pipeline = new IngestPipeline(1, 2, pv -> ImportResult.IMPORTED_BEST);
        pipeline.start();
        pipeline.stop();
        try {
            pipeline.start();
            fail("a stopped pipeline must not restart");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            pipeline.submit(wrapShared());
            fail("a stopped pipeline must not accept blocks");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(0, pipeline.submitted());
    }
}
