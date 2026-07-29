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
import io.xdag.evm.state.EvmStateSchema.AccountRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * A persistent Besu {@link WorldUpdater} backed by the single {@code EVM_STATE} {@link KVSource}.
 *
 * <p>Structured exactly like Besu's in-memory {@code SimpleWorld} (Apache-2.0 template): a nested
 * updater with a {@code parent} and an {@link Optional}-cache of accounts. The difference is the
 * <b>root</b> (parent == null): {@link #getAccount}/{@link #get} lazily load+deserialize from the
 * store, and {@link #commit()} serializes every touched account and persists the whole delta in one
 * {@link KVSource#batchWrite} call (atomic within this single store).
 *
 * <p>The Sub-project A executor is unchanged: {@code XdagEvmExecutor.deploy/call(WorldUpdater, ...)}
 * works against {@code new RocksDbWorldUpdater(evmStateStore)} exactly as it did against SimpleWorld.
 *
 * <p><b>Not thread-safe.</b> The {@code accounts} map and the mutable {@link RocksDbAccount} fields it
 * holds carry no synchronization. The intended (and only supported) model is one updater per execution,
 * confined to a single thread. Sharing an instance across threads will corrupt state. Allocate a fresh
 * {@code RocksDbWorldUpdater} (or a child via {@link #updater()}) per execution rather than reusing one.
 */
public class RocksDbWorldUpdater implements WorldUpdater {

    private final RocksDbWorldUpdater parent;
    private final KVSource<byte[], byte[]> store;
    private Map<Address, Optional<RocksDbAccount>> accounts = new HashMap<>();

    /** Root updater backed by the EVM_STATE store. */
    public RocksDbWorldUpdater(KVSource<byte[], byte[]> store) {
        this.parent = null;
        this.store = store;
    }

    private RocksDbWorldUpdater(RocksDbWorldUpdater parent) {
        this.parent = parent;
        this.store = parent.store;
    }

    @Override
    public WorldUpdater updater() {
        return new RocksDbWorldUpdater(this);
    }

    @Override
    public Account get(Address address) {
        return getAccount(address);
    }

    @Override
    public MutableAccount getAccount(Address address) {
        Optional<RocksDbAccount> cached = accounts.get(address);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (parent != null) {
            Account parentAccount = parent.getAccount(address);
            if (parentAccount != null) {
                RocksDbAccount child = new RocksDbAccount(parentAccount, parentAccount.getAddress(),
                        parentAccount.getNonce(), parentAccount.getBalance(), parentAccount.getCode());
                accounts.put(address, Optional.of(child));
                return child;
            }
            return null;
        }
        // Root: load from the store.
        byte[] raw = store.get(EvmStateSchema.accountKey(address));
        if (raw == null) {
            return null;
        }
        AccountRecord record = EvmStateSchema.decodeAccount(raw);
        Bytes code = Bytes.EMPTY;
        if (!record.codeHash().equals(Hash.EMPTY)) {
            byte[] codeBytes = store.get(EvmStateSchema.codeKey(record.codeHash()));
            if (codeBytes != null) {
                code = Bytes.wrap(codeBytes);
            }
        }
        RocksDbAccount account = new RocksDbAccount(store, address, record.nonce(), record.balance(), code);
        accounts.put(address, Optional.of(account));
        return account;
    }

    @Override
    public MutableAccount createAccount(Address address, long nonce, Wei balance) {
        if (getAccount(address) != null) {
            throw new IllegalStateException("Cannot create an account when one already exists");
        }
        // S-28: guard against storage resurrection. getAccount() above returned null, so any cache
        // entry that still exists is a pending deletion (Optional.empty) from this same updater. If the
        // address was deleted here, or the store still holds slots under it, the freshly created account
        // must wipe those prior slots at commit; otherwise the new account would inherit (resurrect) the
        // old storage. clearStorage() sets the storageCleared flag the commit path honours — the just-
        // created account's updatedStorage is empty, so nothing else is affected.
        boolean previouslyDeletedHere = accounts.containsKey(address);
        RocksDbAccount account = parent != null
                ? new RocksDbAccount((Account) null, address, nonce, balance, Bytes.EMPTY)
                : new RocksDbAccount(store, address, nonce, balance, Bytes.EMPTY);
        if (previouslyDeletedHere || hasPersistedStorage(address)) {
            account.clearStorage();
        }
        accounts.put(address, Optional.of(account));
        return account;
    }

    /** True if the underlying store still holds any storage slot keyed under {@code address}. */
    private boolean hasPersistedStorage(Address address) {
        return !store.prefixKeyLookup(EvmStateSchema.storagePrefix(address)).isEmpty();
    }

    @Override
    public void deleteAccount(Address address) {
        accounts.put(address, Optional.empty());
    }

    @Override
    public Collection<? extends Account> getTouchedAccounts() {
        return accounts.values().stream().filter(Optional::isPresent).map(Optional::get).toList();
    }

    @Override
    public Collection<Address> getDeletedAccountAddresses() {
        return accounts.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }

    @Override
    public void revert() {
        accounts = new HashMap<>();
    }

    @Override
    public void commit() {
        commitAndDigest();
    }

    /**
     * Flushes this updater like {@link #commit()} and returns a deterministic keccak digest of exactly
     * the {@code (puts, deletes)} persisted by this commit — the world-state commitment the consensus
     * layer folds into the chained state root so a balance/storage divergence changes the root
     * (EvmBlockProcessor). A child updater ({@code parent != null}) only pushes changes up to its
     * parent and writes nothing to the store, so it has no persisted delta and returns
     * {@link Bytes32#ZERO}; only the root commit touches the store.
     */
    public Bytes32 commitAndDigest() {
        if (parent != null) {
            // In-memory child: push changes up to the parent, exactly like SimpleWorld.
            accounts.forEach((address, account) -> {
                if (account.isEmpty() || !account.get().commit()) {
                    parent.accounts.put(address, account);
                }
            });
            return Bytes32.ZERO;
        }
        // Root: flush the whole delta to the store in one atomic batchWrite.
        Map<byte[], byte[]> puts = new HashMap<>();
        // Content-addressed (NOT a reference-equality HashSet): a key reached by more than one path —
        // e.g. a slot both zeroed this commit and swept by clearStorage — must appear exactly once, so
        // the state-root digest counts it once and stays an injective function of the persisted delta.
        Set<byte[]> deletes = new TreeSet<>(Arrays::compareUnsigned);
        accounts.forEach((address, optAccount) -> {
            if (optAccount.isEmpty()) {
                // Deleted account (SELFDESTRUCT): drop its header and every storage slot.
                deletes.add(EvmStateSchema.accountKey(address));
                deletes.addAll(store.prefixKeyLookup(EvmStateSchema.storagePrefix(address)));
                return;
            }
            RocksDbAccount account = optAccount.get();
            Hash codeHash = account.getCodeHash();
            puts.put(EvmStateSchema.accountKey(address),
                    EvmStateSchema.encodeAccount(account.getNonce(), account.getBalance(), codeHash));
            Bytes code = account.getCode();
            if (code != null && !code.isEmpty()) {
                puts.put(EvmStateSchema.codeKey(codeHash), code.toArray());
            }

            // Slots being (re)written this commit, by content — used to keep deletes disjoint.
            Set<Bytes> writtenSlots = new HashSet<>();
            account.getUpdatedStorage().forEach((slot, value) -> {
                byte[] key = EvmStateSchema.storageKey(address, slot);
                if (value == null || value.isZero()) {
                    // Zero is "absent" in EVM semantics: remove the slot rather than store 32 zero bytes.
                    deletes.add(key);
                } else {
                    puts.put(key, EvmStateSchema.encodeStorageValue(value));
                    writtenSlots.add(Bytes.wrap(key));
                }
            });

            // clearStorage()/account-reset: remove every persisted original slot that is not being
            // rewritten with a fresh value in this same commit.
            if (account.isStorageCleared()) {
                for (byte[] original : store.prefixKeyLookup(EvmStateSchema.storagePrefix(address))) {
                    if (!writtenSlots.contains(Bytes.wrap(original))) {
                        deletes.add(original);
                    }
                }
            }
        });
        Bytes32 delta = stateDelta(puts, deletes);
        store.batchWrite(puts, deletes);
        accounts = new HashMap<>();
        return delta;
    }

    /** Domain tags separating the puts section from the deletes section inside the delta digest. */
    private static final byte STATE_DELTA_PUT_TAG = 0x01;
    private static final byte STATE_DELTA_DELETE_TAG = 0x00;

    /**
     * A deterministic keccak over the exact {@code (puts, deletes)} of one root commit, independent of
     * HashMap iteration order: keys are sorted lexicographically (unsigned), every key and value is
     * length-framed so no concatenation is ambiguous, and the two sections are domain-separated. Two
     * nodes that persist the same world-state delta get the same digest; any diverging byte — a
     * balance, nonce, code hash, a written storage slot, or a removed key — changes it.
     */
    private static Bytes32 stateDelta(Map<byte[], byte[]> puts, Set<byte[]> deletes) {
        List<Bytes> parts = new ArrayList<>();
        parts.add(Bytes.of(STATE_DELTA_PUT_TAG));
        puts.entrySet().stream()
                .sorted((a, b) -> Arrays.compareUnsigned(a.getKey(), b.getKey()))
                .forEach(e -> {
                    parts.add(framed(e.getKey()));
                    parts.add(framed(e.getValue()));
                });
        parts.add(Bytes.of(STATE_DELTA_DELETE_TAG));
        deletes.stream()
                .sorted(Arrays::compareUnsigned)
                .forEach(key -> parts.add(framed(key)));
        return org.hyperledger.besu.crypto.Hash.keccak256(Bytes.concatenate(parts.toArray(new Bytes[0])));
    }

    /** Length-prefixes a byte array (8-byte big-endian length ‖ bytes) so concatenation is unambiguous. */
    private static Bytes framed(byte[] value) {
        return Bytes.concatenate(Bytes.ofUnsignedLong(value.length), Bytes.wrap(value));
    }

    @Override
    public Optional<WorldUpdater> parentUpdater() {
        return Optional.ofNullable(parent);
    }
}
