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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.l1.ChainL1TestBase;
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
        assertTrue(r.describe().contains("--repairchain"));
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
        assertTrue(r.stuck().get(0).reason().contains("in flight"));
        // the height in flight is itself suspect, so the repair target sits one below it
        assertEquals(h - 1, r.repairTarget());
        assertTrue(r.describe().contains("--repairchain"));
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
}
