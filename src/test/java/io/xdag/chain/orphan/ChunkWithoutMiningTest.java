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
package io.xdag.chain.orphan;

import static org.junit.Assert.assertNotNull;

import io.xdag.core.Block;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Task 11 made a chunk's only home the orphan pool, and the pool's entrance sits behind the mining
 * gate, so a node that does not mine stored an arriving chunk in neither place and every paying
 * block naming it was {@code NO_PARENT} for ever. Two shapes reach that: a node whose PoW instance
 * does not exist yet, and a node with block generation turned off in config.
 *
 * <h2>Which half of the gate this class holds, and why it is two classes</h2>
 *
 * <p>The gate is {@code getEnableGenerateBlock() && getPow() != null}. Here the config half is left
 * alone — devnet sets {@code node.generate.block.enable = true} — so {@code getPow() == null} is
 * the only half holding it shut. That is the startup window every node passes through whatever its
 * configuration says, which is why it is worth a test of its own rather than a second spelling of
 * the config shape; the config shape is {@link ChunkWithGenerationDisabledTest}.
 *
 * <p>They are two classes rather than two methods because {@code AbstractConfig.enableGenerateBlock}
 * is a field with no setter and {@link io.xdag.chain.l1.ChainL1TestBase#newConfig()} is answered
 * once per class, so one class cannot show generation enabled to one test and disabled to another.
 */
public class ChunkWithoutMiningTest extends ChunkOrphanTestBase {

    /**
     * The base's {@code @Before} minus the one line that installs the PoW mock, which is what makes
     * this a node that does not mine.
     *
     * <p>An override rather than simply not calling it: the base declares
     * {@link ChunkOrphanTestBase#armTheOrphanPool()} {@code @Before}, so JUnit runs it for every
     * subclass and the only way out is to replace the body. Its other half has to stay — one real
     * main block, so the chain top weighs at least 2^46 and a delivered block drawn below that
     * floor cannot hijack it. Mining it needs no PoW instance: {@code mineMain} searches a nonce
     * itself and imports the result through {@code tryToConnect}.
     */
    @Override
    @Before
    public void armTheOrphanPool() {
        mineMain(List.of());
    }

    @Test
    public void aNodeWhosePowDoesNotExistYetStillKeepsAnArrivingChunk() {
        // Deliberately unarmed above: generation is on in config, and getPow() == null is the only
        // half holding the gate.
        Block chunk = lightChunk(711);
        assertImported(deliver(chunk));
        assertNotNull("the startup window before the PoW instance exists must not lose chunks",
                pool().chunkBody(chunk.getHashLow()));
    }
}
