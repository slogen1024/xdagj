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

import io.xdag.Kernel;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.listener.BlockMessage;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
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
 * differ for uninteresting reasons. {@code freshFixture} rebuilds the fixture on the same mining
 * timeline, which is what {@code IngestEquivalenceTest} and {@code ChunkFeePolicyTest} do for the
 * same reason.
 *
 * <h2>And one measurement, which is here for the same two reasons</h2>
 *
 * <p>{@link #aChunkFloodMintsLinkBlocksThatCanReferenceNothing} is not about losing chunks; it
 * prices what holding them costs a mining node, which is the other half of the subproject's
 * dealings with a chunk flood. It sits in this class because it needs exactly what the tests above
 * need and nothing more: the armed pool that makes the node produce blocks at all, and the
 * same-timeline {@code freshFixture} to put its two arms on identical nodes.
 *
 * <p><b>It began as a measurement and is now also a regression</b>, which is why this class builds
 * a {@link CountingBlockchain} where the other two would be content with the plain fixture. The
 * waste it priced has since been fixed (the 2026-09-23 addendum,
 * {@code docs/superpowers/specs/2026-09-23-sp0b3-chunk-pooling-without-mining-design.md}), and what
 * it holds in place now is the node's side of that fix: with nothing to reference it builds no
 * block at all. It is also the only place in the suite that reaches {@code checkOrphan}'s guard
 * against a null link block — see {@link #aChunkFloodMintsLinkBlocksThatCanReferenceNothing} for
 * why no cheaper fixture gets there.
 */
public class ChunkFloodAdversarialTest extends ChunkOrphanTestBase {

    /**
     * Orphans this node must be holding for {@code checkOrphan} to enter its minting round at a
     * rate this test can predict. See {@link #aChunkFloodMintsLinkBlocksThatCanReferenceNothing} for the
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
     * Step 3b: the cost Task 10 left behind, measured rather than argued about — and, since the
     * 2026-09-23 addendum, the regression that holds in place what replaced it.
     *
     * <p><b>The name is the state this was written to catch, not the state it now finds</b>: the
     * node does not mint those blocks any more, and the assertions below are what stops it going
     * back to doing so. The name is kept because {@code BlockchainImpl.checkOrphan} cites this
     * method by name from beside the guard it witnesses, and a citation that still resolves is
     * worth more than a tidier name.
     *
     * <h2>The concern</h2>
     *
     * <p>A chunk raises {@code nnoref} on arrival like any other orphan, but since Task 10 filed
     * chunks in their own category no block this node mines can reference one —
     * {@code selectBlocks} never consults the chunk set, by design, because an unpaid chunk is data
     * nobody has paid to have referenced. {@code checkOrphan} mines link blocks at
     * {@code nnoref / 11}, so a chunk flood drove this node to mine link blocks that had nothing
     * to carry. The counting itself is right — Task 7 made the decrement per entry, so the number
     * is not wrong — but a correct count is not the same thing as no waste.
     *
     * <h2>How the sampling is disarmed rather than averaged over</h2>
     *
     * <p>{@code checkOrphan} samples: {@code nblk = nnoref / 11}, then
     * {@code b = (nblk % 61) > nextLong(0, 61)} and {@code nblk = nblk / 61 + (b ? 1 : 0)}. The
     * draw cannot change the answer exactly when {@code nblk % 61 == 0}, because {@code 0 > x} is
     * false for every {@code x} it can produce. {@link #NNOREF_TARGET} = 671 puts {@code nblk} at
     * 61, so the tick enters exactly one minting round with no randomness in it — and in the chunk
     * arm {@code nnoref} never moves, so that holds for all {@link #TICKS} ticks rather than just
     * the first. The link arm's own count does move, and from its second tick on the draw is live
     * again; that is why nothing below asserts an exact number for it — what is asserted there is
     * how its three counts relate to each other, not what any of them is.
     *
     * <h2>What it found, before the fix</h2>
     *
     * <p>Eight ticks against 670 flood blocks on each side. The numbers are kept here in the tense
     * they were taken in, because they are what the correction is measured against — Task 16's
     * document compares them with an after.
     *
     * <ul>
     *   <li><b>Link flood</b> (the control): every tick that minted did so with twelve references
     *       and retired twelve orphans, so {@code nnoref} fell by eleven per block — 671 &rarr; 583
     *       over eight blocks in one run, 671 &rarr; 594 over seven in another (the draw is live
     *       again from the second tick, which is why the count varies and nothing asserts it).
     *       Mining is how a link flood is drained, and it drains. <b>This arm is unchanged</b>: the
     *       fix was to the "nothing to reference" state and a link flood is never in it.</li>
     *   <li><b>Chunk flood</b>: {@code nnoref} ended exactly where it started, 671, and not one of
     *       the 670 pooled chunks was touched. Worse than idle: with the pool holding nothing but
     *       chunks the node had nothing at all to reference, so every block it mined came out with
     *       zero references and a timestamp of 1 ({@code sendtime[1]} defaulted to 0 when the
     *       selection was empty, and the "one past the newest reference" line ran anyway) — which
     *       its own import path then refused as "Block's time is illegal". Eight ticks, eight
     *       blocks built and signed, <b>none</b> of them imported. And it did not stop: because
     *       {@code nnoref} cannot move, {@code nblk} stayed at 61 for every subsequent tick too,
     *       so the node kept at it until the chunks' two epochs were up and the cleaner gave the
     *       count back. The waste was bounded by the TTL, not by anything the mining did.</li>
     * </ul>
     *
     * <h2>What it pins now</h2>
     *
     * <p>The chunk arm still enters its mining round once per tick — {@code nnoref} is not
     * redefined, so {@code nblk} is still 1 — and comes away with nothing built:
     * {@code createLinkBlock} declines rather than construct a block with no references, and
     * {@code checkOrphan} ends the round on that null. Eight rounds entered, none of them built,
     * none imported.
     *
     * <p><b>Why "built" had to be counted separately, and why this class did not notice the change
     * on its own.</b> {@code minted} comes from an {@code onNewBlock} listener, and
     * {@code checkOrphan} calls {@code onNewBlock} only on an {@code IMPORTED_*} result — so
     * "built and then refused" and "never built at all" satisfy {@code minted == 0} identically,
     * and this test stayed green straight through the fix while the behaviour underneath it
     * changed completely. Construction leaves no trace of its own to read: the block that is not
     * built is in no store, no listener and no statistic. {@link CountingBlockchain} is where that
     * is read instead, and {@link Tick#attempts} beside {@link Tick#built} is what separates a node
     * that declined to build from one that simply stopped mining.
     *
     * <p><b>The {@code checkOrphan} call in {@code floodThenTick} is load-bearing twice over.</b>
     * Besides driving the measurement it is the only regression witness in the suite for that null
     * guard: reaching it deterministically needs {@code nblk > 0} with {@code nblk % 61 == 0}, so
     * {@code nnoref} must be at least 671, which is this class's 670-block flood and nothing
     * cheaper. Take the guard away and the chunk arm dies on {@code signOut} of a null block. The
     * same note is at the guard itself.
     *
     * <h2>Where the mismatch lived</h2>
     *
     * <p>{@code nnoref / 11} was what drove the mining, but it was not the only place a count that
     * includes chunks met a selection that cannot supply them, and it was not the narrowest. The
     * budget a link block is built with comes from {@code OrphanBlockStoreImpl.getOrphanLocked},
     * which for a link block asked for a share of {@code getOrphanSize()} —
     * {@code ChainOrphanPool.totalSize()}, <b>all four categories, chunks included</b> — while
     * {@code selectBlocks} never offers a {@link OrphanCategory#CHUNK} entry to anybody. So the
     * budget was computed from a total that counted blocks the selection was not allowed to hand
     * over, and the gap between the two was exactly the pooled chunk count.
     *
     * <p><b>Of the two ways to close that, the budget was the one that moved.</b> It now comes from
     * {@link ChainOrphanPool#selectableSize}, which is the total less the chunks — the count
     * describing what the packing walk can actually hand out. The other option, narrowing the count
     * that drives the mining, was rejected deliberately (addendum D3): {@code nnoref} is persisted
     * and read in many places, and Task 13 had just fixed its meaning on purpose — "is this node
     * holding the block anywhere" — so letting a mining decision redefine it, or mixing a durable
     * counter with a live in-memory one in the same expression, would have been dirtier than the
     * problem. The correction went downstream of it instead, which is also why the node still
     * decides to mine on every tick above and finds nothing to mine with.
     *
     * <p>Naming the place here rather than only in the plan is deliberate — a line number in a plan
     * goes stale, and a measurement that cannot say which expression it indicts leaves the next
     * person to find it again.
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
        assertEquals("and it mines by building: every round the control entered produced a block."
                + " This is what makes the chunk arm's zero below a decision rather than a reading"
                + " that could only ever answer zero: " + links, links.attempts, links.built);
        assertEquals("all of which imported, so here the two counts agree and either one would"
                + " have done: " + links, links.built, links.minted);
        assertTrue("under a non-chunk flood the mining retires the flood -- about twelve orphans"
                        + " per block minted: " + links,
                links.floodOrphansRetired >= 10L * links.minted);
        assertTrue("and brings nnoref down with it: " + links,
                links.nnorefAfter < links.nnorefBefore);

        assertEquals("a pool of nothing but chunks offers a link block nothing to carry: "
                + chunks, 0, chunks.linkOrphansAvailable);
        assertEquals("a link block cannot reference a pooled chunk, so a chunk flood retires none"
                + " of itself: " + chunks, 0, chunks.floodOrphansRetired);
        assertEquals("the node still decides to mine on every tick -- nnoref is not redefined, so"
                + " nblk is 1 every time -- which is what makes the next line a refusal to build"
                + " rather than a node that stopped mining: " + chunks, TICKS, chunks.attempts);
        assertEquals("and with nothing to reference it builds nothing at all, where it used to"
                + " build and sign one block per tick for its own import to refuse: " + chunks,
                0, chunks.built);
        assertEquals("so nothing is imported either -- which this line cannot tell apart from the"
                + " line above on its own, and is why both are here: " + chunks, 0, chunks.minted);
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

    /**
     * One arm of the idle-mining measurement.
     *
     * <p>{@code attempts}, {@code built} and {@code minted} are three different numbers and the
     * distinction is the point: a round entered, a block constructed, a block this node's own
     * import accepted. Before the addendum the chunk arm was 8 / 8 / 0 and only the last of those
     * was being read.
     */
    private record Tick(String flood, long nnorefBefore, long nnorefAfter, int linkOrphansAvailable,
                        int attempts, int built, int minted, int referencesCarried,
                        long floodOrphansRetired) {
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
        // IMPORTED -- so this listener counts imports and nothing else. That is the whole reason
        // the counting node below exists: a block that was built and then refused, and a block that
        // was never built, are indistinguishable from here.
        blockchain.registerListener(message -> {
            if (message instanceof BlockMessage block) {
                minted.add(new Block(new XdagBlock(block.getData().toArray())));
            }
        });
        // Deltas rather than totals. Nothing in this fixture builds a link block before this point
        // today -- the base's main blocks are built directly, not through createLinkBlock -- and a
        // delta stays right if that ever stops being true.
        int attemptsBefore = counting().attempts;
        int builtBefore = counting().built;
        for (int i = 0; i < TICKS; i++) {
            // Load-bearing twice over; see this class's measurement javadoc. Besides driving the
            // arm, this is the only call in the suite that reaches checkOrphan's guard against a
            // null link block, because reaching it needs nnoref >= 671.
            blockchain.checkOrphan();
        }
        int attempts = counting().attempts - attemptsBefore;
        int built = counting().built - builtBefore;

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
                linkAvailable, attempts, built, minted.size(), references, retired);
    }

    // ---- fixture -------------------------------------------------------------------------

    /**
     * A node that records what {@code checkOrphan} asked it to build and what it managed to build.
     *
     * <h2>Why the count is taken here and not anywhere cheaper</h2>
     *
     * <p>A block that is never constructed leaves nothing behind to count: it reaches no store, no
     * listener and no statistic, and the {@code onNewBlock} listener the arm already installs fires
     * only for a block that <em>imported</em>. One level down would not do either — counting what
     * {@code getOrphan} handed back would answer "nothing was built" even if
     * {@code createLinkBlock} went back to building the reference-less block anyway, which is
     * precisely the regression this arm is here to catch. So the reading is taken at the method
     * whose decision it is, on its way out.
     *
     * <p>Overriding it on the instance means the measurement costs the pool nothing. The obvious
     * alternative — have the test call {@code createLinkBlock} itself and look at the result —
     * would be free in the chunk arm and destructive in the control arm, where selection takes the
     * entries it packs <em>out</em> of the pool: every probe would quietly retire a dozen link
     * orphans into a block nobody imports, which is the one thing the control arm measures.
     */
    private static class CountingBlockchain extends MockBlockchain {

        /** Rounds entered: {@code createLinkBlock} calls, whatever they returned. */
        private int attempts;
        /** Of those, the ones that produced a block. */
        private int built;

        CountingBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public Block createLinkBlock(String remark, boolean isRoll) {
            attempts++;
            Block block = super.createLinkBlock(remark, isRoll);
            if (block != null) {
                built++;
            }
            return block;
        }
    }

    /**
     * The counting node, which is the only kind this class builds. Read through a cast rather than
     * kept in a field of its own: {@code freshFixture} replaces the instance between the two arms,
     * and a second reference to it is a second thing that has to be kept in step.
     */
    private CountingBlockchain counting() {
        return (CountingBlockchain) blockchain;
    }

    @Override
    protected MockBlockchain newBlockchain(Kernel kernel) {
        return new CountingBlockchain(kernel);
    }

    private OrphanBlockStoreImpl store() {
        return (OrphanBlockStoreImpl) blockchain.getOrphanBlockStore();
    }

    /**
     * The base's fresh fixture plus the one thing this class's {@code @Before} did that JUnit will
     * not run again: arming the pool, which is also what mines the main block every delivered block
     * is kept lighter than.
     */
    @Override
    protected void freshFixture(long fixtureStart) throws Exception {
        super.freshFixture(fixtureStart);
        armTheOrphanPool();
    }
}
