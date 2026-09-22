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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.Network;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChainBuilder;
import io.xdag.chain.ext.ChunkExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ingest.IngestPipeline;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.XdagPow;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.net.Peer;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The peer-and-kind pipeline, end to end through the real {@link IngestPipeline}.
 *
 * <p>Tasks 5 and 6 built three defences that nothing could reach: the per-source chunk quota needs
 * to know who sent a block, the per-chain quota needs to know which chunk chain it hangs off, and
 * the two-epoch chunk TTL needs to know it is looking at a chunk at all. None of those facts
 * survived the last hop into {@code dealOrphan}, so every chunk arrived unattributed, ungrouped and
 * filed as an ordinary link block — {@code OrphanCategory.of(..., null)} cannot return
 * {@link OrphanCategory#CHUNK}. This class is what pins them arriving.
 *
 * <p>Delivery is through a real pipeline rather than {@code blockchain.tryToConnect(block)}: the
 * bare entry point fabricates a peerless wrapper and asks for no classification, so it is exactly
 * the path that carries none of this, and testing the wiring on it would test nothing.
 */
public class OrphanPeerAttributionTest extends ChainL1TestBase {

    private static final String PEER_A_IP = "198.51.100.7";
    private static final String PEER_B_IP = "203.0.113.9";

    /** Keeps a flood block from out-weighing the mined chain top; see {@link #lightChunk}. */
    private static final BigInteger MAX_FLOOD_DIFFICULTY = BigInteger.ONE.shiftLeft(46);

    /** Seed room per built block, so one block's redraws can never collide with the next one's. */
    private static final int SEEDS_PER_BLOCK = 64;

    private IngestPipeline pipeline;

    /** Every committer verdict, so a delivery that was rejected says so instead of just vanishing. */
    private final List<String> verdicts = new CopyOnWriteArrayList<>();

    /**
     * {@code dealOrphan} pools nothing unless the node is configured to generate blocks and a PoW
     * instance exists. Devnet sets {@code node.generate.block.enable = true}; the mock supplies the
     * other half, and nothing in the import path calls into it.
     */
    @Before
    public void armTheOrphanPool() {
        kernel.setPow(Mockito.mock(XdagPow.class));
        // One real main block first, so the chain top weighs at least 2^46 -- the floor of the
        // window mineMain searches in, and the ceiling lightChunk keeps every chunk under.
        mineMain(List.of());
        pipeline = new IngestPipeline(2, 64, pv -> {
            ImportResult r = blockchain.tryToConnect(pv);
            verdicts.add(r + " " + r.getErrorInfo());
            return r;
        });
        pipeline.start();
    }

    @After
    public void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    /**
     * Two sources, two budgets. This is the whole point of the per-peer tier: one flooder must
     * spend its own share and nobody else's, which it cannot do while every chunk in the pool is
     * attributed to nothing.
     */
    @Test
    public void twoPeersAreChargedSeparateChunkBudgets() {
        Block fromA1 = lightChunk(1);
        Block fromA2 = lightChunk(2);
        Block fromB = lightChunk(3);
        deliver(fromA1, peer("peer-a", PEER_A_IP));
        deliver(fromA2, peer("peer-a", PEER_A_IP));
        deliver(fromB, peer("peer-b", PEER_B_IP));
        drain();

        assertEquals("the source peer must reach the pool as its IP",
                PEER_A_IP, pooled(fromA1).peerKey());
        assertEquals(PEER_A_IP, pooled(fromA2).peerKey());
        assertEquals(PEER_B_IP, pooled(fromB).peerKey());
        assertNotEquals("two peers must not share one key", pooled(fromA1).peerKey(),
                pooled(fromB).peerKey());

        assertEquals("each source must hold a bucket of its own", 2, pool().peerBucketCount());
        assertEquals("peer A spent two of its own slots", 2, pool().chunksHeldBy(PEER_A_IP));
        assertEquals("and none of peer B's", 1, pool().chunksHeldBy(PEER_B_IP));
    }

    /**
     * The mutation this catches: keying the quota on {@code Peer.getPeerId()} instead of the IP.
     *
     * <p>The id is cryptographically bound at handshake, so it cannot be borrowed — but nothing
     * stops a flooder minting a fresh keypair and reconnecting, and a budget that is reset for free
     * as often as the flooder likes is not a budget. Here the same host comes back under a second
     * identity: the two chunks must land in <em>one</em> bucket and spend <em>one</em> budget.
     * Keyed on the id they would be two buckets of one, and the quota would bound nothing.
     */
    @Test
    public void aFlooderCannotResetItsBudgetByReconnectingUnderANewIdentity() {
        Block first = lightChunk(4);
        Block second = lightChunk(5);
        deliver(first, peer("identity-one", PEER_A_IP));
        deliver(second, peer("identity-two", PEER_A_IP));
        drain();

        assertEquals("a second identity from the same socket must not open a second bucket",
                1, pool().peerBucketCount());
        assertEquals("both chunks must come out of the one budget that host has",
                2, pool().chunksHeldBy(PEER_A_IP));
        assertEquals(PEER_A_IP, pooled(first).peerKey());
        assertEquals("the announced identity must not be what the pool charges",
                PEER_A_IP, pooled(second).peerKey());
    }

    /**
     * A block this node produced itself owes no peer and spends nobody's budget. The bare entry
     * point is the one the miner, the RPC and the CLI use, and it is right that it attributes
     * nothing: charging local blocks to a catch-all bucket would let this node's own work shut its
     * own intake down.
     */
    @Test
    public void aLocallyProducedBlockIsUnattributed() {
        Block local = lightChunk(6);
        assertImported(local);

        assertNull("a block with no wrapper behind it must not be attributed",
                pooled(local).peerKey());
        assertEquals("and must not open a bucket", 0, pool().peerBucketCount());
        assertEquals("with no classification it is a link block, gate and store agreeing",
                OrphanCategory.LINK, pooled(local).category());
    }

    /**
     * The classification arriving is what turns the chunk category on at all: before it, {@code
     * OrphanCategory.of(..., null)} could only ever answer LINK, so the chunk cap and the epoch TTL
     * were unreachable from production however well they were tested.
     *
     * <p>Two consequences of the category ride along here because they are the things that changed
     * the day chunks stopped being link blocks, and both are wanted: a chunk gets no ORPHANIND row
     * (it is memory-only, so a restart forgets it rather than pointing a row at a body that is not
     * there), and it is aged against chain epochs instead of the flat fifteen local minutes.
     */
    @Test
    public void anArrivingChunkIsClassifiedAndAgedOnItsOwnEpoch() {
        Block chunk = lightChunk(7);
        long rowsBefore = persistedOrphanRows();
        deliver(chunk, peer("peer-a", PEER_A_IP));
        drain();

        assertEquals("a classified chunk must be filed as one", OrphanCategory.CHUNK,
                pooled(chunk).category());
        assertEquals("a chunk is memory-only: no ORPHANIND row, and ORPHAN_SIZE unmoved",
                rowsBefore, persistedOrphanRows());
        assertEquals("but the pool still counts it", 1,
                blockchain.getOrphanBlockStore().getOrphanSize());

        long epoch = XdagTime.getEpoch(chunk.getTimestamp());
        // Fifteen local minutes, the clock every other category runs on, must not touch it.
        pool().evictExpired(System.currentTimeMillis() + 16 * 60_000L, epoch);
        assertNotNull("a chunk is not aged by the local clock", pool().get(hashLow(chunk)));
        pool().evictExpired(0L, epoch + 1);
        assertNotNull("the epoch a paying block may still reference it from",
                pool().get(hashLow(chunk)));

        List<OrphanEntry> evicted = pool().evictExpired(0L, epoch + 2);
        assertEquals("past the age rule's reach the chunk TTL must fire", 1, evicted.size());
        assertNull(pool().get(hashLow(chunk)));
    }

    /**
     * Grouping along {@code next}: a tail chunk roots its own chain, and a chunk that names it
     * attaches to that same key.
     *
     * <p>The mutation this catches is the lazy one — returning null for the chain head because it
     * "could not be worked out". Null is not free: {@code ChainOrphanPool} charges nothing to a
     * null key, so an ungrouped chunk skips the per-chain tier entirely and stands on the per-peer
     * and global caps alone. A grouping that gives up easily is a tier handed back to the flooder.
     */
    @Test
    public void aChunkIsGroupedOntoTheChainItNames() {
        List<Block> chain = ChunkChainBuilder.split(config, payload(ChunkExt.MAX_DATA_LEN + 16, 99L), txTime());
        Block tail = chain.get(1);
        Block head = chain.get(0);

        deliver(tail, peer("peer-a", PEER_A_IP));
        drain();
        assertEquals("a tail names no successor, so it roots its own chain",
                hashLow(tail), pooled(tail).chainHead());
        assertEquals(1, pool().chunksOnChain(hashLow(tail)));

        deliver(head, peer("peer-a", PEER_A_IP));
        drain();
        // Importing the head un-orphaned the tail it references -- that is what tryToConnect does
        // with every link, before it pools the block itself -- so the tail is out of the pool and
        // the head is the only chunk left. It is still charged to the chain it named.
        assertNull("the head's import un-orphans the tail it references", pool().get(hashLow(tail)));
        assertEquals("the head attaches to the chain it names, not to nothing",
                hashLow(tail), pooled(head).chainHead());
        assertEquals(1, pool().chainBucketCount());
    }

    /**
     * The other half of the walk: where the pool still holds the successor, its key is inherited
     * rather than recomputed, so a whole chain collapses onto one bucket instead of one bucket per
     * hop. Three chunks are the shortest chain that tells the two apart — with two, the successor's
     * key and the successor's hashlow are the same value.
     *
     * <p>Driven through the store rather than through an import, because an import cannot reach
     * this branch today: {@code tryToConnect} un-orphans everything a block references before it
     * pools the block itself, so by the time a chunk is admitted its successor has just left the
     * pool. That is a property of the import path, not of the derivation, and it stops being true
     * as soon as a chunk stops being reachable through {@code getBlockByHash} — {@code
     * removeOrphan} then finds nothing to un-orphan and the successor stays. Pinning the branch
     * here is what makes that a change of retention rather than a rediscovery of the grouping.
     */
    @Test
    public void aChunkInheritsThePooledSuccessorsChainKey() {
        List<Block> chain = ChunkChainBuilder.split(config,
                payload(2 * ChunkExt.MAX_DATA_LEN + 16, 5L), txTime());
        assertEquals("three chunks, head first", 3, chain.size());
        Block tail = chain.get(2);
        Block mid = chain.get(1);
        Block head = chain.get(0);
        poolDirectly(tail);
        poolDirectly(mid);
        poolDirectly(head);

        assertEquals(hashLow(tail), pooled(tail).chainHead());
        assertEquals(hashLow(tail), pooled(mid).chainHead());
        assertEquals("the head must take the chain its pooled successor is on, not name the successor",
                hashLow(tail), pooled(head).chainHead());
        assertEquals("one chain, one bucket", 1, pool().chainBucketCount());
        assertEquals(3, pool().chunksOnChain(hashLow(tail)));
    }

    /**
     * And the shape the per-chain tier exists to bound: many chunks naming one block. They share a
     * key, so {@code chunkPerChain} can cut them off — which it cannot do to chunks it was handed
     * as ungrouped.
     */
    @Test
    public void chunksPilingOntoOneBlockShareOneChainBudget() {
        Block target = lightChunk(8);
        deliver(target, peer("peer-a", PEER_A_IP));
        drain();

        List<Block> pile = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Block naming = chunkNaming(hashLow(target), 20 + i);
            pile.add(naming);
            deliver(naming, peer("peer-b", PEER_B_IP));
            drain();
        }

        assertEquals("one chain bucket, not three", 1, pool().chainBucketCount());
        assertEquals("every chunk naming that block is charged to the same chain",
                3, pool().chunksOnChain(hashLow(target)));
        for (Block naming : pile) {
            assertEquals(hashLow(target), pooled(naming).chainHead());
        }
    }

    /**
     * The one chunk that is genuinely ungroupable: its own CHUNK extension does not decode, so
     * there is no {@code next} field to believe. It is still filed as a chunk — deliberately, since
     * that is the cheap bucket for a block no paying block can ever settle — and it still spends
     * its sender's budget, which is what stops "send garbage that groups onto nothing" being a way
     * past every tier at once.
     */
    @Test
    public void anUndecodableChunkIsUngroupedButStillChargedToItsSender() {
        Block malformed = malformedChunk(30);
        deliver(malformed, peer("peer-a", PEER_A_IP));
        drain();

        assertEquals("a chunk kind byte is enough to file it as a chunk",
                OrphanCategory.CHUNK, pooled(malformed).category());
        assertNull("with no readable next there is no chain to name",
                pooled(malformed).chainHead());
        assertEquals("but it is still somebody's chunk", 1, pool().chunksHeldBy(PEER_A_IP));
        assertEquals("and it opens no chain bucket", 0, pool().chainBucketCount());
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * Admits a block into the pool without importing it, with the classification the pipeline would
     * have computed. For the one derivation branch the import path cannot currently produce.
     */
    private void poolDirectly(Block block) {
        ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).addOrphan(block, false,
                UInt64.ZERO, XAmount.ZERO, null, PEER_A_IP, ChainBlockClassifier.classify(block));
    }

    private ChainOrphanPool pool() {
        return ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getPool();
    }

    private OrphanEntry pooled(Block block) {
        OrphanEntry entry = pool().get(hashLow(block));
        assertNotNull("the block never reached the pool: " + block.getHashLow() + " " + verdicts,
                entry);
        return entry;
    }

    /** ORPHAN_SIZE as the store itself persists it — the count a chunk must not move. */
    private long persistedOrphanRows() {
        byte[] v = ((OrphanBlockStoreImpl) blockchain.getOrphanBlockStore()).getOrphanSource()
                .get(OrphanBlockStore.ORPHAN_SIZE);
        return v == null ? 0 : BytesUtils.bytesToLong(v, 0, false);
    }

    private static Peer peer(String peerId, String ip) {
        return new Peer(Network.DEVNET, (short) 0, peerId, ip, 8001, "xdagj", new String[0], 0,
                false, "tag");
    }

    private void deliver(Block block, Peer from) {
        // The instance itself, not a re-serialisation of it: Block.toBytes() on an already-parsed
        // block does not reproduce the bytes it was parsed from, so re-wrapping would deliver a
        // different block than the test then looks for. submit()'s precondition holds either way --
        // this one was parsed from its 512 bytes and still carries them.
        //
        // getHashLow() is a lazy mutator, so it is forced here, on this thread, before the block is
        // handed over: the test reads it again afterwards and the pipeline must not be racing it.
        block.getHashLow();
        // ttl 0 so relayImported has nothing to gossip; isOld false is the live-traffic shape.
        pipeline.submit(new BlockWrapper(block, 0, from, false));
    }

    /**
     * Waits for everything submitted so far to be committed, then insists that every one of them
     * really was imported. Without that second half a rejected block would simply never appear in
     * the pool, and the assertion that follows would blame the wiring for a block the chain threw
     * out for some unrelated reason.
     */
    private void drain() {
        for (int i = 0; i < 200; i++) {
            if (pipeline.inFlight() == 0) {
                for (String verdict : verdicts) {
                    assertTrue("a delivered block was not imported: " + verdict,
                            verdict.startsWith("IMPORTED_"));
                }
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("the ingest pipeline did not drain");
    }

    /**
     * A one-chunk chain, drawn again until its raw-hash difficulty is below the window
     * {@link #mineMain} searches in. A link-less block's chain weight is just its own difficulty,
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

    /**
     * A well-formed CHUNK block whose {@code next} is a block of the caller's choosing, which
     * {@link ChunkChainBuilder} cannot express (it only builds whole chains). Timestamped one tick
     * after {@link #txTime()} so it is strictly later than anything {@link #lightChunk} built:
     * {@code tryToConnect} requires a block to be later than everything it references.
     */
    private Block chunkNaming(Bytes32 next, int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            ChunkExt ext = new ChunkExt(1, 64, 32, next, payload(32, s));
            List<Bytes32> fields = new ArrayList<>();
            fields.add(ext.encodeHeader());
            fields.addAll(ext.encodePayload());
            Block raw = new Block(config, txTime() + 1, null,
                    List.of(new Address(next, XDAG_FIELD_OUT, false)), false, null, null, -1,
                    XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }

    /**
     * A block whose extension header says {@link ExtKind#CHUNK} but whose reserved tail is not
     * zero, so {@code ChunkExt.decode} refuses it. {@code ChainBlockClassifier} still reports the
     * kind — the kind byte is read before the codec runs — which is exactly the shape that is a
     * chunk to the category and nothing at all to the grouping.
     */
    private Block malformedChunk(int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            ChunkExt ext = new ChunkExt(0, 32, 32, null, payload(32, s));
            byte[] header = ext.encodeHeader().toArray();
            header[20] = 0x7f; // inside the reserved b11..b31 range
            List<Bytes32> fields = new ArrayList<>();
            fields.add(Bytes32.wrap(header));
            fields.addAll(ext.encodePayload());
            Block raw = new Block(config, txTime(), null, null, false, null, null, -1,
                    XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_FLOOD_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }
}
