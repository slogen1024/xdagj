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

package io.xdag.net.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.XdagStats;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.net.message.consensus.BlocksRequestMessage;
import io.xdag.net.message.consensus.SyncBlockRequestMessage;
import io.xdag.net.message.p2p.InitMessage;
import io.xdag.net.message.p2p.PingMessage;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.MutableBytes;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the {@link MessageQueue} invariant that the ingest commit thread depends on: no send ever
 * blocks unboundedly on a peer's queue.
 *
 * <p>The queue here is deliberately never {@code activate()}d. Without the timer task nothing
 * drains it, which reproduces exactly the two ways the real one wedges — a drain starved by
 * prioritized traffic, and a {@code deactivate()}d channel whose drain task is already cancelled —
 * and in the second of those no slot ever frees again, so the old {@code queue.put} parked forever.
 */
public class MessageQueueTest {

    /** The bounded queue's capacity, as declared in {@link MessageQueue}. */
    private static final int CAPACITY = 8192;

    /**
     * Ceiling for a single bounded send. Generously above {@code SEND_OFFER_TIMEOUT_MS} so a loaded
     * CI box cannot make this flaky, and still far enough below the test timeout to distinguish
     * "waited its deadline and gave up" from "blocked".
     */
    private static final long BOUNDED_CEILING_MS = 2_000;

    private Config config;
    private MessageQueue queue;

    @Before
    public void setUp() {
        config = new DevnetConfig();
        queue = new MessageQueue(config);
    }

    /**
     * The message at the heart of the defect. {@code SyncManager.releaseWaiters} sends this from the
     * single ingest commit thread, once per active channel, on every {@code NO_PARENT} — which is
     * the common case during historical sync.
     */
    private static SyncBlockRequestMessage syncBlockRequest() {
        return new SyncBlockRequestMessage(MutableBytes.create(32),
                new XdagStats(BigInteger.ZERO, 0, 0, 0, 0));
    }

    private void fillToCapacity() {
        for (int i = 0; i < CAPACITY; i++) {
            queue.sendMessage(new PingMessage());
        }
        assertEquals("the queue should be exactly full, with nothing dropped yet", CAPACITY, queue.size());
        assertEquals(0, queue.getDroppedMessageCount());
    }

    @Test
    public void syncBlockRequestIsNotPrioritized() {
        // If this ever changes, the defect is gone by another route and this whole test is moot --
        // better to be told than to keep asserting a drop that no longer happens.
        assertFalse(config.getNodeSpec().getNetPrioritizedMessages().contains(MessageCode.SYNCBLOCK_REQUEST));
    }

    @Test(timeout = 30_000)
    public void sendOnAFullQueueReturnsWithinTheBoundInsteadOfBlocking() {
        fillToCapacity();

        long startNanos = System.nanoTime();
        queue.sendMessage(syncBlockRequest());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue("send returned after " + elapsedMs + " ms; it must not outrun its bounded wait",
                elapsedMs >= MessageQueue.SEND_OFFER_TIMEOUT_MS - 10);
        assertTrue("send took " + elapsedMs + " ms; the bounded wait is "
                        + MessageQueue.SEND_OFFER_TIMEOUT_MS + " ms",
                elapsedMs < BOUNDED_CEILING_MS);
        assertEquals("the dropped message must be visible to an operator", 1, queue.getDroppedMessageCount());
        assertEquals("a drop must not grow the queue past its capacity", CAPACITY, queue.size());
    }

    @Test(timeout = 30_000)
    public void everyDropIsCounted() {
        fillToCapacity();

        for (int i = 0; i < 3; i++) {
            queue.sendMessage(syncBlockRequest());
        }

        assertEquals(3, queue.getDroppedMessageCount());
    }

    /** The prioritized lane is unbounded and untouched by this change: it still never waits. */
    @Test(timeout = 30_000)
    public void prioritizedMessagesStillBypassTheFullQueue() {
        fillToCapacity();

        long startNanos = System.nanoTime();
        queue.sendMessage(new BlocksRequestMessage(0, 1, new XdagStats(BigInteger.ZERO, 0, 0, 0, 0)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals("a prioritized message must never be dropped", 0, queue.getDroppedMessageCount());
        assertTrue("a prioritized message must not wait, it took " + elapsedMs + " ms",
                elapsedMs < MessageQueue.SEND_OFFER_TIMEOUT_MS);
        assertEquals(CAPACITY + 1, queue.size());
    }

    /**
     * The handshake trio is sent once per connection and retried by nothing, so it must never be
     * dropped: it takes the unbounded lane even though it is not in {@code netPrioritizedMessages}.
     */
    @Test(timeout = 30_000)
    public void handshakeMessagesAreNeverDroppedOrDelayed() {
        fillToCapacity();

        long startNanos = System.nanoTime();
        queue.sendMessage(new InitMessage(CryptoProvider.nextBytes(InitMessage.SECRET_LENGTH),
                System.currentTimeMillis()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals("a handshake message must never be dropped", 0, queue.getDroppedMessageCount());
        assertTrue("a handshake message must not even wait, it took " + elapsedMs + " ms",
                elapsedMs < MessageQueue.SEND_OFFER_TIMEOUT_MS);
        assertEquals("it should have gone to the unbounded lane", CAPACITY + 1, queue.size());
    }

    /**
     * An interrupt on the sending thread is a shutdown, not backpressure: the send gives the message
     * up, restores the flag and returns, rather than throwing on a thread that is already stopping.
     */
    @Test(timeout = 30_000)
    public void anInterruptedSendDropsInsteadOfThrowing() throws Exception {
        fillToCapacity();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flagSurvived = new AtomicBoolean();
        Thread sender = new Thread(() -> {
            Thread.currentThread().interrupt();
            queue.sendMessage(syncBlockRequest());
            flagSurvived.set(Thread.currentThread().isInterrupted());
        });
        sender.setUncaughtExceptionHandler((t, e) -> thrown.set(e));
        sender.start();
        sender.join(BOUNDED_CEILING_MS);

        assertFalse("the interrupted send must have returned", sender.isAlive());
        assertNull("sendMessage must not throw on interrupt", thrown.get());
        assertTrue("the interrupt flag must survive the send", flagSurvived.get());
        assertEquals(1, queue.getDroppedMessageCount());
    }
}
