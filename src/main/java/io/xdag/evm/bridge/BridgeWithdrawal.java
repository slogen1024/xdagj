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
package io.xdag.evm.bridge;

import org.apache.tuweni.bytes.Bytes;

/**
 * One burn recorded by the bridge contract's Withdrawal event (spec §3.2): the 20-byte NATIVE
 * target address and the amount in nano-XDAG (event wei / 10^9; the contract enforces exact
 * divisibility). Also reused as the release-journal entry (EVM_META 0x08) — reversal reverses
 * exactly what was released.
 */
public record BridgeWithdrawal(Bytes nativeTarget20, long amountNano) {

    public BridgeWithdrawal {
        if (nativeTarget20.size() != 20) {
            throw new IllegalArgumentException("native target must be 20 bytes, got " + nativeTarget20.size());
        }
        if (amountNano < 0) {
            throw new IllegalArgumentException("negative withdrawal amount " + amountNano);
        }
    }
}
