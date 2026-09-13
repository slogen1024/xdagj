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

import static io.xdag.lane.ext.ChunkExtTest.hashLow;
import static io.xdag.lane.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class DeployExtTest {

    private static final Bytes ZERO_LANE = Bytes.wrap(new byte[20]);
    private static final Bytes LANE = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final LaneConfigExt CONFIG = new LaneConfigExt(5L, 32L, 10_000_000L);

    @Test
    public void laneConfigRoundTrip() {
        ExtResult<LaneConfigExt> r = LaneConfigExt.decode(CONFIG.encode());
        assertTrue(r.isOk());
        assertEquals(CONFIG, r.value());
        byte[] dirty = CONFIG.encode().toArray();
        dirty[20] = 1;
        assertEquals(ExtError.RESERVED_NONZERO, LaneConfigExt.decode(Bytes32.wrap(dirty)).error());
    }

    @Test
    public void newLaneWithCodeChainAndInlineArgs() {
        Bytes32 codeHead = hashLow(11);
        Bytes args = Bytes.random(50);
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_LANE | DeployExt.FLAG_CODE_CHAIN, ZERO_LANE, 1_000_000L, 50,
                CODE_HASH, CONFIG, args, codeHead, null);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(codeHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertTrue(r.value().newLane());
        assertTrue(r.value().codeByChain());
        // payload = codeHash + config + 2 args fields
        assertEquals(4, d.encodePayload().size());
    }

    @Test
    public void intoLaneWithKnownCodeAndChainedArgs() {
        Bytes32 argsHead = hashLow(12);
        DeployExt d = new DeployExt(DeployExt.FLAG_ARGS_CHAIN, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, argsHead);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(argsHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertNull(r.value().config());
        assertEquals(1, d.encodePayload().size());
    }

    @Test
    public void intoLaneWithBothChainsOrdersLinksCodeThenArgs() {
        Bytes32 codeHead = hashLow(13);
        Bytes32 argsHead = hashLow(14);
        DeployExt d = new DeployExt(DeployExt.FLAG_CODE_CHAIN | DeployExt.FLAG_ARGS_CHAIN, LANE, 1L, 0, CODE_HASH,
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
        DeployExt nonZeroLane = new DeployExt(DeployExt.FLAG_NEW_LANE, LANE, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(nonZeroLane.encodeHeader(), nonZeroLane.encodePayload(), List.of()).error());

        DeployExt ok = new DeployExt(0, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH, DeployExt.decode(ok.encodeHeader(), List.of(), List.of()).error());

        DeployExt missingConfig = new DeployExt(DeployExt.FLAG_NEW_LANE, ZERO_LANE, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH,
                DeployExt.decode(missingConfig.encodeHeader(), List.of(CODE_HASH), List.of()).error());

        DeployExt badFlags = new DeployExt(0x08, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(badFlags.encodeHeader(), badFlags.encodePayload(), List.of()).error());
    }

    @Test
    public void headerAndConfigLayoutArePinned() {
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_LANE | DeployExt.FLAG_CODE_CHAIN, ZERO_LANE, 0x0A0B0C0DL,
                0x0102, CODE_HASH, CONFIG, Bytes.random(0x0102), hashLow(1), null);
        assertEquals("0x0203" + "00".repeat(20) + "0d0c0b0a" + "0201" + "00000000", d.encodeHeader().toHexString());

        assertEquals("0x0807060504030201" + "0d0c0b0a" + "44332211" + "00".repeat(16),
                new LaneConfigExt(0x0102030405060708L, 0x0A0B0C0DL, 0x11223344L).encode().toHexString());
    }

    @Test
    public void constructorRejectsInconsistentRecords() {
        // laneId must be exactly 20 bytes.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, Bytes.random(19), 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // inlineArgs.size() must equal argsLen.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, LANE, 1L, 5, CODE_HASH, null, Bytes.random(3), null, null));
        // FLAG_CODE_CHAIN set but no codeChainHead.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(DeployExt.FLAG_CODE_CHAIN, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // argsChainHead given without FLAG_ARGS_CHAIN.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, hashLow(20)));
        // FLAG_NEW_LANE set but config is null.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(DeployExt.FLAG_NEW_LANE, ZERO_LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // config given without FLAG_NEW_LANE.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0, LANE, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null));
        // flags does not fit a u8.
        assertThrows(IllegalArgumentException.class,
                () -> new DeployExt(0x100, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null));
        // LaneConfigExt.deliveryDelayD must fit a u32.
        assertThrows(IllegalArgumentException.class, () -> new LaneConfigExt(1, 0x1_0000_0000L, 1));
    }

    @Test
    public void decodeNeverThrowsOnNullShapes() {
        assertEquals(ExtError.NO_EXT, DeployExt.decode(null, List.of(), List.of()).error());

        DeployExt intoLane = new DeployExt(0, LANE, 1L, 20, CODE_HASH, null, Bytes.random(20), null, null);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                DeployExt.decode(intoLane.encodeHeader(), null, List.of()).error());

        ExtResult<DeployExt> r = DeployExt.decode(intoLane.encodeHeader(), intoLane.encodePayload(), null);
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(intoLane, r.value());

        Bytes32 codeHead = hashLow(21);
        DeployExt chained = new DeployExt(DeployExt.FLAG_CODE_CHAIN, LANE, 1L, 0, CODE_HASH, null, Bytes.EMPTY,
                codeHead, null);
        assertEquals(ExtError.MISSING_LINK,
                DeployExt.decode(chained.encodeHeader(), chained.encodePayload(), Arrays.asList((Address) null))
                        .error());
    }
}
