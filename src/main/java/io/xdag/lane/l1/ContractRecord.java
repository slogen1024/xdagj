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
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * LANE_L1 0x02 value: laneId 20 | codeHash 32 | deployHeight u64 | deployBlockHash 32.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire
 * format: {@code laneId} is non-null and exactly 20 bytes; {@code codeHash} and
 * {@code deployBlockHash} are non-null. {@code deployHeight} is a raw little-endian 64-bit bit
 * pattern, unconstrained by this record. {@code laneId}, {@code codeHash} and
 * {@code deployBlockHash} are defensively copied.
 */
public record ContractRecord(Bytes laneId, Bytes32 codeHash, long deployHeight, Bytes32 deployBlockHash) {

    public static final int SIZE = 20 + 32 + 8 + 32;

    public ContractRecord {
        Objects.requireNonNull(laneId, "laneId");
        if (laneId.size() != 20) {
            throw new IllegalArgumentException("laneId must be 20 bytes: " + laneId.size());
        }
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(deployBlockHash, "deployBlockHash");
        laneId = Bytes.wrap(laneId.toArray());
        codeHash = Bytes32.wrap(codeHash.toArray());
        deployBlockHash = Bytes32.wrap(deployBlockHash.toArray());
    }

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(laneId.toArray(), 0, a, 0, 20);
        System.arraycopy(codeHash.toArray(), 0, a, 20, 32);
        ExtCodec.putU64(a, 52, deployHeight);
        System.arraycopy(deployBlockHash.toArray(), 0, a, 60, 32);
        return a;
    }

    public static ContractRecord decode(byte[] a) {
        return new ContractRecord(Bytes.wrap(Arrays.copyOfRange(a, 0, 20)), Bytes32.wrap(Arrays.copyOfRange(a, 20, 52)),
                ExtCodec.u64(a, 52), Bytes32.wrap(Arrays.copyOfRange(a, 60, 92)));
    }
}
