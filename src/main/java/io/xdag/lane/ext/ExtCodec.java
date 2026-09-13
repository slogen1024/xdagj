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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Little-endian integer helpers and 32-byte payload field packing shared by all extension codecs. */
public final class ExtCodec {

    public static final int FIELD = 32;

    private ExtCodec() {
    }

    /** Reads an unsigned 8-bit value at byte offset {@code off}. */
    public static int u8(byte[] a, int off) {
        return a[off] & 0xff;
    }

    /** Reads a little-endian unsigned 16-bit value starting at byte offset {@code off}. */
    public static int u16(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off + 1] & 0xff) << 8);
    }

    /** Reads a little-endian unsigned 32-bit value starting at byte offset {@code off}. */
    public static long u32(byte[] a, int off) {
        return (u16(a, off) & 0xffffL) | ((long) u16(a, off + 2) << 16);
    }

    /**
     * Reads a little-endian 64-bit value starting at byte offset {@code off}.
     * Returns the unsigned 64-bit value as a raw bit pattern; values >= 2^63 come back negative
     * — compare with {@code Long.compareUnsigned}, never with {@code <}/{@code >}.
     */
    public static long u64(byte[] a, int off) {
        return (u32(a, off) & 0xffffffffL) | (u32(a, off + 4) << 32);
    }

    /**
     * Writes {@code v} as a little-endian unsigned 16-bit value at byte offset {@code off}.
     *
     * @throws IllegalArgumentException if {@code v} does not fit in 16 bits
     */
    public static void putU16(byte[] a, int off, int v) {
        if ((v & ~0xFFFF) != 0) {
            throw new IllegalArgumentException("u16 out of range: " + v);
        }
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >>> 8);
    }

    /**
     * Writes {@code v} as a little-endian unsigned 32-bit value at byte offset {@code off}.
     *
     * @throws IllegalArgumentException if {@code v} does not fit in 32 bits
     */
    public static void putU32(byte[] a, int off, long v) {
        if ((v & ~0xFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("u32 out of range: " + v);
        }
        for (int i = 0; i < 4; i++) {
            a[off + i] = (byte) (v >>> (8 * i));
        }
    }

    /** Writes {@code v} as a little-endian 64-bit raw bit pattern at byte offset {@code off}. */
    public static void putU64(byte[] a, int off, long v) {
        for (int i = 0; i < 8; i++) {
            a[off + i] = (byte) (v >>> (8 * i));
        }
    }

    public static boolean isZero(byte[] a, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of 32-byte fields needed to carry {@code len} bytes; overflow-free.
     *
     * @throws IllegalArgumentException if {@code len} is negative
     */
    public static int fieldsFor(int len) {
        if (len < 0) {
            throw new IllegalArgumentException("negative length: " + len);
        }
        return Math.ceilDiv(len, FIELD);
    }

    /**
     * Concatenates payload fields and returns the first {@code len} bytes; trailing bytes must be zero.
     * Never throws; malformed input is reported via {@link ExtResult#error()}.
     */
    public static ExtResult<Bytes> readBytes(List<Bytes32> payload, int len) {
        if (payload == null || len < 0) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (payload.size() != fieldsFor(len)) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        byte[] all = new byte[payload.size() * FIELD];
        int p = 0;
        for (Bytes32 f : payload) {
            if (f == null) {
                return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
            }
            System.arraycopy(f.toArray(), 0, all, p, FIELD);
            p += FIELD;
        }
        if (!isZero(all, len, all.length)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(Bytes.wrap(Arrays.copyOf(all, len)));
    }

    /** Splits bytes into zero-padded 32-byte fields. */
    public static List<Bytes32> writeBytes(Bytes data) {
        List<Bytes32> out = new ArrayList<>();
        byte[] src = data.toArray();
        for (int off = 0; off < src.length; off += FIELD) {
            byte[] f = new byte[FIELD];
            System.arraycopy(src, off, f, 0, Math.min(FIELD, src.length - off));
            out.add(Bytes32.wrap(f));
        }
        return out;
    }
}
