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
package io.xdag.rpc.eth;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Ethereum JSON-RPC hex codec: QUANTITY (minimal-width, no leading zeros, {@code "0x0"} for zero)
 * and DATA (every byte, {@code "0x"} for empty), plus the strict decoders the eth handler needs.
 */
public final class EthHex {

    private EthHex() {
    }

    public static String quantity(long value) {
        return quantity(BigInteger.valueOf(value));
    }

    public static String quantity(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("quantity must be non-negative: " + value);
        }
        return value.signum() == 0 ? "0x0" : "0x" + value.toString(16);
    }

    public static String data(Bytes bytes) {
        return bytes.toHexString(); // tuweni yields lowercase "0x…", and "0x" for empty
    }

    public static BigInteger decodeQuantity(String hex) {
        if (hex == null || !hex.startsWith("0x") || hex.length() < 3) {
            throw new IllegalArgumentException("invalid QUANTITY: " + hex);
        }
        try {
            return new BigInteger(hex.substring(2), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid QUANTITY: " + hex);
        }
    }

    public static Bytes decodeData(String hex) {
        if (hex == null || !hex.startsWith("0x")) {
            throw new IllegalArgumentException("invalid DATA: " + hex);
        }
        try {
            return Bytes.fromHexString(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid DATA: " + hex);
        }
    }

    public static Address decodeAddress(String hex) {
        Bytes bytes = decodeData(hex);
        if (bytes.size() != 20) {
            throw new IllegalArgumentException("address must be 20 bytes: " + hex);
        }
        return Address.wrap(bytes);
    }
}
