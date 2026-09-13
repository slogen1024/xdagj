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

        // The lookup returns null only for the third chunk's hash. countLenient's loop condition
        // runs `visited.add(cur)` *before* the body calls the lookup, so by the time the null
        // result is discovered, the third hash has already been recorded as visited. The walk then
        // breaks, having visited all 3 hashes -- not 2. (Deviation from the task description, which
        // expected 2 for this scenario; verified against the unmodified plan algorithm, kept as
        // given per requirement B.)
        Map<Bytes32, Block> missingThird = new HashMap<>(idx);
        missingThird.remove(thirdHash);
        assertEquals(3, ChunkChain.countLenient(head(chunks), h -> missingThird.get(h), 4096));

        // The second block IS reachable (lookup succeeds) but classifies as a non-chunk, so it
        // counts as visited and then the walk stops on the classification check -- exactly 2.
        Map<Bytes32, Block> notChunk = new HashMap<>(idx);
        notChunk.put(Bytes32.wrap(chunks.get(1).getHashLow().toArray()), LaneBlockClassifierTest.extBlock(config, List.of(), List.of()));
        assertEquals(2, ChunkChain.countLenient(head(chunks), h -> notChunk.get(h), 4096));
    }

    @Test
    public void splitRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> ChunkChainBuilder.split(config, Bytes.EMPTY, TS));
    }
}
