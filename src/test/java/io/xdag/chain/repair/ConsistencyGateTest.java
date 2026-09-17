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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.db.BlockStore;
import java.util.List;
import org.junit.Test;

/**
 * The startup gate: {@code BlockchainImpl}'s constructor runs {@link ChainConsistencyCheck} and
 * refuses to build a blockchain on a main chain the chain hooks cannot trust, unless the kernel is
 * in repair mode — where the report is only recorded, for the offline repair tool to act on.
 */
public class ConsistencyGateTest extends ChainL1TestBase {

    /**
     * Records whether the constructor started the check-main loop. {@code MockBlockchain} already
     * no-ops {@code startCheckMain}, so overriding it again is the only way to see the call.
     *
     * <p>The flag is written from the super constructor and deliberately has NO initializer: an
     * explicit {@code = false} would be compiled into this constructor, i.e. run after {@code
     * super(kernel)}, and would erase what the super constructor recorded.
     */
    private static final class RecordingBlockchain extends MockBlockchain {

        volatile boolean checkMainStarted;

        RecordingBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
            checkMainStarted = true;
        }
    }

    private void mine(int n) {
        for (int i = 0; i < n; i++) {
            mineMain(List.of());
        }
    }

    @Test
    public void cleanStoreConstructsAndRecordsAReport() {
        mine(4);
        RecordingBlockchain restarted = new RecordingBlockchain(kernel); // "restart" on the same stores
        assertNotNull(kernel.getConsistencyReport());
        assertTrue(kernel.getConsistencyReport().describe(), kernel.getConsistencyReport().clean());
        assertEquals(blockchain.getXdagStats().nmain, restarted.getXdagStats().nmain);
        assertTrue("a clean boot starts the check-main loop", restarted.checkMainStarted);
    }

    @Test
    public void inconsistentStoreRefusesToConstruct() {
        mine(4);
        kernel.getBlockStore().saveLastCompletedMain(blockchain.getXdagStats().nmain - 1);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MockBlockchain(kernel));
        assertTrue(e.getMessage(), e.getMessage().contains("setMain incomplete"));
        assertTrue(e.getMessage(), e.getMessage().contains("--repairchain"));
    }

    @Test
    public void repairModeConstructsWithoutStartingCheckMain() {
        mine(4);
        kernel.getBlockStore().saveLastCompletedMain(blockchain.getXdagStats().nmain - 1);
        kernel.setRepairMode(true);
        RecordingBlockchain repair = new RecordingBlockchain(kernel);
        assertNotNull(repair);
        assertFalse("repair mode must not confirm anything behind the repair tool's back",
                repair.checkMainStarted);
        assertFalse(kernel.getConsistencyReport().clean());
        assertEquals(blockchain.getXdagStats().nmain - 1, kernel.getConsistencyReport().repairTarget());
    }

    /** A MAIN_IN_FLIGHT record on its own is fatal too: a setMain was entered and never finished. */
    @Test
    public void inFlightRecordAloneRefusesToConstruct() {
        mine(4);
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveMainInFlight(tip, BlockStore.IN_FLIGHT_SET_MAIN);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MockBlockchain(kernel));
        assertTrue(e.getMessage(), e.getMessage().contains("in flight"));
        assertTrue(e.getMessage(), e.getMessage().contains("--repairchain"));

        kernel.setRepairMode(true);
        RecordingBlockchain repair = new RecordingBlockchain(kernel);
        assertFalse(repair.checkMainStarted);
        assertFalse(kernel.getConsistencyReport().clean());
        assertEquals(tip - 1, kernel.getConsistencyReport().repairTarget());
    }

    @Test
    public void absentMarkerIsInitializedOnFirstBoot() {
        mine(4);
        kernel.getBlockStore().saveLastCompletedMain(-1L);
        new MockBlockchain(kernel);
        assertTrue(kernel.getConsistencyReport().markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, kernel.getBlockStore().getLastCompletedMain());
    }
}
