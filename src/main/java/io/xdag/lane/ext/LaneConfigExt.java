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

import org.apache.tuweni.bytes.Bytes32;

/**
 * Lane configuration carried by a new-lane DEPLOY (the second payload field, present only when
 * {@link DeployExt#FLAG_NEW_LANE} is set). Layout: b0..7 {@code gasPriceNano} u64 | b8..11
 * {@code deliveryDelayD} u32 | b12..15 {@code maxCallGas} u32 | b16..31 zero.
 *
 * <p>{@code gasPriceNano} is the raw little-endian 64-bit bit pattern of the gas price in nano-XDAG
 * per gas unit; it is unconstrained by this record (any of the 2^64 bit patterns round-trips). Values
 * at or above 2^63 come back as a negative {@code long} — compare two {@code gasPriceNano} values with
 * {@link Long#compareUnsigned(long, long)}, never with {@code <}/{@code >}. {@code deliveryDelayD} and
 * {@code maxCallGas} are true u32 quantities and are range-checked accordingly.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code deliveryDelayD} and {@code maxCallGas} must fit a u32 ({@code gasPriceNano} is unconstrained,
 * being a raw u64 bit pattern).
 *
 * <p>{@link #decode} validates the reserved zero tail (bytes 16..32); it never throws.
 *
 * <p>{@link #encode()} throws {@link IllegalArgumentException} if {@code deliveryDelayD} or
 * {@code maxCallGas} do not fit their header width; the compact constructor already rejects such
 * values, so this cannot happen for an instance built through the public constructor.
 */
public record LaneConfigExt(long gasPriceNano, long deliveryDelayD, long maxCallGas) {

    public LaneConfigExt {
        if (deliveryDelayD < 0 || deliveryDelayD > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("deliveryDelayD out of u32 range: " + deliveryDelayD);
        }
        if (maxCallGas < 0 || maxCallGas > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("maxCallGas out of u32 range: " + maxCallGas);
        }
    }

    /** Decodes a single 32-byte payload field into a {@link LaneConfigExt}; never throws. */
    public static ExtResult<LaneConfigExt> decode(Bytes32 field) {
        if (field == null) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        byte[] a = field.toArray();
        if (!ExtCodec.isZero(a, 16, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new LaneConfigExt(ExtCodec.u64(a, 0), ExtCodec.u32(a, 8), ExtCodec.u32(a, 12)));
    }

    /** Encodes the 32-byte lane config field (gasPriceNano, deliveryDelayD, maxCallGas, zero padding). */
    public Bytes32 encode() {
        byte[] a = new byte[32];
        ExtCodec.putU64(a, 0, gasPriceNano);
        ExtCodec.putU32(a, 8, deliveryDelayD);
        ExtCodec.putU32(a, 12, maxCallGas);
        return Bytes32.wrap(a);
    }
}
