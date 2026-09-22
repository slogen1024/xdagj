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

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.Network;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.ingest.PreValidator;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.XdagPow;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.net.Peer;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Deferred persistence: a chunk block reaches the block store when something references it, and
 * never before.
 *
 * <p>What this is worth. Before it, every chunk that arrived was written to disk on arrival with no
 * fee check of any kind — {@code tryToConnect} is deliberately ignorant of the chain protocol — so
 * a flood of chunks nobody would ever pay for was a flood of unconditional writes. After it a chunk
 * is 512 bytes of memory bounded by three quotas and aged out in two epochs, and it costs a disk
 * write only when a block that references it is imported. The chunk blocks of a real chain are
 * referenced within an epoch or two by the block that pays for them, so nothing legitimate is
 * delayed by more than that.
 *
 * <p><b>Delivery is through {@code PreValidator.reimport}</b>, which is the path
 * {@code SyncManager.importBlock} takes for a block that arrived from a peer: it re-parses the
 * wrapper's bytes and computes the extension classification. The bare {@code tryToConnect(Block)}
 * entry point deliberately computes no classification, and without one
 * {@code OrphanCategory.of(..., null)} can never answer CHUNK — so a chunk delivered that way is an
 * ordinary link block, goes to disk as before, and would test none of this.
 */
public class ChunkDeferredPersistTest extends ChainL1TestBase {

    private static final String PEER_IP = "198.51.100.11";

    /** Keeps a delivered block from out-weighing the mined chain top; see {@link #lightChunk}. */
    private static final BigInteger MAX_FLOOD_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per built block, so one block's redraws can never collide with the next one's. */
    private static final int SEEDS_PER_BLOCK = 64;

