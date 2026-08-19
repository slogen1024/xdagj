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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.crypto.encoding.Base58;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Test;

/**
 * Bridge remark codec tests. The encode vectors were produced by ethers v6's independent
 * base58 implementation — they pin cross-implementation compatibility.
 */
public class BridgeRemarkTest {

    private static final Address FUNDED = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    @Test
    public void lock_address_constant_matches_the_derivation() {
        assertEquals("0x3109ff8cf0be958a428c12d86c0abf64f529f7db",
                BridgeConstants.LOCK_ADDRESS_20.toHexString());
        assertEquals(20, BridgeConstants.LOCK_ADDRESS_20.size());
    }

    @Test
    public void encodes_to_the_external_vectors() {
        assertEquals("2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1", BridgeRemark.encode(FUNDED));
        assertEquals("2RueRbXvwjnXoWxfFFesBWpWV3P8h6BK", BridgeRemark.encode(
                Address.fromHexString("0x3535353535353535353535353535353535353535")));
        assertEquals("2SrgkEeRYqh57C6az4ZAFLpF8cSnrugm", BridgeRemark.encode(
                Address.fromHexString("0xffffffffffffffffffffffffffffffffffffffff")));
        assertEquals("2RfCqRDrtGgVuWAeeBxjFxzLjJYDtyhd", BridgeRemark.encode(
                Address.fromHexString("0x0000000000000000000000000000000000000000")));
    }

    @Test
    public void encoded_remark_is_always_exactly_32_chars() {
        assertEquals(32, BridgeRemark.encode(FUNDED).length());
        assertEquals(32, BridgeRemark.encode(
                Address.fromHexString("0x3535353535353535353535353535353535353535")).length());
        assertEquals(32, BridgeRemark.encode(
                Address.fromHexString("0xffffffffffffffffffffffffffffffffffffffff")).length());
        assertEquals(32, BridgeRemark.encode(
                Address.fromHexString("0x0000000000000000000000000000000000000000")).length());
    }

    @Test
    public void decodes_a_zero_padded_remark_field() {
        byte[] remark = new byte[32];
        byte[] ascii = "2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, ascii.length); // 32 chars fill the field exactly
        assertEquals(Optional.of(FUNDED), BridgeRemark.decode(remark));
    }

    @Test
    public void round_trips_any_address() {
        Address a = Address.fromHexString("0x00000000000000000000000000000000cafebabe");
        byte[] remark = new byte[32];
        byte[] ascii = BridgeRemark.encode(a).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, ascii.length); // valid encodings always fill all 32 bytes — the pad here is a no-op, kept for the field shape
        assertEquals(Optional.of(a), BridgeRemark.decode(remark));
    }

    @Test
    public void rejects_null_empty_garbage_and_tampering() {
        assertTrue(BridgeRemark.decode(null).isEmpty());
        assertTrue(BridgeRemark.decode(new byte[32]).isEmpty());                       // all zeros
        assertTrue(BridgeRemark.decode(pad("hello world")).isEmpty());                 // not base58 payload
        assertTrue(BridgeRemark.decode(pad("2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA2")).isEmpty()); // bad checksum
        assertTrue(BridgeRemark.decode(pad("0OIl+/not-base58-chars!!")).isEmpty());    // invalid alphabet
        // A NATIVE address string (Base58Check, 24-byte payload, 33 chars) gets truncated to 32
        // remark bytes and must fail decode (length/checksum), never alias into an EVM target.
        assertTrue(BridgeRemark.decode(pad("PKcBtHWDSnAWfZntqWPBLedqBShuKSTzS")).isEmpty());
    }

    @Test
    public void rejects_wrong_version_byte() {
        // Re-encode the funded address with version 0x46 and a VALID checksum over it: structure
        // ok, version wrong -> must be rejected (the version byte is load-bearing, not decorative).
        Bytes payload = Bytes.concatenate(Bytes.of(0x46), FUNDED.getBytes());
        Bytes ck = Hash.keccak256(payload).slice(0, 2);
        String s = Base58.encode(Bytes.concatenate(payload, ck));
        assertTrue(BridgeRemark.decode(pad(s)).isEmpty());
    }

    @Test
    public void rejects_inputs_longer_than_the_remark_field() {
        // First 32 bytes are a VALID encoded remark; bytes 33-40 are extra — must be rejected.
        byte[] valid32 = BridgeRemark.encode(FUNDED).getBytes(StandardCharsets.US_ASCII);
        byte[] oversized = Arrays.copyOf(valid32, 40); // pads with zeros beyond 32
        assertTrue(BridgeRemark.decode(oversized).isEmpty());
    }

    private static byte[] pad(String s) {
        byte[] remark = new byte[32];
        byte[] ascii = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, Math.min(ascii.length, 32));
        return remark;
    }
}
