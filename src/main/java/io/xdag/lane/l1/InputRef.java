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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;

/**
 * Address of one input record: laneId 20 | height u64 | index u32. A block can own several (one
 * per vault output).
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire
 * format: {@code laneId} is non-null and exactly 20 bytes; {@code index} fits a u32.
 * {@code height} is a raw little-endian 64-bit bit pattern, unconstrained by this record.
 * {@code laneId} is defensively copied.
 */
public record InputRef(Bytes laneId, long height, long index) {

    public static final int SIZE = 20 + 8 + 4;

    public InputRef {
        Objects.requireNonNull(laneId, "laneId");
        if (laneId.size() != 20) {
            throw new IllegalArgumentException("laneId must be 20 bytes: " + laneId.size());
        }
        if (index < 0 || index > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("index out of u32 range: " + index);
        }
        laneId = Bytes.wrap(laneId.toArray());
    }

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(laneId.toArray(), 0, a, 0, 20);
        ExtCodec.putU64(a, 20, height);
        ExtCodec.putU32(a, 28, index);
        return a;
    }

    public static InputRef decode(byte[] a, int off) {
        return new InputRef(Bytes.wrap(Arrays.copyOfRange(a, off, off + 20)), ExtCodec.u64(a, off + 20), ExtCodec.u32(a, off + 28));
    }

    public static byte[] encodeList(List<InputRef> refs) {
        byte[] a = new byte[refs.size() * SIZE];
        for (int i = 0; i < refs.size(); i++) {
            System.arraycopy(refs.get(i).encode(), 0, a, i * SIZE, SIZE);
        }
        return a;
    }

    public static List<InputRef> decodeList(byte[] a) {
        List<InputRef> out = new ArrayList<>(a.length / SIZE);
        for (int off = 0; off + SIZE <= a.length; off += SIZE) {
            out.add(decode(a, off));
        }
        return out;
    }
}
