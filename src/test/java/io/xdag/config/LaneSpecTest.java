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

package io.xdag.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.lane.LaneActivation;
import org.junit.Test;

public class LaneSpecTest {

    @Test
    public void devnetIsActiveFromGenesisWithProtocolDefaults() {
        Config config = new DevnetConfig();
        assertEquals(0L, config.getLaneSpec().getLaneActivationHeight());
        assertEquals(4096, config.getLaneSpec().getLaneMaxChunksPerChain());
        assertEquals(1024 * 1024, config.getLaneSpec().getLaneMaxWasmBytes());
        assertEquals(256, config.getLaneSpec().getLaneMaxInlineArgs());
        assertEquals(XAmount.of(10, XUnit.MILLI_XDAG), config.getLaneSpec().getLaneChunkFee());
    }

    @Test
    public void sharedNetworksAreNotScheduled() {
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getLaneSpec().getLaneActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getLaneSpec().getLaneActivationHeight());
    }

    @Test
    public void activationCanBeOverriddenProgrammatically() {
        Config config = new DevnetConfig();
        config.getLaneSpec().setLaneActivationHeight(100L);
        LaneActivation activation = new LaneActivation(config.getLaneSpec());
        assertFalse(activation.isActive(99L));
        assertTrue(activation.isActive(100L));
        assertTrue(activation.isActive(101L));
    }

    /**
     * {@code getSetting()} (which parses {@code lane.activation.height} into
     * {@code laneActivationHeightOverride}) runs inside the {@link AbstractConfig}
     * constructor, before the per-network subclass constructor body assigns its
     * own {@code laneActivationHeight} default. This pins down that ordering: a
     * conf-sourced override must survive the subsequent network-default
     * assignment, and only the programmatic {@link LaneSpec#setLaneActivationHeight(long)}
     * setter (not a direct field assignment) is allowed to clear it.
     */
    @Test
    public void confOverrideSurvivesNetworkDefaults() {
        DevnetConfig config = new DevnetConfig();

        // Simulate a conf-sourced override captured by getSetting() before the
        // DevnetConfig constructor body set its own (0L) network default.
        config.setLaneActivationHeightOverrideForTest(250L);
        assertEquals(250L, config.getLaneActivationHeight());

        // The programmatic setter clears the override so the new value sticks.
        config.setLaneActivationHeight(999L);
        assertEquals(999L, config.getLaneActivationHeight());
    }
}
