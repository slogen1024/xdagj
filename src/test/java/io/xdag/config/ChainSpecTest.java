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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.typesafe.config.ConfigFactory;
import io.xdag.chain.ChainActivation;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChunkExt;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import java.util.function.Supplier;
import org.junit.Test;

public class ChainSpecTest {

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
        assertEquals(0L, config.getChainSpec().getChainActivationHeight());
        assertEquals(4096, config.getChainSpec().getChainMaxChunksPerChain());
        assertEquals(1024 * 1024, config.getChainSpec().getChainMaxWasmBytes());
        assertEquals(256, config.getChainSpec().getChainMaxInlineArgs());
        assertEquals(XAmount.of(10, XUnit.MILLI_XDAG), config.getChainSpec().getChainChunkFee());
    }

    @Test
    public void sharedNetworksAreNotScheduled() {
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getChainSpec().getChainActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getChainSpec().getChainActivationHeight());
    }

    @Test
    public void activationCanBeOverriddenProgrammatically() {
        Config config = new DevnetConfig();
        config.getChainSpec().setChainActivationHeight(100L);
        ChainActivation activation = new ChainActivation(config.getChainSpec());
        assertFalse(activation.isActive(99L));
        assertTrue(activation.isActive(100L));
        assertTrue(activation.isActive(101L));
    }

    /**
     * {@code getSetting()} (which parses {@code chain.activation.height} into
     * {@code chainActivationHeightOverride}) runs inside the {@link AbstractConfig}
     * constructor, before the per-network subclass constructor body assigns its own
     * {@code chainActivationHeight} default. This pins down that ordering: a conf-sourced
     * override (simulated here with a real JVM system property, overlaid by Typesafe above
     * the resource file) must survive the subsequent network-default assignment, and only
     * the programmatic {@link io.xdag.config.spec.ChainSpec#setChainActivationHeight(long)}
     * setter clears it.
     */
    @Test
    public void confOverrideSurvivesNetworkDefaults() {
        DevnetConfig config = withProperty("chain.activation.height", "250", DevnetConfig::new);
        assertEquals(250L, config.getChainActivationHeight());

        // The programmatic setter clears the override so the new value sticks, even though
        // the conf-sourced override is still technically present in the environment.
        config.setChainActivationHeight(999L);
        assertEquals(999L, config.getChainActivationHeight());
    }

    @Test
    public void confActivationHeightBeatsNetworkDefault() {
        long devnetHeight = withProperty("chain.activation.height", "250",
                () -> new DevnetConfig().getChainActivationHeight());
        assertEquals(250L, devnetHeight);

        long mainnetHeight = withProperty("chain.activation.height", "250",
                () -> new MainnetConfig().getChainActivationHeight());
        assertEquals(250L, mainnetHeight);
    }

    @Test
    public void confMaxChunksPerChainIsRead() {
        // Large enough that the default chain.wasm.maxBytes (1 MiB) still fits within
        // maxChunksPerChain x CHUNK_DATA_LEN, so this exercises only the conf-read path.
        int maxChunksPerChain = withProperty("chain.chunk.maxPerChain", "8192",
                () -> new DevnetConfig().getChainMaxChunksPerChain());
        assertEquals(8192, maxChunksPerChain);
    }

    @Test
    public void confMaxWasmBytesIsRead() {
        int maxWasmBytes = withProperty("chain.wasm.maxBytes", "2048",
                () -> new DevnetConfig().getChainMaxWasmBytes());
        assertEquals(2048, maxWasmBytes);
    }

    @Test
    public void confChunkFeeMilliXdagIsRead() {
        XAmount chunkFee = withProperty("chain.chunk.feeMilliXdag", "20",
                () -> new DevnetConfig().getChainChunkFee());
        assertEquals(XAmount.of(20, XUnit.MILLI_XDAG), chunkFee);
    }

    @Test
    public void negativeChunkFeeIsRejected() {
        try {
            withProperty("chain.chunk.feeMilliXdag", "-5", DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("chain.chunk.feeMilliXdag"));
        }
    }

    @Test
    public void chunkFeeOverflowIsRejected() {
        try {
            withProperty("chain.chunk.feeMilliXdag", String.valueOf(Long.MAX_VALUE), DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("chain.chunk.feeMilliXdag"));
            assertTrue(e.getCause() instanceof ArithmeticException);
        }
    }

    @Test
    public void negativeActivationHeightIsRejected() {
        try {
            withProperty("chain.activation.height", "-1", DevnetConfig::new);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("chain.activation.height"));
        }
    }

    @Test
    public void wasmBoundAboveChainCapacityIsRejected() {
        withProperty("chain.chunk.maxPerChain", "1", () -> withProperty("chain.wasm.maxBytes", "353", () -> {
            try {
                new DevnetConfig();
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("chain.wasm.maxBytes"));
            }
            return null;
        }));
    }

    @Test
    public void chunkFeeTimesMaxPerChainOverflowIsRejected() {
        // feeMilliXdag=1e10 -> chainChunkFee = 1e16 nano, itself well within XAmount.of's range; but
        // 1e16 x maxPerChain=2e9 overflows a long, which must be rejected at startup rather than
        // silently wrapped by the consensus chunk-fee re-check later.
        withProperty("chain.chunk.feeMilliXdag", "10000000000", () -> withProperty("chain.chunk.maxPerChain",
                "2000000000", () -> {
                    try {
                        new DevnetConfig();
                        fail("expected IllegalArgumentException");
                    } catch (IllegalArgumentException e) {
                        assertTrue(e.getMessage().contains("chain.chunk.feeMilliXdag"));
                        assertTrue(e.getMessage().contains("chain.chunk.maxPerChain"));
                    }
                    return null;
                }));
    }

    @Test
    public void inlineArgsBoundMatchesWireFormat() {
        assertEquals(CallExt.MAX_INLINE_ARGS, new DevnetConfig().getChainMaxInlineArgs());
    }

    /**
     * Pins {@code AbstractConfig.getSetting()}'s local {@code CHUNK_DATA_LEN} (used to
     * cross-check {@code chain.wasm.maxBytes} against chunk-chain capacity) against the real
     * wire-format constant, so the two can never silently drift apart.
     */
    @Test
    public void chunkDataLenConstantMatchesWireFormat() {
        assertEquals(352, ChunkExt.MAX_DATA_LEN);
    }

    @Test
    public void consistencyWindowDefaultsTo128() {
        assertEquals(128, new DevnetConfig().getChainSpec().getChainConsistencyWindow());
    }

    @Test
    public void consistencyWindowIsNodeLocalAndValidated() {
        assertEquals(64, (int) withProperty("chain.consistency.window", "64",
                () -> new DevnetConfig().getChainSpec().getChainConsistencyWindow()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.consistency.window", "0", DevnetConfig::new));
        assertTrue(e.getMessage().contains("chain.consistency.window"));
    }

    @Test
    public void ingestAndPersistKeysHaveNodeLocalDefaults() {
        ChainSpec spec = new DevnetConfig().getChainSpec();
        assertEquals(Runtime.getRuntime().availableProcessors(), spec.getChainIngestThreads());
        assertEquals(4096, spec.getChainIngestQueue());
        assertEquals(4096, spec.getChainPersistMaxPending());
        assertEquals(20, spec.getChainPersistFlushMs());
        assertEquals(256, spec.getChainPersistFlushEntries());
        assertEquals(65536, spec.getChainPersistReadCache());
    }

    @Test
    public void ingestAndPersistKeysAreOverridableAndValidated() {
        assertEquals(0, (int) withProperty("chain.ingest.threads", "0",
                () -> new DevnetConfig().getChainSpec().getChainIngestThreads()));
        assertEquals(0, (int) withProperty("chain.persist.maxPending", "0",
                () -> new DevnetConfig().getChainSpec().getChainPersistMaxPending()));
        assertEquals(7, (int) withProperty("chain.persist.flushMs", "7",
                () -> new DevnetConfig().getChainSpec().getChainPersistFlushMs()));
        for (String[] bad : new String[][] {
                {"chain.ingest.threads", "-1"}, {"chain.ingest.queue", "0"}, {"chain.persist.maxPending", "-1"},
                {"chain.persist.flushMs", "0"}, {"chain.persist.flushEntries", "0"}, {"chain.persist.readCache", "-1"}}) {
            IllegalArgumentException e = assertThrows(bad[0], IllegalArgumentException.class,
                    () -> withProperty(bad[0], bad[1], DevnetConfig::new));
            assertTrue(e.getMessage(), e.getMessage().contains(bad[0]));
        }
    }

    @Test
    public void flushEntriesAboveMaxPendingIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.persist.maxPending", "8",
                        () -> withProperty("chain.persist.flushEntries", "9", DevnetConfig::new)));
        assertTrue(e.getMessage(), e.getMessage().contains("chain.persist.flushEntries"));
        assertTrue(e.getMessage(), e.getMessage().contains("chain.persist.maxPending"));
    }
}
