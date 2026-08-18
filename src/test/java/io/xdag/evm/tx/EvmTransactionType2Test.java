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
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.junit.Test;

/**
 * EIP-2718/1559 (type-2) codec tests. Vectors A/B were signed OFFLINE by ethers v6.13 with
 * private key 0x…01 on chainId 51966 — they pin cross-client compatibility (hash, sender,
 * field parse) independent of our own encoder.
 */
public class EvmTransactionType2Test {

    private static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE);
    private static final Address FUNDED = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    private static final Address TO = Address.fromHexString("0x3535353535353535353535353535353535353535");

    private static final Bytes VECTOR_A = Bytes.fromHexString(
            "0x02f86c82cafe800102825208943535353535353535353535353535353535353535880de0b6b3a764"
                    + "000080c080a0104918ce86f9f8d8cf9389231fd938103ff60ff2d4a6492346eaeed3b7988342"
                    + "a00b06ab51bfa66f8c44836f79ae76dfbfcf491269bea8e99ccb7228f86451f6a2");
    private static final Bytes VECTOR_B = Bytes.fromHexString(
            "0x02f8cc82cafe01843b9aca00847735940082ea60943535353535353535353535353535353535"
                    + "3535358084deadbeeff85bf859943535353535353535353535353535353535353535f842a00000"
                    + "000000000000000000000000000000000000000000000000000000000001a000000000000000"
                    + "0000000000000000000000000000000000000000000000000201a0ca5b5c2fad6148b287bbf1"
                    + "00bfeaa05c0ee0aa0d4034cdd8d935392b433fe32aa057a7275ae418fdbf429749d14e72a813"
                    + "4e5ccb4289da3fe62445731b7edd681f");

    @Test
    public void decodes_ethers_vector_a_and_recovers_sender() {
        EvmTransaction tx = EvmTransaction.decode(VECTOR_A);
        assertEquals(2, tx.getType());
        assertEquals(0L, tx.getNonce());
        assertEquals(Wei.of(1L), tx.getMaxPriorityFeePerGas());
        assertEquals(Wei.of(2L), tx.getMaxFeePerGas());
        assertEquals(21000L, tx.getGasLimit());
        assertEquals(TO, tx.getTo().orElseThrow());
        assertEquals(Wei.of(new BigInteger("1000000000000000000")), tx.getValue());
        assertEquals(0, tx.getPayload().size());
        assertTrue(tx.getAccessList().isEmpty());
        assertEquals(DEVNET_CHAIN_ID, tx.getChainId());
        assertEquals(FUNDED, tx.getSender());
        assertEquals("0xb3fc4070c80b884bd43a25f925c2fd779f25bab8b31b4715a0fe29394b83a242",
                tx.getHash().toHexString());
        assertEquals(VECTOR_A, tx.getRawRlp());
        // Fee accessors: effective = min(priority, maxFee) at baseFee 0; feeCap = maxFee.
        assertEquals(Wei.of(1L), tx.getEffectiveGasPrice());
        assertEquals(Wei.of(2L), tx.getFeeCapPerGas());
    }

    @Test
    public void decodes_ethers_vector_b_with_access_list() {
        EvmTransaction tx = EvmTransaction.decode(VECTOR_B);
        assertEquals(2, tx.getType());
        assertEquals(1L, tx.getNonce());
        assertEquals(Wei.of(1_000_000_000L), tx.getMaxPriorityFeePerGas());
        assertEquals(Wei.of(2_000_000_000L), tx.getMaxFeePerGas());
        assertEquals(60000L, tx.getGasLimit());
        assertEquals(Bytes.fromHexString("0xdeadbeef"), tx.getPayload());
        List<AccessListEntry> al = tx.getAccessList();
        assertEquals(1, al.size());
        assertEquals(TO, al.get(0).address());
        assertEquals(List.of(
                Bytes32.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001"),
                Bytes32.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000002")),
                al.get(0).storageKeys());
        assertEquals(FUNDED, tx.getSender());
        assertEquals("0x693612145e379b4f0325a0bbac91f5ec4774101422f981ecc1cfa7b6935965a1",
                tx.getHash().toHexString());
    }

    @Test
    public void signs_and_round_trips_type2_including_access_list() {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction unsigned = EvmTransaction.unsignedType2(
                7L, Wei.of(1L), Wei.of(5L), 50_000L, Optional.of(TO), Wei.of(123L),
                Bytes.fromHexString("0xabcd"),
                List.of(new AccessListEntry(TO, List.of(Bytes32.leftPad(Bytes.of(9))))),
                DEVNET_CHAIN_ID);
        EvmTransaction signed = unsigned.sign(key, algo);
        assertEquals((byte) 0x02, signed.getRawRlp().get(0));
        EvmTransaction decoded = EvmTransaction.decode(signed.getRawRlp());
        assertEquals(2, decoded.getType());
        assertEquals(7L, decoded.getNonce());
        assertEquals(FUNDED, decoded.getSender());
        assertEquals(signed.getHash(), decoded.getHash());
        assertEquals(1, decoded.getAccessList().size());
    }

    @Test
    public void rejects_type1_and_unknown_types() {
        Bytes type1 = Bytes.concatenate(Bytes.of(0x01), VECTOR_A.slice(1));
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> EvmTransaction.decode(type1));
        assertTrue(e1.getMessage().contains("type-1"));
        Bytes type3 = Bytes.concatenate(Bytes.of(0x03), VECTOR_A.slice(1));
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> EvmTransaction.decode(type3));
        assertTrue(e3.getMessage().contains("unsupported"));
    }

    @Test
    public void rejects_bad_y_parity_and_priority_above_max_fee() {
        // yParity = 5: hand-encode the envelope with our own RLP writer.
        assertThrows(IllegalArgumentException.class,
                () -> EvmTransaction.decode(craft(1L, 2L, 5, BigInteger.ONE, BigInteger.TWO)));
        // priority (7) > maxFee (2): structurally valid, semantically rejected.
        assertThrows(IllegalArgumentException.class,
                () -> EvmTransaction.decode(craft(7L, 2L, 0, BigInteger.ONE, BigInteger.TWO)));
    }

    @Test
    public void rejects_truncated_envelope() {
        assertThrows(RuntimeException.class,
                () -> EvmTransaction.decode(VECTOR_A.slice(0, VECTOR_A.size() - 3)));
    }

    @Test
    public void legacy_accessors_throw_on_type2_and_type_marks_legacy() {
        EvmTransaction t2 = EvmTransaction.decode(VECTOR_A);
        assertThrows(IllegalStateException.class, t2::getGasPrice);
        // Legacy tx: type 0, effective == feeCap == gasPrice.
        EvmTransaction legacy = EvmTransaction.decode(Bytes.fromHexString(
                "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a764"
                        + "00008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a0"
                        + "67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"));
        assertEquals(0, legacy.getType());
        assertEquals(legacy.getGasPrice(), legacy.getEffectiveGasPrice());
        assertEquals(legacy.getGasPrice(), legacy.getFeeCapPerGas());
        assertTrue(legacy.getAccessList().isEmpty());
    }

    /** Hand-encodes a minimal type-2 envelope (empty access list) with arbitrary fee/parity fields. */
    private static Bytes craft(long priority, long maxFee, int yParity, BigInteger r, BigInteger s) {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        out.writeBigIntegerScalar(DEVNET_CHAIN_ID);
        out.writeLongScalar(0L);
        out.writeBigIntegerScalar(BigInteger.valueOf(priority));
        out.writeBigIntegerScalar(BigInteger.valueOf(maxFee));
        out.writeLongScalar(21000L);
        out.writeBytes(TO.getBytes());
        out.writeBigIntegerScalar(BigInteger.ZERO);
        out.writeBytes(Bytes.EMPTY);
        out.startList();
        out.endList();
        out.writeIntScalar(yParity);
        out.writeBigIntegerScalar(r);
        out.writeBigIntegerScalar(s);
        out.endList();
        return Bytes.concatenate(Bytes.of(0x02), out.encoded());
    }
}
