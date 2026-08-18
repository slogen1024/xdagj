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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;

/**
 * The EVM mempool (spec §4.4): accepts raw EIP-155 blobs from RPC/P2P, validates them against the
 * current world state, persists accepted blobs into the EVM_TX store, and hands miners a
 * effective-gas-price-ordered selection.
 *
 * <p>v2 policy: per-sender nonce chains. A sender may queue up to MAX_PER_SENDER consecutive
 * pending transactions covering the nonce window [accountNonce, accountNonce + MAX_PER_SENDER - 1].
 * Nonces outside that window are rejected with NONCE_MISMATCH. Stale entries (nonce &lt;
 * accountNonce) are pruned at admission time. Admission requires that the sender's cumulative cost
 * (Σ value + gasLimit×feeCap over all queued txs including the new one) does not exceed the
 * account balance. Same-slot replacement requires a strictly higher effective gas price (replace-by-fee).
 * Thread-safe via a single lock — pool throughput is nowhere near contention territory in v2.
 */
public class EvmTxPool {

    public enum AddResult {
        ADDED, REPLACED, DUPLICATE, INVALID_ENCODING, WRONG_CHAIN_ID, INVALID_SIGNATURE,
        GAS_LIMIT_TOO_HIGH, INTRINSIC_GAS_TOO_LOW, UNDERPRICED, NONCE_MISMATCH, INSUFFICIENT_BALANCE,
        POOL_FULL
    }

    /** Hard cap on distinct pending txs; a P2P-exposed pool must bound its memory (spec §6 DoS). */
    public static final int MAX_POOL_SIZE = 4096;

