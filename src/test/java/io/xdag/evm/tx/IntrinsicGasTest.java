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
package io.xdag.evm.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

/** Shanghai intrinsic gas: 21000 + 4/zero byte + 16/non-zero byte (+ create: 32000 + 2/initcode word). */
public class IntrinsicGasTest {

    @Test
    public void plain_transfer_is_21000() {
        assertEquals(21_000L, IntrinsicGas.compute(Bytes.EMPTY, false));
    }

    @Test
    public void calldata_bytes_cost_4_and_16() {
        // one zero byte (4) + one non-zero byte (16)
        assertEquals(21_020L, IntrinsicGas.compute(Bytes.fromHexString("0x00ff"), false));
    }

    @Test
    public void creation_adds_32000_plus_initcode_words() {
        // 33 bytes of initcode = 2 words (EIP-3860: 2 gas each); all non-zero: 33 * 16
        Bytes initCode = Bytes.repeat((byte) 1, 33);
        assertEquals(21_000L + 32_000L + 33 * 16L + 2 * 2L, IntrinsicGas.compute(initCode, true));
    }

    @Test
    public void oversized_initcode_rejected() {
        Bytes tooBig = Bytes.repeat((byte) 1, IntrinsicGas.MAX_INITCODE_SIZE + 1);
        assertThrows(IllegalArgumentException.class, () -> IntrinsicGas.compute(tooBig, true));
    }
}
