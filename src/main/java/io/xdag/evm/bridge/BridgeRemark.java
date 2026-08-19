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
package io.xdag.evm.bridge;

import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * Encodes a 20-byte EVM deposit target into the 32-byte ASCII native remark field and back
 * (spec §2.1): {@code base58( 0x45 ‖ addr20 ‖ keccak256(0x45‖addr20)[0:2] )}. The 23-byte
 * payload encodes to at most 32 chars (standard Base58Check's 24-byte payload is 33 chars and
 * does NOT fit — that is why this custom format exists). The 0x45 ('E') version byte makes a
 * pasted NATIVE address string structurally undecodable here instead of silently aliasing.
 */
public final class BridgeRemark {

    private static final byte VERSION = 0x45;
    private static final int PAYLOAD_LENGTH = 23;

    private BridgeRemark() {
    }

    /** The <=32-char ASCII remark string for a deposit to {@code target}. */
    public static String encode(Address target) {
        Bytes versioned = Bytes.concatenate(Bytes.of(VERSION), target.getBytes());
        Bytes checksum = Hash.keccak256(versioned).slice(0, 2);
        return Base58.encode(Bytes.concatenate(versioned, checksum));
    }

    /**
     * Decodes a raw 32-byte remark field; empty on ANY failure (null, padding-only, bad base58,
     * wrong length, wrong version, bad checksum). Pure and deterministic — consensus code calls
     * this, so there must be no environment-dependent behavior.
     */
    public static Optional<Address> decode(byte[] remark) {
        if (remark == null) {
            return Optional.empty();
        }
        int end = remark.length;
        while (end > 0 && remark[end - 1] == 0) {
            end--;
        }
        if (end == 0) {
            return Optional.empty();
        }
        String ascii = new String(remark, 0, end, StandardCharsets.US_ASCII);
        Bytes payload;
        try {
            payload = Base58.decode(ascii);
        } catch (AddressFormatException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (payload.size() != PAYLOAD_LENGTH || payload.get(0) != VERSION) {
            return Optional.empty();
        }
        Bytes versioned = payload.slice(0, 21);
        if (!Hash.keccak256(versioned).slice(0, 2).equals(payload.slice(21, 2))) {
            return Optional.empty();
        }
        return Optional.of(Address.wrap(payload.slice(1, 20)));
    }
}
