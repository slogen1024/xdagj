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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A node holding nothing but chunk blocks used to build link blocks with no references in them:
 * eight built and signed per flood in the Task 15 measurement, none of them imported, each refused
 * by this node's own import as {@code Block's time is illegal}.
 *
 * <h2>The two halves of that, and which one this class opens with</h2>
 *
 * <p>The budget comes first. {@code getOrphanLocked} sized the packing budget from {@code
 * totalSize()}, which counts all four categories, while {@code selectBlocks} never offers a
 * {@link OrphanCategory#CHUNK} entry — a chunk is held for the block that will pay for it, not for
 * this node to reference. The gap between the two is exactly the pooled chunk count, so the budget
 * described work the selection could not do.
 *
 * <p>Correcting the budget does not on its own stop the empty block being built: {@code
 * checkOrphan} still decides to mine from {@code nnoref}, and an empty selection still fabricated
 * the timestamp that got the block refused. Those are the second half and they are held elsewhere
 * in this class.
 */
public class IdleLinkMintingTest extends ChunkOrphanTestBase {

    /**
     * The budget a link block is built against must describe what the packing walk can actually
     * hand out. {@code totalSize()} counts all four categories; {@code selectBlocks} never offers a
     * {@code CHUNK}, because a chunk is held for the block that will pay for it rather than for
     * this node to reference.
     *
     * <p>{@code armTheOrphanPool} is the base's {@code @Before} and JUnit has already run it, so
     * the pool is armed before the first line here; re-running it would only mine a second main
     * block for nothing.
     */
    @Test
    public void theSelectableCountExcludesChunks() {
        assertEquals("arming leaves the pool empty, which is the premise the counts below rest on",
                0, pool().totalSize());

        for (int i = 0; i < 5; i++) {
            assertImported(deliver(lightChunk(760 + i)));
        }
        assertEquals("five chunks arrived", 5, pool().size(OrphanCategory.CHUNK));
        assertEquals("and none of them is selectable", 0, pool().selectableSize());
        assertEquals("while the total counts them", 5, pool().totalSize());

        // One ordinary link block, so that "selectable" is asserted to be something other than
        // zero at least once: a reading of it that always answered zero would be just as wrong as
        // one that counted the chunks, and the two lines above alone cannot tell them apart. It
        // names the mined main block and so may legitimately take the chain top, which is why it
        // goes through the top-free form and comes after the chunks rather than before them.
        assertLanded("a plain link block must land", deliver(linkTo(topRef, 750)));
        assertEquals("it is pooled as a LINK", 1, pool().size(OrphanCategory.LINK));
        assertEquals("a link block is selectable; the five chunks beside it still are not",
                1, pool().selectableSize());
        assertEquals("and the total counts all six", 6, pool().totalSize());
    }
}
