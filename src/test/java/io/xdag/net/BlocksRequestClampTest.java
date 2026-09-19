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

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagStats;
import io.xdag.consensus.SyncManager;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PrivateKey;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.message.consensus.BlocksRequestMessage;
import java.math.BigInteger;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code BLOCKS_REQUEST} hands a peer-chosen {@code [startTime, endTime)} straight to
 * {@code BlockStoreImpl#getBlocksUsedTime}, which steps {@code 0x10000} per iteration and
 * materialises every block it finds into one list on the netty event-loop thread. Nothing
 * validated {@code startTime}/{@code endTime} before this fix, so a single peer message set the
 * scan count, the result size and the bytes sent.
 *
 * <p>{@link XdagP2pHandler#processBlocksRequest} must clamp the span it actually serves to
 * {@link io.xdag.config.Constants#REQUEST_BLOCKS_MAX_TIME} — the widest span this node's own sync
 * ever asks a peer for in one message ({@code XdagSync#sendGetBlocks}: {@code t, t+MAX_TIME}) —
 * while leaving a request already at or under that bound completely untouched.
 *
 * <p>{@code chain} is a plain Mockito mock, not the real {@code BlockchainImpl}/{@code
 * BlockStoreImpl}, deliberately: an early version of this test drove the real store with a
 * {@code startTime} near {@link Long#MAX_VALUE} and hung for minutes, because
 * {@code getBlocksUsedTime}'s own {@code time += 0x10000} stride overflows past
 * {@code Long.MAX_VALUE} and wraps to a huge negative value that still satisfies
 * {@code time < endTime}. These tests exist to pin down what {@code XdagP2pHandler} hands the
 * store, in isolation from that store's own sharp edges.
 */
public class BlocksRequestClampTest {

    private XdagP2pHandler handler;
    private Blockchain chain;

    @Before
    public void setUp() {
        Config config = new DevnetConfig();
        ECKeyPair key = ECKeyPair.fromPrivateKey(PrivateKey.fromBigInteger(
                new BigInteger("c85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4", 16)));
        Kernel kernel = new Kernel(config, key);

        chain = mock(Blockchain.class);
        XdagStats stats = new XdagStats();
        stats.setMaxdifficulty(BigInteger.ONE);
        stats.setDifficulty(BigInteger.ONE);
        when(chain.getXdagStats()).thenReturn(stats);
        when(chain.getBlocksByTime(anyLong(), anyLong())).thenReturn(Collections.emptyList());
        kernel.setBlockchain(chain);

        // processBlocksRequest calls updateXdagStats(), which reaches into the SyncManager; nothing
        // here exercises sync itself.
        SyncManager syncMgr = mock(SyncManager.class);
        when(syncMgr.getIsUpdateXdagStats()).thenReturn(new AtomicBoolean(false));
        kernel.setSyncMgr(syncMgr);

        MessageQueue msgQueue = mock(MessageQueue.class);
        Channel channel = mock(Channel.class);
        when(channel.getMessageQueue()).thenReturn(msgQueue);
        handler = new XdagP2pHandler(channel, kernel);
    }

    @Test
    public void anOversizedRequestIsClampedToTheProtocolBound() {
        long startTime = 10_000_000L;
        long endTime = startTime + REQUEST_BLOCKS_MAX_TIME * 50;

        handler.processBlocksRequest(request(startTime, endTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals("an oversized span must be served no wider than REQUEST_BLOCKS_MAX_TIME",
                startTime + REQUEST_BLOCKS_MAX_TIME, served[1]);
    }

    /** The exact span this node's own sync sends: {@code XdagSync#sendGetBlocks(t, t+MAX_TIME)}. */
    @Test
    public void aRequestExactlyAtTheBoundIsUnaffected() {
        long startTime = 10_000_000L;
        long endTime = startTime + REQUEST_BLOCKS_MAX_TIME;

        handler.processBlocksRequest(request(startTime, endTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals("a request already at the bound must reach the store unchanged", endTime, served[1]);
    }

    @Test
    public void aSmallRequestIsUnaffected() {
        long startTime = 10_000_000L;
        long endTime = startTime + 100;

        handler.processBlocksRequest(request(startTime, endTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals("a normal, well-inside-the-bound request must reach the store unchanged",
                endTime, served[1]);
    }

    /**
     * {@code endTime == startTime}: {@code getBlocksUsedTime}'s {@code while (time < endTime)}
     * already does zero iterations, so this is a no-op today and must stay one.
     */
    @Test
    public void aZeroWidthRequestIsLeftAlone() {
        long startTime = 10_000_000L;

        handler.processBlocksRequest(request(startTime, startTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals(startTime, served[1]);
    }

    /** {@code endTime < startTime}: same zero-iteration no-op as the zero-width case. */
    @Test
    public void aNegativeSpanRequestIsLeftAlone() {
        long startTime = 10_000_000L;
        long endTime = startTime - 5_000_000L;

        handler.processBlocksRequest(request(startTime, endTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals(endTime, served[1]);
    }

    /**
     * {@code startTime} within {@code REQUEST_BLOCKS_MAX_TIME} of {@link Long#MAX_VALUE}: no real
     * xdag timestamp is anywhere near this boundary, and {@code getBlocksUsedTime}'s own stride
     * overflows past it and wraps negative, staying {@code < endTime} for roughly 2^48 further
     * iterations. The handler must refuse (zero iterations), not saturate to {@code Long.MAX_VALUE}
     * and still hand the store a startTime it cannot safely step away from.
     */
    @Test
    public void aStartTimeNearOverflowIsRefused() {
        long startTime = Long.MAX_VALUE - 10;
        long endTime = Long.MAX_VALUE;

        handler.processBlocksRequest(request(startTime, endTime));

        long[] served = capturedRange();
        assertEquals(startTime, served[0]);
        assertEquals("a startTime this close to Long.MAX_VALUE must be refused, not stepped from",
                startTime, served[1]);
    }

    private BlocksRequestMessage request(long startTime, long endTime) {
        return new BlocksRequestMessage(startTime, endTime, chain.getXdagStats());
    }

    private long[] capturedRange() {
        ArgumentCaptor<Long> start = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> end = ArgumentCaptor.forClass(Long.class);
        verify(chain, times(1)).getBlocksByTime(start.capture(), end.capture());
        return new long[]{start.getValue(), end.getValue()};
    }
}
