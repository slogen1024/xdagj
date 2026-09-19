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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagState;
import io.xdag.net.ChannelManager;
import io.xdag.pool.PoolAwardManagerImpl;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The mining loop must outlive a single bad event, and the threads it runs on must not outlive the
 * kernel.
 *
 * <p>Before this, {@link XdagPow#run()} rethrew anything that came out of an event handler as a
 * {@code RuntimeException}. That ended the thread while {@code running} stayed true, so
 * {@link XdagPow#start()} would not bring it back and the node stopped producing main blocks in
 * silence until an operator restarted it — reachable from a plain race in the orphan pool that
 * {@code createMainBlock} walks.
 */
public class XdagPowLoopResilienceTest {

    /** Every thread the PoW owns is named after it, which is how this test finds them again. */
    private static final String THREAD_PREFIX = "XdagPow-";

    private Kernel kernel;

    @Before
    public void setUp() {
        kernel = Mockito.mock(Kernel.class);
        Mockito.when(kernel.getBlockchain()).thenReturn(Mockito.mock(Blockchain.class));
        Mockito.when(kernel.getChannelMgr()).thenReturn(Mockito.mock(ChannelManager.class));
        Mockito.when(kernel.getPoolAwardManager()).thenReturn(Mockito.mock(PoolAwardManagerImpl.class));
        Mockito.when(kernel.getWallet()).thenReturn(Mockito.mock(Wallet.class));
        // The state the run loop requires before it dispatches an event at all.
        Mockito.when(kernel.getXdagState()).thenReturn(XdagState.SYNC);
    }

    private static List<Thread> powThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith(THREAD_PREFIX))
                .collect(Collectors.toList());
    }

    private static void awaitNoPowThreads() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !powThreads().isEmpty()) {
            Thread.sleep(20);
        }
        Set<String> alive = powThreads().stream().map(Thread::getName).collect(Collectors.toSet());
        assertTrue("stop() left PoW threads alive: " + alive, alive.isEmpty());
    }

    /**
     * The defect itself: an event handler that throws costs that one event, not the miner. The
     * second event is only ever handled if the loop survived the first.
     */
    @Test
    public void aFailingEventDoesNotEndTheMiningLoop() throws Exception {
        CountDownLatch handled = new CountDownLatch(2);
        XdagPow pow = new XdagPow(kernel) {
            @Override
            protected void onTimeout() {
                handled.countDown();
                // The shape the orphan pool's isEmpty()-then-peek() race throws.
                throw new IllegalStateException("boom");
            }
        };
        pow.start();
        try {
            pow.events.add(new XdagPow.Event(XdagPow.Event.Type.TIMEOUT));
            pow.events.add(new XdagPow.Event(XdagPow.Event.Type.TIMEOUT));

            assertTrue("the loop died on the first failing event",
                    handled.await(5, TimeUnit.SECONDS));
            assertTrue("the PoW still reports itself running", pow.isRunning());
        } finally {
            pow.stop();
        }
        awaitNoPowThreads();
    }

    /**
     * stop() has to end the threads, not only ask the loops to notice a flag: before this the four
     * executors were never shut down and their factories did not set daemon, so every thread
     * outlived the kernel.
     */
    @Test
    public void stopEndsEveryPowThreadAndTheyAreAllDaemons() throws Exception {
        XdagPow pow = new XdagPow(kernel) {
            @Override
            protected void onTimeout() {
                // Never produce a block in this test: the kernel is a mock.
            }
        };
        pow.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && powThreads().size() < 4) {
                Thread.sleep(20);
            }
            List<Thread> threads = powThreads();
            assertEquals("all four PoW workers should be running: " + threads, 4, threads.size());
            for (Thread t : threads) {
                assertTrue(t.getName() + " is not a daemon thread, so it outlives the kernel",
                        t.isDaemon());
            }
        } finally {
            pow.stop();
        }
        awaitNoPowThreads();
    }

    /**
     * A stop() that lands while the workers are still being scheduled must still stop them. The
     * loops used to raise their own flag as their first statement, which overwrote exactly this
     * stop() and left them spinning for the life of the JVM.
     */
    @Test
    public void aStopRacingTheStartStillEndsEveryThread() throws Exception {
        XdagPow pow = new XdagPow(kernel);
        pow.start();
        pow.stop();

        awaitNoPowThreads();
    }
}
