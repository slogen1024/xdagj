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
package io.xdag.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.db.BlockStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The award thread must outlive a block it cannot find.
 *
 * <p>{@code payPools} read {@code block.getInfo()} three lines before its own {@code block == null}
 * guard, so a block that is not in the store threw an NPE; the work loop caught only
 * {@link InterruptedException}, so that ended {@code run()} and nothing re-submits the runnable —
 * pool rewards stopped in silence until the node was restarted. A missing block is reachable:
 * {@code XdagPow.onTimeout} used to queue an award without looking at what its own
 * {@code tryToConnect} returned.
 */
public class PoolAwardManagerResilienceTest {

    private Kernel kernel;
    private Blockchain blockchain;

    @Before
    public void setUp() {
        kernel = Mockito.mock(Kernel.class);
        blockchain = Mockito.mock(Blockchain.class);
        // Deep stubs for the config only: every spec below it is read in the constructor, and the
        // ratios a mocked getter answers with (0.0) are the ones this test wants anyway.
        Config config = Mockito.mock(Config.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(kernel.getConfig()).thenReturn(config);
        Mockito.when(kernel.getBlockchain()).thenReturn(blockchain);
        Mockito.when(kernel.getWallet()).thenReturn(Mockito.mock(Wallet.class));
        Mockito.when(kernel.getBlockStore()).thenReturn(Mockito.mock(BlockStore.class));
    }

    /** awardEpoch is 0 on a mocked config, so every round lands on index 0. */
    private void queueAwardAt(PoolAwardManagerImpl manager, int index) {
        manager.blockPreHashs.set(index, Bytes32.ZERO);
        manager.blockHashs.set(index, Bytes32.ZERO);
        manager.minShares.set(index, Bytes32.ZERO);
    }

    /**
     * The defect: a queued award whose block is not in the store must be reported, not thrown.
     * {@code getBlockByHash} answers null here, which is what it answers for a block the chain
     * rejected.
     */
    @Test
    public void aMissingBlockIsReportedInsteadOfThrowing() {
        PoolAwardManagerImpl manager = new PoolAwardManagerImpl(kernel);
        queueAwardAt(manager, 0);
        Mockito.when(blockchain.getBlockByHash(Mockito.any(), Mockito.anyBoolean())).thenReturn(null);

        assertEquals("a block that is not in the store is -2, not an NPE", -2, manager.payPools(0L));
    }

    /**
     * And even if a payout does throw, the loop has to take the next block: nothing re-submits this
     * runnable, so an escaping exception stops every later award for the life of the process.
     */
    @Test
    public void aFailingPayoutDoesNotEndTheAwardLoop() throws Exception {
        CountDownLatch handled = new CountDownLatch(2);
        PoolAwardManagerImpl manager = new PoolAwardManagerImpl(kernel) {
            @Override
            public void payAndAddNewAwardBlock(AwardBlock awardBlock) {
                handled.countDown();
                throw new IllegalStateException("boom");
            }
        };
        manager.start();
        try {
            manager.addAwardBlock(Bytes32.ZERO, Bytes32.ZERO, Bytes32.ZERO, 0L);
            manager.addAwardBlock(Bytes32.ZERO, Bytes32.ZERO, Bytes32.ZERO, 0L);

            assertTrue("the award loop died on the first failing payout",
                    handled.await(10, TimeUnit.SECONDS));
            assertTrue("the manager still reports itself running", manager.isRunning());
        } finally {
            manager.stop();
        }
    }

    /**
     * Moving the guard must not move the payout: a block that IS found still walks the same checks
     * it always did. This one's nonce carries its own coinbase, i.e. the node mined it rather than
     * a pool, which is the -3 the method has always answered with.
     */
    @Test
    public void aBlockThatIsFoundIsStillClassifiedTheSameWay() {
        PoolAwardManagerImpl manager = new PoolAwardManagerImpl(kernel);
        queueAwardAt(manager, 0);
        Block block = Mockito.mock(Block.class);
        Address coinBase = Mockito.mock(Address.class);
        Mockito.when(coinBase.getAddress()).thenReturn(MutableBytes32.create());
        Mockito.when(block.getNonce()).thenReturn(Bytes32.ZERO);
        Mockito.when(block.getCoinBase()).thenReturn(coinBase);
        Mockito.when(blockchain.getBlockByHash(Mockito.any(), Mockito.anyBoolean())).thenReturn(block);

        assertEquals("a block the node mined itself is -3, and it got there past the null guard",
                -3, manager.payPools(0L));
    }
}
