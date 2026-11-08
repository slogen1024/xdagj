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
package io.xdag.chain.anchor;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.ext.ExtCodec;

/**
 * {@code 0x05 ‖ chain20}. {@code windowBase} is the W-aligned height of the open withdraw window
 * and {@code withdrawn} is how much CLAIM has already paid in it.
 *
 * <pre>
 * seq u64 LE ‖ anchorHash 32 ‖ windowBase u64 LE ‖ withdrawn u64 LE
 * </pre>
 */
public record AnchorHead(long seq, Bytes32 anchorHash, long windowBase, long withdrawn) {

    public static final int BYTES = 8 + 32 + 8 + 8;

    public AnchorHead {
        anchorHash = Bytes32.wrap(anchorHash.toArray());
    }

    public byte[] encode() {
        byte[] raw = new byte[BYTES];
        ExtCodec.putU64(raw, 0, seq);
        System.arraycopy(anchorHash.toArray(), 0, raw, 8, 32);
        ExtCodec.putU64(raw, 40, windowBase);
        ExtCodec.putU64(raw, 48, withdrawn);
        return raw;
    }

    public static AnchorHead decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        return new AnchorHead(ExtCodec.u64(raw, 0), Bytes32.wrap(Arrays.copyOfRange(raw, 8, 40)),
                ExtCodec.u64(raw, 40), ExtCodec.u64(raw, 48));
    }
}
