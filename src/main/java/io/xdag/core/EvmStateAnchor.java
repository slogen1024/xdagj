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

import java.nio.ByteOrder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * EVM state-root anchor carried in a versioned (blockFormatVersion &gt;= 1) main block, encoded into
 * one 32-byte field whose nibble is physically 0x0A (see the G1-T1 plan / ADR-013..015).
 *
 * <p>Layout (32 bytes): {@code flags(1) | height(8, big-endian) | rootLow(23)}.
 * <ul>
 *   <li>flags bit0 = DA-skip marker (ADR-015): this height's EVM execution was skipped.</li>
 *   <li>height = the anchored EVM height; the lag (evm.stateRootLag) is applied by the caller
 *       (G1-T2). This record is layout-only and stores whatever height it is given.
 *       height is treated as a signed non-negative long.</li>
 *   <li>rootLow = the low 23 bytes of the 32-byte chained state root committed for {@code height}.</li>
 * </ul>
 */
public record EvmStateAnchor(long height, Bytes rootLow, boolean daSkip) {

    public static final int ROOT_LOW_LENGTH = 23;
    private static final int FLAG_DA_SKIP = 0x01;

    public EvmStateAnchor {
        if (height < 0) {
            throw new IllegalArgumentException("anchor height must be non-negative: " + height);
        }
        if (rootLow == null || rootLow.size() != ROOT_LOW_LENGTH) {
            throw new IllegalArgumentException("anchor rootLow must be " + ROOT_LOW_LENGTH + " bytes");
        }
    }

    /** Take the low 23 bytes of a full 32-byte chained root. */
    public static Bytes rootLowOf(Bytes32 fullRoot) {
        return fullRoot.slice(Bytes32.SIZE - ROOT_LOW_LENGTH, ROOT_LOW_LENGTH);
    }

    /** Encode to the fixed 32-byte field payload. */
    public Bytes32 toBytes() {
        MutableBytes out = MutableBytes.create(Bytes32.SIZE);
        out.set(0, (byte) (daSkip ? FLAG_DA_SKIP : 0));
        out.set(1, Bytes.ofUnsignedLong(height));   // 8 bytes big-endian at offset 1
        out.set(9, rootLow);                        // 23 bytes at offset 9
        return Bytes32.wrap(out);
    }

    /** Decode from a 32-byte field payload. */
    public static EvmStateAnchor parse(Bytes field) {
        if (field == null || field.size() != Bytes32.SIZE) {
            throw new IllegalArgumentException("anchor field must be 32 bytes");
        }
        int flags = field.get(0) & 0xFF;
        if ((flags & ~FLAG_DA_SKIP) != 0) {
            throw new IllegalArgumentException("anchor flags has unknown bits set: 0x" + Integer.toHexString(flags));
        }
        boolean daSkip = (flags & FLAG_DA_SKIP) != 0;
        long height = field.getLong(1, ByteOrder.BIG_ENDIAN);
        Bytes rootLow = field.slice(9, ROOT_LOW_LENGTH);
        return new EvmStateAnchor(height, rootLow, daSkip);
    }
}
