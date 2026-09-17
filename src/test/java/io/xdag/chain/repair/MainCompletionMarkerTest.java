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

import static io.xdag.config.Constants.BI_MAIN_REF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class MainCompletionMarkerTest extends ChainL1TestBase {

    /** Throws on the first BOND it is handed, once armed; a plain no-op otherwise. */
    private static final class ExplodingHandler implements ChainKindHandler {
        volatile boolean armed;
        /** Hash of the block it actually threw on, so a test need not guess the DFS order. */
        volatile Bytes32 exploded;

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            if (armed) {
                armed = false;
                exploded = Bytes32.wrap(block.getHashLow().toArray());
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

    /** A link-less BOND block; {@code tag} distinguishes two otherwise byte-identical blocks. */
    private Block bondBlock(byte tag) {
        Block b = new Block(config, txTime(), null, null, false, null, null, -1, XAmount.ZERO, null,
                List.of(new BondExt(false, Bytes.repeat(tag, 20), 0L).encodeHeader()));
        return new Block(new XdagBlock(b.toBytes()));
    }

    /** The first of the two candidates in the main block's own link order — the one the DFS reaches first. */
    private static Bytes32 firstLinkedOf(Block mainRaw, Bytes32 a, Bytes32 b) {
        for (Address link : mainRaw.getBlockLinks()) {
            Bytes32 h = Bytes32.wrap(link.getAddress().toArray());
            if (h.equals(a) || h.equals(b)) {
                return h;
            }
        }
        throw new AssertionError("neither BOND block is linked by the main block");
    }

    @Test
    public void markerFollowsNormalConfirmationAndUnwind() {
        assertEquals("fresh store carries no marker", -1L, kernel.getBlockStore().getLastCompletedMain());
        assertEquals("fresh store has nothing in flight", -1L, kernel.getBlockStore().getMainInFlight());
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        assertEquals(blockchain.getXdagStats().nmain, kernel.getBlockStore().getLastCompletedMain());
        assertEquals("a completed setMain leaves nothing in flight", -1L, kernel.getBlockStore().getMainInFlight());
        long before = blockchain.getXdagStats().nmain;

        // unSetMain moves the marker down with the height it removes
        Block tip = blockchain.getBlockByHeight(before);
        blockchain.unSetMain(tip);
        assertEquals(before - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(before - 1, blockchain.getXdagStats().nmain);
        assertEquals("a completed unSetMain leaves nothing in flight", -1L, kernel.getBlockStore().getMainInFlight());

        // ... and keeps doing so across a multi-block unwind (each call reads the marker before it
        // lowers it, so the RandomX gate in unSetMain stays true for every height it undoes)
        Block next = blockchain.getBlockByHeight(before - 1);
        assertNotNull("the block below the tip is still addressable by height", next);
        blockchain.unSetMain(next);
        assertEquals(before - 2, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(before - 2, blockchain.getXdagStats().nmain);
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
    }

    @Test
    public void failedSetMainLeavesRefOnSelfAndNoMarkerAdvance() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }

        Block bond = bondBlock((byte) 0x22);
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
        assertEquals(stuck.getHashLow(), Bytes32.wrap(stuck.getInfo().getRef()));
        // G2 marker: a setMain that did not finish never advances the marker
        assertEquals(completed, kernel.getBlockStore().getLastCompletedMain());
        // I3 record: and it leaves proof of the height it died at
        assertEquals(failedHeight, kernel.getBlockStore().getMainInFlight());
    }

    /**
     * I1: the same shape as the G1 fix one level down — a child's ref must already point at its main
     * block when the DFS descends into it, so a throw inside the child's own apply still leaves a
     * child that {@code unApplyBlock} can reach and undo.
     */
    @Test
    public void childRefIsSetBeforeApplyAndRestoredByUnwind() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }

        Block bondA = bondBlock((byte) 0x22);
        Block bondB = bondBlock((byte) 0x23);
        assertImported(bondA);
        assertImported(bondB);

        Block main = mineMain(List.of(hashLow(bondA), hashLow(bondB)));
        long completed = kernel.getBlockStore().getLastCompletedMain();

        handler.armed = true;
        assertThrows(IllegalStateException.class, () -> mineMain(List.of()));

        long failedHeight = blockchain.getXdagStats().nmain;
        assertEquals(completed + 1, failedHeight);

        Block mainRaw = blockchain.getBlockByHash(hashLow(main), true);
        assertNotNull("the failed main block is stored raw", mainRaw);
        assertEquals(failedHeight, mainRaw.getInfo().getHeight());
        assertEquals("the main block points at itself", hashLow(main), Bytes32.wrap(mainRaw.getInfo().getRef()));

        // The DFS visits children in link order, so the first BOND in the link list is the one the
        // handler threw on; the other one was never reached.
        Bytes32 firstBond = firstLinkedOf(mainRaw, hashLow(bondA), hashLow(bondB));
        Bytes32 secondBond = firstBond.equals(hashLow(bondA)) ? hashLow(bondB) : hashLow(bondA);
        assertEquals("the handler threw on the first BOND in link order", firstBond, handler.exploded);

        Block child = blockchain.getBlockByHash(firstBond, false);
        assertNotNull("the exploded child's ref was written before the DFS descended", child.getInfo().getRef());
        assertEquals(hashLow(main), Bytes32.wrap(child.getInfo().getRef()));
        Block untouched = blockchain.getBlockByHash(secondBond, false);
        assertNull("the DFS aborted before the second BOND", untouched.getInfo().getRef());

        // The whole point: the half-applied height can still be unwound.
        blockchain.unSetMain(blockchain.getBlockByHash(hashLow(main), true));

        Block childAfter = blockchain.getBlockByHash(firstBond, false);
        assertNull("unwinding restores the child's ref", childAfter.getInfo().getRef());
        assertEquals("unwinding clears BI_MAIN_REF on the child", 0, childAfter.getInfo().getFlags() & BI_MAIN_REF);
        assertEquals(completed, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
    }

    /**
     * B2: a snapshot boot marks the tip the store actually carries. With SnapshotJ off nothing is
     * imported, so that tip is 0 — writing the configured snapshot height there would claim a
     * completion that never happened.
     */
    @Test
    public void snapshotBootMarksTheTipItActuallyCarries() {
        // Keeps the CHAIN_L1 snapshot gate out of it: at an activation height above the snapshot
        // height the gate returns before demanding a SNAPSHOT/CHAIN_L1 export.
        config.getChainSpec().setChainActivationHeight(Long.MAX_VALUE);
        config.getSnapshotSpec().snapshotEnable();
        config.getSnapshotSpec().setSnapshotHeight(100L);
        config.getSnapshotSpec().setSnapshotJ(false);

        MockBlockchain booted = new MockBlockchain(kernel);
        assertEquals("nothing was imported, so the tip is still 0", 0L, booted.getXdagStats().nmain);
        assertEquals("the marker follows nmain, not the configured snapshot height",
                0L, kernel.getBlockStore().getLastCompletedMain());
    }
}
