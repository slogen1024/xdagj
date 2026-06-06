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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * Derives a 20-byte EVM/Ethereum account address from a secp256k1 public key, using the canonical
 * recipe {@code address = keccak256(uncompressedPublicKey[1:])[12:]} (true Keccak-256). This is
 * identical to MetaMask/Ethereum, so an XDAG secp256k1 key maps to the same EVM address a wallet
 * would show.
 *
 * <p>The thin adapter from {@code io.xdag.crypto.keys.ECKeyPair} to the 64-byte public key is added
 * in Sub-project B2 (it depends on the external crypto library's API); this class deliberately takes
 * raw public-key bytes so it can be unit-tested against a known vector.
 */
public final class EvmAddress {

    private EvmAddress() {
    }

    /**
     * @param publicKey the secp256k1 public key, either the 64-byte uncompressed form (X||Y) or the
     *                  65-byte form with a leading 0x04 prefix.
     * @return the 20-byte EVM address
     */
    public static Address fromPublicKey(Bytes publicKey) {
        Bytes xy = publicKey.size() == 65 ? publicKey.slice(1) : publicKey;
        if (xy.size() != 64) {
            throw new IllegalArgumentException(
                    "expected a 64-byte uncompressed public key (X||Y), got " + xy.size() + " bytes");
        }
        return Address.wrap(Hash.keccak256(xy).slice(12, 20));
    }
}
