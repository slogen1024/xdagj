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
package io.xdag.consensus;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Blockchain;
import io.xdag.net.ChannelManager;
import java.util.Optional;
import org.junit.Test;

/**
 * Stopping the sync manager has to end the thread it started, not only ask it to notice a flag.
 *
 * <p>{@code xdag-stateListener} spends its first 100 seconds asleep and every later loop 10 seconds
 * asleep, and {@code doStop()} used to do nothing but clear a plain, non-volatile boolean. A node
 * that had been cleanly stopped therefore kept a non-daemon thread — and with it the JVM — alive
 * for up to 100 more seconds, or, if the flag write was never seen by the listener thread, forever.
 */
public class SyncManagerShutdownTest {

    private static final String LISTENER_THREAD = "xdag-stateListener";

    /** The listener has no work to do here; the point is only whether it is still alive. */
    private static SyncManager syncManager() {
        Kernel kernel = mock(Kernel.class);
        when(kernel.getBlockchain()).thenReturn(mock(Blockchain.class));
        when(kernel.getChannelMgr()).thenReturn(mock(ChannelManager.class));
        Config config = mock(Config.class);
        ChainSpec chainSpec = mock(ChainSpec.class);
        // No ingest pipeline: this test is about the listener thread, and a pipeline would start
        // threads of its own that have nothing to do with it.
        when(chainSpec.getChainIngestThreads()).thenReturn(0);
        when(config.getChainSpec()).thenReturn(chainSpec);
        when(kernel.getConfig()).thenReturn(config);
        return new SyncManager(kernel);
    }

    private static Optional<Thread> listenerThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> LISTENER_THREAD.equals(t.getName()))
                .findFirst();
    }

    /**
     * The defect itself. Two seconds is generous for an interrupt and a join; it is two orders of
     * magnitude short of the 100-second sleep the old code sat out.
     */
    @Test
    public void stopEndsTheStateListenerThreadPromptly() throws Exception {
        SyncManager syncManager = syncManager();
        syncManager.start();
        try {
            assertTrue("start() did not start " + LISTENER_THREAD, listenerThread().isPresent());
        } catch (AssertionError e) {
            syncManager.stop();
            throw e;
        }

        long start = System.currentTimeMillis();
        syncManager.stop();
        long stopTookMs = System.currentTimeMillis() - start;

        // stop() joins the listener, so it is already gone by the time we get here; the poll is
        // only so this does not depend on that being true.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && listenerThread().isPresent()) {
            Thread.sleep(20);
        }
        assertTrue(LISTENER_THREAD + " was still alive 2s after stop(); stop() itself took "
                + stopTookMs + "ms", listenerThread().isEmpty());
    }
}
