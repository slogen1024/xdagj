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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_EXT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_HEAD_TEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_TRANSACTION_NONCE;
import static io.xdag.lane.ext.ChunkChainTest.index;
import static io.xdag.lane.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagField;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.XdagTime;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class LaneBlockBuilderTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private static final long TS = 0x16a00000000L;
    private static final Bytes LANE = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final XAmount ONE = XAmount.of(1, XUnit.XDAG);
    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    private static final LaneConfigExt CFG = new LaneConfigExt(1L, 32L, 10_000_000L);

    @Test
    public void callWithInlineArgs() {
        ExtResult<LaneBlockBuilder.Built> r = LaneBlockBuilder.call(config, TS, sender, UInt64.ONE, LANE, CONTRACT, 7, 100L, ONE, FEE, payload(200, 1));
        assertTrue(String.valueOf(r.error()), r.isOk());
        LaneBlockBuilder.Built built = r.value();
        assertTrue(built.chunks().isEmpty());
        Block b = built.block();
        Classified c = LaneBlockClassifier.classify(b);
        assertTrue(String.valueOf(c.error()), c.isOk());
        assertEquals(ExtKind.CALL, c.kind());
        assertEquals(payload(200, 1), c.as(CallExt.class).inlineArgs());
        assertEquals(1, b.getInputs().size());
        assertEquals(1, b.getOutputs().size());
        assertTrue(b.getBlockLinks().isEmpty());
        assertEquals(1, b.verifiedKeys().size());
        assertEquals(UInt64.ONE, b.getTxNonceField().getTransactionNonce());
        assertEquals(FEE, b.getFee());
    }

    @Test
    public void callWithChainedArgs() {
        Bytes args = payload(1000, 2);
        LaneBlockBuilder.Built built = LaneBlockBuilder.call(config, TS, sender, UInt64.ONE, LANE, CONTRACT, 7, 100L, ONE, FEE, args).value();
        assertEquals(3, built.chunks().size());
        Classified c = LaneBlockClassifier.classify(built.block());
        assertTrue(c.isOk());
        assertTrue(c.as(CallExt.class).argsByChain());
        assertEquals(1, built.block().getBlockLinks().size());
        assertTrue(built.chunks().get(0).getTimestamp() < built.block().getTimestamp());
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(args, ChunkChain.assemble(c.as(CallExt.class).argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void deployNewLaneChainsCodeAndKeepsSmallArgsInline() {
        Bytes wasm = payload(10_000, 3);
        LaneBlockBuilder.Built built = LaneBlockBuilder.deployNewLane(config, TS, sender, UInt64.ONE, FEE, wasm, CFG, payload(160, 4), 1_000L).value();
        assertEquals(29, built.chunks().size());
        Classified c = LaneBlockClassifier.classify(built.block());
        assertTrue(String.valueOf(c.error()), c.isOk());
        DeployExt d = c.as(DeployExt.class);
        assertTrue(d.newLane());
        assertTrue(d.codeByChain());
        assertEquals(false, d.argsByChain());
        assertEquals(HashUtils.sha256(wasm), d.codeHash());
        assertEquals(CFG, d.config());
        assertEquals(160, d.inlineArgs().size());
        assertEquals(1, built.block().getBlockLinks().size());
        assertEquals(wasm, ChunkChain.assemble(d.codeChainHead(), index(built.chunks())::get, 4096).value());
    }

    @Test
    public void deployNewLaneChainsArgsWhenTheyDoNotFit() {
        LaneBlockBuilder.Built built = LaneBlockBuilder.deployNewLane(config, TS, sender, UInt64.ONE, FEE, payload(500, 5), CFG, payload(161, 6), 1L).value();
        DeployExt d = LaneBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertTrue(d.argsByChain());
        assertEquals(2, built.block().getBlockLinks().size());
        assertEquals(2 + 1, built.chunks().size()); // 500B code = 2 chunks, 161B args = 1 chunk
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(payload(161, 6), ChunkChain.assemble(d.argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void feeHelpers() {
        assertEquals(0, LaneBlockBuilder.chunksFor(0));
        assertEquals(1, LaneBlockBuilder.chunksFor(352));
        assertEquals(2, LaneBlockBuilder.chunksFor(353));
        assertEquals(XAmount.of(30, XUnit.MILLI_XDAG), LaneBlockBuilder.minHeaderFee(XAmount.of(10, XUnit.MILLI_XDAG), 3));
    }

    @Test
    public void deployIntoLaneWithKnownCode() {
        Bytes32 codeHash = Bytes32.random();
        LaneBlockBuilder.Built built = LaneBlockBuilder.deployIntoLane(config, TS, sender, UInt64.ONE, LANE, ONE, FEE, null, codeHash, payload(100, 7), 1L).value();
        assertTrue(built.chunks().isEmpty());
        DeployExt d = LaneBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertEquals(false, d.newLane());
        assertEquals(false, d.codeByChain());
        assertEquals(codeHash, d.codeHash());
        assertEquals(LANE, d.laneId());
        assertEquals(payload(100, 7), d.inlineArgs());
    }

    @Test
    public void chunksSatisfyTheAgeRuleForEveryEpochOffset() {
        long base = 0x16a00000000L;
        long[] offsets = {0, 1, 2, 0x8000, 0xfffe, 0xffff};
        for (long offset : offsets) {
            long ts = base + offset;

            LaneBlockBuilder.Built call = LaneBlockBuilder.call(config, ts, sender, UInt64.ONE, LANE, CONTRACT, 7,
                    100L, ONE, FEE, payload(1000, 1000 + offset)).value();
            LaneBlockBuilder.Built deploy = LaneBlockBuilder.deployNewLane(config, ts, sender, UInt64.ONE, FEE,
                    payload(5000, 2000 + offset), CFG, payload(300, 3000 + offset), 1_000L).value();

            long callMinEpoch = assertChunksWithinAgeRule(call.block(), call.chunks());
            long deployMinEpoch = assertChunksWithinAgeRule(deploy.block(), deploy.chunks());

            CallExt callExt = LaneBlockClassifier.classify(call.block()).as(CallExt.class);
            Map<Bytes32, Block> callIdx = index(call.chunks());
            assertTrue("offset " + offset,
                    ChunkChain.assemble(callExt.argsChainHead(), callIdx::get, 4096, callMinEpoch).isOk());

            DeployExt deployExt = LaneBlockClassifier.classify(deploy.block()).as(DeployExt.class);
            Map<Bytes32, Block> deployIdx = index(deploy.chunks());
            assertTrue("offset " + offset,
                    ChunkChain.assemble(deployExt.codeChainHead(), deployIdx::get, 4096, deployMinEpoch).isOk());
            assertTrue("offset " + offset,
                    ChunkChain.assemble(deployExt.argsChainHead(), deployIdx::get, 4096, deployMinEpoch).isOk());
        }
    }

    /** Asserts every chunk of {@code chunks} predates {@code block} and stays within the age rule; returns {@code epoch(block) - 1}. */
    private static long assertChunksWithinAgeRule(Block block, List<Block> chunks) {
        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        for (Block chunk : chunks) {
            assertTrue(chunk.getTimestamp() < block.getTimestamp());
            assertTrue(XdagTime.getEpoch(chunk.getTimestamp()) >= minEpoch);
        }
        return minEpoch;
    }

    @Test
    public void fieldOrderMatchesEncodedOrder() {
        Bytes args = payload(1000, 42);
        LaneBlockBuilder.Built built = LaneBlockBuilder.call(config, TS, sender, UInt64.ONE, LANE, CONTRACT, 7, 100L, ONE, FEE, args).value();
        Block b = built.block();

        List<XdagField.FieldType> expected = List.of(XDAG_FIELD_HEAD_TEST, XDAG_FIELD_TRANSACTION_NONCE,
                XDAG_FIELD_INPUT, XDAG_FIELD_OUTPUT, XDAG_FIELD_OUT, XDAG_FIELD_EXT);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("field " + i, expected.get(i), b.getXdagBlock().getField(i).getType());
        }
        assertEquals(1, b.getInputs().size());
        assertEquals(2, b.getOutputs().size());
        assertEquals(1, b.getBlockLinks().size());
    }

    @Test
    public void deployIntoLaneWithBothChainsUsesSameHeadTimestamp() {
        LaneBlockBuilder.Built built = LaneBlockBuilder.deployIntoLane(config, TS, sender, UInt64.ONE, LANE, ONE, FEE,
                payload(500, 51), null, payload(300, 52), 1L).value();
        Block block = built.block();
        DeployExt d = LaneBlockClassifier.classify(block).as(DeployExt.class);
        assertTrue(d.codeByChain());
        assertTrue(d.argsByChain());
        assertEquals(2, block.getBlockLinks().size());
        assertEquals(d.codeChainHead(), block.getBlockLinks().get(0).getAddress());
        assertEquals(d.argsChainHead(), block.getBlockLinks().get(1).getAddress());

        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        Map<Bytes32, Block> idx = index(built.chunks());
        assertTrue(ChunkChain.assemble(d.codeChainHead(), idx::get, 4096, minEpoch).isOk());
        assertTrue(ChunkChain.assemble(d.argsChainHead(), idx::get, 4096, minEpoch).isOk());
    }
}
