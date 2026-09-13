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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ExtCodecTest {

    @Test
    public void littleEndianRoundTrips() {
        byte[] a = new byte[32];
        ExtCodec.putU16(a, 0, 0xBEEF);
        ExtCodec.putU32(a, 2, 0xDEADBEEFL);
        ExtCodec.putU64(a, 6, 0x0102030405060708L);
        assertEquals(0xBEEF, ExtCodec.u16(a, 0));
        assertEquals(0xDEADBEEFL, ExtCodec.u32(a, 2));
        assertEquals(0x0102030405060708L, ExtCodec.u64(a, 6));
        assertEquals(0xEF, a[0] & 0xff); // least significant byte first
        assertEquals(0x08, a[6] & 0xff);
        assertTrue(ExtCodec.isZero(a, 14, 32));
        assertFalse(ExtCodec.isZero(a, 0, 32));
    }

    @Test
    public void payloadRoundTripAndPadding() {
        Bytes data = Bytes.random(100);
        List<Bytes32> fields = ExtCodec.writeBytes(data);
        assertEquals(4, fields.size());
        ExtResult<Bytes> back = ExtCodec.readBytes(fields, 100);
        assertTrue(back.isOk());
        assertEquals(data, back.value());
        assertEquals(0, ExtCodec.writeBytes(Bytes.EMPTY).size());
        assertEquals(1, ExtCodec.fieldsFor(1));
        assertEquals(1, ExtCodec.fieldsFor(32));
        assertEquals(2, ExtCodec.fieldsFor(33));
    }

    @Test
    public void payloadErrors() {
        Bytes data = Bytes.random(40);
        List<Bytes32> fields = ExtCodec.writeBytes(data);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ExtCodec.readBytes(fields, 70).error());
        byte[] dirty = fields.get(1).toArray();
        dirty[31] = 1; // trailing byte must be zero
        ExtResult<Bytes> r = ExtCodec.readBytes(List.of(fields.get(0), Bytes32.wrap(dirty)), 40);
        assertEquals(ExtError.RESERVED_NONZERO, r.error());
        assertNull(r.value());
    }

    @Test
    public void kindCodes() {
        assertEquals(ExtKind.CALL, ExtKind.fromCode(1));
        assertEquals(ExtKind.CLAIM, ExtKind.fromCode(7));
        assertNull(ExtKind.fromCode(0));
        assertNull(ExtKind.fromCode(8));
        assertEquals(3, ExtKind.CHUNK.code());
    }

    @Test
    public void resultHelpers() {
        assertTrue(ExtResult.ok(1).isOk());
        assertFalse(ExtResult.fail(ExtError.NO_EXT).isOk());
        assertArrayEquals(new byte[]{1, 2}, ExtResult.ok(new byte[]{1, 2}).value());
    }
}
