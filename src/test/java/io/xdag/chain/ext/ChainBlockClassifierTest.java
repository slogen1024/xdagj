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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainBlockClassifierTest {

    private final Config config = new DevnetConfig();

    /** Builds an unsigned block carrying the given extension fields and block links, re-parsed from bytes. */
    public static Block extBlock(Config config, List<Bytes32> ext, List<Address> links) {
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, links.isEmpty() ? null : links, false,
                null, null, -1, XAmount.ZERO, null, ext);
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void blockWithoutExtIsNone() {
        Block b = extBlock(config, List.of(), List.of());
        Classified c = ChainBlockClassifier.classify(b);
        assertSame(Classified.NONE, c);
        assertNull(c.kind());
        assertEquals(ExtError.NO_EXT, c.error());
    }

    @Test
    public void classifiesCall() {
        CallExt call = new CallExt(0, Bytes.random(20), 9, 100L, 3, Bytes.of((byte) 1, (byte) 2, (byte) 3), null);
        List<Bytes32> ext = new ArrayList<>();
        ext.add(call.encodeHeader());
        ext.addAll(call.encodePayload());
        Classified c = ChainBlockClassifier.classify(extBlock(config, ext, List.of()));
        assertTrue(String.valueOf(c.error()), c.isOk());
        assertEquals(ExtKind.CALL, c.kind());
        assertEquals(call, c.as(CallExt.class));
    }

    @Test
    public void classifiesChunkWithNextLink() {
        Bytes32 next = hashLow(3);
        ChunkExt chunk = new ChunkExt(0, 40, 40, next, Bytes.random(40));
        List<Bytes32> ext = new ArrayList<>();
        ext.add(chunk.encodeHeader());
        ext.addAll(chunk.encodePayload());
        Classified c = ChainBlockClassifier.classify(extBlock(config, ext, List.of(link(next))));
        assertTrue(c.isOk());
        assertEquals(ExtKind.CHUNK, c.kind());
        assertEquals(next, c.as(ChunkExt.class).next());
    }

    @Test
    public void unknownKindAndDecodeErrorsAreReported() {
        byte[] h = new byte[32];
        h[0] = 9;
        Classified unknown = ChainBlockClassifier.classify(extBlock(config, List.of(Bytes32.wrap(h)), List.of()));
        assertEquals(ExtError.UNKNOWN_KIND, unknown.error());
        assertNull(unknown.kind());

        CallExt chained = new CallExt(CallExt.FLAG_ARGS_CHAIN, Bytes.random(20), 1, 1, 0, Bytes.EMPTY, hashLow(1));
        Classified missing = ChainBlockClassifier.classify(extBlock(config, List.of(chained.encodeHeader()), List.of()));
        assertEquals(ExtKind.CALL, missing.kind());
        assertEquals(ExtError.MISSING_LINK, missing.error());
        assertNull(missing.value());
    }

    @Test
    public void addressOutputsAreNotLinks() {
        Bytes32 next = hashLow(3);
        ChunkExt chunk = new ChunkExt(0, 40, 40, next, Bytes.random(40));
        List<Bytes32> ext = new ArrayList<>();
        ext.add(chunk.encodeHeader());
        ext.addAll(chunk.encodePayload());

        Address addressOutput = new Address(BytesUtils.arrayToByte32(Bytes.random(20).toArray()), XDAG_FIELD_OUTPUT,
                XAmount.of(1, XUnit.XDAG), true);

        Classified withLink = ChainBlockClassifier.classify(extBlock(config, ext, List.of(addressOutput, link(next))));
        assertTrue(String.valueOf(withLink.error()), withLink.isOk());
        assertEquals(ExtKind.CHUNK, withLink.kind());
        assertEquals(next, withLink.as(ChunkExt.class).next());

        Classified withoutLink = ChainBlockClassifier.classify(extBlock(config, ext, List.of(addressOutput)));
        assertTrue(String.valueOf(withoutLink.error()), withoutLink.isOk());
        assertEquals(ExtKind.CHUNK, withoutLink.kind());
        assertNull(withoutLink.as(ChunkExt.class).next());
    }

    @Test
    public void linksKeepFieldOrder() {
        DeployExt deploy = new DeployExt(DeployExt.FLAG_CODE_CHAIN | DeployExt.FLAG_ARGS_CHAIN, Bytes.random(20), 0, 0,
                Bytes32.wrap(Bytes.random(32)), null, Bytes.EMPTY, hashLow(11), hashLow(12));
        List<Bytes32> ext = new ArrayList<>();
        ext.add(deploy.encodeHeader());
        ext.addAll(deploy.encodePayload());

        Classified forward = ChainBlockClassifier.classify(
                extBlock(config, ext, List.of(link(hashLow(11)), link(hashLow(12)))));
        assertTrue(String.valueOf(forward.error()), forward.isOk());
        assertEquals(hashLow(11), forward.as(DeployExt.class).codeChainHead());
        assertEquals(hashLow(12), forward.as(DeployExt.class).argsChainHead());

        Classified reversed = ChainBlockClassifier.classify(
                extBlock(config, ext, List.of(link(hashLow(12)), link(hashLow(11)))));
        assertTrue(String.valueOf(reversed.error()), reversed.isOk());
        assertEquals(hashLow(12), reversed.as(DeployExt.class).codeChainHead());
    }

    @Test
    public void everyKindDispatchesToItsCodec() {
        for (ExtKind k : ExtKind.values()) {
            byte[] h = new byte[32];
            h[0] = k.code();
            Classified c = ChainBlockClassifier.classify(extBlock(config, List.of(Bytes32.wrap(h)), List.of()));
            assertEquals(k, c.kind());
            // a mis-wired switch arm would still stamp the right kind but the wrong codec answers UNKNOWN_KIND
            assertNotEquals(ExtError.UNKNOWN_KIND, c.error());
        }
    }

    @Test
    public void unknownKindForZeroAndFf() {
        byte[] zero = new byte[32];
        Classified zeroResult = ChainBlockClassifier.classify(extBlock(config, List.of(Bytes32.wrap(zero)), List.of()));
        assertEquals(ExtError.UNKNOWN_KIND, zeroResult.error());
        assertNull(zeroResult.kind());

        byte[] ff = new byte[32];
        ff[0] = (byte) 0xFF;
        Classified ffResult = ChainBlockClassifier.classify(extBlock(config, List.of(Bytes32.wrap(ff)), List.of()));
        assertEquals(ExtError.UNKNOWN_KIND, ffResult.error());
        assertNull(ffResult.kind());
    }

    @Test
    public void blockInfoOnlyBlockClassifiesAsNone() {
        Classified c = ChainBlockClassifier.classify(new Block(new BlockInfo()));
        assertSame(Classified.NONE, c);
    }

    @Test
    public void asThrowsOnErrorResult() {
        CallExt chained = new CallExt(CallExt.FLAG_ARGS_CHAIN, Bytes.random(20), 1, 1, 0, Bytes.EMPTY, hashLow(1));
        Classified c = ChainBlockClassifier.classify(extBlock(config, List.of(chained.encodeHeader()), List.of()));
        assertEquals(ExtError.MISSING_LINK, c.error());
        assertThrows(IllegalStateException.class, () -> c.as(CallExt.class));
    }
}
