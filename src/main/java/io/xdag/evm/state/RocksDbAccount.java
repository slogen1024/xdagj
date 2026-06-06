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

import io.xdag.db.rocksdb.KVSource;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.ModificationNotAllowedException;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.AccountStorageEntry;
import org.hyperledger.besu.evm.account.MutableAccount;

/**
 * A {@link MutableAccount} for the RocksDB-backed world state. Mirrors Besu's {@code SimpleAccount}
 * (Apache-2.0) but, at the root, reads "original" storage slots lazily from the {@link KVSource}
 * instead of from an in-memory parent. Children copied from a parent account delegate originals to
 * the parent, giving correct warm/cold and refund semantics.
 */
public class RocksDbAccount implements MutableAccount {

    /** Non-null for child accounts (delegate originals here); null for root/store-backed accounts. */
    private final Account parent;
    /** Non-null only for root/store-backed accounts (read original storage from here). */
    private final KVSource<byte[], byte[]> store;

    private final Address address;
    private long nonce;
    private Wei balance;
    private Bytes code;
    private final Map<UInt256, UInt256> updatedStorage = new HashMap<>();
    private boolean immutable = false;

    /** Root/store-backed account loaded from the EVM_STATE store. */
    public RocksDbAccount(KVSource<byte[], byte[]> store, Address address, long nonce, Wei balance, Bytes code) {
        this.parent = null;
        this.store = store;
        this.address = address;
        this.nonce = nonce;
        this.balance = balance;
        this.code = code == null ? Bytes.EMPTY : code;
    }

    /** Child account copied from a parent updater's account. */
    public RocksDbAccount(Account parent, Address address, long nonce, Wei balance, Bytes code) {
        this.parent = parent;
        this.store = null;
        this.address = address;
        this.nonce = nonce;
        this.balance = balance;
        this.code = code == null ? Bytes.EMPTY : code;
    }

    @Override
    public Address getAddress() {
        return address;
    }

    @Override
    public Hash getAddressHash() {
        return address == null ? Hash.ZERO : address.addressHash();
    }

    @Override
    public long getNonce() {
        return nonce;
    }

    @Override
    public Wei getBalance() {
        return balance;
    }

    @Override
    public Bytes getCode() {
        return code;
    }

    @Override
    public Hash getCodeHash() {
        return (code == null || code.isEmpty()) ? Hash.EMPTY : Hash.hash(code);
    }

    @Override
    public UInt256 getStorageValue(UInt256 key) {
        if (updatedStorage.containsKey(key)) {
            return updatedStorage.get(key);
        }
        return getOriginalStorageValue(key);
    }

    @Override
    public UInt256 getOriginalStorageValue(UInt256 key) {
        if (parent != null) {
            return parent.getStorageValue(key);
        }
        if (store != null) {
            byte[] raw = store.get(EvmStateSchema.storageKey(address, key));
            return raw == null ? UInt256.ZERO : EvmStateSchema.decodeStorageValue(raw);
        }
        return UInt256.ZERO;
    }

    @Override
    public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(Bytes32 startKeyHash, int limit) {
        throw new UnsupportedOperationException("Storage iteration not supported by RocksDbAccount");
    }

    @Override
    public boolean isStorageEmpty() {
        return updatedStorage.isEmpty();
    }

    @Override
    public void setNonce(long value) {
        requireMutable();
        this.nonce = value;
    }

    @Override
    public void setBalance(Wei value) {
        requireMutable();
        this.balance = value;
    }

    @Override
    public void setCode(Bytes code) {
        requireMutable();
        this.code = code == null ? Bytes.EMPTY : code;
    }

    @Override
    public void setStorageValue(UInt256 key, UInt256 value) {
        requireMutable();
        updatedStorage.put(key, value);
    }

    @Override
    public void clearStorage() {
        requireMutable();
        updatedStorage.clear();
    }

    @Override
    public Map<UInt256, UInt256> getUpdatedStorage() {
        return updatedStorage;
    }

    @Override
    public void becomeImmutable() {
        immutable = true;
    }

    private void requireMutable() {
        if (immutable) {
            throw new ModificationNotAllowedException();
        }
    }

    /**
     * Merge this (child) account's changes into its parent account, mirroring Besu SimpleAccount.
     *
     * @return true if there was a parent {@link RocksDbAccount} that was merged into
     */
    public boolean commit() {
        if (parent instanceof RocksDbAccount parentAccount) {
            parentAccount.balance = balance;
            parentAccount.nonce = nonce;
            parentAccount.code = code;
            parentAccount.updatedStorage.putAll(updatedStorage);
            return true;
        }
        return false;
    }
}
