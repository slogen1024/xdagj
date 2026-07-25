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
package io.xdag.evm.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Test;

/**
 * Verifies the EIP-155 legacy transaction codec against the worked example from the EIP itself,
 * plus a sign/decode round-trip on the provisional devnet chain id.
 */
public class EvmTransactionTest {

    /** The worked example from EIP-155 (chainId 1, private key 0x46..46, nonce 9, 1 ETH to 0x3535..). */
    private static final Bytes EIP155_RAW = Bytes.fromHexString(
            "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a764"
                    + "00008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a0"
                    + "67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83");

    private static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE);

    @Test
    public void decodes_eip155_spec_vector_and_recovers_sender() {
        EvmTransaction tx = EvmTransaction.decode(EIP155_RAW);
        assertEquals(9L, tx.getNonce());
        assertEquals(Wei.of(new BigInteger("20000000000")), tx.getGasPrice());
        assertEquals(21000L, tx.getGasLimit());
        assertEquals(Address.fromHexString("0x3535353535353535353535353535353535353535"),
                tx.getTo().orElseThrow());
        assertEquals(Wei.of(new BigInteger("1000000000000000000")), tx.getValue());
        assertEquals(0, tx.getPayload().size());
        assertEquals(BigInteger.ONE, tx.getChainId());
        assertEquals(Address.fromHexString("0x9d8A62f656a8d1615C1294fd71e9CFb3E4855A4F"),
                tx.getSender());
        // The tx hash is keccak256 of the exact raw bytes, and the raw bytes are kept verbatim.
        assertEquals(org.hyperledger.besu.crypto.Hash.keccak256(EIP155_RAW), tx.getHash().getBytes());
        assertEquals(EIP155_RAW, tx.getRawRlp());
    }

    @Test
    public void signs_and_round_trips_on_devnet_chain_id() {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction unsigned = EvmTransaction.unsigned(
                0L, Wei.of(1_000_000_000L), 100_000L,
                Optional.empty(),                              // contract creation
                Wei.ZERO, Bytes.fromHexString("0x6001600155"), // arbitrary initcode
                DEVNET_CHAIN_ID);
        EvmTransaction signed = unsigned.sign(key, algo);

        EvmTransaction decoded = EvmTransaction.decode(signed.getRawRlp());
        assertEquals(DEVNET_CHAIN_ID, decoded.getChainId());
        assertTrue(decoded.getTo().isEmpty());
        assertTrue(decoded.isContractCreation());
        // Private key 1 -> the canonical Ethereum address for the secp256k1 generator point.
        assertEquals(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"),
                decoded.getSender());
        assertEquals(signed.getHash(), decoded.getHash());
    }

    @Test
    public void rejects_unprotected_pre_eip155_v() {
        EvmTransaction tx = EvmTransaction.decode(EIP155_RAW);
        Bytes unprotectedRlp = tx.encodeWithV(BigInteger.valueOf(27));
        assertThrows(IllegalArgumentException.class, () -> EvmTransaction.decode(unprotectedRlp));
    }

    @Test
    public void rejects_garbage_rlp() {
        assertThrows(RuntimeException.class,
                () -> EvmTransaction.decode(Bytes.fromHexString("0xdeadbeef")));
    }
}
