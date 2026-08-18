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
import java.util.List;
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
 * A signed Ethereum transaction accepted by the XDAG EVM: either an EIP-155 legacy (type-0) RLP
 * list, or an EIP-2718 typed envelope — currently only type-2 (EIP-1559). {@link #decode(Bytes)}
 * dispatches on the first byte: {@code >= 0xc0} is a legacy list, {@code 0x02} is a type-2
 * envelope; type-1 (EIP-2930) and every other type byte are rejected.
 *
 * <p>Immutable. {@link #decode(Bytes)} keeps the exact input bytes as the canonical encoding, so
 * {@code txHash = keccak256(rawRlp)} is bit-identical to Ethereum for both forms and survives
 * re-broadcasts. Unprotected (v = 27/28) legacy transactions are rejected: replay protection is
 * mandatory because the XDAG networks share tooling with other EVM chains.
 */
public final class EvmTransaction {

    /** Shared curve instance; stateless and thread-safe for sign/recover. */
    private static final SECP256K1 SECP = new SECP256K1();

    private static final BigInteger EIP155_V_BASE = BigInteger.valueOf(35);

    public static final int TYPE_LEGACY = 0;
    /** EIP-2718 envelope type byte for EIP-1559 transactions. */
    public static final int TYPE_EIP1559 = 2;

    private final int type;
    private final long nonce;
    /** Legacy (type-0) gas price; null for type-2 — use the effective/feeCap accessors. */
    private final Wei gasPrice;
    /** Type-2 only; null for type-0. */
    private final Wei maxPriorityFeePerGas;
    /** Type-2 only; null for type-0. */
    private final Wei maxFeePerGas;
    /** Never null; empty for type-0. */
    private final List<AccessListEntry> accessList;
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

    private EvmTransaction(int type, long nonce, Wei gasPrice, Wei maxPriorityFeePerGas,
                           Wei maxFeePerGas, List<AccessListEntry> accessList, long gasLimit,
                           Optional<Address> to, Wei value, Bytes payload, BigInteger chainId,
                           SECPSignature signature, Bytes rawRlp) {
        this.type = type;
        this.nonce = nonce;
        this.gasPrice = gasPrice;
        this.maxPriorityFeePerGas = maxPriorityFeePerGas;
        this.maxFeePerGas = maxFeePerGas;
        this.accessList = accessList == null ? List.of() : List.copyOf(accessList);
        this.gasLimit = gasLimit;
        this.to = to;
        this.value = value;
        this.payload = payload == null ? Bytes.EMPTY : payload;
        this.chainId = chainId;
        this.signature = signature;
        this.rawRlp = rawRlp;
    }

    /** An unsigned legacy template — only useful as input to {@link #sign(KeyPair, SECP256K1)}. */
    public static EvmTransaction unsigned(long nonce, Wei gasPrice, long gasLimit, Optional<Address> to,
                                          Wei value, Bytes payload, BigInteger chainId) {
        return new EvmTransaction(TYPE_LEGACY, nonce, gasPrice, null, null, List.of(), gasLimit, to,
                value, payload, chainId, null, null);
    }

    /** An unsigned type-2 template — only useful as input to {@link #sign(KeyPair, SECP256K1)}. */
    public static EvmTransaction unsignedType2(long nonce, Wei maxPriorityFeePerGas, Wei maxFeePerGas,
                                               long gasLimit, Optional<Address> to, Wei value,
                                               Bytes payload, List<AccessListEntry> accessList,
                                               BigInteger chainId) {
        return new EvmTransaction(TYPE_EIP1559, nonce, null, maxPriorityFeePerGas, maxFeePerGas,
                accessList, gasLimit, to, value, payload, chainId, null, null);
    }

    /**
     * Parses a signed transaction: a legacy EIP-155 RLP list, or an EIP-2718 envelope
     * (currently only type-2 / EIP-1559). The exact input bytes are kept as the canonical
     * encoding, so txHash = keccak256(bytes) is bit-identical to Ethereum for both forms.
     *
     * @throws IllegalArgumentException on a structurally valid but semantically invalid tx
     *         (unprotected legacy, bad yParity, high-s, priority above maxFee, unsupported type)
     * @throws org.hyperledger.besu.ethereum.rlp.RLPException on malformed RLP
     */
    public static EvmTransaction decode(Bytes rlp) {
        if (rlp.isEmpty()) {
            throw new IllegalArgumentException("empty transaction bytes");
        }
        int first = rlp.get(0) & 0xFF;
        if (first >= 0xc0) {
            return decodeLegacy(rlp);
        }
        if (first == TYPE_EIP1559) {
            return decodeType2(rlp);
        }
        if (first == 0x01) {
            throw new IllegalArgumentException("type-1 (EIP-2930) transactions not supported");
        }
        // 0x03..0x7f: valid EIP-2718 type space we do not implement; 0x80..0xbf: not a tx at all.
        throw new IllegalArgumentException(
                "unsupported transaction type 0x" + Integer.toHexString(first));
    }

    private static EvmTransaction decodeLegacy(Bytes rlp) {
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
        // EIP-2: reject the high-s half of every signature. Without this, the malleated twin
        // (r, n-s, v^recId) recovers the same sender and has the same effect but a DIFFERENT tx hash,
        // so both could be relayed/stored/referenced under distinct hashes.
        if (s.compareTo(SECP.getHalfCurveOrder()) > 0) {
            throw new IllegalArgumentException("non-canonical (high-s) signature rejected");
        }
        // v = chainId * 2 + 35 + recId, recId in {0, 1}
        BigInteger shifted = v.subtract(EIP155_V_BASE);
        byte recId = (byte) (shifted.testBit(0) ? 1 : 0);
        BigInteger chainId = shifted.shiftRight(1);
        SECPSignature signature = SECP.createSignature(r, s, recId);
        return new EvmTransaction(TYPE_LEGACY, nonce, gasPrice, null, null, List.of(), gasLimit, to,
                value, payload, chainId, signature, rlp);
    }

    private static EvmTransaction decodeType2(Bytes envelope) {
        RLPInput in = RLP.input(envelope.slice(1));
        in.enterList();
        BigInteger chainId = in.readBigIntegerScalar();
        long nonce = in.readLongScalar();
        Wei maxPriorityFeePerGas = Wei.of(in.readBigIntegerScalar());
        Wei maxFeePerGas = Wei.of(in.readBigIntegerScalar());
        long gasLimit = in.readLongScalar();
        Bytes toBytes = in.readBytes();
        Optional<Address> to = toBytes.isEmpty() ? Optional.empty() : Optional.of(Address.wrap(toBytes));
        Wei value = Wei.of(in.readBigIntegerScalar());
        Bytes payload = in.readBytes();
        List<AccessListEntry> accessList = in.readList(AccessListEntry::readFrom);
        int yParity = in.readIntScalar();
        BigInteger r = in.readBigIntegerScalar();
        BigInteger s = in.readBigIntegerScalar();
        in.leaveList();

        if (yParity != 0 && yParity != 1) {
            throw new IllegalArgumentException("invalid yParity " + yParity + " (must be 0 or 1)");
        }
        // EIP-2 applies to typed txs exactly as to legacy (same malleated-twin hazard).
        if (s.compareTo(SECP.getHalfCurveOrder()) > 0) {
            throw new IllegalArgumentException("non-canonical (high-s) signature rejected");
        }
        if (maxPriorityFeePerGas.compareTo(maxFeePerGas) > 0) {
            throw new IllegalArgumentException("maxPriorityFeePerGas exceeds maxFeePerGas");
        }
        SECPSignature signature = SECP.createSignature(r, s, (byte) yParity);
        return new EvmTransaction(TYPE_EIP1559, nonce, null, maxPriorityFeePerGas, maxFeePerGas,
                accessList, gasLimit, to, value, payload, chainId, signature, envelope);
    }

    /**
     * The signing preimage hash. Legacy: keccak256 of the EIP-155 payload
     * {@code rlp([nonce,gasPrice,gasLimit,to,value,data,chainId,0,0])}. Type-2: keccak256 of
     * {@code 0x02 ‖ rlp([chainId,nonce,priority,maxFee,gasLimit,to,value,data,accessList])}.
     */
    public Bytes32 signingHash() {
        if (type == TYPE_EIP1559) {
            BytesValueRLPOutput out = new BytesValueRLPOutput();
            out.startList();
            writeType2UnsignedBody(out);
            out.endList();
            return org.hyperledger.besu.crypto.Hash.keccak256(
                    Bytes.concatenate(Bytes.of(TYPE_EIP1559), out.encoded()));
        }
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
        if (type == TYPE_EIP1559) {
            BytesValueRLPOutput out = new BytesValueRLPOutput();
            out.startList();
            writeType2UnsignedBody(out);
            out.writeIntScalar(sig.getRecId());
            out.writeBigIntegerScalar(sig.getR());
            out.writeBigIntegerScalar(sig.getS());
            out.endList();
            Bytes raw = Bytes.concatenate(Bytes.of(TYPE_EIP1559), out.encoded());
            return new EvmTransaction(TYPE_EIP1559, nonce, null, maxPriorityFeePerGas, maxFeePerGas,
                    accessList, gasLimit, to, value, payload, chainId, sig, raw);
        }
        BigInteger v = chainId.shiftLeft(1).add(EIP155_V_BASE).add(BigInteger.valueOf(sig.getRecId()));
        Bytes raw = encodeSigned(v, sig.getR(), sig.getS());
        return new EvmTransaction(TYPE_LEGACY, nonce, gasPrice, null, null, List.of(), gasLimit, to,
                value, payload, chainId, sig, raw);
    }

    /** Re-encodes this transaction's body with an arbitrary v (kept for negative tests). */
    public Bytes encodeWithV(BigInteger v) {
        if (type != TYPE_LEGACY) {
            throw new IllegalStateException("legacy-only test helper");
        }
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

    private void writeType2UnsignedBody(BytesValueRLPOutput out) {
        out.writeBigIntegerScalar(chainId);
        out.writeLongScalar(nonce);
        out.writeBigIntegerScalar(maxPriorityFeePerGas.getAsBigInteger());
        out.writeBigIntegerScalar(maxFeePerGas.getAsBigInteger());
        out.writeLongScalar(gasLimit);
        out.writeBytes(to.map(a -> (Bytes) a.getBytes()).orElse(Bytes.EMPTY));
        out.writeBigIntegerScalar(value.getAsBigInteger());
        out.writeBytes(payload);
        out.startList();
        for (AccessListEntry e : accessList) {
            e.writeTo(out);
        }
        out.endList();
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

    public int getType() {
        return type;
    }

    /** Legacy gas price. Throws for typed txs — those price via effective/feeCap accessors. */
    public Wei getGasPrice() {
        if (type != TYPE_LEGACY) {
            throw new IllegalStateException("type-" + type
                    + " transaction has no gasPrice; use getEffectiveGasPrice()/getFeeCapPerGas()");
        }
        return gasPrice;
    }

    /**
     * The per-gas price actually charged: Ethereum's
     * {@code baseFee + min(maxPriorityFee, maxFee - baseFee)} with baseFee ≡ 0 in step 1
     * (spec §3), i.e. {@code min(maxPriorityFee, maxFee)}. For type-0 this is the gas price.
     */
    public Wei getEffectiveGasPrice() {
        if (type == TYPE_LEGACY) {
            return gasPrice;
        }
        return maxPriorityFeePerGas.compareTo(maxFeePerGas) <= 0 ? maxPriorityFeePerGas : maxFeePerGas;
    }

    /** The per-gas price ceiling the sender must afford: maxFee (type-2) / gasPrice (type-0). */
    public Wei getFeeCapPerGas() {
        return type == TYPE_LEGACY ? gasPrice : maxFeePerGas;
    }

    public Wei getMaxPriorityFeePerGas() {
        return maxPriorityFeePerGas;
    }

    public Wei getMaxFeePerGas() {
        return maxFeePerGas;
    }

    public List<AccessListEntry> getAccessList() {
        return accessList;
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
