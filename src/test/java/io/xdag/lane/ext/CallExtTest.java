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

public class CallExtTest {

    private static final Bytes CONTRACT = Bytes.random(20);

    @Test
    public void inlineArgsRoundTrip() {
        Bytes args = Bytes.random(20);
        CallExt c = new CallExt(0, CONTRACT, 0x12345678, 5_000_000L, 20, args, null);
        ExtResult<CallExt> r = CallExt.decode(c.encodeHeader(), c.encodePayload(), List.of());
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(1, c.encodePayload().size());
    }

    @Test
    public void inlineArgsAtLimitAndBeyond() {
        Bytes max = Bytes.random(CallExt.MAX_INLINE_ARGS);
        CallExt ok = new CallExt(0, CONTRACT, 1, 1, CallExt.MAX_INLINE_ARGS, max, null);
        assertTrue(CallExt.decode(ok.encodeHeader(), ok.encodePayload(), List.of()).isOk());

        Bytes over = Bytes.random(CallExt.MAX_INLINE_ARGS + 1);
        CallExt bad = new CallExt(0, CONTRACT, 1, 1, CallExt.MAX_INLINE_ARGS + 1, over, null);
        assertEquals(ExtError.INLINE_ARGS_TOO_LONG,
                CallExt.decode(bad.encodeHeader(), bad.encodePayload(), List.of()).error());
    }

    @Test
    public void chainedArgs() {
        Bytes32 head = hashLow(5);
        CallExt c = new CallExt(CallExt.FLAG_ARGS_CHAIN, CONTRACT, 7, 10, 0, Bytes.EMPTY, head);
        ExtResult<CallExt> r = CallExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(head)));
        assertTrue(r.isOk());
        assertEquals(head, r.value().argsChainHead());
        assertTrue(r.value().argsByChain());
        assertEquals(0, c.encodePayload().size());

        assertEquals(ExtError.MISSING_LINK, CallExt.decode(c.encodeHeader(), List.of(), List.of()).error());
        assertEquals(ExtError.EXTRA_LINK,
                CallExt.decode(c.encodeHeader(), List.of(), List.of(link(head), link(hashLow(6)))).error());
    }

    @Test
    public void rejectsUnknownFlagsAndWrongKind() {
        CallExt c = new CallExt(0x02, CONTRACT, 1, 1, 0, Bytes.EMPTY, null);
        assertEquals(ExtError.RESERVED_NONZERO, CallExt.decode(c.encodeHeader(), List.of(), List.of()).error());

        byte[] h = new CallExt(0, CONTRACT, 1, 1, 0, Bytes.EMPTY, null).encodeHeader().toArray();
        h[0] = ExtKind.CHUNK.code();
        ExtResult<CallExt> r = CallExt.decode(Bytes32.wrap(h), List.of(), List.of());
        assertEquals(ExtError.UNKNOWN_KIND, r.error());
        assertNull(r.value());
    }

    @Test
    public void headerLayoutIsPinned() {
        Bytes contract = Bytes.repeat((byte) 0x11, 20);
        CallExt c = new CallExt(0, contract, 0x01020304, 0x0A0B0C0DL, 3, Bytes.of((byte) 1, (byte) 2, (byte) 3), null);
        assertEquals("0x0100" + "11".repeat(20) + "040302010d0c0b0a0300", c.encodeHeader().toHexString());
    }

    @Test
    public void constructorRejectsInconsistentRecords() {
        assertThrows(IllegalArgumentException.class,
                () -> new CallExt(0, Bytes.random(19), 1, 1, 0, Bytes.EMPTY, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CallExt(0, CONTRACT, 1, 1, 5, Bytes.random(3), null));
        assertThrows(IllegalArgumentException.class,
                () -> new CallExt(CallExt.FLAG_ARGS_CHAIN, CONTRACT, 1, 1, 0, Bytes.EMPTY, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CallExt(0, CONTRACT, 1, 1, 0, Bytes.EMPTY, hashLow(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new CallExt(0, CONTRACT, 1, 0x1_0000_0000L, 0, Bytes.EMPTY, null));
    }

    @Test
    public void decodeNeverThrowsOnNullShapes() {
        assertEquals(ExtError.NO_EXT, CallExt.decode(null, List.of(), List.of()).error());

        CallExt inline = new CallExt(0, CONTRACT, 1, 1, 0, Bytes.EMPTY, null);
        ExtResult<CallExt> r = CallExt.decode(inline.encodeHeader(), inline.encodePayload(), null);
        assertTrue(r.isOk());

        CallExt chained = new CallExt(CallExt.FLAG_ARGS_CHAIN, CONTRACT, 1, 1, 0, Bytes.EMPTY, hashLow(2));
        assertEquals(ExtError.MISSING_LINK,
                CallExt.decode(chained.encodeHeader(), List.of(), Arrays.asList((Address) null)).error());
    }
}
