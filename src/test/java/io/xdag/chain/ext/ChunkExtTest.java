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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
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
import org.apache.tuweni.bytes.MutableBytes32;
import org.junit.Test;

public class ChunkExtTest {

    /** A block hashlow: first 8 bytes zero (that is where Address keeps the amount). */
    static Bytes32 hashLow(int seed) {
        MutableBytes32 h = MutableBytes32.create();
        byte[] tail = new byte[24];
        tail[0] = (byte) seed;
        tail[23] = (byte) (seed * 7);
        h.set(8, Bytes.wrap(tail));
        return Bytes32.wrap(h.toArray());
    }

    static Address link(Bytes32 hashLow) {
        return new Address(hashLow, XDAG_FIELD_OUT, false);
    }

    @Test
    public void roundTripFullChunkWithNext() {
        Bytes data = Bytes.random(ChunkExt.MAX_DATA_LEN);
        ChunkExt c = new ChunkExt(3, 1000, ChunkExt.MAX_DATA_LEN, hashLow(9), data);
        ExtResult<ChunkExt> r = ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(hashLow(9))));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(11, c.encodePayload().size());
    }

    @Test
    public void roundTripOneByteTailWithoutNext() {
        ChunkExt c = new ChunkExt(0, 1, 1, null, Bytes.of((byte) 0x42));
        ExtResult<ChunkExt> r = ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of());
        assertTrue(r.isOk());
        assertNull(r.value().next());
        assertEquals(Bytes.of((byte) 0x42), r.value().data());
    }

    @Test
    public void rejectsBadLengths() {
        ChunkExt zero = new ChunkExt(0, 10, 0, null, Bytes.EMPTY);
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(zero.encodeHeader(), List.of(), List.of()).error());

        ChunkExt tooBig = new ChunkExt(0, 400, 353, null, Bytes.random(353));
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(tooBig.encodeHeader(), tooBig.encodePayload(), List.of()).error());

        ChunkExt moreThanTotal = new ChunkExt(0, 5, 10, null, Bytes.random(10));
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(moreThanTotal.encodeHeader(), moreThanTotal.encodePayload(), List.of()).error());
    }

    @Test
    public void rejectsStructuralErrors() {
        ChunkExt c = new ChunkExt(1, 100, 40, null, Bytes.random(40));
        assertEquals(ExtError.EXTRA_LINK,
                ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(hashLow(1)), link(hashLow(2)))).error());

        byte[] h = c.encodeHeader().toArray();
        h[20] = 1;
        assertEquals(ExtError.RESERVED_NONZERO, ChunkExt.decode(Bytes32.wrap(h), c.encodePayload(), List.of()).error());

        byte[] wrongKind = c.encodeHeader().toArray();
        wrongKind[0] = ExtKind.CALL.code();
        assertEquals(ExtError.UNKNOWN_KIND, ChunkExt.decode(Bytes32.wrap(wrongKind), c.encodePayload(), List.of()).error());

        List<Bytes32> dirty = new ArrayList<>(c.encodePayload());
        byte[] last = dirty.get(1).toArray();
        last[31] = 1;
        dirty.set(1, Bytes32.wrap(last));
        assertEquals(ExtError.RESERVED_NONZERO, ChunkExt.decode(c.encodeHeader(), dirty, List.of()).error());

        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ChunkExt.decode(c.encodeHeader(), List.of(dirty.get(0)), List.of()).error());
    }

    @Test
    public void headerLayoutIsPinned() {
        ChunkExt c = new ChunkExt(0x01020304L, 0x0A0B0C0DL, 0x0102, null, Bytes.random(0x0102));
        assertEquals("0x03040302010d0c0b0a0201000000000000000000000000000000000000000000", c.encodeHeader().toHexString());
    }

    @Test
    public void constructorRejectsInconsistentRecords() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkExt(0, 100, 5, null, Bytes.wrap(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> new ChunkExt(0x1_0000_0000L, 0, 0, null, Bytes.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new ChunkExt(0, 0, 70000, null, Bytes.EMPTY));
        assertThrows(NullPointerException.class, () -> new ChunkExt(0, 0, 0, null, null));
    }

    @Test
    public void decodeNeverThrowsOnNullShapes() {
        assertEquals(ExtError.NO_EXT, ChunkExt.decode(null, List.of(), List.of()).error());

        ChunkExt tail = new ChunkExt(0, 1, 1, null, Bytes.of((byte) 0x42));
        ExtResult<ChunkExt> r = ChunkExt.decode(tail.encodeHeader(), tail.encodePayload(), null);
        assertTrue(r.isOk());

        assertEquals(ExtError.MISSING_LINK,
                ChunkExt.decode(tail.encodeHeader(), tail.encodePayload(), Arrays.asList((Address) null)).error());

        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                ChunkExt.decode(tail.encodeHeader(), Arrays.asList((Bytes32) null), List.of()).error());
    }

    @Test
    public void maxU32AndBoundaryRoundTrip() {
        ChunkExt maxSeq = new ChunkExt(4294967295L, 4294967295L, 352, null, Bytes.random(352));
        ExtResult<ChunkExt> r1 = ChunkExt.decode(maxSeq.encodeHeader(), maxSeq.encodePayload(), List.of());
        assertTrue(r1.isOk());
        assertEquals(maxSeq, r1.value());

        ChunkExt boundary = new ChunkExt(1, 352, 352, null, Bytes.random(352));
        ExtResult<ChunkExt> r2 = ChunkExt.decode(boundary.encodeHeader(), boundary.encodePayload(), List.of());
        assertTrue(r2.isOk());
        assertEquals(boundary, r2.value());
    }
}
