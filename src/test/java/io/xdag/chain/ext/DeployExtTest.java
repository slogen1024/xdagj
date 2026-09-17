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

import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class DeployExtTest {

    private static final Bytes ZERO_CHAIN = Bytes.wrap(new byte[20]);
    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final ChainConfigExt CONFIG = new ChainConfigExt(5L, 32L, 10_000_000L);

    @Test
    public void chainConfigRoundTrip() {
        ExtResult<ChainConfigExt> r = ChainConfigExt.decode(CONFIG.encode());
        assertTrue(r.isOk());
        assertEquals(CONFIG, r.value());
        byte[] dirty = CONFIG.encode().toArray();
        dirty[20] = 1;
        assertEquals(ExtError.RESERVED_NONZERO, ChainConfigExt.decode(Bytes32.wrap(dirty)).error());
    }

    @Test
    public void newChainWithCodeChainAndInlineArgs() {
        Bytes32 codeHead = hashLow(11);
        Bytes args = Bytes.random(50);
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_CHAIN | DeployExt.FLAG_CODE_CHAIN, ZERO_CHAIN, 1_000_000L, 50,
                CODE_HASH, CONFIG, args, codeHead, null);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(codeHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertTrue(r.value().newChain());
        assertTrue(r.value().codeByChain());
        // payload = codeHash + config + 2 args fields
        assertEquals(4, d.encodePayload().size());
    }

    @Test
    public void intoChainWithKnownCodeAndChainedArgs() {
        Bytes32 argsHead = hashLow(12);
        DeployExt d = new DeployExt(DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, argsHead);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(argsHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertNull(r.value().config());
        assertEquals(1, d.encodePayload().size());
    }

    @Test
    public void intoChainWithBothChainsOrdersLinksCodeThenArgs() {
        Bytes32 codeHead = hashLow(13);
        Bytes32 argsHead = hashLow(14);
        DeployExt d = new DeployExt(DeployExt.FLAG_CODE_CHAIN | DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH,
                null, Bytes.EMPTY, codeHead, argsHead);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(),
                List.of(link(codeHead), link(argsHead)));
        assertTrue(r.isOk());
        assertEquals(codeHead, r.value().codeChainHead());
        assertEquals(argsHead, r.value().argsChainHead());
        assertEquals(ExtError.MISSING_LINK,
                DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(codeHead))).error());
        assertEquals(ExtError.EXTRA_LINK, DeployExt.decode(d.encodeHeader(), d.encodePayload(),
                List.of(link(codeHead), link(argsHead), link(hashLow(15)))).error());
    }

    @Test
    public void rejectsBadShapes() {
        DeployExt nonZeroChain = new DeployExt(DeployExt.FLAG_NEW_CHAIN, CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(nonZeroChain.encodeHeader(), nonZeroChain.encodePayload(), List.of()).error());

        DeployExt ok = new DeployExt(0, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH, DeployExt.decode(ok.encodeHeader(), List.of(), List.of()).error());

        DeployExt missingConfig = new DeployExt(DeployExt.FLAG_NEW_CHAIN, ZERO_CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH,
                DeployExt.decode(missingConfig.encodeHeader(), List.of(CODE_HASH), List.of()).error());

        DeployExt badFlags = new DeployExt(0x08, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(badFlags.encodeHeader(), badFlags.encodePayload(), List.of()).error());
    }

    @Test
    public void headerAndConfigLayoutArePinned() {
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_CHAIN | DeployExt.FLAG_CODE_CHAIN, ZERO_CHAIN, 0x0A0B0C0DL,
                0x0102, CODE_HASH, CONFIG, Bytes.random(0x0102), hashLow(1), null);
        assertEquals("0x0203" + "00".repeat(20) + "0d0c0b0a" + "0201" + "00000000", d.encodeHeader().toHexString());

        assertEquals("0x0807060504030201" + "0d0c0b0a" + "44332211" + "00".repeat(16),
                new ChainConfigExt(0x0102030405060708L, 0x0A0B0C0DL, 0x11223344L).encode().toHexString());
    }

    @Test
    public void constructorRejectsInconsistentRecords() {
        // chainId must be exactly 20 bytes.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, Bytes.random(19), 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // inlineArgs.size() must equal argsLen.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, CHAIN, 1L, 5, CODE_HASH, null, Bytes.random(3), null, null));
        // FLAG_CODE_CHAIN set but no codeChainHead.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(DeployExt.FLAG_CODE_CHAIN, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // argsChainHead given without FLAG_ARGS_CHAIN.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, hashLow(20)));
        // FLAG_NEW_CHAIN set but config is null.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(DeployExt.FLAG_NEW_CHAIN, ZERO_CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // config given without FLAG_NEW_CHAIN.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null));
        // flags does not fit a u8.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0x100, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // ChainConfigExt.deliveryDelayD must fit a u32.
        assertThrows(IllegalArgumentException.class, () -> new ChainConfigExt(1, 0x1_0000_0000L, 1));
    }

    @Test
    public void decodeNeverThrowsOnNullShapes() {
        assertEquals(ExtError.NO_EXT, DeployExt.decode(null, List.of(), List.of()).error());

        DeployExt intoChain = new DeployExt(0, CHAIN, 1L, 20, CODE_HASH, null, Bytes.random(20), null, null);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                DeployExt.decode(intoChain.encodeHeader(), null, List.of()).error());

        ExtResult<DeployExt> r = DeployExt.decode(intoChain.encodeHeader(), intoChain.encodePayload(), null);
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(intoChain, r.value());

        Bytes32 codeHead = hashLow(21);
        DeployExt chained = new DeployExt(DeployExt.FLAG_CODE_CHAIN, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY,
                codeHead, null);
        assertEquals(ExtError.MISSING_LINK,
                DeployExt.decode(chained.encodeHeader(), chained.encodePayload(), Arrays.asList((Address) null))
                        .error());
    }

    @Test
    public void allFlagCombinationsRoundTrip() {
        for (int f = 0; f < 8; f++) {
            boolean newChain = (f & DeployExt.FLAG_NEW_CHAIN) != 0;
            boolean codeChain = (f & DeployExt.FLAG_CODE_CHAIN) != 0;
            boolean argsChain = (f & DeployExt.FLAG_ARGS_CHAIN) != 0;

            Bytes chainId = newChain ? ZERO_CHAIN : CHAIN;
            ChainConfigExt config = newChain ? CONFIG : null;
            Bytes32 codeHead = codeChain ? hashLow(10 + f) : null;
            Bytes32 argsHead = argsChain ? hashLow(20 + f) : null;
            int argsLen = argsChain ? 0 : 40;
            Bytes inlineArgs = argsChain ? Bytes.EMPTY : Bytes.random(40);

            DeployExt d = new DeployExt(f, chainId, 1L, argsLen, CODE_HASH, config, inlineArgs, codeHead, argsHead);

            List<Address> links = new ArrayList<>();
            if (codeChain) {
                links.add(link(codeHead));
            }
            if (argsChain) {
                links.add(link(argsHead));
            }

            ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), links);
            assertTrue("flags=" + f + ": " + r.error(), r.isOk());
            assertEquals("flags=" + f, d, r.value());

            int expectedFields = 1 + (newChain ? 1 : 0) + (argsChain ? 0 : 2);
            assertEquals("flags=" + f, expectedFields, d.encodePayload().size());
        }
    }

    @Test
    public void decodeBranchGuards() {
        DeployExt c = new DeployExt(0, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);

        byte[] wrongKind = c.encodeHeader().toArray();
        wrongKind[0] = ExtKind.CALL.code();
        assertEquals(ExtError.UNKNOWN_KIND,
                DeployExt.decode(Bytes32.wrap(wrongKind), c.encodePayload(), List.of()).error());

        byte[] dirtyTail = c.encodeHeader().toArray();
        dirtyTail[29] = 1;
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(Bytes32.wrap(dirtyTail), c.encodePayload(), List.of()).error());

        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                DeployExt.decode(c.encodeHeader(), Arrays.asList((Bytes32) null), List.of()).error());

        DeployExt nl = new DeployExt(DeployExt.FLAG_NEW_CHAIN, ZERO_CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        List<Bytes32> dirtyConfig = new ArrayList<>(nl.encodePayload());
        byte[] cfg = dirtyConfig.get(1).toArray();
        cfg[20] = 1;
        dirtyConfig.set(1, Bytes32.wrap(cfg));
        assertEquals(ExtError.RESERVED_NONZERO, DeployExt.decode(nl.encodeHeader(), dirtyConfig, List.of()).error());

        Bytes32 argsHead = hashLow(30);
        DeployExt ac = new DeployExt(DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, argsHead);
        byte[] badArgsLen = ac.encodeHeader().toArray();
        badArgsLen[26] = 4;
        assertEquals(ExtError.BAD_LENGTH,
                DeployExt.decode(Bytes32.wrap(badArgsLen), ac.encodePayload(), List.of(link(argsHead))).error());

        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                DeployExt.decode(ac.encodeHeader(), List.of(CODE_HASH, CODE_HASH), List.of(link(argsHead))).error());

        Bytes tenBytes = Bytes.random(10);
        DeployExt inl = new DeployExt(0, CHAIN, 1L, 10, CODE_HASH, null, tenBytes, null, null);
        List<Bytes32> dirtyPadding = new ArrayList<>(inl.encodePayload());
        byte[] last = dirtyPadding.get(1).toArray();
        last[31] = 1;
        dirtyPadding.set(1, Bytes32.wrap(last));
        assertEquals(ExtError.RESERVED_NONZERO, DeployExt.decode(inl.encodeHeader(), dirtyPadding, List.of()).error());

        Bytes32 codeHead2 = hashLow(31);
        Bytes32 argsHead2 = hashLow(32);
        DeployExt both = new DeployExt(DeployExt.FLAG_CODE_CHAIN | DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH,
                null, Bytes.EMPTY, codeHead2, argsHead2);
        ExtResult<DeployExt> swapped = DeployExt.decode(both.encodeHeader(), both.encodePayload(),
                List.of(link(argsHead2), link(codeHead2)));
        assertTrue(swapped.isOk());
        assertEquals(argsHead2, swapped.value().codeChainHead());
    }

    @Test
    public void inlineArgsAtLimitAndBeyond() {
        Bytes atLimit = Bytes.random(CallExt.MAX_INLINE_ARGS);
        DeployExt ok = new DeployExt(0, CHAIN, 1L, CallExt.MAX_INLINE_ARGS, CODE_HASH, null, atLimit, null, null);
        ExtResult<DeployExt> r = DeployExt.decode(ok.encodeHeader(), ok.encodePayload(), List.of());
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(9, ok.encodePayload().size());

        Bytes over = Bytes.random(CallExt.MAX_INLINE_ARGS + 1);
        DeployExt bad = new DeployExt(0, CHAIN, 1L, CallExt.MAX_INLINE_ARGS + 1, CODE_HASH, null, over, null, null);
        assertEquals(ExtError.INLINE_ARGS_TOO_LONG,
                DeployExt.decode(bad.encodeHeader(), bad.encodePayload(), List.of()).error());
    }

    @Test
    public void chainConfigKeepsU64BitPattern() {
        ChainConfigExt maxBits = new ChainConfigExt(-1L, 0xFFFFFFFFL, 0xFFFFFFFFL);
        assertEquals("0x" + "ff".repeat(16) + "00".repeat(16), maxBits.encode().toHexString());
        ExtResult<ChainConfigExt> r = ChainConfigExt.decode(maxBits.encode());
        assertTrue(r.isOk());
        assertEquals(maxBits, r.value());
    }
}
