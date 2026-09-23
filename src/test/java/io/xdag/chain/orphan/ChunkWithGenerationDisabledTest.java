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
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import org.junit.Test;

/**
 * The other half of {@link ChunkWithoutMiningTest}: an explorer or a pure RPC node, one that says
 * {@code node.generate.block.enable = false} and means it. Task 11 made the orphan pool a chunk's
 * only home, so such a node held an arriving chunk nowhere at all and every block paying for it was
 * {@code NO_PARENT} for ever.
 *
 * <h2>Which half of the gate this class holds</h2>
 *
 * <p>The gate is {@code getEnableGenerateBlock() && getPow() != null}. The base's {@code @Before}
 * installs the PoW mock, so that half is open and the config override below is the only half
 * holding the gate shut — the mirror image of {@link ChunkWithoutMiningTest}, which leaves config
 * alone and withholds the PoW instance. Written any other way the two would measure one thing
 * twice.
 *
 * <p>They are two classes rather than two methods because
 * {@code AbstractConfig.enableGenerateBlock} is a field with no setter and
 * {@link io.xdag.chain.l1.ChainL1TestBase#newConfig()} is answered once per class.
 */
public class ChunkWithGenerationDisabledTest extends ChunkOrphanTestBase {

    /**
     * A node that generates no blocks. The setting has no setter — the chain settings deliberately
     * do not, and assigning the inherited {@code config} field is lost anyway — so a whole
     * {@code Config} is what a test supplies to change one.
     */
    @Override
    protected Config newConfig() {
        return new GenerationOffDevnetConfig();
    }

    private static final class GenerationOffDevnetConfig extends DevnetConfig {
        @Override
        public boolean getEnableGenerateBlock() {
            return false;
        }
    }

    @Test
    public void aNodeWithBlockGenerationOffStillKeepsAnArrivingChunk() {
        // The base's @Before has already installed the PoW mock, so config is the only half
        // holding the gate.
        Block chunk = lightChunk(701);
        assertImported(deliver(chunk));
        assertNotNull("a node that does not mine must still hold the chunk it was sent",
                pool().chunkBody(chunk.getHashLow()));

        Block paying = linkTo(hashLow(chunk), 702);
        ImportResult r = deliver(paying);
        assertTrue("the block that pays for the chunk must import, not hang on NO_PARENT: "
                + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
    }
}
