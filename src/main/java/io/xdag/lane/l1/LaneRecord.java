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

package io.xdag.lane.l1;

import io.xdag.lane.ext.ExtCodec;
import java.util.Arrays;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes32;

/**
 * LANE_L1 0x01 value: createdHeight u64 | createBlockHash 32 | gasPriceNano u64 | D u32 |
 * maxCallGas u32 | contractCount u32.
 *
 * <p>{@code createdHeight} and {@code gasPriceNano} are raw little-endian 64-bit bit patterns,
 * unconstrained by this record (matching {@code AnchorExt.seq}'s convention); values at or above
 * 2^63 come back as a negative {@code long} and must be compared with
 * {@link Long#compareUnsigned(long, long)}, never {@code <}/{@code >}. {@code deliveryDelayD},
 * {@code maxCallGas} and {@code contractCount} are true u32 quantities and are range-checked
 * accordingly by the compact constructor. {@code createBlockHash} is defensively copied.
 */
public record LaneRecord(long createdHeight, Bytes32 createBlockHash, long gasPriceNano, long deliveryDelayD,
                         long maxCallGas, long contractCount) {

    public static final int SIZE = 8 + 32 + 8 + 4 + 4 + 4;

    public LaneRecord {
        Objects.requireNonNull(createBlockHash, "createBlockHash");
        if (deliveryDelayD < 0 || deliveryDelayD > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("deliveryDelayD out of u32 range: " + deliveryDelayD);
        }
        if (maxCallGas < 0 || maxCallGas > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("maxCallGas out of u32 range: " + maxCallGas);
        }
        if (contractCount < 0 || contractCount > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("contractCount out of u32 range: " + contractCount);
        }
        createBlockHash = Bytes32.wrap(createBlockHash.toArray());
    }

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        ExtCodec.putU64(a, 0, createdHeight);
        System.arraycopy(createBlockHash.toArray(), 0, a, 8, 32);
        ExtCodec.putU64(a, 40, gasPriceNano);
        ExtCodec.putU32(a, 48, deliveryDelayD);
        ExtCodec.putU32(a, 52, maxCallGas);
        ExtCodec.putU32(a, 56, contractCount);
        return a;
    }

    /**
     * Decodes a value previously produced by {@link #encode()}.
     *
     * @throws IllegalStateException if {@code a.length != SIZE} (a corrupt or truncated record)
     */
    public static LaneRecord decode(byte[] a) {
        if (a.length != SIZE) {
            throw new IllegalStateException("corrupt lane record: expected " + SIZE + " bytes, got " + a.length);
        }
        return new LaneRecord(ExtCodec.u64(a, 0), Bytes32.wrap(Arrays.copyOfRange(a, 8, 40)), ExtCodec.u64(a, 40),
                ExtCodec.u32(a, 48), ExtCodec.u32(a, 52), ExtCodec.u32(a, 56));
    }

    public LaneRecord withContractCount(long count) {
        return new LaneRecord(createdHeight, createBlockHash, gasPriceNano, deliveryDelayD, maxCallGas, count);
    }
}
