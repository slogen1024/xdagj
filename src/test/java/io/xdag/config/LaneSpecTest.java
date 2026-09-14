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
import static org.junit.Assert.fail;

import com.typesafe.config.ConfigFactory;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.lane.LaneActivation;
import io.xdag.lane.ext.CallExt;
import io.xdag.lane.ext.ChunkExt;
import java.util.function.Supplier;
import org.junit.Test;

public class LaneSpecTest {

    /**
     * Sets a JVM system property, invalidates Typesafe's config caches so the next
     * {@code ConfigFactory.load(...)} overlays it above the resource file, runs {@code body},
     * then always clears the property afterwards (and invalidates the caches again) — even if
     * {@code body} throws.
     */
    private static <T> T withProperty(String key, String value, Supplier<T> body) {
        System.setProperty(key, value);
        ConfigFactory.invalidateCaches();
        try {
            return body.get();
        } finally {
            System.clearProperty(key);
            ConfigFactory.invalidateCaches();
        }
    }

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
     * constructor, before the per-network subclass constructor body assigns its own
     * {@code laneActivationHeight} default. This pins down that ordering: a conf-sourced
     * override (simulated here with a real JVM system property, overlaid by Typesafe above
     * the resource file) must survive the subsequent network-default assignment, and only
     * the programmatic {@link io.xdag.config.spec.LaneSpec#setLaneActivationHeight(long)}
     * setter clears it.
     */
    @Test
    public void confOverrideSurvivesNetworkDefaults() {
        DevnetConfig config = withProperty("lane.activation.height", "250", DevnetConfig::new);
        assertEquals(250L, config.getLaneActivationHeight());

        // The programmatic setter clears the override so the new value sticks, even though
        // the conf-sourced override is still technically present in the environment.
        config.setLaneActivationHeight(999L);
        assertEquals(999L, config.getLaneActivationHeight());
    }

    @Test
    public void confActivationHeightBeatsNetworkDefault() {
        long devnetHeight = withProperty("lane.activation.height", "250",
                () -> new DevnetConfig().getLaneActivationHeight());
        assertEquals(250L, devnetHeight);

        long mainnetHeight = withProperty("lane.activation.height", "250",
                () -> new MainnetConfig().getLaneActivationHeight());
        assertEquals(250L, mainnetHeight);
    }

    @Test
    public void confMaxChunksPerChainIsRead() {
        // Large enough that the default lane.wasm.maxBytes (1 MiB) still fits within
        // maxChunksPerChain x CHUNK_DATA_LEN, so this exercises only the conf-read path.
        int maxChunksPerChain = withProperty("lane.chunk.maxPerChain", "8192",
                () -> new DevnetConfig().getLaneMaxChunksPerChain());
        assertEquals(8192, maxChunksPerChain);
    }

    @Test
    public void confMaxWasmBytesIsRead() {
        int maxWasmBytes = withProperty("lane.wasm.maxBytes", "2048",
                () -> new DevnetConfig().getLaneMaxWasmBytes());
        assertEquals(2048, maxWasmBytes);
    }

    @Test
    public void confChunkFeeMilliXdagIsRead() {
        XAmount chunkFee = withProperty("lane.chunk.feeMilliXdag", "20",
                () -> new DevnetConfig().getLaneChunkFee());
        assertEquals(XAmount.of(20, XUnit.MILLI_XDAG), chunkFee);
    }

    @Test
    public void negativeChunkFeeIsRejected() {
        try {
            withProperty("lane.chunk.feeMilliXdag", "-5", DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("lane.chunk.feeMilliXdag"));
        }
    }

    @Test
    public void chunkFeeOverflowIsRejected() {
        try {
            withProperty("lane.chunk.feeMilliXdag", String.valueOf(Long.MAX_VALUE), DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("lane.chunk.feeMilliXdag"));
            assertTrue(e.getCause() instanceof ArithmeticException);
        }
    }

    @Test
    public void negativeActivationHeightIsRejected() {
        try {
            withProperty("lane.activation.height", "-1", DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("lane.activation.height"));
        }
    }

    @Test
    public void wasmBoundAboveChainCapacityIsRejected() {
        withProperty("lane.chunk.maxPerChain", "1", () -> withProperty("lane.wasm.maxBytes", "353", () -> {
            try {
                new DevnetConfig();
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("lane.wasm.maxBytes"));
            }
            return null;
        }));
    }

    @Test
    public void inlineArgsBoundMatchesWireFormat() {
        assertEquals(CallExt.MAX_INLINE_ARGS, new DevnetConfig().getLaneMaxInlineArgs());
    }

    /**
     * Pins {@code AbstractConfig.getSetting()}'s local {@code CHUNK_DATA_LEN} (used to
     * cross-check {@code lane.wasm.maxBytes} against chunk-chain capacity) against the real
     * wire-format constant, so the two can never silently drift apart.
     */
    @Test
    public void chunkDataLenConstantMatchesWireFormat() {
        assertEquals(352, ChunkExt.MAX_DATA_LEN);
    }
}
