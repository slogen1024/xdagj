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
package io.xdag.rpc.eth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Test;

public class EthHexTest {

    @Test
    public void quantity_encodes_minimal_width_with_zero_as_0x0() {
        assertEquals("0x0", EthHex.quantity(0));
        assertEquals("0x2a", EthHex.quantity(42));
        assertEquals("0x400", EthHex.quantity(1024));
        assertEquals("0x3b9aca00", EthHex.quantity(BigInteger.valueOf(1_000_000_000L)));
    }

    @Test
    public void quantity_rejects_negative() {
        assertThrows(IllegalArgumentException.class, () -> EthHex.quantity(BigInteger.valueOf(-1)));
    }

    @Test
    public void data_encodes_all_bytes_and_empty_as_0x() {
        assertEquals("0x", EthHex.data(Bytes.EMPTY));
        assertEquals("0x00ff", EthHex.data(Bytes.fromHexString("0x00ff")));
    }

    @Test
    public void decodeQuantity_round_trips_including_zero() {
        assertEquals(BigInteger.ZERO, EthHex.decodeQuantity("0x0"));
        assertEquals(BigInteger.valueOf(255), EthHex.decodeQuantity("0xff"));
    }

    @Test
    public void decodeQuantity_rejects_bad_input() {
        assertThrows(IllegalArgumentException.class, () -> EthHex.decodeQuantity("ff"));
        assertThrows(IllegalArgumentException.class, () -> EthHex.decodeQuantity("0x"));
        assertThrows(IllegalArgumentException.class, () -> EthHex.decodeQuantity(null));
    }

    @Test
    public void decodeAddress_requires_20_bytes() {
        assertEquals(Address.fromHexString("0x1111111111111111111111111111111111111111"),
                EthHex.decodeAddress("0x1111111111111111111111111111111111111111"));
        assertThrows(IllegalArgumentException.class, () -> EthHex.decodeAddress("0x1234"));
    }

    @Test
    public void decodeData_parses_hex_and_empty() {
        assertEquals(Bytes.EMPTY, EthHex.decodeData("0x"));
        assertEquals(Bytes.fromHexString("0xdeadbeef"), EthHex.decodeData("0xdeadbeef"));
    }
}