    /**
     * {@code dealOrphan} pools nothing unless the node is configured to generate blocks and a PoW
     * instance exists. Devnet supplies the first; the mock supplies the second, and nothing in the
     * import path calls into it. One real main block first, so the chain top weighs at least 2^46.
     */
    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
        mineMain(List.of());
    }

    // ---- the two the plan asks for -------------------------------------------------------

    @Test
    public void anUnreferencedChunkIsNotOnDisk() {
        Block chunk = lightChunk(1);
        assertImported(deliver(chunk));

        assertNull("an unreferenced chunk must not reach the block store",
                kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        assertFalse("nor leave a BlockInfo row behind for a body that is not there",
                kernel.getBlockStore().hasBlockInfo(chunk.getHashLow()));

        // Still held, and still answerable for -- through the merged lookup, which reads the body
        // store as its third source, and through that store directly.
        assertNotNull("but it must still be servable from memory",
                blockchain.getBlockByHash(hashLow(chunk), true));
        Bytes body = blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk));
        assertNotNull("but it must still be servable from memory", body);
        assertEquals("and byte for byte what arrived", chunk.getXdagBlock().getData(), body);
        assertEquals("one pooled chunk, one body", 1, pool().chunkBodyCount());
        assertEquals("and the pool entry is where the body hangs off",
                body, pool().get(hashLow(chunk)).body());
    }

    @Test
    public void aReferencedChunkIsPersisted() {
        ChainBlockBuilder.Built deploy = deployNewChain(payload(1000, 3), Bytes.EMPTY);
        assertEquals("a three-chunk chain, so head, middle and tail are all distinct", 3,
                deploy.chunks().size());
        deliverChainTailFirst(deploy);

        for (Block chunk : deploy.chunks()) {
            assertNull("no chunk may be on disk before the block that pays for it",
                    kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
        assertEquals(3, pool().chunkBodyCount());

        assertImported(deliver(deploy.block()));

        for (Block chunk : deploy.chunks()) {
            Block onDisk = kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow());
            assertNotNull("a chunk referenced by an imported paying block must be persisted",
                    onDisk);
            assertEquals("and persisted byte for byte", chunk.getXdagBlock().getData(),
                    onDisk.getXdagBlock().getData());
        }
        assertEquals("the bodies go as they are written: memory is not a second copy of the disk",
                0, pool().chunkBodyCount());
        assertEquals("and the chunks are no longer orphans", 0,
                pool().size(OrphanCategory.CHUNK));
    }

    // ---- the corners the persist has to survive -------------------------------------------

    /**
     * A chunk aged out before its paying block arrives. "Sanely" turns out to mean: the paying
     * block is refused with {@link ImportResult#NO_PARENT}, which is the same answer a node that
     * never received the chunk at all gives, and which is what drives the existing fetch-and-retry
     * path. It is not a new verdict and not a silent acceptance — the alternative, importing a
     * block whose chain this node cannot produce, is what would make two honest nodes disagree at
     * {@code setMain}.
     *
     * <p>The TTL itself is the consensus-safe one: a chunk in epoch N can only ever be referenced
     * from epoch N or N+1, so a paying block that turns up after that would have been refused by
     * {@code ChunkChain}'s age rule whether or not this node still held the bytes.
     */
    @Test
    public void aChunkAgedOutBeforeItsPayingBlockIsNoParent() {
        ChainBlockBuilder.Built deploy = deployNewChain(payload(1000, 4), Bytes.EMPTY);
        deliverChainTailFirst(deploy);
        assertEquals(3, pool().chunkBodyCount());

        long epoch = XdagTime.getEpoch(deploy.chunks().get(0).getTimestamp());
        // Two epochs past the last one that could legitimately pay for them, which is what the
        // store's cleaner tick hands the pool once the chain has moved that far.
        assertEquals(3, pool().evictExpired(0L, epoch + 2).size());
        assertEquals("nothing left to persist", 0, pool().chunkBodyCount());

        ImportResult r = deliver(deploy.block());
        assertSame("a paying block whose chain this node no longer holds has no parent",
                ImportResult.NO_PARENT, r);
        for (Block chunk : deploy.chunks()) {
            assertNull("and nothing was written on the way to that verdict",
                    kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
    }

    /**
     * The same chain referenced a second time. The first reference wrote it and took the bodies
     * out of the pool, so the second finds nothing to write: it reads the chain out of the block
     * store like any other block, and {@code nnoref} is given back once, not twice.
     */
    @Test
    public void aChunkChainIsWrittenOnceHoweverOftenItIsReferenced() {
        ChainBlockBuilder.Built deploy = deployNewChain(payload(1000, 5), Bytes.EMPTY);
        deliverChainTailFirst(deploy);
        assertImported(deliver(deploy.block()));

        long noRefAfterFirst = blockchain.getXdagStats().nnoref;
        Block head = deploy.chunks().get(0);

        Block second = linkTo(hashLow(head), 40);
        assertImported(deliver(second));

        assertEquals("the second reference must find nothing left to persist", 0,
                pool().chunkBodyCount());
        assertNotNull(kernel.getBlockStore().getRawBlockByHash(head.getHashLow()));
        assertEquals("nnoref moves by the one block that arrived, not by the chain again",
                noRefAfterFirst + 1, blockchain.getXdagStats().nnoref);
    }

    /**
     * A block that is refused persists nothing. This one names the chain head but is timestamped
     * before it, which {@code tryToConnect} rejects at the reference check — upstream of the
     * persist, which is the ordering the whole design rests on: everything that can say no comes
     * first, and the writing happens once nothing is left that can.
     */
    @Test
    public void aPayingBlockThatIsRefusedPersistsNothing() {
        ChainBlockBuilder.Built deploy = deployNewChain(payload(1000, 6), Bytes.EMPTY);
        deliverChainTailFirst(deploy);
        Block head = deploy.chunks().get(0);

        Block tooEarly = linkTo(hashLow(head), 41, head.getTimestamp() - 1);
        assertSame(ImportResult.INVALID_BLOCK, deliver(tooEarly));

        for (Block chunk : deploy.chunks()) {
            assertNull("a refused block must leave the chain exactly where it was",
                    kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
        assertEquals(3, pool().chunkBodyCount());
    }

    /**
     * A chunk referencing a chunk is not a payment. The chain's own interior references must not
     * trigger the write, or a flooder would pay for the whole thing with one extra chunk block —
     * which is the cheapest block there is, and the one this change exists to stop being free.
     */
    @Test
    public void aChunkReferencingAChunkPersistsNothing() {
        ChainBlockBuilder.Built deploy = deployNewChain(payload(1000, 7), Bytes.EMPTY);
        deliverChainTailFirst(deploy);

        assertEquals("all three chunks imported, none of them on disk", 3, pool().chunkBodyCount());
        for (Block chunk : deploy.chunks()) {
            assertNull(kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
    }

    /**
     * The body store is bounded by pool membership and nothing else: one body per pooled chunk,
     * gone the moment the entry is. Without that a body outliving its entry would be 512 bytes no
     * counter is watching and nothing will ever come back for.
     */
    @Test
    public void everyBodyLeavesWithItsEntry() {
        for (int i = 0; i < 5; i++) {
            assertImported(deliver(lightChunk(10 + i)));
        }
        assertEquals(5, pool().size(OrphanCategory.CHUNK));
        assertEquals(5, pool().chunkBodyCount());

        pool().evictExpired(0L, Long.MAX_VALUE / 2);
        assertEquals(0, pool().size(OrphanCategory.CHUNK));
        assertEquals("a body must not outlive the entry that owns it", 0, pool().chunkBodyCount());
    }

    /**
     * A re-delivered chunk is recognised as one this node already holds. Without that check a chunk
     * is invisible to both existence tests — it is in neither the block store nor
     * {@code memOrphanPool} — so a peer could re-send one for ever and every copy would be imported
     * again, {@code nblocks} and {@code nnoref} climbing once per delivery while the pool, which
     * refuses the repeat as a duplicate, stayed exactly where it was.
     */
    @Test
    public void aRedeliveredChunkIsNotImportedTwice() {
        Block chunk = lightChunk(20);
        assertImported(deliver(chunk));
        long blocks = blockchain.getXdagStats().nblocks;
        long noRef = blockchain.getXdagStats().nnoref;

        assertSame("this node has the block, and says so", ImportResult.EXIST, deliver(chunk));

        assertEquals("a repeat must not count as a new block", blocks,
                blockchain.getXdagStats().nblocks);
        assertEquals("nor as a second orphan", noRef, blockchain.getXdagStats().nnoref);
        assertEquals(1, pool().chunkBodyCount());
    }

    /**
     * What the body store hands out is a copy. The pool keeps immutable bytes rather than a block
     * precisely so that this is the only thing it can hand out: {@code a51e09c5} fixed this node
     * serving peers the very instance the chain goes on mutating, and a store of parsed blocks
     * would have reopened it on a path that is read by netty threads holding no lock at all.
     */
    @Test
    public void whatComesOutOfTheBodyStoreIsACopy() {
        Block chunk = lightChunk(21);
        assertImported(deliver(chunk));

        Bytes body = blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk));
        Block first = new Block(new XdagBlock(body.toArray()));
        Block second = new Block(new XdagBlock(body.toArray()));
        assertNotSame("two readers must not share one block", first, second);
        assertNotSame("nor one byte array", first.getXdagBlock().getData(),
                second.getXdagBlock().getData());
        assertEquals(chunk.getHashLow(), first.getHashLow());
        assertEquals(chunk.getXdagBlock().getData(), first.getXdagBlock().getData());
    }

    // ---- helpers -------------------------------------------------------------------------

    private ChainOrphanPool pool() {
        return ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getPool();
    }

    /**
     * Imports a block the way a block from a peer is imported: a private re-parse of its 512 bytes,
     * the arriving wrapper's source peer, and the extension classification. This is
     * {@code SyncManager.importBlock}'s call, term for term.
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
        // Nothing delivered here may take the chain top; see lightChunk.
        assertEquals("a delivered block hijacked the chain top; change the seed",
                topRef, Bytes32.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    private static Peer peer() {
        return new Peer(Network.DEVNET, (short) 0, "peer-a", PEER_IP, 8001, "xdagj", new String[0],
                0, false, "tag");
    }

    /**
     * A one-chunk chain, drawn again until its raw-hash difficulty is below the window
     * {@code mineMain} searches in. A link-less block's chain weight is just its own difficulty,
     * which is heavy-tailed, so an unfiltered chunk can out-weigh the mined top and drag a reorg
     * through the middle of the measurement.
     */
    private Block lightChunk(int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block chunk = ChunkChainBuilder.split(config, payload(32, s), txTime()).get(0);
            if (blockchain.calculateCurrentBlockDiff(chunk).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return chunk;
            }
        }
    }

    private Block linkTo(Bytes32 target, int seed) {
        return linkTo(target, seed, txTime() + 1);
    }

    /**
     * A plain link block naming one other block, redrawn through its remark until it is light
     * enough not to take the top. No extension field, so it classifies as nothing at all — which is
     * the point: it is an ordinary block that happens to reference a chunk.
     */
    private Block linkTo(Bytes32 target, int seed, long timestamp) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            Block raw = new Block(config, timestamp, null,
                    List.of(new Address(target, XDAG_FIELD_OUT, false)), false, null, "s" + s, -1,
                    XAmount.ZERO, null);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }
}
