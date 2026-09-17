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

import static io.xdag.config.Constants.BI_MAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XdagStats;
import io.xdag.db.BlockStore;
import java.util.List;
import org.junit.Test;

public class ChainConsistencyCheckTest extends ChainL1TestBase {

    private ChainConsistencyCheck.Report check() {
        return ChainConsistencyCheck.run(kernel.getBlockStore(), blockchain.getXdagStats(),
                config.getChainSpec(), config.getChainSpec().getChainConsistencyWindow());
    }

    @Test
    public void cleanChainIsClean() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
        assertFalse(r.markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, r.nmain());
        assertEquals(blockchain.getXdagStats().nmain, r.marker());
    }

    @Test
    public void missingMarkerIsInitializedNotFatal() {
        for (int i = 0; i < 3; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(-1L); // simulate a store from before SP0b-1
        // (getLastCompletedMain treats a stored -1 exactly like an absent marker: see Task 1)
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
        assertTrue(r.markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, r.marker());
    }

    @Test
    public void markerBehindTipIsAnIncompleteSetMain() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(tip - 2);
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(2, r.stuck().size());
        assertEquals(tip - 1, r.stuck().get(0).height());
        assertEquals(tip, r.stuck().get(1).height());
        assertTrue(r.stuck().get(0).reason().contains("setMain incomplete"));
        assertTrue(r.describeForBoot().contains("--repairchain"));
        assertFalse("the report text carries no advice of its own", r.describe().contains("--repairchain"));
    }

    @Test
    public void mainBlockWithoutRefIsStuck() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain - 1;
        Block m = blockchain.getBlockByHeight(h);
        blockchain.updateBlockRef(m, null); // legacy stuck shape (pre-SP0b-1 crash)
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("without ref"));
    }

    @Test
    public void mainBlockAbovePersistedStatsIsStuck() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        // simulate a crash after the main block at nmain was stored but before stats were persisted
        XdagStats stale = new XdagStats();
        stale.nmain = blockchain.getXdagStats().nmain - 1;
        kernel.getBlockStore().saveLastCompletedMain(stale.nmain);
        ChainConsistencyCheck.Report r = ChainConsistencyCheck.run(kernel.getBlockStore(), stale,
                config.getChainSpec(), 128);
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(stale.nmain + 1, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("above persisted stats"));
    }

    @Test
    public void windowBoundsTheScan() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long old = 2;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(old), null);
        BlockStore store = kernel.getBlockStore();
        ChainConsistencyCheck.Report narrow = ChainConsistencyCheck.run(store, blockchain.getXdagStats(),
                config.getChainSpec(), 2);
        assertTrue("outside the window the stuck block is not scanned", narrow.clean());
        ChainConsistencyCheck.Report wide = ChainConsistencyCheck.run(store, blockchain.getXdagStats(),
                config.getChainSpec(), 128);
        assertFalse(wide.clean());
    }

    /**
     * A present MAIN_IN_FLIGHT record is the only evidence a half-done {@code unSetMain} leaves: the
     * completion marker is only lowered once that call finishes, so on its own it looks clean.
     */
    @Test
    public void inFlightRecordIsStuck() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveMainInFlight(h);
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("setMain in flight"));
        assertFalse("a setMain record is not an unwind", r.inFlightUnwind());
        // the height in flight is itself suspect, so the repair target sits one below it
        assertEquals(h - 1, r.repairTarget());
        assertTrue(r.describeForBoot().contains("--repairchain"));
    }

    @Test
    public void clearedInFlightRecordIsClean() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveMainInFlight(h);
        assertFalse(check().clean());
        kernel.getBlockStore().clearMainInFlight();
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
        assertEquals(h, r.repairTarget());
    }

    /**
     * F1: both scan rules are statements about the main chain, not about the chain protocol. A
     * shared net that has not activated the chain protocol carries {@code Long.MAX_VALUE} there,
     * which used to clamp the scan to nothing — on exactly the stores that hold the legacy shape.
     */
    @Test
    public void scanIsNotClampedByTheChainActivationHeight() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain - 1;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(h), null);
        config.getChainSpec().setChainActivationHeight(Long.MAX_VALUE);
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
    }

    /**
     * F2: {@code saveBlockInfo} never deletes the height key it wrote before, so a block unwound at
     * height H and re-confirmed lower down is still reachable from {@code key(H)} with BI_MAIN set.
     * That stale entry is not a stuck block: a real one agrees with its index height, because
     * setMain writes the height before the DFS it may die in.
     */
    @Test
    public void staleHeightIndexEntryIsIgnored() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain;
        Block tip = blockchain.getBlockByHeight(h);
        blockchain.unSetMain(tip);
        assertEquals(h - 1, blockchain.getXdagStats().nmain);
        assertEquals("key(H) still resolves to the unwound block", hashLow(tip),
                hashLow(kernel.getBlockStore().getBlockByHeight(h)));

        // the same block, re-confirmed one height lower (as a reorg would): key(h - 1) now points
        // at it as well, while the stale key(h) entry is left behind pointing at the same block.
        Block reconfirmed = blockchain.getBlockByHash(hashLow(tip), false);
        reconfirmed.getInfo().setHeight(h - 1);
        blockchain.updateBlockFlag(reconfirmed, BI_MAIN, true);
        blockchain.updateBlockRef(reconfirmed, new Address(reconfirmed));

        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
    }

    /**
     * F3: a marker left far behind enumerates at most one window (the same [nmain - window, nmain]
     * range the block scan covers), not the whole chain.
     */
    @Test
    public void markerBehindTipIsBoundedByTheWindow() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(0);
        ChainConsistencyCheck.Report r = ChainConsistencyCheck.run(kernel.getBlockStore(),
                blockchain.getXdagStats(), config.getChainSpec(), 2);
        assertEquals(3, r.stuck().size());
        assertTrue("the whole chain below the marker is not enumerated", r.stuck().size() < tip);
        assertEquals(tip - 2, r.stuck().get(0).height());
        assertEquals(tip, r.stuck().get(2).height());
        // everything above the marker is still suspect, so the repair target stays at the marker
        assertEquals(0, r.repairTarget());
    }

    /** M8(b): the target never sits above the marker, even when the window hides the earlier heights. */
    @Test
    public void repairTargetIsClampedToTheMarker() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(tip - 3);
        ChainConsistencyCheck.Report r = ChainConsistencyCheck.run(kernel.getBlockStore(),
                blockchain.getXdagStats(), config.getChainSpec(), 1);
        assertEquals(2, r.stuck().size());
        assertEquals(tip - 1, r.stuck().get(0).height());
        assertEquals(tip - 3, r.repairTarget());
    }

    /**
     * F4: a half-finished unwind is a different repair from a half-finished setMain — unwinding
     * further cannot finish it — so the record says which one it was.
     */
    @Test
    public void unSetMainInFlightIsReportedAsAnUnwind() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveMainInFlight(h, BlockStore.IN_FLIGHT_UNSET_MAIN);
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("unSetMain in flight"));
        assertTrue(r.inFlightUnwind());
    }

    /** M8(a): the rules overlap, and the most direct evidence owns the height. */
    @Test
    public void theInFlightRuleOwnsItsHeight() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(tip - 2);
        kernel.getBlockStore().saveMainInFlight(tip - 1, BlockStore.IN_FLIGHT_SET_MAIN);
        ChainConsistencyCheck.Report r = check();
        assertEquals(2, r.stuck().size());
        assertEquals(tip - 1, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("in flight"));
        assertEquals(tip, r.stuck().get(1).height());
        assertTrue(r.stuck().get(1).reason().contains("setMain incomplete"));
        assertEquals(tip - 2, r.repairTarget());
    }

    /** M8(c): an operator reading a clean report must still see that the marker was invented. */
    @Test
    public void describeReportsAnInitializedMarker() {
        for (int i = 0; i < 3; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(-1L);
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.markerInitialized());
        assertTrue(r.describe(), r.describe().contains("initialized"));
    }

    /** M8(d): a height with no stored block still reports, with an unknown hash. */
    @Test
    public void inFlightHeightWithoutAStoredBlockReportsAnUnknownHash() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain + 100;
        kernel.getBlockStore().saveMainInFlight(h, BlockStore.IN_FLIGHT_SET_MAIN);
        ChainConsistencyCheck.Report r = check();
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
        assertNull(r.stuck().get(0).hash());
        assertTrue(r.describe(), r.describe().contains("height " + h + " ?:"));
    }
}
