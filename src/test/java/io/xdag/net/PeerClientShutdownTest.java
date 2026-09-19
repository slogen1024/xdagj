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
package io.xdag.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.ECKeyPair;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Closing the client must not be able to park the whole shutdown.
 *
 * <p>{@code close()} used to be {@code shutdownGracefully()} followed by an unbounded, and
 * uninterruptible, {@code syncUninterruptibly()} on the termination future. A single worker that
 * never finishes — a slow peer, a blocking store call on an event loop — stopped the node there for
 * good, with no log line to say where it had gone.
 */
public class PeerClientShutdownTest {

    /** Far longer than the client's own bound, and far shorter than "never". */
    private static final long CLOSE_MUST_RETURN_WITHIN_MS = 30_000;

    /**
     * A worker stuck in a task of its own never completes the group's termination future: the
     * promise is set by the event loop thread as it leaves its run loop, and this one never does.
     * That is exactly the shape the old {@code close()} waited on forever.
     */
    @Test
    public void closeReturnsEvenWhenAWorkerNeverTerminates() throws Exception {
        Config config = new DevnetConfig();
        PeerClient client = new PeerClient(config, ECKeyPair.generate());

        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.getWorkerGroup().next().execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue("no worker took the blocking task", occupied.await(10, TimeUnit.SECONDS));

        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean leftInterrupted = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            client.close();
            leftInterrupted.set(Thread.currentThread().isInterrupted());
            closed.countDown();
        }, "peer-client-closer");
        // Daemon so a regression cannot wedge the surefire JVM the way it wedges a node.
        closer.setDaemon(true);
        closer.start();
        try {
            assertTrue("close() had not returned after " + CLOSE_MUST_RETURN_WITHIN_MS
                            + "ms with a worker that cannot terminate",
                    closed.await(CLOSE_MUST_RETURN_WITHIN_MS, TimeUnit.MILLISECONDS));
            // The stopping thread carries on with a clear interrupt status: nobody interrupted
            // it, so close() must not have raised the flag on its own.
            assertFalse("close() left the calling thread interrupted", leftInterrupted.get());
        } finally {
            release.countDown();
        }
    }
}
