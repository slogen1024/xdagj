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

import static io.xdag.config.Constants.SEND_PERIOD;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.xdag.config.Config;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.xdag.net.message.p2p.DisconnectMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

/**
 * The per-connection outbound message queue.
 *
 * <p><b>Invariant: {@link #sendMessage} never blocks unboundedly.</b> It is not only netty I/O
 * threads that send: the single ingest commit thread sends too, by way of
 * {@code SyncManager.releaseWaiters} -> {@code XdagP2pHandler.sendGetBlock}, which fans a
 * {@code NO_PARENT} out to <i>every</i> active channel. Parking that one thread on one slow peer
 * stops the whole node importing — and it parks holding the {@code SyncManager} monitor, so
 * wallet and RPC submission stop with it, while {@code isCommitterDead()} stays false and nothing
 * is logged. Only a restart recovers. So the bounded {@link #queue} is offered to with a deadline
 * ({@link #SEND_OFFER_TIMEOUT_MS}) and the message is dropped when it expires.
 *
 * <p>Dropping is safe because of what reaches that lane. Everything not in
 * {@code netPrioritizedMessages} is either a request its sender re-issues on a timer
 * ({@code SYNCBLOCK_REQUEST} via {@code SyncManager.syncPushBlock}'s 64-second rule,
 * {@code SUMS_REQUEST} and {@code PING} on their own schedules) or a reply whose requester
 * re-requests when its future times out ({@code BLOCKS_REPLY}, {@code SUMS_REPLY},
 * {@code SYNC_BLOCK}). The exception is the handshake trio, which is sent exactly once per
 * connection and retried by nothing; those are listed in {@link #UNDROPPABLE} and take the
 * unbounded prioritized lane instead, where they cost at most three messages per connection.
 */
