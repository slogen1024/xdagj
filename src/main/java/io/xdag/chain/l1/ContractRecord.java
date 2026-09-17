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

package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.Arrays;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CHAIN_L1 0x02 value: chainId 20 | codeHash 32 | deployHeight u64 | deployBlockHash 32.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire
 * format: {@code chainId} is non-null and exactly 20 bytes; {@code codeHash} and
 * {@code deployBlockHash} are non-null. {@code deployHeight} is a raw little-endian 64-bit bit
 * pattern, unconstrained by this record. {@code chainId}, {@code codeHash} and
 * {@code deployBlockHash} are defensively copied.
 */
public record ContractRecord(Bytes chainId, Bytes32 codeHash, long deployHeight, Bytes32 deployBlockHash) {

    public static final int SIZE = 20 + 32 + 8 + 32;

    public ContractRecord {
        Objects.requireNonNull(chainId, "chainId");
        if (chainId.size() != 20) {
            throw new IllegalArgumentException("chainId must be 20 bytes: " + chainId.size());
        }
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(deployBlockHash, "deployBlockHash");
        chainId = Bytes.wrap(chainId.toArray());
        codeHash = Bytes32.wrap(codeHash.toArray());
        deployBlockHash = Bytes32.wrap(deployBlockHash.toArray());
    }

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(chainId.toArray(), 0, a, 0, 20);
        System.arraycopy(codeHash.toArray(), 0, a, 20, 32);
        ExtCodec.putU64(a, 52, deployHeight);
        System.arraycopy(deployBlockHash.toArray(), 0, a, 60, 32);
        return a;
    }

    /**
     * Decodes a value previously produced by {@link #encode()}.
     *
     * @throws IllegalStateException if {@code a.length != SIZE} (a corrupt or truncated record)
     */
    public static ContractRecord decode(byte[] a) {
        if (a.length != SIZE) {
            throw new IllegalStateException("corrupt contract record: expected " + SIZE + " bytes, got " + a.length);
        }
        return new ContractRecord(Bytes.wrap(Arrays.copyOfRange(a, 0, 20)), Bytes32.wrap(Arrays.copyOfRange(a, 20, 52)),
                ExtCodec.u64(a, 52), Bytes32.wrap(Arrays.copyOfRange(a, 60, 92)));
    }
}
