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
     * pipeline either refuses the submit outright or carries it all the way to the committer.
     */
    @Test(timeout = 30_000)
    public void stopNeverLosesAnAcceptedBlock() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(3, 4, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> { });
        pipeline.start();
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch flowing = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            while (true) {
                try {
                    pipeline.submit(new BlockWrapper(block(accepted.get() % 8), 0));
                } catch (IllegalStateException stopped) {
                    return;
                }
                accepted.incrementAndGet();
                flowing.countDown();
            }
        }, "producer");
        producer.start();
        assertTrue(flowing.await(10, TimeUnit.SECONDS));
        pipeline.stop();
        producer.join(10_000);
        assertFalse("the producer stopped once the pipeline did", producer.isAlive());
        assertTrue("the race is only meaningful with blocks in flight", accepted.get() > 0);
        assertEquals("every accepted block reached the committer", accepted.get(), committed.size());
        assertEquals(accepted.get(), pipeline.submitted());
        assertEquals(accepted.get(), pipeline.committed());
    }
}
