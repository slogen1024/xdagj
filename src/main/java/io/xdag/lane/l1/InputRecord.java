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

import io.xdag.lane.ext.ExtKind;
import java.util.Arrays;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * LANE_L1 0x0C value: blockHash 32 | kind u8 (0 = none) | status u8 | contract 20.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire
 * format: {@code blockHash} and {@code status} are non-null; {@code contract} is non-null and
 * exactly 20 bytes. {@code kind} may be {@code null} (encoded as byte 0), meaning the input's
 * extension kind could not be classified. {@code blockHash} and {@code contract} are defensively
 * copied.
 */
public record InputRecord(Bytes32 blockHash, ExtKind kind, InputStatus status, Bytes contract) {

    public static final int SIZE = 32 + 1 + 1 + 20;

    public InputRecord {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contract, "contract");
        if (contract.size() != 20) {
            throw new IllegalArgumentException("contract must be 20 bytes: " + contract.size());
        }
        blockHash = Bytes32.wrap(blockHash.toArray());
        contract = Bytes.wrap(contract.toArray());
    }

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(blockHash.toArray(), 0, a, 0, 32);
        a[32] = kind == null ? 0 : kind.code();
        a[33] = status.code();
        System.arraycopy(contract.toArray(), 0, a, 34, 20);
        return a;
    }

    public static InputRecord decode(byte[] a) {
        int kindCode = a[32] & 0xff;
        return new InputRecord(Bytes32.wrap(Arrays.copyOfRange(a, 0, 32)), kindCode == 0 ? null : ExtKind.fromCode(kindCode),
                InputStatus.fromCode(a[33] & 0xff), Bytes.wrap(Arrays.copyOfRange(a, 34, 54)));
    }
}
