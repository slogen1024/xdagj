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

import java.math.BigInteger;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECPPublicKey;
import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

/**
 * An EIP-155 signed legacy (type-0) Ethereum transaction — the only transaction type XDAG EVM v1
 * accepts (spec §5.1).
 *
 * <p>Immutable. {@link #decode(Bytes)} keeps the exact input bytes as the canonical encoding, so
 * {@code txHash = keccak256(rawRlp)} is bit-identical to Ethereum and survives re-broadcasts.
 * Unprotected (v = 27/28) transactions are rejected: replay protection is mandatory because the
 * XDAG networks share tooling with other EVM chains.
 */
public final class EvmTransaction {

    /** Shared curve instance; stateless and thread-safe for sign/recover. */
    private static final SECP256K1 SECP = new SECP256K1();

    private static final BigInteger EIP155_V_BASE = BigInteger.valueOf(35);

    private final long nonce;
    private final Wei gasPrice;
    private final long gasLimit;
    private final Optional<Address> to;
    private final Wei value;
    private final Bytes payload;
    private final BigInteger chainId;
    /** Null only for an unsigned template produced by {@link #unsigned}. */
    private final SECPSignature signature;
    /** Canonical signed RLP; null only for an unsigned template. */
    private final Bytes rawRlp;

    private volatile Address cachedSender;

    private EvmTransaction(long nonce, Wei gasPrice, long gasLimit, Optional<Address> to, Wei value,
                           Bytes payload, BigInteger chainId, SECPSignature signature, Bytes rawRlp) {
        this.nonce = nonce;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.to = to;
        this.value = value;
        this.payload = payload == null ? Bytes.EMPTY : payload;
        this.chainId = chainId;
        this.signature = signature;
        this.rawRlp = rawRlp;
    }

    /** An unsigned template — only useful as input to {@link #sign(KeyPair, SECP256K1)}. */
    public static EvmTransaction unsigned(long nonce, Wei gasPrice, long gasLimit, Optional<Address> to,
                                          Wei value, Bytes payload, BigInteger chainId) {
        return new EvmTransaction(nonce, gasPrice, gasLimit, to, value, payload, chainId, null, null);
    }

    /**
     * Parses and validates a signed EIP-155 RLP blob.
     *
     * @throws IllegalArgumentException on a structurally valid but unprotected (pre-EIP-155) tx
     * @throws org.hyperledger.besu.ethereum.rlp.RLPException on malformed RLP
     */
    public static EvmTransaction decode(Bytes rlp) {
        RLPInput in = RLP.input(rlp);
        in.enterList();
        long nonce = in.readLongScalar();
        Wei gasPrice = Wei.of(in.readBigIntegerScalar());
        long gasLimit = in.readLongScalar();
        Bytes toBytes = in.readBytes();
        // Address.wrap enforces the 20-byte length for us.
        Optional<Address> to = toBytes.isEmpty() ? Optional.empty() : Optional.of(Address.wrap(toBytes));
        Wei value = Wei.of(in.readBigIntegerScalar());
        Bytes payload = in.readBytes();
        BigInteger v = in.readBigIntegerScalar();
        BigInteger r = in.readBigIntegerScalar();
        BigInteger s = in.readBigIntegerScalar();
        in.leaveList();

        if (v.compareTo(EIP155_V_BASE) < 0) {
            throw new IllegalArgumentException("unprotected (pre-EIP-155) transaction rejected, v=" + v);
        }
        // v = chainId * 2 + 35 + recId, recId in {0, 1}
        BigInteger shifted = v.subtract(EIP155_V_BASE);
        byte recId = (byte) (shifted.testBit(0) ? 1 : 0);
        BigInteger chainId = shifted.shiftRight(1);
        SECPSignature signature = SECP.createSignature(r, s, recId);
        return new EvmTransaction(nonce, gasPrice, gasLimit, to, value, payload, chainId, signature, rlp);
    }

    /** keccak256 of the EIP-155 signing payload: rlp([nonce,gasPrice,gasLimit,to,value,data,chainId,0,0]). */
    public Bytes32 signingHash() {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        writeUnsignedBody(out);
        out.writeBigIntegerScalar(chainId);
        out.writeLongScalar(0);
        out.writeLongScalar(0);
        out.endList();
        return org.hyperledger.besu.crypto.Hash.keccak256(out.encoded());
    }

    /** Signs this template and returns the signed transaction with its canonical RLP (test/tooling helper). */
    public EvmTransaction sign(KeyPair key, SECP256K1 algo) {
        SECPSignature sig = algo.sign(signingHash(), key);
        BigInteger v = chainId.shiftLeft(1).add(EIP155_V_BASE).add(BigInteger.valueOf(sig.getRecId()));
        Bytes raw = encodeSigned(v, sig.getR(), sig.getS());
        return new EvmTransaction(nonce, gasPrice, gasLimit, to, value, payload, chainId, sig, raw);
    }

    /** Re-encodes this transaction's body with an arbitrary v (kept for negative tests). */
    public Bytes encodeWithV(BigInteger v) {
        return encodeSigned(v, signature.getR(), signature.getS());
    }

    private Bytes encodeSigned(BigInteger v, BigInteger r, BigInteger s) {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        writeUnsignedBody(out);
        out.writeBigIntegerScalar(v);
        out.writeBigIntegerScalar(r);
        out.writeBigIntegerScalar(s);
        out.endList();
        return out.encoded();
    }

    private void writeUnsignedBody(BytesValueRLPOutput out) {
        out.writeLongScalar(nonce);
        out.writeBigIntegerScalar(gasPrice.getAsBigInteger());
        out.writeLongScalar(gasLimit);
        out.writeBytes(to.map(a -> (Bytes) a.getBytes()).orElse(Bytes.EMPTY));
        out.writeBigIntegerScalar(value.getAsBigInteger());
        out.writeBytes(payload);
    }

    /** EIP-155 ecrecover of the sender address; cached after the first call. */
    public Address getSender() {
        Address sender = cachedSender;
        if (sender == null) {
            SECPPublicKey publicKey = SECP.recoverPublicKeyFromSignature(signingHash(), signature)
                    .orElseThrow(() -> new IllegalStateException("signature recovery failed"));
            sender = Address.extract(publicKey);
            cachedSender = sender;
        }
        return sender;
    }

    /** Ethereum-identical transaction hash: keccak256 of the canonical signed RLP. */
    public Hash getHash() {
        return Hash.hash(rawRlp);
    }

    public boolean isContractCreation() {
        return to.isEmpty();
    }

    public long getNonce() {
        return nonce;
    }

    public Wei getGasPrice() {
        return gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public Optional<Address> getTo() {
        return to;
    }

    public Wei getValue() {
        return value;
    }

    public Bytes getPayload() {
        return payload;
    }

    public BigInteger getChainId() {
        return chainId;
    }

    public SECPSignature getSignature() {
        return signature;
    }

    public Bytes getRawRlp() {
        return rawRlp;
    }
}
