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

import static org.junit.Assert.assertEquals;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Test;

/** Verifies EVM address derivation against a well-known vector (private key = 1 -> secp256k1 G). */
public class EvmAddressTest {

    // Uncompressed public key (X||Y) of private key 0x1 (the secp256k1 generator point G).
    private static final Bytes G_PUBKEY = Bytes.fromHexString(
            "0x79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
                    + "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8");

    // The canonical Ethereum address for private key = 1.
    private static final Address EXPECTED = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    @Test
    public void derives_known_address_from_64_byte_pubkey() {
        assertEquals(EXPECTED, EvmAddress.fromPublicKey(G_PUBKEY));
    }

    @Test
    public void accepts_65_byte_uncompressed_pubkey_with_0x04_prefix() {
        Bytes withPrefix = Bytes.concatenate(Bytes.of(0x04), G_PUBKEY);
        assertEquals(EXPECTED, EvmAddress.fromPublicKey(withPrefix));
    }
}
