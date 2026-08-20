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

import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * The bridge withdrawal system contract (spec §3.1): a fixed runtime bytecode seeded at a
 * nothing-up-my-sleeve address (last 20 bytes of keccak256("XDAG-EVM-BRIDGE-CONTRACT-v1")).
 * Compiled from src/test/resources/solidity/xdag_bridge.sol with solc 0.8.26, optimizer OFF,
 * metadata hash NONE — the bytecode below is consensus data; changing it is a hard fork.
 * The contract has no admin, no upgrade path, and never releases its balance: contract balance
 * == cumulative burned wei (audit figure).
 */
public final class BridgeContract {

    /** EVM address of the bridge contract: 0x97d38b2e167709f0ddb4880d197ce2920241e3ea. */
    public static final Address ADDRESS = Address.wrap(
            Hash.keccak256(Bytes.wrap("XDAG-EVM-BRIDGE-CONTRACT-v1".getBytes(StandardCharsets.US_ASCII)))
                    .slice(12, 20));

    /** The seeded runtime bytecode (634 bytes; solc 0.8.26, optimizer off, metadata none). */
    public static final Bytes RUNTIME_BYTECODE = Bytes.fromHexString(
            "0x60806040526004361061001d575f3560e01c8063dce0f64e14610021575b5f80fd5b61003b6004803603810190"
            + "610036919061013c565b61003d565b005b5f3411801561005a57505f633b9aca0034610058919061019d565b14"
            + "5b610099576040517f08c379a0000000000000000000000000000000000000000000000000000000008152600401"
            + "61009090610227565b60405180910390fd5b806bffffffffffffffffffffffff19167fcb0a8ccf10deec2c41d172"
            + "3a1ab013f59377a9853e5f541e6894b47c2e413a42346040516100d89190610254565b60405180910390a25056"
            + "5b5f80fd5b5f7fffffffffffffffffffffffffffffffffffffffff0000000000000000000000008216905091905056"
            + "5b61011b816100e7565b8114610125575f80fd5b50565b5f8135905061013681610112565b92915050565b5f6020"
            + "8284031215610151576101506100e3565b5b5f61015e84828501610128565b91505092915050565b5f81905091"
            + "9050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f5260126004526024"
            + "5ffd5b5f6101a782610167565b91506101b283610167565b9250826101c2576101c1610170565b5b8282069050"
            + "92915050565b5f82825260208201905092915050565b7f62616420616d6f756e74000000000000000000000000"
            + "000000000000000000005f82015250565b5f610211600a836101cd565b915061021c826101dd565b6020820190"
            + "50919050565b5f6020820190508181035f83015261023e81610205565b9050919050565b61024e81610167565b"
            + "82525050565b5f6020820190506102675f830184610245565b9291505056fea164736f6c634300081a000a");

    /** keccak256("Withdrawal(bytes20,uint256)"). */
    public static final Bytes32 WITHDRAWAL_TOPIC0 = Bytes32.fromHexString(
            "0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42");

    /** withdraw(bytes20) function selector; calldata = selector ‖ target20 ‖ 12 zero bytes. */
    public static final Bytes WITHDRAW_SELECTOR = Bytes.fromHexString("0xdce0f64e");

    private BridgeContract() {
    }
}
