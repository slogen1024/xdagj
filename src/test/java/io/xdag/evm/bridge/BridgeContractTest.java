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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.Test;

/** Pins the Phase-3b protocol artifacts (solc 0.8.26, optimizer off, metadata none — spec §3.1). */
public class BridgeContractTest {

    @Test
    public void contract_address_matches_the_derivation() {
        assertEquals("0x97d38b2e167709f0ddb4880d197ce2920241e3ea",
                BridgeContract.ADDRESS.toHexString());
    }

    @Test
    public void runtime_bytecode_hash_is_pinned() {
        assertEquals(634, BridgeContract.RUNTIME_BYTECODE.size());
        assertEquals("0x80d42d27751c0527c5efcaddfa9fb802ffda21137cf695c78cf5c114c73d2c78",
                Hash.keccak256(BridgeContract.RUNTIME_BYTECODE).toHexString());
    }

    @Test
    public void withdrawal_event_topic_is_pinned_and_embedded_in_the_bytecode() {
        assertEquals("0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42",
                BridgeContract.WITHDRAWAL_TOPIC0.toHexString());
        // The compiler embeds the event topic in the runtime code — cross-checks source and constant.
        assertEquals(Hash.keccak256(Bytes.wrap("Withdrawal(bytes20,uint256)".getBytes())).toHexString(),
                BridgeContract.WITHDRAWAL_TOPIC0.toHexString());
    }

    @Test
    public void withdraw_selector_is_pinned() {
        assertEquals("0xdce0f64e", BridgeContract.WITHDRAW_SELECTOR.toHexString());
    }
}
