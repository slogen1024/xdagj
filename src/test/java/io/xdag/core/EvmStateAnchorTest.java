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
package io.xdag.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteOrder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.Test;

public class EvmStateAnchorTest {

    private static final Bytes ROOT_LOW = Bytes.repeat((byte) 0xAB, EvmStateAnchor.ROOT_LOW_LENGTH);

    @Test
    public void round_trips_through_the_32_byte_payload() {
        EvmStateAnchor anchor = new EvmStateAnchor(123456789L, ROOT_LOW, false);
        EvmStateAnchor back = EvmStateAnchor.parse(anchor.toBytes());
        assertEquals(123456789L, back.height());
        assertEquals(ROOT_LOW, back.rootLow());
        assertFalse(back.daSkip());
    }

    @Test
    public void the_da_skip_flag_round_trips() {
        EvmStateAnchor anchor = new EvmStateAnchor(7L, ROOT_LOW, true);
        EvmStateAnchor back = EvmStateAnchor.parse(anchor.toBytes());
        assertTrue(back.daSkip());
        assertEquals(7L, back.height());
        assertEquals(ROOT_LOW, back.rootLow());
    }

    @Test
    public void payload_is_exactly_32_bytes_with_flags_height_root_layout() {
        Bytes32 payload = new EvmStateAnchor(1L, ROOT_LOW, true).toBytes();
        assertEquals(32, payload.size());
        assertEquals(0x01, payload.get(0) & 0xFF);                 // flags: DA-skip
        assertEquals(1L, payload.getLong(1, ByteOrder.BIG_ENDIAN)); // height big-endian at offset 1
        assertEquals(ROOT_LOW, payload.slice(9, 23));              // rootLow at offset 9
    }

    @Test
    public void rootLowOf_takes_the_low_23_bytes_dropping_the_high_9() {
        MutableBytes b = MutableBytes.create(32);
        for (int i = 0; i < 32; i++) {
            b.set(i, (byte) i); // 0x00,0x01,...,0x1F — every byte distinct
        }
        Bytes expected = Bytes.fromHexString("0x090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        assertEquals(expected, EvmStateAnchor.rootLowOf(Bytes32.wrap(b)));
        assertEquals(23, EvmStateAnchor.rootLowOf(Bytes32.wrap(b)).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejects_wrong_length_rootLow() {
        new EvmStateAnchor(1L, Bytes.repeat((byte) 0x00, 20), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_rejects_a_non_32_byte_field() {
        EvmStateAnchor.parse(Bytes.repeat((byte) 0x00, 16));
    }

    @Test
    public void reencode_is_byte_identical() {
        Bytes32 payload = new EvmStateAnchor(42L, ROOT_LOW, true).toBytes();
        assertEquals(payload, EvmStateAnchor.parse(payload).toBytes());
    }

    @Test
    public void height_zero_is_accepted_and_round_trips() {
        EvmStateAnchor back = EvmStateAnchor.parse(new EvmStateAnchor(0L, ROOT_LOW, false).toBytes());
        assertEquals(0L, back.height());
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_rejects_a_high_bit_height_as_negative() {
        // Byte 1 is the top byte of the big-endian height; 0xFF there decodes to a negative long,
        // which the "signed non-negative long" contract must reject on parse.
        MutableBytes field = MutableBytes.create(32);
        field.set(1, (byte) 0xFF);
        EvmStateAnchor.parse(field);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_rejects_unknown_flag_bits() {
        MutableBytes bad = MutableBytes.create(32);
        bad.set(0, (byte) 0x02); // an unknown (reserved) flag bit
        EvmStateAnchor.parse(bad);
    }
}
