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
package io.xdag.evm.state;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

/**
 * Key layout and (de)serialization for EVM world state inside the single {@code EVM_STATE} RocksDB
 * store. Three logical namespaces are separated by a one-byte prefix:
 *
 * <pre>
 *   0x00 | address(20)            -> account record  = nonce(8) || balance(32) || codeHash(32)
 *   0x01 | codeHash(32)           -> contract code bytes
 *   0x02 | address(20) | slot(32) -> storage value(32)
 * </pre>
 */
public final class EvmStateSchema {

    public static final byte PREFIX_ACCOUNT = 0x00;
    public static final byte PREFIX_CODE = 0x01;
    public static final byte PREFIX_STORAGE = 0x02;
    /** Single-byte marker recording that the genesis allocation has been seeded (funding on-ramp). */
    public static final byte PREFIX_GENESIS = 0x03;
    /** Bridge-contract seeded-once marker (spec §3.1); sibling of the genesis marker. */
    public static final byte PREFIX_BRIDGE_CONTRACT = 0x04;

    /** Fixed on-disk size of an account record: nonce(8) || balance(32) || codeHash(32). */
    public static final int ACCOUNT_RECORD_LENGTH = 8 + 32 + 32;

    private EvmStateSchema() {
    }

    /** The reserved 1-byte key whose presence means the genesis allocation is already in EVM_STATE. */
    public static byte[] genesisMarkerKey() {
        return new byte[]{PREFIX_GENESIS};
    }

    /** The reserved 1-byte key whose presence means the bridge contract code is already seeded. */
    public static byte[] bridgeContractMarkerKey() {
        return new byte[]{PREFIX_BRIDGE_CONTRACT};
    }

    public static byte[] accountKey(Address address) {
        return Bytes.concatenate(Bytes.of(PREFIX_ACCOUNT), address.getBytes()).toArray();
    }

    public static byte[] codeKey(Hash codeHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_CODE), codeHash.getBytes()).toArray();
    }

    public static byte[] storageKey(Address address, UInt256 slot) {
        return Bytes.concatenate(Bytes.of(PREFIX_STORAGE), address.getBytes(), slot.toBytes()).toArray();
    }

    /** Prefix ({@code 0x02 || address}) that all of an account's storage-slot keys share. */
    public static byte[] storagePrefix(Address address) {
        return Bytes.concatenate(Bytes.of(PREFIX_STORAGE), address.getBytes()).toArray();
    }

    /** Decoded account header (code is stored separately, keyed by codeHash). */
    public record AccountRecord(long nonce, Wei balance, Hash codeHash) {
    }

    public static byte[] encodeAccount(long nonce, Wei balance, Hash codeHash) {
        Bytes record = Bytes.concatenate(
                Bytes.ofUnsignedLong(nonce),          // 8 bytes, big-endian
                Bytes32.leftPad(balance.toBytes()),   // 32 bytes (Wei is UInt256-bounded, so always <= 32)
                codeHash.getBytes());                 // 32 bytes
        return record.toArray();
    }

    public static AccountRecord decodeAccount(byte[] raw) {
        if (raw == null || raw.length != ACCOUNT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "corrupt account record, expected " + ACCOUNT_RECORD_LENGTH + " bytes but got "
                            + (raw == null ? "null" : raw.length));
        }
        Bytes b = Bytes.wrap(raw);
        long nonce = b.slice(0, 8).toLong();
        Wei balance = Wei.of(b.slice(8, 32).toUnsignedBigInteger());
        Hash codeHash = Hash.wrap(Bytes32.wrap(b.slice(40, 32)));
        return new AccountRecord(nonce, balance, codeHash);
    }

    public static byte[] encodeStorageValue(UInt256 value) {
        return value.toBytes().toArray();
    }

    public static UInt256 decodeStorageValue(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalStateException(
                    "corrupt storage value, expected 32 bytes but got " + (raw == null ? "null" : raw.length));
        }
        return UInt256.fromBytes(Bytes32.wrap(Bytes.wrap(raw)));
    }
}
