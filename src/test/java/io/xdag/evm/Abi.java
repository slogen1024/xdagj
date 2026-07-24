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
package io.xdag.evm;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * Minimal ABI encode/decode helpers for tests (avoids a web3j dependency). Uses Besu's Keccak-256
 * (available transitively via besu-evm) for the function selector.
 */
public final class Abi {

    private Abi() {
    }

    /** 4-byte function selector = keccak256(signature)[0:4]. True Keccak-256, not SHA3-256. */
    public static Bytes selector(String signature) {
        return Hash.keccak256(Bytes.wrap(signature.getBytes(StandardCharsets.US_ASCII))).slice(0, 4);
    }

    /** Left-pad a 20-byte address into a 32-byte ABI word. */
    public static Bytes encodeAddress(Address address) {
        return Bytes.concatenate(Bytes.wrap(new byte[12]), address.getBytes());
    }

    /** Encode a non-negative integer as a 32-byte big-endian ABI word. */
    public static Bytes encodeUint256(BigInteger value) {
        byte[] magnitude = value.toByteArray();
        byte[] out = new byte[32];
        int copyLen = Math.min(magnitude.length, 32);
        int src = magnitude.length - copyLen;
        System.arraycopy(magnitude, src, out, 32 - copyLen, copyLen);
        return Bytes.wrap(out);
    }

    /** Decode the first 32-byte word of {@code data} as an unsigned integer. */
    public static BigInteger decodeUint256(Bytes data) {
        return new BigInteger(1, data.slice(0, 32).toArray());
    }

    /** Decode the address held in the low 20 bytes of the first 32-byte word. */
    public static Address decodeAddress(Bytes data) {
        return Address.wrap(data.slice(12, 20));
    }

    public static Bytes concat(Bytes... parts) {
        return Bytes.concatenate(parts);
    }
}
