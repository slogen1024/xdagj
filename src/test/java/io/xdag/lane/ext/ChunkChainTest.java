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

package io.xdag.lane.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_SIGN_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChunkChainTest {

    private final Config config = new DevnetConfig();
    private static final long TS = 0x16a00000000L; // any timestamp after the devnet era

    static Bytes payload(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return Bytes.wrap(b);
    }

    static Map<Bytes32, Block> index(List<Block> blocks) {
        Map<Bytes32, Block> m = new HashMap<>();
        for (Block b : blocks) {
            m.put(Bytes32.wrap(b.getHashLow().toArray()), b);
        }
        return m;
    }

    static Bytes32 head(List<Block> chunks) {
        return Bytes32.wrap(chunks.get(0).getHashLow().toArray());
    }

    /** Builds one chunk block by hand so tests can produce malformed chains. */
    static Block rawChunk(Config config, long ts, ChunkExt ext) {
        List<Bytes32> fields = new ArrayList<>();
        fields.add(ext.encodeHeader());
        fields.addAll(ext.encodePayload());
        List<Address> links = ext.next() == null ? null : List.of(new Address(ext.next(), XDAG_FIELD_OUT, false));
        Block b = new Block(config, ts, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void splitAndAssembleRoundTrip() {
        for (int len : new int[]{1, 351, 352, 353, 704, 1000}) {
            Bytes data = payload(len, len);
            List<Block> chunks = ChunkChainBuilder.split(config, data, TS);
            assertEquals("chunks for " + len, (len + 351) / 352, chunks.size());
            Map<Bytes32, Block> idx = index(chunks);
            ExtResult<Bytes> back = ChunkChain.assemble(head(chunks), h -> idx.get(h), 4096);
            assertTrue("len " + len + ": " + back.error(), back.isOk());
            assertEquals(data, back.value());
            assertEquals(chunks.size(), ChunkChain.countLenient(head(chunks), h -> idx.get(h), 4096));
        }
    }

    @Test
    public void chunkBlocksHaveDescendingTimestampsZeroSignaturesAndSeqFromHead() {
        List<Block> chunks = ChunkChainBuilder.split(config, payload(1000, 7), TS);
        for (int i = 0; i < chunks.size(); i++) {
            Block b = chunks.get(i);
            assertEquals(TS - i, b.getTimestamp());
            ChunkExt c = LaneBlockClassifier.classify(b).as(ChunkExt.class);
            assertEquals(i, c.seq());
            assertEquals(1000, c.totalLen());
            assertNotNull(b.getOutsig()); // zero signature parsed as the (1,1) pseudo signature
            int fields = 0;
            for (int f = 1; f < 16; f++) {
                if (b.getXdagBlock().getField(f).getType() == XDAG_FIELD_SIGN_OUT) {
                    fields++;
                }
            }
            assertEquals(2, fields);
        }
        assertNull(LaneBlockClassifier.classify(chunks.get(chunks.size() - 1)).as(ChunkExt.class).next());
        assertEquals(1, chunks.get(0).getBlockLinks().size());
    }

    @Test
    public void assembleReportsStructuralErrors() {
        List<Block> good = ChunkChainBuilder.split(config, payload(1000, 1), TS);
        Map<Bytes32, Block> idx = index(good);

        assertEquals(ExtError.CHUNK_TOO_MANY, ChunkChain.assemble(head(good), h -> idx.get(h), 2).error());
        assertEquals(ExtError.MISSING_LINK, ChunkChain.assemble(head(good), h -> null, 4096).error());

        // seq gap: middle chunk re-encoded with seq 5
        ChunkExt mid = LaneBlockClassifier.classify(good.get(1)).as(ChunkExt.class);
        Block badMid = rawChunk(config, TS - 1, new ChunkExt(5, mid.totalLen(), mid.dataLen(), mid.next(), mid.data()));
        Map<Bytes32, Block> gap = new HashMap<>(idx);
        gap.put(Bytes32.wrap(good.get(1).getHashLow().toArray()), badMid);
        assertEquals(ExtError.CHUNK_SEQ_GAP, ChunkChain.assemble(head(good), h -> gap.get(h), 4096).error());

        // total mismatch: tail claims a different total
        ChunkExt tail = LaneBlockClassifier.classify(good.get(2)).as(ChunkExt.class);
        Block badTail = rawChunk(config, TS - 2, new ChunkExt(2, 999, tail.dataLen(), null, tail.data()));
        Map<Bytes32, Block> mismatch = new HashMap<>(idx);
        mismatch.put(Bytes32.wrap(good.get(2).getHashLow().toArray()), badTail);
        assertEquals(ExtError.CHUNK_TOTAL_MISMATCH, ChunkChain.assemble(head(good), h -> mismatch.get(h), 4096).error());

        // not a chunk
        Map<Bytes32, Block> notChunk = new HashMap<>(idx);
        notChunk.put(head(good), LaneBlockClassifierTest.extBlock(config, List.of(), List.of()));
        assertEquals(ExtError.NOT_A_CHUNK, ChunkChain.assemble(head(good), h -> notChunk.get(h), 4096).error());
    }

    @Test
    public void tailWithLinkAndCycleAreRejected() {
        // chunk A: seq 0 already carries the whole total but still links B
        Bytes data = payload(10, 3);
        Block b = rawChunk(config, TS - 1, new ChunkExt(1, 10, 10, null, data));
        Block a = rawChunk(config, TS, new ChunkExt(0, 10, 10, Bytes32.wrap(b.getHashLow().toArray()), data));
        Map<Bytes32, Block> idx = index(List.of(a, b));
        assertEquals(ExtError.CHUNK_TAIL_HAS_LINK, ChunkChain.assemble(Bytes32.wrap(a.getHashLow().toArray()), h -> idx.get(h), 4096).error());

        // cycle: A(seq0) -> X, lookup(X) = B(seq1) -> A
        Bytes32 x = ChunkExtTest.hashLow(77);
        Block a2 = rawChunk(config, TS, new ChunkExt(0, 30, 10, x, data));
        Block b2 = rawChunk(config, TS - 1, new ChunkExt(1, 30, 10, Bytes32.wrap(a2.getHashLow().toArray()), data));
        Map<Bytes32, Block> cyc = new HashMap<>();
        cyc.put(Bytes32.wrap(a2.getHashLow().toArray()), a2);
        cyc.put(x, b2);
        assertEquals(ExtError.CHUNK_CYCLE, ChunkChain.assemble(Bytes32.wrap(a2.getHashLow().toArray()), h -> cyc.get(h), 4096).error());
    }

    @Test
    public void absurdTotalLenIsRejectedEarly() {
        // A single hand-built head chunk that claims a totalLen far beyond what maxChunks=4096
        // chunks of MAX_DATA_LEN=352 bytes each could ever carry. assemble() must reject this the
        // moment it decodes this first chunk, not after walking (or trying to buffer) anything.
        ChunkExt huge = new ChunkExt(0, 4096L * ChunkExt.MAX_DATA_LEN + 1, 10, null, payload(10, 55));
        Block head = rawChunk(config, TS, huge);
        Map<Bytes32, Block> idx = index(List.of(head));
        ExtResult<Bytes> result = ChunkChain.assemble(Bytes32.wrap(head.getHashLow().toArray()), h -> idx.get(h), 4096);
        assertEquals(ExtError.CHUNK_TOO_MANY, result.error());
    }

    @Test
    public void countLenientStopsAtFirstProblem() {
        List<Block> chunks = ChunkChainBuilder.split(config, payload(1000, 42), TS);
        assertEquals(3, chunks.size());
        Map<Bytes32, Block> idx = index(chunks);
        Bytes32 thirdHash = Bytes32.wrap(chunks.get(2).getHashLow().toArray());

        // The lookup returns null only for the third chunk's hash. countLenient only increments its
        // counter after a block was found and decoded as a chunk, so chunk 0 and chunk 1 count
        // (2), then the missing third hash ends the walk without being counted.
        Map<Bytes32, Block> missingThird = new HashMap<>(idx);
        missingThird.remove(thirdHash);
        assertEquals(2, ChunkChain.countLenient(head(chunks), h -> missingThird.get(h), 4096));

        // The second block IS reachable (lookup succeeds) but classifies as a non-chunk. Chunk 0
        // counts (1), then the walk stops on the classification check without counting the
        // non-chunk block.
        Map<Bytes32, Block> notChunk = new HashMap<>(idx);
        notChunk.put(Bytes32.wrap(chunks.get(1).getHashLow().toArray()), LaneBlockClassifierTest.extBlock(config, List.of(), List.of()));
        assertEquals(1, ChunkChain.countLenient(head(chunks), h -> notChunk.get(h), 4096));
    }

    @Test
    public void splitRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> ChunkChainBuilder.split(config, Bytes.EMPTY, TS));
    }

    @Test
    public void exactMaxChunksAssemblesAndOneMoreIsRejected() {
        Bytes exact = payload(4096 * ChunkExt.MAX_DATA_LEN, 4096);
        List<Block> exactChunks = ChunkChainBuilder.split(config, exact, TS);
        assertEquals(4096, exactChunks.size());
        Map<Bytes32, Block> exactIdx = index(exactChunks);
        ExtResult<Bytes> exactResult = ChunkChain.assemble(head(exactChunks), exactIdx::get, 4096);
        assertTrue(String.valueOf(exactResult.error()), exactResult.isOk());
        assertEquals(exact, exactResult.value());
        assertEquals(4096, ChunkChain.countLenient(head(exactChunks), exactIdx::get, 4096));

        Bytes oneMore = payload(4096 * ChunkExt.MAX_DATA_LEN + 1, 4097);
        List<Block> overChunks = ChunkChainBuilder.split(config, oneMore, TS);
        assertEquals(4097, overChunks.size());
        Map<Bytes32, Block> overIdx = index(overChunks);
        // The early total-vs-maxChunks bound fires on the head chunk before any further hop is
        // looked up, so assemble rejects the chain outright.
        assertEquals(ExtError.CHUNK_TOO_MANY, ChunkChain.assemble(head(overChunks), overIdx::get, 4096).error());
        // countLenient does not apply that bound; it just walks well-formed chunks up to the
        // maxChunks cap, so it stops at exactly 4096 rather than reporting all 4097.
        assertEquals(4096, ChunkChain.countLenient(head(overChunks), overIdx::get, 4096));
    }

    @Test
    public void chainEndingShortAndOvershootAreMismatches() {
        // Ends short: totalLen claims 30 bytes, but the two linked chunks only supply 10 + 10 = 20,
        // and the tail carries no further link -- the shortfall is only visible once the walk ends.
        Bytes shortData = payload(10, 51);
        Block shortTail = rawChunk(config, TS - 1, new ChunkExt(1, 30, 10, null, shortData));
        Block shortHead = rawChunk(config, TS,
                new ChunkExt(0, 30, 10, Bytes32.wrap(shortTail.getHashLow().toArray()), payload(10, 52)));
        Map<Bytes32, Block> shortIdx = index(List.of(shortHead, shortTail));
        assertEquals(ExtError.CHUNK_TOTAL_MISMATCH,
                ChunkChain.assemble(Bytes32.wrap(shortHead.getHashLow().toArray()), h -> shortIdx.get(h), 4096).error());

        // Overshoots mid-chain: totalLen claims 15 bytes, but two 10-byte chunks are linked, so the
        // running payload exceeds totalLen while the second chunk is still being buffered.
        Bytes overData1 = payload(10, 53);
        Bytes overData2 = payload(10, 54);
        Block overTail = rawChunk(config, TS - 1, new ChunkExt(1, 15, 10, null, overData2));
        Block overHead = rawChunk(config, TS,
                new ChunkExt(0, 15, 10, Bytes32.wrap(overTail.getHashLow().toArray()), overData1));
        Map<Bytes32, Block> overIdx = index(List.of(overHead, overTail));
        assertEquals(ExtError.CHUNK_TOTAL_MISMATCH,
                ChunkChain.assemble(Bytes32.wrap(overHead.getHashLow().toArray()), h -> overIdx.get(h), 4096).error());
    }

    @Test
    public void codecErrorsPropagateUnchanged() {
        List<Block> good = ChunkChainBuilder.split(config, payload(1000, 61), TS);
        Map<Bytes32, Block> idx = index(good);

        // Re-encode the second chunk with byte 20 of its header (a reserved-zero byte) patched
        // non-zero, keeping its seq/totalLen/dataLen/next/data otherwise identical.
        ChunkExt mid = LaneBlockClassifier.classify(good.get(1)).as(ChunkExt.class);
        byte[] header = mid.encodeHeader().toArray();
        header[20] = 1;
        List<Bytes32> fields = new ArrayList<>();
        fields.add(Bytes32.wrap(header));
        fields.addAll(mid.encodePayload());
        List<Address> links = mid.next() == null ? null : List.of(new Address(mid.next(), XDAG_FIELD_OUT, false));
        Block raw = new Block(config, TS - 1, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
        Block patched = new Block(new XdagBlock(raw.toBytes()));

        Map<Bytes32, Block> patchedIdx = new HashMap<>(idx);
        patchedIdx.put(Bytes32.wrap(good.get(1).getHashLow().toArray()), patched);
        assertEquals(ExtError.RESERVED_NONZERO, ChunkChain.assemble(head(good), h -> patchedIdx.get(h), 4096).error());
    }

    @Test
    public void ageRuleRejectsChunksBeforeThePreviousEpoch() {
        List<Block> chunks = ChunkChainBuilder.split(config, payload(1000, 71), TS);
        Map<Bytes32, Block> idx = index(chunks);
        long epoch = XdagTime.getEpoch(TS);

        // minEpoch one epoch behind the head's own epoch: every chunk (head at `epoch`, the rest at
        // `epoch - 1` since TS falls exactly on an epoch boundary) is still new enough.
        ExtResult<Bytes> okResult = ChunkChain.assemble(head(chunks), h -> idx.get(h), 4096, epoch - 1);
        assertTrue(String.valueOf(okResult.error()), okResult.isOk());

        // minEpoch past the head's own epoch: even the head chunk is now too old.
        assertEquals(ExtError.CHUNK_TOO_OLD, ChunkChain.assemble(head(chunks), h -> idx.get(h), 4096, epoch + 1).error());
        assertEquals(0, ChunkChain.countLenient(head(chunks), h -> idx.get(h), 4096, epoch + 1));

        // A single-chunk chain (payload small enough to need no continuation) carries its only
        // block at TS itself, so it satisfies minEpoch == epoch with no epoch-1 slack needed.
        List<Block> single = ChunkChainBuilder.split(config, payload(100, 72), TS);
        assertEquals(1, single.size());
        Map<Bytes32, Block> singleIdx = index(single);
        ExtResult<Bytes> sameEpochResult = ChunkChain.assemble(head(single), h -> singleIdx.get(h), 4096, epoch);
        assertTrue(String.valueOf(sameEpochResult.error()), sameEpochResult.isOk());
    }

    @Test
    public void nullHeadIsMissingLink() {
        assertEquals(ExtError.MISSING_LINK, ChunkChain.assemble(null, h -> null, 4096).error());
        assertEquals(0, ChunkChain.countLenient(null, h -> null, 4096));
    }
}
