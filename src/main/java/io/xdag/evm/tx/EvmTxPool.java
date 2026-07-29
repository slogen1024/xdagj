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

import io.xdag.db.rocksdb.KVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;

/**
 * The EVM mempool (spec §4.4): accepts raw EIP-155 blobs from RPC/P2P, validates them against the
 * current world state, persists accepted blobs into the EVM_TX store, and hands miners a
 * gas-price-ordered selection.
 *
 * <p>v1 policy: strictly one pending tx per sender (nonce must equal the account nonce), replaced
 * only by a strictly higher gas price. Thread-safe via a single lock — pool throughput is nowhere
 * near contention territory in v1.
 */
public class EvmTxPool {

    public enum AddResult {
        ADDED, REPLACED, DUPLICATE, INVALID_ENCODING, WRONG_CHAIN_ID, INVALID_SIGNATURE,
        GAS_LIMIT_TOO_HIGH, INTRINSIC_GAS_TOO_LOW, UNDERPRICED, NONCE_MISMATCH, INSUFFICIENT_BALANCE,
        POOL_FULL
    }

    /** Hard cap on distinct pending txs; a P2P-exposed pool must bound its memory (spec §6 DoS). */
    public static final int MAX_POOL_SIZE = 4096;

    private record PoolEntry(EvmTransaction tx, Address sender, long addedAtSeconds) {
    }

    private final EvmTxStore txStore;
    private final KVSource<byte[], byte[]> evmStateStore;
    private final BigInteger chainId;
    private final long blockGasLimit;
    private final Wei minGasPrice;
    private final long ttlSeconds;
    private final LongSupplier clockSeconds;

    /** txHash -> entry; insertion order preserved for deterministic same-price tiebreaks. */
    private final Map<Hash, PoolEntry> byHash = new LinkedHashMap<>();
    /** sender -> entry (v1: one pending tx per sender). */
    private final Map<Address, PoolEntry> bySender = new LinkedHashMap<>();

    public EvmTxPool(EvmTxStore txStore, KVSource<byte[], byte[]> evmStateStore, BigInteger chainId,
                     long blockGasLimit, Wei minGasPrice, long ttlSeconds, LongSupplier clockSeconds) {
        this.txStore = txStore;
        this.evmStateStore = evmStateStore;
        this.chainId = chainId;
        this.blockGasLimit = blockGasLimit;
        this.minGasPrice = minGasPrice;
        this.ttlSeconds = ttlSeconds;
        this.clockSeconds = clockSeconds;
    }

    public synchronized AddResult add(Bytes rawRlp) {
        // Opportunistic eviction keeps the size cap honest without a background timer.
        evictExpired();
        EvmTransaction tx;
        try {
            tx = EvmTransaction.decode(rawRlp);
        } catch (RuntimeException e) {
            return AddResult.INVALID_ENCODING;
        }
        if (!chainId.equals(tx.getChainId())) {
            return AddResult.WRONG_CHAIN_ID;
        }
        Address sender;
        try {
            sender = tx.getSender();
        } catch (RuntimeException e) {
            return AddResult.INVALID_SIGNATURE;
        }
        if (tx.getGasLimit() > blockGasLimit) {
            return AddResult.GAS_LIMIT_TOO_HIGH;
        }
        try {
            if (IntrinsicGas.compute(tx.getPayload(), tx.isContractCreation()) > tx.getGasLimit()) {
                return AddResult.INTRINSIC_GAS_TOO_LOW;
            }
        } catch (IllegalArgumentException e) {
            // EIP-3860 oversized initcode
            return AddResult.INTRINSIC_GAS_TOO_LOW;
        }
        if (tx.getGasPrice().compareTo(minGasPrice) < 0) {
            return AddResult.UNDERPRICED;
        }

        // Account checks against the CURRENT persisted world state. A fresh root updater per call:
        // read-only usage, nothing is committed.
        Account account = new RocksDbWorldUpdater(evmStateStore).getAccount(sender);
        long accountNonce = account == null ? 0L : account.getNonce();
        Wei balance = account == null ? Wei.ZERO : account.getBalance();
        if (tx.getNonce() != accountNonce) {
            return AddResult.NONCE_MISMATCH;
        }
        // Gas settles in EVM wei (缺口2 / Path α): the sender must cover the transferred value plus the
        // maximum gas fee (gasLimit * gasPrice). The unused gas is refunded when the tx executes; the
        // net charge (gasUsed * gasPrice) is burned. EvmBlockProcessor re-checks this at execution.
        BigInteger maxCost = tx.getValue().getAsBigInteger()
                .add(tx.getGasPrice().getAsBigInteger().multiply(BigInteger.valueOf(tx.getGasLimit())));
        if (balance.getAsBigInteger().compareTo(maxCost) < 0) {
            return AddResult.INSUFFICIENT_BALANCE;
        }

        Hash hash = tx.getHash();
        if (byHash.containsKey(hash)) {
            return AddResult.DUPLICATE;
        }
        PoolEntry existing = bySender.get(sender);
        boolean replaced = false;
        if (existing != null) {
            if (existing.tx().getNonce() == tx.getNonce()) {
                // Genuine same-slot competition: replace-by-fee only for a strictly higher gas price.
                if (tx.getGasPrice().compareTo(existing.tx().getGasPrice()) <= 0) {
                    return AddResult.UNDERPRICED;
                }
            }
            // Otherwise the cached entry is for a different nonce. Under strict nonce equality only
            // one nonce is valid per sender at a time, so this new (validated) tx's nonce is the live
            // one and the cached entry is stale — evict it regardless of price.
            byHash.remove(existing.tx().getHash());
            replaced = true;
        } else if (byHash.size() >= MAX_POOL_SIZE) {
            // A new sender slot would grow the pool past its cap.
            return AddResult.POOL_FULL;
        }
        PoolEntry entry = new PoolEntry(tx, sender, clockSeconds.getAsLong());
        byHash.put(hash, entry);
        bySender.put(sender, entry);
        txStore.put(tx);
        return replaced ? AddResult.REPLACED : AddResult.ADDED;
    }

    /** Live (non-expired) txs, highest gas price first; insertion order breaks ties. */
    public synchronized List<EvmTransaction> selectTransactions(int maxCount) {
        long now = clockSeconds.getAsLong();
        return byHash.values().stream()
                .filter(e -> now - e.addedAtSeconds() <= ttlSeconds)
                .sorted(Comparator.comparing((PoolEntry e) -> e.tx().getGasPrice()).reversed())
                .limit(maxCount)
                .map(PoolEntry::tx)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    public synchronized void evictExpired() {
        long now = clockSeconds.getAsLong();
        List<Hash> expired = byHash.entrySet().stream()
                .filter(e -> now - e.getValue().addedAtSeconds() > ttlSeconds)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(this::removeInternal);
    }

    public synchronized Optional<EvmTransaction> get(Hash txHash) {
        return Optional.ofNullable(byHash.get(txHash)).map(PoolEntry::tx);
    }

    /** Drops the pool entry (e.g. once the tx is buried in a main block). The EVM_TX blob stays. */
    public synchronized void remove(Hash txHash) {
        removeInternal(txHash);
    }

    private void removeInternal(Hash txHash) {
        PoolEntry entry = byHash.remove(txHash);
        if (entry != null) {
            PoolEntry senderEntry = bySender.get(entry.sender());
            if (senderEntry != null && senderEntry.tx().getHash().equals(txHash)) {
                bySender.remove(entry.sender());
            }
        }
    }

    public synchronized int size() {
        return byHash.size();
    }
}
