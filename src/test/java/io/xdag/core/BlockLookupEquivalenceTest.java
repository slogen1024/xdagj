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
package io.xdag.core;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.Network;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.ingest.PreValidator;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.chain.orphan.ChainOrphanPool;
import io.xdag.chain.orphan.OrphanCategory;
import io.xdag.consensus.XdagPow;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.net.Peer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The merged {@code getBlockByHash}: {@code memOrphanPool}, then the block store, then the orphan
 * pool's chunk body store.
 *
 * <h2>Why this is the riskiest lookup in the node</h2>
 *
 * <p>It is on the consensus path — {@code ChainL1Processor} reaches it through the lambda
 * {@code BlockchainImpl} hands its constructor — and it is called from netty I/O threads holding no
 * lock at all, on the two P2P serve paths. A third source added to it has to be provably invisible
 * to every block that could already be found, which is what the property test here is for.
 *
 * <h2>The two decisions this file pins</h2>
 *
 * <p><b>Order: the body store goes last.</b> Equivalence is then structural rather than
 * discovered — a branch reached only when both older sources returned null cannot change a non-null
 * answer. It also matters because the three are not disjoint: {@code persistReferencedChunkChains}
 * writes a chunk to the block store and only then drops its body, so between those two statements
 * the hash is in both, and netty threads read the lookup throughout. Store-first makes the
 * persisted, complete form win that race — the body carries no {@code BlockInfo} at all.
 *
 * <p><b>{@code isRaw}: the body store answers {@code true} only.</b> {@code isRaw} is not a
 * formatting flag; it selects which of the two stored artifacts the caller wants. {@code true} is
 * {@code getRawBlockByHash} — the 512 wire bytes — which is exactly what the body store holds and
 * exactly what {@code ChunkChain} and the serve path ask for. {@code false} is
 * {@code getBlockInfoByHash}, the block's stored chain metadata, and a memory-only chunk has none
 * anywhere on this node; answering it with an all-zero {@code BlockInfo} would be inventing the
 * flags, difficulty and ref that every {@code isRaw=false} caller reads.
 * {@link #theNonRawFormIsNeverAnsweredFromTheBodyStore} shows what that would cost.
 */
public class BlockLookupEquivalenceTest extends ChainL1TestBase {

    private static final String PEER_IP = "198.51.100.23";

    /** Fixed, so a failure replays. */
    private static final long[] SEEDS = {1L, 7L, 42L, 1337L, 20260919L};

    /** Steps per seed; each step delivers one to four blocks. */
    private static final int STEPS_PER_SEED = 25;

    /** Keeps a delivered block from out-weighing the mined chain top. */
    private static final BigInteger MAX_FLOOD_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per built block, so one block's redraws can never collide with the next one's. */
    private static final int SEEDS_PER_BLOCK = 64;

    private int seedCounter = 1;

    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
        mineMain(List.of());
    }

    // ---- the two the plan asks for -------------------------------------------------------

    /**
     * The merged lookup, over random block sequences, must lose nothing and must serve every block
     * byte for byte — and must leave every block that was already findable exactly where it was.
     *
     * <p>Three clauses, and the second and third are the ones that would catch a wrong merge rather
     * than a missing one:
     *
     * <ul>
     *   <li>every delivered block is found in its raw form, byte-identical to what arrived. Unpaid
     *       chunks are in that set, and they are findable nowhere else — this is the clause the
     *       mutation check turns red;</li>
     *   <li>for a block that is on disk, the answer is the disk answer: same flags, same difficulty.
     *       A body store consulted before the block store, or one allowed to shadow it, changes
     *       exactly this and nothing the first clause can see;</li>
     *   <li>{@code isRaw=false} still means "the stored {@code BlockInfo}, or nothing". The body
     *       store is not a second answer to that question.</li>
     * </ul>
     */
    @Test
    public void theMergedLookupIsByteIdenticalToTheOldOne() {
        int unpaidChunks = 0;
        for (long seed : SEEDS) {
            List<Block> blocks = deliverRandomBlocks(seed, STEPS_PER_SEED);
            for (Block b : blocks) {
                Bytes32 h = hashLow(b);
                Block viaLookup = blockchain.getBlockByHash(h, true);
                assertNotNull("seed " + seed + " lost " + h, viaLookup);
                assertArrayEquals("seed " + seed + " served different bytes for " + h,
                        b.getXdagBlock().getData().toArray(),
                        viaLookup.getXdagBlock().getData().toArray());

                Block onDisk = kernel.getBlockStore().getRawBlockByHash(h);
                if (onDisk != null) {
                    assertEquals("seed " + seed + ": the body store shadowed the block store for " + h,
                            onDisk.getInfo().getFlags(), viaLookup.getInfo().getFlags());
                    assertEquals("seed " + seed + ": stored difficulty lost for " + h,
                            onDisk.getInfo().getDifficulty(), viaLookup.getInfo().getDifficulty());
                } else {
                    unpaidChunks++;
                }

                assertEquals("seed " + seed + ": isRaw=false must mean the stored BlockInfo or nothing, for " + h,
                        onDisk != null, blockchain.getBlockByHash(h, false) != null);
            }
        }
        assertTrue("the sequences produced no memory-only chunk, so nothing here exercised the merge",
                unpaidChunks > 0);
    }

    /**
     * The body store is read by netty I/O threads holding no lock while the import thread, under the
     * blockchain monitor, is putting chunks into it. {@code a51e09c5} is the precedent: the same
     * shape on the same path, where an unsynchronized {@code LinkedHashMap} handed a netty thread a
     * spurious null mid-resize and a peer's request for a freshly arrived block went unanswered.
     *
     * <p>So: no exception on either side, and no block that has finished importing may ever be
     * missing from a lookup that runs after it.
     */
    @Test(timeout = 60_000)
    public void concurrentLookupsDuringImportNeverThrowOrLoseABlock() throws Exception {
        List<Block> chunks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            chunks.add(lightChunk(9000 + i));
        }

        ConcurrentLinkedQueue<Block> published = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean importing = new AtomicBoolean(true);
        CountDownLatch go = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                go.await();
                long lookups = 0;
                while (importing.get() || lookups == 0) {
                    for (Block b : published) {
                        Bytes32 h = hashLow(b);
                        Block found = blockchain.getBlockByHash(h, true);
                        if (found == null) {
                            throw new AssertionError("a block that finished importing is not findable: " + h);
                        }
                        if (!found.getXdagBlock().getData().equals(b.getXdagBlock().getData())) {
                            throw new AssertionError("torn or wrong bytes served for " + h);
                        }
                        lookups++;
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "lookup-reader");
        reader.start();

        try {
            go.countDown();
            for (Block chunk : chunks) {
                assertImported(deliver(chunk));
                published.add(chunk);
            }
        } finally {
            importing.set(false);
        }
        reader.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse("the reader thread is still running", reader.isAlive());

        if (failure.get() != null) {
            throw new AssertionError("concurrent lookup failed", failure.get());
        }
        assertEquals("every chunk must still be held in memory and nowhere else",
                chunks.size(), pool().chunkBodyCount());
    }

    // ---- the two decisions -----------------------------------------------------------------

    /**
     * A hash in both the block store and the body store. The window is real — inside
     * {@code persistReferencedChunkChains}, between the {@code saveBlock} of a chunk and the
     * {@code removeOrphan} that drops its body — and a netty thread can read the lookup in the
     * middle of it. Here it is reproduced by handing the pool a body for a chunk that is already on
     * disk.
     *
     * <p>The disk answer must win. It carries the {@code BlockInfo} the import computed; the body
     * carries none, so a body-first lookup would hand a caller a block with no difficulty, no flags
     * and no ref for a block this node has fully imported.
     */
    @Test
    public void theBlockStoreWinsWhenAHashIsInBothPlaces() {
        ChainBlockBuilder.Built deploy = lightChain(3);
        deliverChainTailFirst(deploy);
        assertImported(deliver(deploy.block()));

        Block head = deploy.chunks().get(0);
        Bytes32 h = hashLow(head);
        Block onDisk = kernel.getBlockStore().getRawBlockByHash(h);
        assertNotNull("the paying block must have put the chain on disk", onDisk);
        assertNotNull("and given it a difficulty", onDisk.getInfo().getDifficulty());

        // Back into the pool it goes, body and all, while the disk copy stays where it is.
        blockchain.getOrphanBlockStore().addOrphan(head, false, UInt64.ZERO, XAmount.ZERO, null,
                PEER_IP, ChainBlockClassifier.classify(head));
        assertNotNull("the overlap this test is about was not created",
                blockchain.getOrphanBlockStore().getChunkBody(h));

        Block viaLookup = blockchain.getBlockByHash(h, true);
        assertNotNull(viaLookup);
        assertEquals("the body store must not shadow a block the node has on disk",
                onDisk.getInfo().getDifficulty(), viaLookup.getInfo().getDifficulty());
        assertEquals(onDisk.getInfo().getFlags(), viaLookup.getInfo().getFlags());
        assertNotNull("and isRaw=false must still find the stored BlockInfo",
                blockchain.getBlockByHash(h, false));
    }

    /**
     * {@code isRaw=false} is not "the same block, cheaper" — it is a different question, "what does
     * the store know about this block", and for a memory-only chunk the honest answer is nothing.
     *
     * <p>What makes it more than a definition: {@code removeOrphan} opens with
     * {@code getBlockByHash(hashlow, false)} and, on any non-null answer whose {@code BI_REF} is
     * clear, runs {@code deleteFromQueue}. A chunk names the chunk after it, so if that lookup
     * started answering from the body store, importing chunk <i>i</i> would evict chunk
     * <i>i+1</i> from the pool and destroy the only copy of its 512 bytes in existence — a chain
     * that can then never be assembled and never be paid for. Before the deferred persist the same
     * removal was harmless, because the successor was already on disk.
     *
     * <p>So this test is really about the chain surviving its own interior references.
     */
    @Test
    public void theNonRawFormIsNeverAnsweredFromTheBodyStore() {
        ChainBlockBuilder.Built deploy = lightChain(3);
        deliverChainTailFirst(deploy);

        for (Block chunk : deploy.chunks()) {
            Bytes32 h = hashLow(chunk);
            assertNotNull("the raw form is answerable out of memory", blockchain.getBlockByHash(h, true));
            assertNull("but there is no stored BlockInfo to hand back, and none may be invented",
                    blockchain.getBlockByHash(h, false));
        }
        assertEquals("every chunk of the chain must survive its predecessor's import",
                deploy.chunks().size(), pool().chunkBodyCount());
        assertEquals(deploy.chunks().size(), pool().size(OrphanCategory.CHUNK));

        // And the chain is still payable, which is the property the eviction would have destroyed.
        assertImported(deliver(deploy.block()));
        for (Block chunk : deploy.chunks()) {
            assertNotNull(kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
    }

    /**
     * What the merged lookup hands out for a pooled chunk is the caller's own block, parsed from the
     * pool's immutable bytes. {@code a51e09c5}'s rule, made structural: the pool stores bytes rather
     * than a {@code Block} precisely so that no reader can be handed something another reader, or
     * the chain itself, is still mutating.
     */
    @Test
    public void whatTheMergedLookupHandsOutForAPooledChunkIsACopy() {
        Block chunk = lightChunk(70);
        assertImported(deliver(chunk));
        Bytes32 h = hashLow(chunk);

        Block first = blockchain.getBlockByHash(h, true);
        Block second = blockchain.getBlockByHash(h, true);
        assertNotNull(first);
        assertNotSame("two readers must not share one block", first, second);
        assertNotSame("nor one byte array", first.getXdagBlock().getData(),
                second.getXdagBlock().getData());
        assertEquals(chunk.getXdagBlock().getData(), first.getXdagBlock().getData());

        first.getInfo().setDifficulty(BigInteger.valueOf(-1));
        assertNull("a reader's scribbles must not reach the next reader",
                blockchain.getBlockByHash(h, true).getInfo().getDifficulty());
    }

    // ---- what the merge closes -------------------------------------------------------------

    /**
     * The P2P serve path. Both of its lookups ({@code processBlockRequest} and
     * {@code processSyncBlockRequest}) ask for the raw form, so after the merge this node can answer
     * a peer's request for a chunk it is holding but has not paid to store — which the spec lists as
     * a benefit of the merge, and which was impossible between the deferred persist and here.
     */
    @Test
    public void anUnpaidChunkCanBeServedToAPeer() {
        Block chunk = lightChunk(71);
        assertImported(deliver(chunk));
        assertNull("premise: it is not on disk",
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));

        Block served = blockchain.getBlockByHash(hashLow(chunk), true);
        assertNotNull("the node holds this block and must be able to say so", served);
        assertNotNull("and it must carry the wire form, or there is nothing to send",
                served.getXdagBlock());
        assertEquals(chunk.getXdagBlock().getData(), served.getXdagBlock().getData());
    }

    /**
     * {@code getBlocksByTime} is <b>not</b> closed by this merge, and that is worth a test rather
     * than a footnote. It walks the block store's TIME index, which only {@code saveBlock} writes,
     * and resolves each row through the <em>store's</em> own {@code getBlockByHash} — neither half
     * passes through {@code BlockchainImpl}. A memory-only chunk has no row to be found by.
     */
    @Test
    public void getBlocksByTimeStillCannotServeAnUnpaidChunk() {
        Block chunk = lightChunk(72);
        assertImported(deliver(chunk));

        long epochStart = chunk.getTimestamp() & ~0xffffL;
        List<Block> served = blockchain.getBlocksByTime(epochStart, epochStart + 0xffffL);
        assertFalse("the time index has no row for a block that never reached saveBlock",
                served.stream().anyMatch(b -> b.getHashLow().equals(chunk.getHashLow())));
    }

    /**
     * The weight walk. {@link BlockchainImpl#calculateBlockDiff} reads each reference through an
     * {@code isRaw=false} lookup, so it still stops at a memory-only chunk — the merge answers the
     * raw form only, and deliberately: see {@link #theNonRawFormIsNeverAnsweredFromTheBodyStore}.
     *
     * <p>What this pins is that the gap costs nothing where it would matter. The difficulty a chunk
     * is given at its own import is never written anywhere — the block is not saved — and it is
     * read for one thing only, the top comparison, where being lighter can only make an unpaid
     * chain <em>less</em> able to move the top. The value that does reach disk is recomputed
     * tail-first by {@code persistReferencedChunkChains} once the chain is paid for, with every
     * successor present, and that is the one two nodes have to agree on.
     */
    @Test
    public void anUnpaidChunkIsGivenLessWeightButStoresTheRightOne() {
        // The gap is only visible when the successor's own draw out-weighs the head's, which is a
        // coin flip; chains are drawn until one shows it, so the test measures rather than hopes.
        for (int attempt = 0; attempt < 24; attempt++) {
            ChainBlockBuilder.Built deploy = lightChain(2);
            deliverChainTailFirst(deploy);

            Block head = deploy.chunks().get(0);
            Bytes32 next = hashLow(deploy.chunks().get(1));
            assertNull("premise: the successor is memory-only, so the walk's lookup finds nothing",
                    blockchain.getBlockByHash(next, false));

            Block whileUnpaid = reparse(head);
            BigInteger unpaid = blockchain.calculateBlockDiff(whileUnpaid,
                    blockchain.calculateCurrentBlockDiff(whileUnpaid));

            assertImported(deliver(deploy.block()));
            assertNotNull("with the chain paid for, the successor is where the walk can read it",
                    blockchain.getBlockByHash(next, false));

            Block whenPaid = reparse(head);
            BigInteger paid = blockchain.calculateBlockDiff(whenPaid,
                    blockchain.calculateCurrentBlockDiff(whenPaid));
            assertTrue("an unpaid chain can only ever weigh less, never more: " + unpaid + " vs " + paid,
                    unpaid.compareTo(paid) <= 0);
            assertEquals("what reaches disk is the full-chain value, which is what two nodes must agree on",
                    paid, kernel.getBlockStore().getBlockInfo(head.getHashLow()).getDifficulty());

            if (unpaid.compareTo(paid) < 0) {
                return;
            }
        }
        fail("no draw in 24 produced a successor heavy enough to show the gap");
    }

    /**
     * {@code xdagTopStatus.top} naming a chunk that is on no disk. Unreachable on a live chain —
     * a chunk is far too light to out-weigh an accumulated top, and the walk breaking at its
     * successor only makes it lighter — so the state is fabricated here, which is the only way to
     * ask the question at all.
     *
     * <p>Before the merge such a top was loadable nowhere. Now it is loadable in the raw form, which
     * is the form {@code unWindMain} uses; the {@code BlockInfo} readers still get null, which is
     * what they always had to tolerate for a pruned or snapshotted top, and they still do.
     */
    @Test
    public void aTopNamingAMemoryOnlyChunkIsLoadableAndSurvivesTheWalks() {
        Block chunk = lightChunk(73);
        assertImported(deliver(chunk));
        Bytes32 h = hashLow(chunk);

        blockchain.getXdagTopStatus().setTop(h.toArray());

        assertNotNull("the top must at least be loadable in the form unWindMain reads",
                blockchain.getBlockByHash(h, true));
        assertNull("and null in the form the BlockInfo walks read, as for any pruned top",
                blockchain.getBlockByHash(h, false));

        // None of the walks over the top may throw on it.
        blockchain.checkMain();
        blockchain.listMinedBlocks(3);
        blockchain.unWindMain(null);
        assertNotNull(blockchain.getXdagStats());
    }

    // ---- helpers -------------------------------------------------------------------------

    /** The block as it arrived: a private parse of its own 512 bytes, with a virgin BlockInfo. */
    private static Block reparse(Block b) {
        return new Block(new XdagBlock(b.getXdagBlock().getData().toArray()));
    }

    private ChainOrphanPool pool() {
        return ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getPool();
    }

    /**
     * One pseudo-random, replayable sequence: standalone chunks that stay in memory, plain link
     * blocks that go to disk, and chunk chains that are sometimes paid for and sometimes not. The
     * mix is what makes the property worth testing — a node's lookup sees all four states at once.
     */
    private List<Block> deliverRandomBlocks(long seed, int steps) {
        Random r = new Random(seed);
        List<Block> delivered = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            int kind = r.nextInt(4);
            if (kind == 2 && !delivered.isEmpty()) {
                // Never the chain top, and never a main block: a link block's weight is its
                // reference's plus its own, so naming the top is naming a new top, and the fixture
                // would reorg under the measurement instead of testing the lookup.
                Block link = linkTo(delivered.get(r.nextInt(delivered.size())), seedCounter++);
                assertImported(deliver(link));
                delivered.add(link);
            } else if (kind == 3) {
                ChainBlockBuilder.Built built = lightChain(1 + r.nextInt(3));
                deliverChainTailFirst(built);
                delivered.addAll(built.chunks());
                if (r.nextBoolean()) {
                    assertImported(deliver(built.block()));
                    delivered.add(built.block());
                }
            } else {
                Block chunk = lightChunk(seedCounter++);
                assertImported(deliver(chunk));
                delivered.add(chunk);
            }
        }
        return delivered;
    }

    /**
     * Imports a block the way a block from a peer is imported: a private re-parse of its 512 bytes,
     * the arriving wrapper's source peer, and the extension classification. Without a classification
     * {@code OrphanCategory.of(..., null)} can never answer CHUNK, and a chunk delivered that way is
     * an ordinary link block that goes straight to disk — testing none of this.
     */
    private ImportResult deliver(Block block) {
        BlockWrapper wrapper = new BlockWrapper(block, 0, peer(), false);
        Block fresh = new Block(new XdagBlock(block.getXdagBlock().getData().toArray()));
        return blockchain.tryToConnect(PreValidator.reimport(wrapper, fresh));
    }

    /** Chunks are imported oldest first: a block may not reference one that came after it. */
    private void deliverChainTailFirst(ChainBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            assertImported(deliver(built.chunks().get(i)));
        }
    }

    private void assertImported(ImportResult r) {
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        assertEquals("a delivered block hijacked the chain top; change the seed",
                topRef, Bytes32.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    private static Peer peer() {
        return new Peer(Network.DEVNET, (short) 0, "peer-a", PEER_IP, 8001, "xdagj", new String[0],
                0, false, "tag");
    }

    /**
     * A chunk chain of {@code chunks} blocks whose every block is light enough not to take the chain
     * top, drawn again through the payload seed until it is. A chunk with no link of its own carries
     * only its own difficulty, which is heavy-tailed, so an unfiltered draw can reorg the chain in
     * the middle of the measurement.
     */
    private ChainBlockBuilder.Built lightChain(int chunks) {
        int len = (chunks - 1) * 400 + 100;
        for (int attempt = 0; ; attempt++) {
            ChainBlockBuilder.Built built = deployNewChain(payload(len, seedCounter++), Bytes.EMPTY);
            boolean light = blockchain.calculateCurrentBlockDiff(built.block())
                    .compareTo(MAX_FLOOD_DIFFICULTY) < 0;
            for (Block chunk : built.chunks()) {
                light &= blockchain.calculateCurrentBlockDiff(chunk)
                        .compareTo(MAX_FLOOD_DIFFICULTY) < 0;
            }
            if (light) {
                return built;
            }
            assertTrue("no light chain after " + attempt + " draws", attempt < 64);
        }
    }

    /** A one-chunk chain, drawn again until its raw-hash difficulty cannot take the top. */
    private Block lightChunk(int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block chunk = ChunkChainBuilder.split(config, payload(32, s), txTime()).get(0);
            if (blockchain.calculateCurrentBlockDiff(chunk).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return chunk;
            }
        }
    }

    /**
     * A plain link block naming one other block, redrawn through its remark until it is light enough
     * not to take the top. No extension field, so it classifies as nothing at all — an ordinary
     * block that happens to reference whatever it references.
     */
    private Block linkTo(Block target, int seed) {
        // One tick after what it names: tryToConnect refuses a block whose reference is not strictly
        // older, and these sequences chain link blocks onto each other and onto paying blocks.
        long timestamp = target.getTimestamp() + 1;
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block raw = new Block(config, timestamp, null,
                    List.of(new Address(hashLow(target), XDAG_FIELD_OUT, false)), false, null,
                    "s" + s, -1, XAmount.ZERO, null);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }
}