    /**
     * Maximum queued txs per sender (nonce window width). A sender may hold nonces
     * [accountNonce, accountNonce + MAX_PER_SENDER - 1] simultaneously.
     */
    public static final int MAX_PER_SENDER = 16;

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
    /** sender -> nonce-sorted queue of pending entries. */
    private final Map<Address, NavigableMap<Long, PoolEntry>> bySender = new LinkedHashMap<>();

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
            if (IntrinsicGas.compute(tx.getPayload(), tx.isContractCreation(), tx.getAccessList()) > tx.getGasLimit()) {
                return AddResult.INTRINSIC_GAS_TOO_LOW;
            }
        } catch (IllegalArgumentException e) {
            // EIP-3860 oversized initcode
            return AddResult.INTRINSIC_GAS_TOO_LOW;
        }
        if (tx.getEffectiveGasPrice().compareTo(minGasPrice) < 0) {
            return AddResult.UNDERPRICED;
        }

        // Account checks against the CURRENT persisted world state. Deliberately opens a fresh
        // read-only root updater per call (same pattern as selectBatch's shared snapshot): balance
        // must be read atomically alongside nonce, and nothing is committed.
        Account account = new RocksDbWorldUpdater(evmStateStore).getAccount(sender);
        long accountNonce = account == null ? 0L : account.getNonce();
        Wei balance = account == null ? Wei.ZERO : account.getBalance();

        // Obtain (or create) the per-sender nonce queue and prune entries that are now below the
        // current account nonce (confirmed on-chain since they were queued).
        NavigableMap<Long, PoolEntry> queue = bySender.computeIfAbsent(sender, s -> new TreeMap<>());
        pruneStale(queue, accountNonce);

        // Admission window: [accountNonce, accountNonce + MAX_PER_SENDER - 1].
        // accountNonce + 16 cannot overflow for any reachable on-chain nonce.
        if (tx.getNonce() < accountNonce || tx.getNonce() >= accountNonce + MAX_PER_SENDER) {
            if (queue.isEmpty()) {
                bySender.remove(sender);
            }
            return AddResult.NONCE_MISMATCH;
        }

        // Cumulative cost admission: the sender must be able to cover ALL queued txs (including the
        // new one, excluding any entry being replaced at the same nonce slot).
        // Gas settles in EVM wei (缺口2 / Path α): value + gasLimit * feeCap.
        BigInteger cumulative = cost(tx);
        for (PoolEntry e : queue.values()) {
            if (e.tx().getNonce() != tx.getNonce()) {
                cumulative = cumulative.add(cost(e.tx()));
            }
        }
        if (balance.getAsBigInteger().compareTo(cumulative) < 0) {
            if (queue.isEmpty()) {
                bySender.remove(sender);
            }
            return AddResult.INSUFFICIENT_BALANCE;
        }

        Hash hash = tx.getHash();
        if (byHash.containsKey(hash)) {
            return AddResult.DUPLICATE;
        }

        PoolEntry existing = queue.get(tx.getNonce());
        boolean replaced = false;
        if (existing != null) {
            // Same-slot competition: replace-by-fee only for a strictly higher effective gas price.
            // Compares EFFECTIVE prices only (cap may drop): sound while baseFee == 0; revisit if a base-fee market activates.
            if (tx.getEffectiveGasPrice().compareTo(existing.tx().getEffectiveGasPrice()) <= 0) {
                return AddResult.UNDERPRICED;
            }
            byHash.remove(existing.tx().getHash());
            replaced = true;
        } else if (byHash.size() >= MAX_POOL_SIZE) {
            // A new entry would grow the pool past its cap.
            if (queue.isEmpty()) {
                bySender.remove(sender);
            }
            return AddResult.POOL_FULL;
        }

        PoolEntry entry = new PoolEntry(tx, sender, clockSeconds.getAsLong());
        byHash.put(hash, entry);
        queue.put(tx.getNonce(), entry);
        txStore.put(tx);
        return replaced ? AddResult.REPLACED : AddResult.ADDED;
    }

    /** Live (non-expired) txs, highest effective gas price first; insertion order breaks ties. */
    public synchronized List<EvmTransaction> selectTransactions(int maxCount) {
        long now = clockSeconds.getAsLong();
        return byHash.values().stream()
                .filter(e -> now - e.addedAtSeconds() <= ttlSeconds)
                .sorted(Comparator.comparing((PoolEntry e) -> e.tx().getEffectiveGasPrice()).reversed())
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
            NavigableMap<Long, PoolEntry> queue = bySender.get(entry.sender());
            if (queue != null) {
                queue.remove(entry.tx().getNonce());
                if (queue.isEmpty()) {
                    bySender.remove(entry.sender());
                }
            }
        }
    }

    public synchronized int size() {
        return byHash.size();
    }

    /** {@code selectBatch(gasBudget, EvmTxStore.MAX_BATCH_TXS)}. */
    public synchronized List<EvmTransaction> selectBatch(long gasBudget) {
        return selectBatch(gasBudget, EvmTxStore.MAX_BATCH_TXS);
    }

    /**
     * Greedy batch fill (spec §3): senders ordered by the effective gas price of their next unselected tx
     * (descending, insertion order breaking ties), nonces strictly ascending within a sender
     * starting at the account nonce (a head gap disqualifies the sender), a tx that exceeds the
     * remaining budget stops its sender (no nonce holes), until the budget, the cap, or the pool
     * is exhausted. An expired entry encountered mid-run truncates the run at that point.
     * Does not mutate the pool.
     */
    public synchronized List<EvmTransaction> selectBatch(long gasBudget, int maxTxs) {
        long now = clockSeconds.getAsLong();
        List<EvmTransaction> selected = new ArrayList<>();
        long remaining = gasBudget;
        List<Deque<EvmTransaction>> runs = new ArrayList<>();
        // One shared read-only root updater for all sender nonce lookups in this snapshot.
        RocksDbWorldUpdater snapshot = new RocksDbWorldUpdater(evmStateStore);
        for (Map.Entry<Address, NavigableMap<Long, PoolEntry>> e : bySender.entrySet()) {
            Account acct = snapshot.getAccount(e.getKey());
            long accountNonce = acct == null ? 0L : acct.getNonce();
            Deque<EvmTransaction> run = new ArrayDeque<>();
            long expected = accountNonce;
            for (PoolEntry entry : e.getValue().tailMap(accountNonce, true).values()) {
                if (now - entry.addedAtSeconds() > ttlSeconds || entry.tx().getNonce() != expected) {
                    break;
                }
                run.add(entry.tx());
                expected++;
            }
            if (!run.isEmpty()) {
                runs.add(run);
            }
        }
        while (selected.size() < maxTxs) {
            Deque<EvmTransaction> best = null;
            for (Deque<EvmTransaction> run : runs) {
                if (run.isEmpty()) {
                    continue;
                }
                if (best == null
                        || run.peek().getEffectiveGasPrice().compareTo(best.peek().getEffectiveGasPrice()) > 0) {
                    best = run;
                }
            }
            if (best == null) {
                break;
            }
            EvmTransaction tx = best.peek();
            if (tx.getGasLimit() > remaining) {
                best.clear();
                continue;
            }
            selected.add(best.poll());
            remaining -= tx.getGasLimit();
        }
        return selected;
    }

    // ---- helpers ----

    /** Worst-case cost of a single transaction: value + gasLimit * feeCap (Ethereum admission rule). */
    private static BigInteger cost(EvmTransaction tx) {
        return tx.getValue().getAsBigInteger()
                .add(tx.getFeeCapPerGas().getAsBigInteger().multiply(BigInteger.valueOf(tx.getGasLimit())));
    }

    /**
     * Remove all entries in {@code queue} whose nonce is strictly less than {@code accountNonce}.
     * The corresponding byHash entries are also removed. The sender key in bySender is NOT removed
     * here even if the queue becomes empty; every caller (NONCE_MISMATCH, INSUFFICIENT_BALANCE, and
     * POOL_FULL branches) cleans up the empty sender key before returning.
     */
    private void pruneStale(NavigableMap<Long, PoolEntry> queue, long accountNonce) {
        Iterator<Map.Entry<Long, PoolEntry>> it =
                queue.headMap(accountNonce, false).entrySet().iterator();
        while (it.hasNext()) {
            byHash.remove(it.next().getValue().tx().getHash());
            it.remove();
        }
    }
}
