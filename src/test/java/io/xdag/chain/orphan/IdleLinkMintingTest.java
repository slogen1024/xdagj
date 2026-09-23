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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.xdag.core.Address;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Message;
import io.xdag.utils.XdagTime;
import org.junit.Test;

/**
 * A node holding nothing but chunk blocks used to build link blocks with no references in them:
 * eight built and signed per flood in {@code ChunkFloodAdversarialTest
 * #aChunkFloodDrivesMiningRoundsThatBuildNothing}, none of them imported, each refused by this
 * node's own import as {@code Block's time is illegal}. This class is where that is taken apart,
 * and it now pins all three pieces of the answer.
 *
 * <h2>The budget</h2>
 *
 * <p>{@code getOrphanLocked} sized the packing budget from {@code totalSize()}, which counts all
 * four categories, while {@code selectBlocks} never offers a {@link OrphanCategory#CHUNK} entry
 * — a chunk is held for the block that will pay for it, not for this node to reference. The gap
 * between the two was exactly the pooled chunk count, so the budget described work the selection
 * could not do. It now comes from {@link ChainOrphanPool#selectableSize}.
 *
 * <h2>The block that was built anyway, and the timestamp it was stamped with</h2>
 *
 * <p>Correcting the budget does not on its own stop the empty block being built: {@code
 * checkOrphan} still decides to mine from {@code nnoref} — deliberately, that statistic is not
 * redefined — so the node still reaches {@code createLinkBlock} with a pool that can hand it
 * nothing. Two changes close it there, and they had to land together: {@code createLinkBlock}
 * returns {@code null} rather than build a reference-less block, and {@code getOrphanLocked} runs
 * its "one past the newest reference" line only when something was selected. Fixing the timestamp
 * alone would have produced a block stamped 0 instead of 1 — worse, not better — and building
 * alone would have kept stamping 1.
 *
 * <p>This is the one externally observable block-production change of the addendum: a node in the
 * "nothing to reference" state stops emitting a link block. What it stops emitting is a block its
 * own import refuses, so nothing is lost.
 */
public class IdleLinkMintingTest extends ChunkOrphanTestBase {

    /**
     * The smallest {@code nnoref} that makes {@code checkOrphan} run exactly one minting round with
     * no sampling: {@code 671 / 11 == 61}, and {@code 61 % 61 == 0}, so the draw cannot change it.
     */
    private static final long NNOREF_FOR_ONE_ROUND = 671;

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

    /**
     * The block this node used to build when the pool could hand it nothing: no references, and a
     * timestamp of 1, because the empty selection still ran the "newest reference plus one" line.
     * Its own import refused it as {@code Block's time is illegal} — eight built and signed per
     * flood in {@code ChunkFloodAdversarialTest#aChunkFloodDrivesMiningRoundsThatBuildNothing},
     * none of them imported.
     *
     * <p>It returns null instead now, and that is what this pins.
     */
    @Test
    public void aPoolHoldingOnlyChunksBuildsNoLinkBlockAtAll() {
        drainSelectableEntries();
        for (int i = 0; i < 5; i++) {
            assertImported(deliver(lightChunk(770 + i)));
        }
        assertEquals("the pool holds only chunks", 0, pool().selectableSize());
        assertEquals("and it really is holding them, so this is not an empty-pool test wearing"
                + " a chunk-pool name", 5, pool().size(OrphanCategory.CHUNK));

        assertNull("with nothing to reference there is no link block to build",
                blockchain.createLinkBlock(null, false));
    }

    /**
     * The caller's half: {@code checkOrphan} must survive the null {@code createLinkBlock} now
     * returns, rather than dereferencing it.
     *
     * <h2>Why this can be cheap, when the comment at the guard used to say it could not</h2>
     *
     * <p>Reaching the guard deterministically needs {@code nblk > 0} with {@code nblk % 61 == 0} —
     * the one point where {@code checkOrphan}'s sampling draw cannot change the answer — and
     * {@code nblk} is {@code nnoref / 11}, so {@code nnoref} has to be 671. That was read as "so it
     * needs the 670-block flood fixture", and it does not: {@code nnoref} is a plain field on
     * {@link io.xdag.core.XdagStats} with a setter, and {@code OrphanBlockStoreWiringTest} already
     * assigns it by hand. Nothing resets it in between here, because {@code MockBlockchain
     * .startCheckMain} is a no-op, so no scheduler is running.
     *
     * <p>Assigning it is not the same experiment as earning it, which is why
     * {@code ChunkFloodAdversarialTest} keeps reaching the same line from a real flood. This one
     * pins that the guard holds; that one pins that the arithmetic above it survives a pool filled
     * the way production fills it.
     */
    @Test
    public void checkOrphanSurvivesAPoolThatCanHandOutNothing() {
        drainSelectableEntries();
        for (int i = 0; i < 5; i++) {
            assertImported(deliver(lightChunk(780 + i)));
        }
        assertEquals("the pool holds only chunks", 0, pool().selectableSize());

        List<Message> minted = new ArrayList<>();
        blockchain.registerListener(message -> {
            if (message instanceof BlockMessage) {
                minted.add(message);
            }
        });

        // 671 / 11 = 61, and 61 % 61 == 0, so the draw cannot fire and this is exactly one round.
        blockchain.getXdagStats().nnoref = NNOREF_FOR_ONE_ROUND;
        blockchain.checkOrphan();

        assertEquals("a round that can reference nothing must mint nothing", 0, minted.size());
        assertEquals("and must leave the count that drove it alone",
                NNOREF_FOR_ONE_ROUND, blockchain.getXdagStats().nnoref);
    }

    /** The latent half: an empty selection must not fabricate a timestamp for its caller. */
    @Test
    public void anEmptySelectionLeavesTheCallersTimestampAlone() {
        drainSelectableEntries();
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getCurrentTimestamp();

        List<Address> refs = blockchain.getBlockFromOrphanPool(13, sendTime, false);

        assertTrue("nothing was selected", refs.isEmpty());
        assertEquals("so nothing may be claimed about when the newest reference was",
                0, sendTime[1]);
    }

    /**
     * Takes selectable entries away until the packing walk can hand out nothing, which is the
     * premise both tests above rest on.
     *
     * <p>Arming leaves the pool empty today — {@link #theSelectableCountExcludesChunks} asserts
     * exactly that — so this normally drains nothing at all. It is written to tolerate zero
     * iterations rather than to require one, and capped so a fixture that started leaving entries
     * behind fails here instead of spinning for ever.
     */
    private void drainSelectableEntries() {
        for (int rounds = 0; pool().selectableSize() > 0; rounds++) {
            assertTrue("the drain is not converging: selectable entries keep coming back, so the"
                    + " fixture is no longer the one this helper was written against", rounds < 64);
            blockchain.getBlockFromOrphanPool(16,
                    new long[]{XdagTime.getCurrentTimestamp(), 0}, false);
        }
        assertEquals("the pool must hand out nothing before the caller's first delivery",
                0, pool().selectableSize());
    }
}