@Slf4j
public class MessageQueue {
    public static final ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            BasicThreadFactory.builder()
                    .namingPattern("MessageQueueTimer-thread-%d")
                    .daemon(true)
                    .build());
    /**
     * How long {@link #sendMessage} waits for room in the bounded queue before dropping the
     * message.
     *
     * <p>100 ms, because {@link #nudgeQueue} ticks every {@code SEND_PERIOD} (10 ms) and writes out
     * up to 8 messages a tick: a healthy queue frees a slot roughly every 1.25 ms, so 100 ms is ten
     * drain ticks and up to 80 slots of headroom — far more than any momentary burst needs, and
     * enough slack that a GC pause across a couple of 10 ms ticks does not turn into a drop. If a
     * hundred milliseconds of ticks free nothing, the queue is not busy but wedged: the drain is
     * being starved because {@code nudgeQueue} empties {@link #prioritized} first, or
     * {@link #deactivate()} has already cancelled the only task that drains it and no slot will
     * ever free. Waiting longer in either case just parks the caller.
     *
     * <p>The ceiling matters on the other side too: the ingest commit thread pays this once per
     * active channel when it fans a {@code NO_PARENT} out, so 100 ms bounds even a 16-peer fan-out
     * to about 1.6 s of stall in the pathological case. 200 ms would double that; 50 ms would leave
     * only five timer ticks of margin.
     */
    public static final long SEND_OFFER_TIMEOUT_MS = 100;

    /** At most one drop WARN per peer per this interval; this path runs at thousands of msg/s. */
    private static final long DROP_WARN_INTERVAL_MS = 10_000;

    /**
     * Messages that must not be dropped and are not covered by {@code netPrioritizedMessages}.
     *
     * <p>The handshake trio only: each is sent once per connection, nothing re-sends it, and losing
     * one leaves a connection that never finishes its handshake and only dies later on the read
     * timeout. They are bounded by the connection count, so the unbounded prioritized lane is safe
     * for them. {@code PING}/{@code PONG} deliberately stay droppable — a peer flooding PINGs would
     * otherwise grow that lane without limit, and a dropped keepalive is recovered by the next one
     * a minute later, well inside the two-minute read timeout.
     */
    private static final Set<MessageCode> UNDROPPABLE = Collections.unmodifiableSet(EnumSet.of(
            MessageCode.HANDSHAKE_INIT,
            MessageCode.HANDSHAKE_HELLO,
            MessageCode.HANDSHAKE_WORLD));

    private final Config config;
    //'8192' is a value obtained from testing experience, not a standard value.Looking forward to optimization.
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>(8192);
    private final Queue<Message> prioritized = new ConcurrentLinkedQueue<>();
    private volatile ChannelHandlerContext ctx;
    private ScheduledFuture<?> timerTask;

    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    /** Every message this queue has thrown away, for the whole life of the connection. */
    private final AtomicLong droppedMessages = new AtomicLong();
    /** {@link #droppedMessages} as of the last WARN, so each WARN can report the delta. */
    private final AtomicLong droppedAtLastWarn = new AtomicLong();
    private final AtomicLong lastDropWarn = new AtomicLong();

    public MessageQueue(Config config) {
        this.config = config;
    }

    public synchronized void activate(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        timerTask = timer.scheduleAtFixedRate(
                () -> {
                    try {
                        nudgeQueue();
                    } catch (Throwable t) {
                        log.error("Unhandled exception", t);
                    }
                },
                10,
                SEND_PERIOD,
                // 10 MILLISECONDS
                TimeUnit.MILLISECONDS);
    }

    public synchronized void deactivate() {
        this.timerTask.cancel(false);
    }

    public void disconnect(ReasonCode code) {
        log.debug("Actively closing the connection: reason = {}", code);

        // avoid repeating close requests
        if (isClosed.compareAndSet(false, true)) {
            ctx.writeAndFlush(new DisconnectMessage(code)).addListener((ChannelFutureListener) future -> ctx.close());
        }
    }

    /**
     * Queues a message for this peer. Returns within {@link #SEND_OFFER_TIMEOUT_MS} whatever the
     * peer is doing — see the class comment for why that bound is the point of this method.
     *
     * <p>The signature stays {@code void}: no caller can do anything useful with a drop that the
     * rate-limited WARN and {@link #getDroppedMessageCount()} do not already do, and the one caller
     * that must not be made to care is the ingest commit thread.
     */
    public void sendMessage(Message msg) {
        MessageCode code = msg.getCode();
        //when full message queue, whitelist don't need to disconnect.
        if (UNDROPPABLE.contains(code) || config.getNodeSpec().getNetPrioritizedMessages().contains(code)) {
            prioritized.add(msg);
            return;
        }
        try {
            //bounded wait on the BlockingQueue of capacity 8192, then drop: never park the caller
            if (!queue.offer(msg, SEND_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordDrop(code);
            }
        } catch (InterruptedException e) {
            // A shutdown, not backpressure. Restore the flag and let the message go; throwing here
            // would surface as an unhandled exception on a thread that is already being stopped.
            Thread.currentThread().interrupt();
            droppedMessages.incrementAndGet();
            log.debug("Interrupted while queueing {} for {}, dropping it", code, peerName());
        }
    }

    /** Number of messages dropped for want of room. Rises only when a peer cannot keep up. */
    public long getDroppedMessageCount() {
        return droppedMessages.get();
    }

    /**
     * Counts a drop and tells the operator about it at most once per {@link #DROP_WARN_INTERVAL_MS},
     * naming the peer and the message code, because the next thing that happens is every message to
     * this peer taking the same path.
     */
    private void recordDrop(MessageCode code) {
        long total = droppedMessages.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropWarn.get();
        if (now - last >= DROP_WARN_INTERVAL_MS && lastDropWarn.compareAndSet(last, now)) {
            long since = total - droppedAtLastWarn.getAndSet(total);
            log.warn("Send queue to {} is not draining: dropped {} messages in the last interval "
                            + "({} since this connection opened), most recently a {}. Requests are "
                            + "re-issued by their senders, so this costs sync round-trips, not blocks.",
                    peerName(), since, total, code);
        }
    }

    private String peerName() {
        ChannelHandlerContext c = this.ctx;
        return c == null ? "an unconnected peer" : String.valueOf(c.channel().remoteAddress());
    }

    public int size() {
        return queue.size() + prioritized.size();
    }

    private void nudgeQueue() {
        //Increase bandwidth consumption of a full used single sync thread to 3 Mbps.
        int n = Math.min(8, size());
        if (n == 0) {
            return;
        }
        // write out n messages
        for (int i = 0; i < n; i++) {
            Message msg = !prioritized.isEmpty() ? prioritized.poll() : queue.poll();

            log.trace("Wiring message: {}", msg);
            ctx.write(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        ctx.flush();
    }
}
