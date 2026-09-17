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
package io.xdag.chain.repair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

public class MainCompletionMarkerTest extends ChainL1TestBase {

    private static final Bytes BOND_CHAIN = Bytes.repeat((byte) 0x22, 20);

    /** Throws on the first BOND it is handed, once armed; a plain no-op otherwise. */
    private static final class ExplodingHandler implements ChainKindHandler {
        volatile boolean armed;

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("simulated failure inside the applyBlock DFS");
            }
        }

        @Override
        public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        }
    }

    private final ExplodingHandler handler = new ExplodingHandler();

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.getChainKindHandlers().put(ExtKind.BOND, handler);
    }

    private Block bondBlock() {
        Block b = new Block(config, txTime(), null, null, false, null, null, -1, XAmount.ZERO, null,
                List.of(new BondExt(false, BOND_CHAIN, 0L).encodeHeader()));
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void markerFollowsNormalConfirmationAndUnwind() {
        assertEquals("fresh store carries no marker", -1L, kernel.getBlockStore().getLastCompletedMain());
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        assertEquals(blockchain.getXdagStats().nmain, kernel.getBlockStore().getLastCompletedMain());
        long before = blockchain.getXdagStats().nmain;

        // unSetMain moves the marker down with the height it removes
        Block tip = blockchain.getBlockByHeight(before);
        blockchain.unSetMain(tip);
        assertEquals(before - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(before - 1, blockchain.getXdagStats().nmain);
    }

    @Test
    public void failedSetMainLeavesRefOnSelfAndNoMarkerAdvance() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }

        Block bond = bondBlock();
        assertImported(bond);
        // Links BOND into the main chain as an extraRef, but does not confirm it yet: setMain lags
        // one block behind mineMain's own top (ChainL1TestBase §0.5 / checkNewMain's i > 1 guard), so
        // this call only confirms the block mined just before it.
        mineMain(List.of(hashLow(bond)));
        long completed = kernel.getBlockStore().getLastCompletedMain();

        handler.armed = true;
        // The next main block's checkMain() is what finally confirms the block carrying the BOND
        // ref; MockBlockchain.checkMain has no try/catch, so the handler's exception escapes setMain
        // (after BI_MAIN, height, reward, nmain++).
        assertThrows(IllegalStateException.class, () -> mineMain(List.of()));

        long failedHeight = blockchain.getXdagStats().nmain;
        assertEquals(completed + 1, failedHeight);
        Block stuck = kernel.getBlockStore().getBlockByHeight(failedHeight);
        assertNotNull("the failed main block is stored with its height", stuck);
        // G1 root fix: the ref points at itself BEFORE the DFS, so unApplyBlock will not skip it
        assertNotNull("ref must be set before applyBlock runs", stuck.getInfo().getRef());
        assertEquals(stuck.getHashLow(), org.apache.tuweni.bytes.Bytes32.wrap(stuck.getInfo().getRef()));
        // G2 marker: a setMain that did not finish never advances the marker
        assertEquals(completed, kernel.getBlockStore().getLastCompletedMain());
    }
}
