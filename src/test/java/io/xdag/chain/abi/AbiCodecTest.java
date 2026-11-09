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
package io.xdag.chain.abi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AbiCodecTest {

    @Test
    public void lengthsAreLittleEndianAndTheSelectorIsTheFirstFourHashBytes() {
        assertArrayEquals(new byte[] {2, 0, 0, 0}, AbiCodec.u32(2));
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, 0, 0}, AbiCodec.u64(1));
        assertArrayEquals(new byte[] {2, 0, 0, 0, 9, 8}, AbiCodec.bytes(new byte[] {9, 8}));
        byte[] event = AbiCodec.event("Transfer(address,address,u64)", AbiCodec.u64(7));
        assertEquals(40, event.length);
        assertEquals(1531377910, AbiCodec.selector("balanceOf(address)"));
        assertEquals(-2021979357, AbiCodec.selector("Transfer(address,address,u64)"));
        assertEquals(20, AbiCodec.address(new byte[20]).length);
    }
}
