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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.listener.BlockMessage;
import io.xdag.utils.XdagTime;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * What a node does when the chunks it was holding are gone — aged out, or lost to a restart — and
 * the block that would have paid for them turns up afterwards.
 *
 * <h2>The claim being tested</h2>
 *
 * <p>SP0b-3 gave chunks a two-epoch TTL and made them memory-only, so a node now routinely throws
 * away blocks it was holding. That is only safe because of {@code ChunkChain}'s age rule: a chunk in
 * epoch N can be referenced legitimately only from epoch N or N+1, and the retention covers exactly
 * that, so anything that arrives later would have been refused <em>whether or not this node still
 * held the bytes</em>. The claim, therefore, is not "dropping chunks is harmless" — it is the much
 * more checkable <b>a node that dropped them reaches the same verdict as a node that never had
 * them</b>. Two honest nodes cannot be made to disagree by their retention.
 *
 * <p>{@code ChunkDeferredPersistTest.aChunkAgedOutBeforeItsPayingBlockIsNoParent} asserts the first
 * half of that (the evicted node says {@code NO_PARENT}) and asserts the second half in a comment.
 * Here both halves are run and compared.
 *
 * <h2>Why each comparison builds a second node</h2>
 *
 * <p>The two runs have to produce byte-identical blocks or the comparison is between two different
 * questions — {@code NO_PARENT} names the hash it could not find, so even the failure text would
 * differ for uninteresting reasons. {@link #freshFixture} rebuilds the fixture on the same mining
 * timeline, which is what {@code IngestEquivalenceTest} and {@code ChunkFeePolicyTest} do for the
 * same reason.
 */
public class ChunkFloodAdversarialTest extends ChunkOrphanTestBase {

    /**
     * Orphans this node must be holding for {@code checkOrphan} to mint link blocks at a rate this
     * test can predict. See {@link #aChunkFloodMintsLinkBlocksThatCanReferenceNothing} for the
     * arithmetic — the number is chosen so the sampling inside {@code checkOrphan} cannot fire.
     */
    private static final long NNOREF_TARGET = 671;

    /** {@code checkOrphan} calls per arm of the idle-mining measurement. */
    private static final int TICKS = 8;

    /**
     * Step 1: a chunk aged out, then the block that would have paid for it.
     *
     * <p>The verdict must be the one a node that never received the chunk gives — which is what the
     * age rule guarantees, and what makes eviction safe to do at all.
     *
     * <p><b>{@code nnoref} is deliberately not part of the comparison.</b> The sweep used here is
     * {@code ChainOrphanPool.evictExpired}, the memory half of the cleaner tick, which is how the
     * other chunk-TTL tests drive eviction. For a chunk the other half is the {@code nnoref}
     * decrement alone — a chunk has no ORPHANIND row to delete — so the evicted node's count sits
     * one higher here than production would leave it. That is node-local mining bookkeeping; it
     * cannot make two nodes disagree about a block, and it is pinned where it belongs
     * ({@code OrphanRefusalAccountingTest}, {@code OrphanBlockStoreWiringTest}).
     */
    @Test
    public void aChunkEvictedByTtlGivesTheSameVerdictAsOneNeverReceived() throws Exception {
        Verdict evicted = payingBlockAfter(Holding.RECEIVED_THEN_EVICTED);
        freshFixture();
        Verdict neverSeen = payingBlockAfter(Holding.NEVER_RECEIVED);

        assertEquals("eviction must not change the verdict the age rule already fixes",
                neverSeen, evicted);
    }

    /**
     * Step 5: the same equality across a restart rather than across a TTL sweep, through the real
     * {@code rebuildMemoryFromDb}.
     *
     * <h2>This is not what the plan asked for, and the plan was stale</h2>
     *
     * <p>The plan's Step 5 was "a chunk's remaining lifetime is not reset by a restart". Task 11
     * then decided chunks are written to neither the block store nor ORPHANIND, which
     * {@code OrphanBlockStoreImpl}'s header spells out: the rebuild "can only ever produce non-chunk
     * entries, by construction rather than by a check". After a restart a chunk does not exist, so
     * its remaining lifetime is not reset for the same reason a number nobody wrote down is not
     * corrupted — vacuously, and a test asserting it would be green whatever the code did.
     *
     * <p>What is worth pinning instead is the property Task 11 relied on when it made that choice:
     * losing them is harmless because the paying block gets the same answer either way. So this is
     * Step 1's comparison with the restart in place of the sweep, and
     * {@link #aRestartRestoresEveryNonChunkOrphanAndThePackingOrder} is the other side of it — what
     * a rebuild does restore.
     */
    @Test
    public void aRestartDropsEveryChunkAndTheVerdictIsStillTheOneTheAgeRuleFixes() throws Exception {
        Verdict restarted = payingBlockAfter(Holding.RECEIVED_THEN_RESTARTED);
        freshFixture();
        Verdict neverSeen = payingBlockAfter(Holding.NEVER_RECEIVED);

        assertEquals("a restart must not change the verdict either: the chunks are gone, and a node"
                + " that never had them says the same thing", neverSeen, restarted);
    }

    /**
     * The other half of Step 5: what the rebuild <em>does</em> bring back, and in what order.
     *
     * <p>A non-chunk orphan has an ORPHANIND row, so it survives; a chunk has none, so it cannot.
     * The rebuild needs no category byte to tell them apart because the row format already does it
     * by construction, and this is that claim as an assertion rather than an argument.
     *
     * <p>The order matters as much as the membership. Link entries are packed oldest-first, and the
     * order comes from the block's own timestamp in the row — not from the order the rows come back
     * in, and not from when this node happened to receive them. So the blocks are delivered newest
     * first, which makes arrival order the opposite of packing order, and the head of the queue has
     * to be the same block before and after.
     *
     * <p><b>What does not survive is the fifteen-minute receipt stamp</b>, which
     * {@code rebuildMemoryFromDb} takes fresh for every rebuilt entry. That is deliberate and
     * harmless: unlike the chunk TTL it is a local memory policy with no protocol rule behind it,
     * so a restart legitimately gives every surviving orphan another fifteen minutes. The chunk TTL
     * is the one that had to be restart-stable, and it is — it is derived from the block's own
     * header, so it does not depend on this node at all.
     */
    @Test
    public void aRestartRestoresEveryNonChunkOrphanAndThePackingOrder() {
        // Newest first, so arrival order is the reverse of packing order.
        Block newest = linkTo(topRef, 71, txTime() + 3);
        Block middle = linkTo(topRef, 72, txTime() + 2);
        Block oldest = linkTo(topRef, 73, txTime() + 1);
        assertTrue("the fixture must deliver these newest first for the order to mean anything",
                newest.getTimestamp() > middle.getTimestamp()
                        && middle.getTimestamp() > oldest.getTimestamp());
        // The chunk first, and through assertImported, because that is the delivery whose chain-top
        // assumption still holds: the three link blocks below name the mined main block, so they
        // inherit its weight and one of them legitimately becomes the new top. That is a
        // consequence of linking the heaviest block in the fixture and has nothing to do with what
        // is being pinned here, so they go through the bare entry point, which asserts nothing
        // about the top.
        Block chunk = lightChunk(74);
        assertImported(deliver(chunk));
        assertLanded(blockchain.tryToConnect(newest));
        assertLanded(blockchain.tryToConnect(middle));
        assertLanded(blockchain.tryToConnect(oldest));

        long pooledBefore = blockchain.getOrphanBlockStore().getOrphanSize();
        assertEquals("three link orphans and one chunk", 4, pooledBefore);
        assertEquals("the link queue is packed oldest first, whatever order the blocks arrived in",
                hashLow(oldest), pool().first(OrphanCategory.LINK).hashlow());

        store().rebuildMemoryFromDb();

        assertNull("a chunk has no row, so a rebuild cannot bring it back", pool().get(hashLow(chunk)));
        assertNull("nor its body", blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
        assertNull("and it was never on disk to be found there either",
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        assertEquals("every non-chunk orphan comes back and nothing else does",
                3, blockchain.getOrphanBlockStore().getOrphanSize());
        for (Block link : List.of(newest, middle, oldest)) {
            OrphanEntry entry = pool().get(hashLow(link));
            assertNotNull("a link orphan must survive the rebuild: " + link.getHashLow(), entry);
            assertEquals(OrphanCategory.LINK, entry.category());
            assertEquals("the row carries the block's own timestamp, which is the sort key",
                    link.getTimestamp(), entry.meta().getTime());
        }
        assertEquals("the queue must be rebuilt in packing order, not in the order the rows"
                        + " came back or the order the blocks arrived",
                hashLow(oldest), pool().first(OrphanCategory.LINK).hashlow());
    }

    /**
     * Step 3b: the cost Task 10 left behind, measured rather than argued about.
     *
     * <h2>The concern</h2>
     *
     * <p>A chunk raises {@code nnoref} on arrival like any other orphan, but since Task 10 filed
     * chunks in their own category no block this node mines can reference one —
     * {@code selectBlocks} never consults the chunk set, by design, because an unpaid chunk is data
     * nobody has paid to have referenced. {@code checkOrphan} mines link blocks at
     * {@code nnoref / 11}, so a chunk flood drives this node to mine link blocks that have nothing
     * to carry. The counting itself is right — Task 7 made the decrement per entry, so the number
     * is not wrong — but a correct count is not the same thing as no waste.
     *
     * <h2>How the sampling is disarmed rather than averaged over</h2>
     *
     * <p>{@code checkOrphan} samples: {@code nblk = nnoref / 11}, then
     * {@code b = (nblk % 61) > nextLong(0, 61)} and {@code nblk = nblk / 61 + (b ? 1 : 0)}. The
     * draw cannot change the answer exactly when {@code nblk % 61 == 0}, because {@code 0 > x} is
     * false for every {@code x} it can produce. {@link #NNOREF_TARGET} = 671 puts {@code nblk} at
     * 61, so the tick mints exactly one link block with no randomness in it — and in the chunk arm
     * {@code nnoref} never moves, so that holds for all {@link #TICKS} ticks rather than just the
     * first. The link arm's own count does move, and from its second tick on the draw is live
     * again; that is why nothing below asserts an exact number for it.
     *
     * <h2>What it found</h2>
     *
     * <p>Eight ticks against 670 flood blocks on each side.
     *
     * <ul>
     *   <li><b>Link flood</b> (the control): every tick that minted did so with twelve references
     *       and retired twelve orphans, so {@code nnoref} fell by eleven per block — 671 &rarr; 583
     *       over eight blocks in one run, 671 &rarr; 594 over seven in another (the draw is live
     *       again from the second tick, which is why the count varies and nothing asserts it).
     *       Mining is how a link flood is drained, and it drains.</li>
     *   <li><b>Chunk flood</b>: {@code nnoref} ended exactly where it started, 671, and not one of
     *       the 670 pooled chunks was touched. Worse than idle: with the pool holding nothing but
     *       chunks the node has nothing at all to reference, so every block it mined came out with
     *       zero references and a timestamp of 1 ({@code sendtime[1]} defaults to 0 when the
     *       selection is empty) — which its own import path then refuses as "Block's time is
     *       illegal". Eight ticks, eight blocks built and signed, <b>none</b> of them imported.</li>
     * </ul>
     *
     * <p>And it does not stop: because {@code nnoref} cannot move, {@code nblk} stays at 61 for
     * every subsequent tick too, so the node keeps doing this until the chunks' two epochs are up
     * and the cleaner gives the count back. The waste is bounded by the TTL, not by anything the
     * mining does.
     *
     * <p><b>This is a measurement and not a fix.</b> Changing what {@code checkOrphan} counts would
     * change block-production cadence, which belongs to another change. The finding is recorded as
     * an open item in Task 16's document.
     *
     * <p><b>If this test ever goes red because the chunk arm started minting or retiring</b>, that
     * is not a broken test — it means the open item has been addressed, and the document should
     * follow.
     */
    @Test
    public void aChunkFloodMintsLinkBlocksThatCanReferenceNothing() throws Exception {
        Tick chunks = floodThenTick(true);
        freshFixture();
        Tick links = floodThenTick(false);

        // The control first: without it every assertion below could be satisfied by a node that had
        // simply stopped mining link blocks.
        assertTrue("the control is empty unless the node really mines under a non-chunk flood: "
                + links, links.minted > 0);
        assertTrue("under a non-chunk flood the mining retires the flood -- about twelve orphans"
                        + " per block minted: " + links,
                links.floodOrphansRetired >= 10L * links.minted);
        assertTrue("and brings nnoref down with it: " + links,
                links.nnorefAfter < links.nnorefBefore);

        assertEquals("a pool of nothing but chunks offers a link block nothing to carry: "
                + chunks, 0, chunks.linkOrphansAvailable);
        assertEquals("a link block cannot reference a pooled chunk, so a chunk flood retires none"
                + " of itself: " + chunks, 0, chunks.floodOrphansRetired);
        assertEquals("nor can any of it be imported -- with nothing to reference, what the node"
                + " mines is refused by its own import path: " + chunks, 0, chunks.minted);
        assertEquals("so nnoref cannot come down by mining at all, and only the TTL gives it back: "
                + chunks, chunks.nnorefBefore, chunks.nnorefAfter);
    }

    // ---- the two comparisons -------------------------------------------------------------

    /** Which of the three states the node is in when the paying block turns up. */
    private enum Holding {
        RECEIVED_THEN_EVICTED, RECEIVED_THEN_RESTARTED, NEVER_RECEIVED
    }

    /**
     * Everything about the paying block's fate that two honest nodes could disagree about: the
     * verdict, what it named, and whether anything was written on the way to it.
     */
    private record Verdict(String result, String errorInfo, String namedHash, boolean payingOnDisk,
                           boolean payingHasInfo, boolean chunkOnDisk, boolean chunkHeldInMemory,
                           String top) {
    }

    /**
     * Builds one chunk and the ordinary link block that would pay for it, puts the node into
     * {@code holding}, delivers the paying block and records what happened.
     *
     * <p>The blocks are built the same way in every state — same seeds, same mining timeline — so
     * two runs of this produce the same two blocks and the records are comparable field by field.
     */
    private Verdict payingBlockAfter(Holding holding) {
        Block chunk = lightChunk(70);
        if (holding != Holding.NEVER_RECEIVED) {
            assertImported(deliver(chunk));
            assertNotNull("the chunk must really be held before it is taken away",
                    blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
        }
        if (holding == Holding.RECEIVED_THEN_EVICTED) {
            long epoch = XdagTime.getEpoch(chunk.getTimestamp());
            // Two epochs on: past the last one a paying block could legitimately reference it from,
            // which is what the store's cleaner hands the pool once the chain has moved that far.
            assertEquals("the chunk TTL must actually fire, or this is not the state it says it is",
                    1, pool().evictExpired(0L, epoch + 2).size());
        }
        if (holding == Holding.RECEIVED_THEN_RESTARTED) {
            // The real rebuild, the one start() runs at boot: it empties the pool and refills it
            // from ORPHANIND, and a chunk has no row there to be refilled from.
            store().rebuildMemoryFromDb();
        }

        Block paying = linkTo(hashLow(chunk), 70);
        ImportResult r = deliver(paying);
        return new Verdict(r.toString(), r.getErrorInfo(),
                r.getHashlow() == null ? null : r.getHashlow().toHexString(),
                kernel.getBlockStore().getRawBlockByHash(paying.getHashLow()) != null,
                kernel.getBlockStore().hasBlockInfo(paying.getHashLow()),
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()) != null,
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)) != null,
                Bytes32.wrap(blockchain.getXdagTopStatus().getTop()).toHexString());
    }

    /** One arm of the idle-mining measurement. */
    private record Tick(String flood, long nnorefBefore, long nnorefAfter, int linkOrphansAvailable,
                        int minted, int referencesCarried, long floodOrphansRetired) {
    }

    /**
     * Floods the node to {@link #NNOREF_TARGET} orphans of one kind, then runs {@link #TICKS}
     * {@code checkOrphan} ticks and records what they mined and what it did to the pool.
     *
     * <p>The same blocks in both arms: a classified delivery files one as a chunk, the bare entry
     * point files the identical block as a link block (a null classification can never answer
     * CHUNK). So the arms differ in the category and in nothing else — not in block size, not in
     * difficulty, not in how many were sent.
     */
    private Tick floodThenTick(boolean asChunks) {
        long start = blockchain.getXdagStats().nnoref;
        for (long i = start; i < NNOREF_TARGET; i++) {
            Block block = lightChunk((int) (100 + i));
            ImportResult r = asChunks ? deliver(block) : blockchain.tryToConnect(block);
            assertTrue("flood block refused: " + r + " " + r.getErrorInfo(),
                    r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        }
        long before = blockchain.getXdagStats().nnoref;
        assertEquals("the flood has to land exactly here or checkOrphan's sampling is live and the"
                + " count stops being predictable", NNOREF_TARGET, before);
        // One of those 671 is the fixture's own address block, counted at setUpChain -- before
        // armTheOrphanPool set the PoW instance, so dealOrphan returned false and pooled nothing
        // while isHeldByThisNode still counted it. That is why the pool holds 670 and nnoref says
        // 671, and it is the same one block in both arms.
        long floodPooledBefore = asChunks
                ? pool().size(OrphanCategory.CHUNK) : pool().size(OrphanCategory.LINK);
        // What a link block would have to carry. Zero in the chunk arm, and that is the finding
        // rather than a fixture quirk: a mined main block is BI_EXTRA and goes to memOrphanPool
        // instead of the orphan pool, so a node whose only inbound traffic is a chunk flood really
        // does hold nothing a link block may reference.
        int linkAvailable = pool().size(OrphanCategory.LINK);

        List<Block> minted = new ArrayList<>();
        // onNewBlock is called from exactly one place -- checkOrphan, for a link block it mined and
        // successfully imported -- so this listener counts that and nothing else.
        blockchain.registerListener(message -> {
            if (message instanceof BlockMessage block) {
                minted.add(new Block(new XdagBlock(block.getData().toArray())));
            }
        });
        for (int i = 0; i < TICKS; i++) {
            blockchain.checkOrphan();
        }

        int references = 0;
        for (Block block : minted) {
            references += block.getLinks().size();
        }
        long floodPooledAfter = asChunks
                ? pool().size(OrphanCategory.CHUNK) : pool().size(OrphanCategory.LINK);
        // What the mining actually took out of the flood. The link arm's own count includes the
        // blocks checkOrphan minted (they are link orphans too), so the minted ones are added back.
        long retired = floodPooledBefore - floodPooledAfter + (asChunks ? 0 : minted.size());
        return new Tick(asChunks ? "chunk" : "link", before, blockchain.getXdagStats().nnoref,
                linkAvailable, minted.size(), references, retired);
    }

    // ---- fixture -------------------------------------------------------------------------

    /**
     * Imported, without the chain-top assertion {@code assertImported} adds. For a delivery that is
     * allowed to move the top — a block naming the mined main block inherits its weight — where the
     * top is not what is being pinned.
     */
    private static void assertLanded(ImportResult r) {
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
    }

    private OrphanBlockStoreImpl store() {
        return (OrphanBlockStoreImpl) blockchain.getOrphanBlockStore();
    }

    /**
     * A second node on the same mining timeline, so both halves of a comparison build byte-identical
     * blocks. {@code setUpChain} calls {@code root.newFolder("node")}, which throws if the directory
     * is still there.
     */
    private void freshFixture() throws Exception {
        tearDownChain();
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = FIXTURE_START;
        setUpChain();
        armTheOrphanPool();
    }
}
