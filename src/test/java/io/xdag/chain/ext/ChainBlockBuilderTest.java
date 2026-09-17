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

package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_EXT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_HEAD_TEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_TRANSACTION_NONCE;
import static io.xdag.chain.ext.ChunkChainTest.index;
import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
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

public class ChainBlockBuilderTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private static final long TS = 0x16a00000000L;
    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final XAmount ONE = XAmount.of(1, XUnit.XDAG);
    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    private static final ChainConfigExt CFG = new ChainConfigExt(1L, 32L, 10_000_000L);

    @Test
    public void callWithInlineArgs() {
        ExtResult<ChainBlockBuilder.Built> r = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, payload(200, 1));
        assertTrue(String.valueOf(r.error()), r.isOk());
        ChainBlockBuilder.Built built = r.value();
        assertTrue(built.chunks().isEmpty());
        Block b = built.block();
        Classified c = ChainBlockClassifier.classify(b);
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
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, args).value();
        assertEquals(3, built.chunks().size());
        Classified c = ChainBlockClassifier.classify(built.block());
        assertTrue(c.isOk());
        assertTrue(c.as(CallExt.class).argsByChain());
        assertEquals(1, built.block().getBlockLinks().size());
        assertTrue(built.chunks().get(0).getTimestamp() < built.block().getTimestamp());
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(args, ChunkChain.assemble(c.as(CallExt.class).argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void deployNewChainSplitsCodeAndKeepsSmallArgsInline() {
        Bytes wasm = payload(10_000, 3);
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, wasm, CFG, payload(160, 4), 1_000L).value();
        assertEquals(29, built.chunks().size());
        Classified c = ChainBlockClassifier.classify(built.block());
        assertTrue(String.valueOf(c.error()), c.isOk());
        DeployExt d = c.as(DeployExt.class);
        assertTrue(d.newChain());
        assertTrue(d.codeByChain());
        assertEquals(false, d.argsByChain());
        assertEquals(HashUtils.sha256(wasm), d.codeHash());
        assertEquals(CFG, d.config());
        assertEquals(160, d.inlineArgs().size());
        assertEquals(1, built.block().getBlockLinks().size());
        assertEquals(wasm, ChunkChain.assemble(d.codeChainHead(), index(built.chunks())::get, 4096).value());
    }

    @Test
    public void deployNewChainSplitsArgsWhenTheyDoNotFit() {
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, payload(500, 5), CFG, payload(161, 6), 1L).value();
        DeployExt d = ChainBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertTrue(d.argsByChain());
        assertEquals(2, built.block().getBlockLinks().size());
        assertEquals(2 + 1, built.chunks().size()); // 500B code = 2 chunks, 161B args = 1 chunk
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(payload(161, 6), ChunkChain.assemble(d.argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void feeHelpers() {
        assertEquals(0, ChainBlockBuilder.chunksFor(0));
        assertEquals(1, ChainBlockBuilder.chunksFor(352));
        assertEquals(2, ChainBlockBuilder.chunksFor(353));
        assertEquals(XAmount.of(30, XUnit.MILLI_XDAG), ChainBlockBuilder.minHeaderFee(XAmount.of(10, XUnit.MILLI_XDAG), 3));
    }

    @Test
    public void deployIntoChainWithKnownCode() {
        Bytes32 codeHash = Bytes32.random();
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE, FEE, null, codeHash, payload(100, 7), 1L).value();
        assertTrue(built.chunks().isEmpty());
        DeployExt d = ChainBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertEquals(false, d.newChain());
        assertEquals(false, d.codeByChain());
        assertEquals(codeHash, d.codeHash());
        assertEquals(CHAIN, d.chainId());
        assertEquals(payload(100, 7), d.inlineArgs());
    }

    @Test
    public void chunksSatisfyTheAgeRuleForEveryEpochOffset() {
        long base = 0x16a00000000L;
        long[] offsets = {0, 1, 2, 0x8000, 0xfffe, 0xffff};
        for (long offset : offsets) {
            long ts = base + offset;

            ChainBlockBuilder.Built call = ChainBlockBuilder.call(config, ts, sender, UInt64.ONE, CHAIN, CONTRACT, 7,
                    100L, ONE, FEE, payload(1000, 1000 + offset)).value();
            ChainBlockBuilder.Built deploy = ChainBlockBuilder.deployNewChain(config, ts, sender, UInt64.ONE, FEE,
                    payload(5000, 2000 + offset), CFG, payload(300, 3000 + offset), 1_000L).value();

            long callMinEpoch = assertChunksWithinAgeRule(call.block(), call.chunks());
            long deployMinEpoch = assertChunksWithinAgeRule(deploy.block(), deploy.chunks());

            CallExt callExt = ChainBlockClassifier.classify(call.block()).as(CallExt.class);
            Map<Bytes32, Block> callIdx = index(call.chunks());
            assertTrue("offset " + offset,
                    ChunkChain.assemble(callExt.argsChainHead(), callIdx::get, 4096, callMinEpoch).isOk());

            DeployExt deployExt = ChainBlockClassifier.classify(deploy.block()).as(DeployExt.class);
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
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, args).value();
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
    public void deployIntoChainWithBothChainsUsesSameHeadTimestamp() {
        Bytes wasm = payload(500, 51);
        Bytes initArgs = payload(300, 52);
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE, FEE,
                wasm, null, initArgs, 1L).value();
        Block block = built.block();
        DeployExt d = ChainBlockClassifier.classify(block).as(DeployExt.class);
        assertTrue(d.codeByChain());
        assertTrue(d.argsByChain());
        assertEquals(2, block.getBlockLinks().size());

        // The code chain head is chunks[0]; the args chain head is the first chunk of the second
        // chain, right after the code chain's chunks. Both chains are rooted at the same
        // headTimestamp (timestamp - 1), so their heads must share a timestamp.
        int argsHeadIndex = ChainBlockBuilder.chunksFor(wasm.size());
        assertEquals(built.chunks().get(0).getTimestamp(), built.chunks().get(argsHeadIndex).getTimestamp());

        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        Map<Bytes32, Block> idx = index(built.chunks());
        assertTrue(ChunkChain.assemble(d.codeChainHead(), idx::get, 4096, minEpoch).isOk());
        assertTrue(ChunkChain.assemble(d.argsChainHead(), idx::get, 4096, minEpoch).isOk());
        // Content assertions (not just link-position echoes) catch a code/args link swap: decoding a
        // swapped pair would still make codeChainHead()/argsChainHead() agree with their own link
        // positions, but assembling them would then yield the wrong payload.
        assertEquals(wasm, ChunkChain.assemble(d.codeChainHead(), idx::get, 4096).value());
        assertEquals(initArgs, ChunkChain.assemble(d.argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void everyShapeSatisfiesTheL1AmountRule() {
        assertAmountRule(ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L,
                ChainBlockBuilder.requiredValue(FEE, 0), FEE, payload(200, 701)).value());
        assertAmountRule(ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L,
                ChainBlockBuilder.requiredValue(FEE, 1), FEE, payload(1000, 702)).value());
        assertAmountRule(ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, payload(5000, 703), CFG,
                payload(160, 704), 1_000L).value());
        assertAmountRule(ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, payload(5000, 705), CFG,
                payload(300, 706), 1_000L).value());
        assertAmountRule(ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN,
                ChainBlockBuilder.requiredValue(FEE, 0), FEE, null, Bytes32.random(), payload(100, 707), 1L).value());
        assertAmountRule(ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN,
                ChainBlockBuilder.requiredValue(FEE, 1), FEE, payload(500, 708), null, payload(100, 709), 1L).value());
        assertAmountRule(ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN,
                ChainBlockBuilder.requiredValue(FEE, 2), FEE, payload(500, 710), null, payload(300, 711), 1L).value());
    }

    /** Asserts the built block satisfies the L1 input/output amount rule and that {@code Built}'s bookkeeping matches the block. */
    private static void assertAmountRule(ChainBlockBuilder.Built built) {
        Block block = built.block();
        assertEquals(built.chainLinks(), block.getBlockLinks().size());
        assertEquals(built.totalChunks(), built.chunks().size());

        int outputs = block.getOutputs().size();
        XAmount txFee = FEE.add(Constants.MIN_GAS.multiply(outputs));
        XAmount inputAmount = block.getInputs().get(0).getAmount();
        assertTrue(inputAmount.compareTo(txFee) >= 0);

        XAmount outputLimit = Constants.MIN_GAS.compareTo(txFee.divide(outputs)) > 0
                ? Constants.MIN_GAS : txFee.divide(outputs);
        for (Address out : block.getOutputs()) {
            if (out.getType() == XDAG_FIELD_OUTPUT) {
                assertTrue(out.getAmount().compareTo(outputLimit) >= 0);
            }
        }
    }

    @Test
    public void inlineCapacityBoundaries() {
        // CALL: 256 bytes stays inline (0 chunks) and exactly fills the 16-field budget.
        ChainBlockBuilder.Built callInline = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7,
                100L, ONE, FEE, payload(256, 801)).value();
        assertTrue(callInline.chunks().isEmpty());
        assertEquals(16, usedFieldCount(callInline.block()));

        // 257 bytes must chain.
        ChainBlockBuilder.Built callChained = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7,
                100L, ONE, FEE, payload(257, 802)).value();
        assertEquals(1, callChained.chunks().size());
        assertTrue(ChainBlockClassifier.classify(callChained.block()).as(CallExt.class).argsByChain());

        // deployIntoChain, no code chain: 224 bytes stays inline, 225 chains.
        ChainBlockBuilder.Built intoInline = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE,
                FEE, null, Bytes32.random(), payload(224, 803), 1L).value();
        assertFalse(ChainBlockClassifier.classify(intoInline.block()).as(DeployExt.class).argsByChain());
        ChainBlockBuilder.Built intoChained = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE,
                FEE, null, Bytes32.random(), payload(225, 804), 1L).value();
        assertTrue(ChainBlockClassifier.classify(intoChained.block()).as(DeployExt.class).argsByChain());

        // deployIntoChain, with a code chain: 192 bytes stays inline, 193 chains.
        ChainBlockBuilder.Built intoCodeInline = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN,
                ONE, FEE, payload(500, 805), null, payload(192, 806), 1L).value();
        assertFalse(ChainBlockClassifier.classify(intoCodeInline.block()).as(DeployExt.class).argsByChain());
        ChainBlockBuilder.Built intoCodeChained = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN,
                ONE, FEE, payload(500, 807), null, payload(193, 808), 1L).value();
        assertTrue(ChainBlockClassifier.classify(intoCodeChained.block()).as(DeployExt.class).argsByChain());
        // deployNewChain's 160/161 boundary is already covered by deployNewChainSplitsCodeAndKeepsSmallArgsInline
        // and deployNewChainSplitsArgsWhenTheyDoNotFit.
    }

    /** Counts field 0 (the header) plus every field 1..15 whose type is not the padding {@code XDAG_FIELD_NONCE}. */
    private static int usedFieldCount(Block b) {
        int count = 1;
        for (int i = 1; i < XdagBlock.XDAG_BLOCK_FIELDS; i++) {
            if (b.getXdagBlock().getField(i).getType() != XdagField.FieldType.XDAG_FIELD_NONCE) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void errorsAreReportedForPayloadProblems() {
        assertEquals(ExtError.BAD_LENGTH, ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE,
                Bytes.EMPTY, CFG, payload(10, 901), 1L).error());

        assertEquals(ExtError.BAD_LENGTH, ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE,
                FEE, null, null, payload(10, 902), 1L).error());

        Bytes hugeArgs = payload(ChainBlockBuilder.MAX_CHUNKS_PER_CHAIN * ChunkExt.MAX_DATA_LEN + 1, 903);
        assertEquals(ExtError.CHUNK_TOO_MANY,
                ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, hugeArgs)
                        .error());

        assertThrows(IllegalArgumentException.class, () -> ChainBlockBuilder.call(config, TS, sender, UInt64.ONE,
                Bytes.random(19), CONTRACT, 7, 100L, ONE, FEE, payload(10, 904)));

        assertThrows(NullPointerException.class, () -> ChainBlockBuilder.call(config, TS, sender, null, CHAIN,
                CONTRACT, 7, 100L, ONE, FEE, payload(10, 905)));
    }

    @Test
    public void identicalChainsAreDeduplicated() {
        Bytes x = payload(500, 1001);
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE, FEE,
                x, null, x, 1L).value();
        int expectedPerChain = ChainBlockBuilder.chunksFor(500);
        assertEquals(expectedPerChain, built.chunks().size());
        assertEquals(2 * expectedPerChain, built.totalChunks());
        assertEquals(2, built.chainLinks());
        DeployExt d = ChainBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertEquals(d.codeChainHead(), d.argsChainHead());
    }

    @Test
    public void chunkHeadsAvoidTheEndOfEpochTick() {
        assertEquals(0, TS & 0xffffL);
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L,
                ONE, FEE, payload(1000, 1101)).value();
        Block head = built.chunks().get(0);
        assertFalse(XdagTime.isEndOfEpoch(head.getTimestamp()));

        long minEpoch = XdagTime.getEpoch(built.block().getTimestamp()) - 1;
        for (Block chunk : built.chunks()) {
            assertTrue(XdagTime.getEpoch(chunk.getTimestamp()) >= minEpoch);
        }
    }

    /**
     * {@link ChainBlockBuilder#splitChain} must cap chain length against {@code config}'s
     * configured {@link io.xdag.config.spec.ChainSpec#getChainMaxChunksPerChain()}, not the
     * protocol-default {@link ChainBlockBuilder#MAX_CHUNKS_PER_CHAIN} constant. The setters for
     * this construction-time parameter are gone (it is set-once via conf/subclass), so the shrunk
     * config is built as an anonymous {@link DevnetConfig} subclass overriding the getter.
     */
    @Test
    public void deployNewChainHonorsConfiguredMaxChunksPerChain() {
        Config shrunk = new DevnetConfig() {
            @Override
            public int getChainMaxChunksPerChain() {
                return 2;
            }
        };

        Bytes threeChunkWasm = payload(3 * ChunkExt.MAX_DATA_LEN, 1201);
        ExtResult<ChainBlockBuilder.Built> tooMany = ChainBlockBuilder.deployNewChain(shrunk, TS, sender, UInt64.ONE,
                FEE, threeChunkWasm, CFG, payload(10, 1202), 1L);
        assertEquals(ExtError.CHUNK_TOO_MANY, tooMany.error());

        Bytes twoChunkWasm = payload(2 * ChunkExt.MAX_DATA_LEN, 1203);
        ExtResult<ChainBlockBuilder.Built> ok = ChainBlockBuilder.deployNewChain(shrunk, TS, sender, UInt64.ONE, FEE,
                twoChunkWasm, CFG, payload(10, 1204), 1L);
        assertTrue(String.valueOf(ok.error()), ok.isOk());
        assertEquals(2, ok.value().totalChunks());
    }

    @Test
    public void requiredValueHelper() {
        assertEquals(FEE.add(XAmount.of(100, XUnit.MILLI_XDAG)), ChainBlockBuilder.requiredValue(FEE, 0));
        assertEquals(FEE.add(XAmount.of(300, XUnit.MILLI_XDAG)), ChainBlockBuilder.requiredValue(FEE, 2));

        assertEquals(ChainBlockBuilder.chunksFor(500) + ChainBlockBuilder.chunksFor(300),
                ChainBlockBuilder.deployNewChainChunks(500, 300));
        assertEquals(ChainBlockBuilder.chunksFor(500), ChainBlockBuilder.deployNewChainChunks(500, 160));
    }
}
