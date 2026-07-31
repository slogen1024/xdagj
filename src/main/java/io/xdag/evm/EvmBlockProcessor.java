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
package io.xdag.evm;

import io.xdag.db.rocksdb.KVSource;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateSchema;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.evm.tx.IntrinsicGas;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleBlockValues;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * Executes the EVM transactions referenced by one confirmed main block and checkpoints the outcome
 * (spec §7). This is the whole consensus-facing EVM surface: {@code BlockchainImpl} only calls
 * {@link #processMainBlock} from {@code setMain} and {@link #rollbackTo} after {@code unWindMain}.
 *
 * <p>v1 policies (see the B2b plan's deviation ledger): gas settles in EVM wei (缺口2 / Path α) —
 * the sender is debited gasLimit*gasPrice upfront, refunded the unused gas, and the net gasUsed*gasPrice
 * is burned (no coinbase credit yet — P2); a ref whose blob is absent is deterministically skipped;
 * the per-height "state root" is a chained commitment
 * {@code root_h = keccak256(root_prev || (txHash || status || gasUsed)... || stateDelta_h)} where
 * {@code stateDelta_h} digests exactly the (puts, deletes) persisted that height (Phase 1). It commits
 * the world-state delta so a balance/storage-only fork changes the root, but it is still a hash-chain
 * over deltas, not an absolute-state MPT root — no membership proofs / light-client support yet (P2).
 *
 * <p>Rollback is R1 Option A taken to its simplest correct form: wipe EVM_STATE entirely and replay
 * every checkpointed height's tx list from the immutable EVM_TX blobs.
 */
@Slf4j
public class EvmBlockProcessor {

    private final XdagEvmExecutor executor;
    private final BigInteger chainId;
    private final long blockGasLimit;
    private final BigInteger minGasPrice;
    private final long activationHeight;
    private final KVSource<byte[], byte[]> stateStore;
    private final EvmTxStore txStore;
    private final EvmMetaStore metaStore;
    /** Genesis pre-funding seeded once into EVM_STATE by {@link #seedGenesisIfAbsent()} (on-ramp). */
    private final List<GenesisAllocEntry> genesisAlloc;

    /** Always-active processor (activation height 0), no genesis alloc — used by tests. */
    public EvmBlockProcessor(EvmConfig config, KVSource<byte[], byte[]> stateStore,
                             EvmTxStore txStore, EvmMetaStore metaStore) {
        this(config, stateStore, txStore, metaStore, 0L, List.of());
    }

    public EvmBlockProcessor(EvmConfig config, KVSource<byte[], byte[]> stateStore,
                             EvmTxStore txStore, EvmMetaStore metaStore, long activationHeight) {
        this(config, stateStore, txStore, metaStore, activationHeight, List.of());
    }

    public EvmBlockProcessor(EvmConfig config, KVSource<byte[], byte[]> stateStore,
                             EvmTxStore txStore, EvmMetaStore metaStore, long activationHeight,
                             List<GenesisAllocEntry> genesisAlloc) {
        this.executor = new XdagEvmExecutor(config);
        this.chainId = config.chainId();
        this.blockGasLimit = config.maxGasLimit();
        this.minGasPrice = config.minGasPrice();
        this.activationHeight = activationHeight;
        this.stateStore = stateStore;
        this.txStore = txStore;
        this.metaStore = metaStore;
        this.genesisAlloc = List.copyOf(genesisAlloc);
    }

    /**
     * Seeds the configured genesis allocation into EVM_STATE exactly once per chain lifetime, guarded
     * by a marker key. On a fresh chain this credits the pre-funded accounts before any tx executes;
     * after a reorg wipe ({@link #rollbackTo} resets EVM_STATE) the marker is gone, so it restores the
     * allocation before replay re-applies the tx history — otherwise the funded balances would vanish
     * and every replayed tx would fail for lack of gas. A normal restart finds the marker present and
     * does nothing, preserving balances that transactions have since changed. Idempotent and safe to
     * call from every execution entry point.
     */
    public synchronized void seedGenesisIfAbsent() {
        if (stateStore.get(EvmStateSchema.genesisMarkerKey()) != null) {
            return;
        }
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateStore);
        for (GenesisAllocEntry entry : genesisAlloc) {
            world.getOrCreate(entry.address()).setBalance(entry.balance()); // last-wins on duplicates
        }
        world.commit();
        stateStore.put(EvmStateSchema.genesisMarkerKey(), new byte[]{1});
    }

    /**
     * Called from {@code setMain} for each confirmed main block that carries EVM tx refs.
     *
     * <p>Execution is strictly in height order and requires every referenced blob to be present. If
     * a blob is missing — or an earlier height is already stalled waiting for one — the block is
     * <b>deferred</b> (persisted to the pending queue), NOT skipped. Skipping would let a node that
     * has the blob and one that doesn't produce different state forever; deferring instead leaves
     * the blob-less node merely <i>behind</i>, and {@link #onBlobsAvailable()} resumes execution in
     * order once the blobs arrive. Native consensus is unaffected either way (I4).
     */
    public synchronized void processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds,
                                              Bytes32 blockHash) {
        if (txRefs == null || txRefs.isEmpty()) {
            return;
        }
        if (height < activationHeight) {
            // Before the EVM hard fork, an EVM_TX_REF field carries no consensus meaning (spec §3.1).
            log.warn("Ignoring {} EVM tx ref(s) in pre-activation main block at height {} (activates at {})",
                    txRefs.size(), height, activationHeight);
            return;
        }
        if (!metaStore.pendingHeights().isEmpty() || !allBlobsPresent(txRefs)) {
            metaStore.putPending(height, blockHash, timestampSeconds, txRefs);
            log.warn("Deferring EVM execution of main block at height {} ({} ref(s)) until blobs arrive",
                    height, txRefs.size());
            return;
        }
        executeAndCheckpoint(txRefs, height, timestampSeconds, blockHash);
    }

    /**
     * Resumes deferred execution after new blobs are stored (e.g. an EVM_TX_REPLY over P2P). Runs
     * stalled heights in ascending order for as long as each one's blobs are all present, stopping
     * at the first still-incomplete height so ordering is never violated.
     */
    public synchronized void onBlobsAvailable() {
        for (long height : metaStore.pendingHeights()) {
            EvmMetaStore.PendingBlock pending = metaStore.getPending(height).orElse(null);
            if (pending == null) {
                continue;
            }
            if (!allBlobsPresent(pending.refs())) {
                return; // the lowest incomplete height blocks everything above it
            }
            executeAndCheckpoint(pending.refs(), height, pending.timestampSeconds(), pending.blockHash());
            metaStore.removePending(height);
        }
    }

    /**
     * The distinct tx hashes that deferred (pending) heights reference but whose blobs are not yet
     * stored, lowest height first, in ref order. The P2P layer periodically re-requests these from
     * peers so a stalled EVM height converges (I4): the retry self-heals a dropped request or a blob
     * reply that arrived before the block was deferred. Empty when nothing is pending or all blobs
     * are present.
     */
    public synchronized List<Bytes32> pendingMissingBlobHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        for (long height : metaStore.pendingHeights()) {
            EvmMetaStore.PendingBlock pending = metaStore.getPending(height).orElse(null);
            if (pending == null) {
                continue;
            }
            for (Bytes32 ref : pending.refs()) {
                if (!txStore.contains(Hash.wrap(ref)) && seen.add(ref)) {
                    missing.add(ref);
                }
            }
        }
        return missing;
    }

    /** True if some deferred height references {@code txHash} and its blob is not yet stored. */
    public synchronized boolean isAwaitingBlob(Hash txHash) {
        if (txStore.contains(txHash)) {
            return false;
        }
        Bytes32 target = Bytes32.wrap(txHash.getBytes());
        for (long height : metaStore.pendingHeights()) {
            EvmMetaStore.PendingBlock pending = metaStore.getPending(height).orElse(null);
            if (pending != null && pending.refs().contains(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean allBlobsPresent(List<Bytes32> txRefs) {
        for (Bytes32 refBytes : txRefs) {
            if (!txStore.contains(Hash.wrap(refBytes))) {
                return false;
            }
        }
        return true;
    }

    /** Executes a confirmed (or drained) main block's refs and writes its EVM_META checkpoint. */
    private void executeAndCheckpoint(List<Bytes32> txRefs, long height, long timestampSeconds,
                                      Bytes32 blockHash) {
        seedGenesisIfAbsent(); // fund the genesis accounts before the first tx reads their balance
        List<Hash> candidates = new ArrayList<>(txRefs.size());
        Set<Hash> seen = new HashSet<>();
        for (Bytes32 refBytes : txRefs) {
            Hash txHash = Hash.wrap(refBytes);
            if (!seen.add(txHash)) {
                // Duplicate ref within this same block — execute once (spec §7.5).
                continue;
            }
            if (metaStore.getReceipt(txHash).isPresent()) {
                // Already executed on the canonical chain: a re-reference must not run it twice,
                // which would overwrite the original receipt with this run's outcome (spec §7.5).
                log.warn("EVM tx {} already executed on the canonical chain; skipping re-reference "
                        + "at height {}", txHash, height);
                continue;
            }
            if (txStore.contains(txHash)) {
                candidates.add(txHash);
            } else {
                // Guarded against by the caller's allBlobsPresent gate; only reachable if the blob
                // was evicted between the gate and here.
                log.error("EVM tx blob vanished for ref {} at height {}; skipping", txHash, height);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ExecutionOutcome outcome = executeList(candidates, height, timestampSeconds, latestRoot());
        if (outcome.executed().isEmpty()) {
            // Everything was over-budget — no state changed, no checkpoint.
            return;
        }
        metaStore.putHeightRecord(height, outcome.root(), blockHash, outcome.executed().size(),
                timestampSeconds);
        metaStore.putTxList(height, outcome.executed());
        log.info("EVM main block {}: executed {} tx(s), root {}", height, outcome.executed().size(),
                outcome.root());
    }

    /**
     * Reorg handling (Option A): truncate EVM_META above {@code height}, wipe the world state, and
     * replay every remaining checkpointed height from the EVM_TX blobs. A replayed chained root that
     * differs from its checkpoint indicates nondeterminism or corruption and is logged loudly.
     */
    public synchronized void rollbackTo(long height) {
        log.info("EVM rollback to main height {}", height);
        metaStore.removeAbove(height);
        stateStore.reset();
        seedGenesisIfAbsent(); // the reset wiped the marker + funded balances; restore before replay
        Bytes32 previousRoot = Bytes32.ZERO;
        for (long h : metaStore.txListHeights()) {
            EvmMetaStore.HeightRecord record = metaStore.getHeightRecord(h).orElse(null);
            if (record == null) {
                log.error("EVM_META tx list without height record at {}; skipping replay entry", h);
                continue;
            }
            // The stored list is already the deduped, budget-filtered executed set, so replay is a
            // faithful re-run (the budget re-applies as a no-op).
            Bytes32 replayedRoot = executeList(metaStore.getTxList(h), h, record.timestampSeconds(),
                    previousRoot).root();
            if (!replayedRoot.equals(record.stateRoot())) {
                log.error("EVM replay root mismatch at height {}: stored {}, replayed {}",
                        h, record.stateRoot(), replayedRoot);
            }
            previousRoot = replayedRoot;
        }
    }

    /** The chained commitment of the most recent checkpoint, or zero before any EVM activity. */
    private Bytes32 latestRoot() {
        return metaStore.highestHeight()
                .flatMap(metaStore::getHeightRecord)
                .map(EvmMetaStore.HeightRecord::stateRoot)
                .orElse(Bytes32.ZERO);
    }

    /** The world root plus the txs that actually executed (survived the per-block gas budget). */
    private record ExecutionOutcome(Bytes32 root, List<Hash> executed) {
    }

    /**
     * Executes one height's txs on a fresh root updater, commits, writes receipts, and returns the
     * chained root plus the executed subset. A deterministic per-main-block gas budget bounds the
     * total work: once the sum of tx gas limits would exceed {@code blockGasLimit}, further refs are
     * skipped (no receipt, absent from the tx list) so they stay executable in a later block. This
     * caps the synchronous EVM work one block can force onto the import thread.
     */
    private ExecutionOutcome executeList(List<Hash> txHashes, long height, long timestampSeconds,
                                         Bytes32 previousRoot) {
        RocksDbWorldUpdater root = new RocksDbWorldUpdater(stateStore);
        List<Bytes> digest = new ArrayList<>(txHashes.size() + 1);
        digest.add(previousRoot);
        List<Hash> executed = new ArrayList<>(txHashes.size());
        long gasBudget = blockGasLimit;
        for (Hash txHash : txHashes) {
            Bytes blob = txStore.get(txHash).orElse(null);
            if (blob == null) {
                // Only reachable in replay if EVM_TX was externally damaged; keep the trace honest.
                log.error("EVM tx blob vanished for {} at height {}", txHash, height);
                continue;
            }
            long txGasLimit = peekGasLimit(blob); // -1 when undecodable (executeOne records the failure)
            if (txGasLimit > gasBudget) {
                log.warn("EVM tx {} gas limit {} exceeds remaining block budget {} at height {}; skipping",
                        txHash, txGasLimit, gasBudget, height);
                continue;
            }
            if (txGasLimit > 0) {
                gasBudget -= txGasLimit;
            }
            EvmReceipt receipt = executeOne(root, blob, height, timestampSeconds);
            metaStore.putReceipt(txHash, receipt);
            executed.add(txHash);
            digest.add(Bytes.concatenate(txHash.getBytes(),
                    Bytes.of((byte) receipt.status()),
                    Bytes.ofUnsignedLong(receipt.gasUsed())));
        }
        // Fold the persisted world-state delta into the chained root (Phase 1): the digest is a keccak
        // over exactly the (puts, deletes) this commit writes, so two nodes diverging on any balance or
        // storage — even with identical (txHash, status, gasUsed) — now produce different roots, which
        // the replay self-check in rollbackTo can detect.
        digest.add(root.commitAndDigest());
        Bytes32 chainedRoot =
                org.hyperledger.besu.crypto.Hash.keccak256(Bytes.concatenate(digest.toArray(new Bytes[0])));
        return new ExecutionOutcome(chainedRoot, executed);
    }

    /** Decodes just the gas limit for budget accounting; -1 if the blob cannot be decoded. */
    private static long peekGasLimit(Bytes blob) {
        try {
            return EvmTransaction.decode(blob).getGasLimit();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * Executes a single tx. Validation failure (undecodable, wrong chain, bad nonce, value not
     * covered, gas out of bounds) produces a status-0 receipt with zero gas and no state change —
     * the tx stays part of consensus history but burns nothing (v1; Ethereum-style gas burn needs
     * the P1 wei settlement).
     */
    private EvmReceipt executeOne(RocksDbWorldUpdater root, Bytes rawRlp, long height,
                                  long timestampSeconds) {
        EvmTransaction tx;
        try {
            tx = EvmTransaction.decode(rawRlp);
        } catch (RuntimeException e) {
            return validationFailure("undecodable blob", e.getMessage());
        }
        if (!chainId.equals(tx.getChainId())) {
            return validationFailure("wrong chain id", tx.getChainId().toString());
        }
        Address sender;
        try {
            sender = tx.getSender();
        } catch (RuntimeException e) {
            return validationFailure("signature recovery failed", e.getMessage());
        }
        if (tx.getGasLimit() > blockGasLimit) {
            return validationFailure("gas limit above block gas limit", String.valueOf(tx.getGasLimit()));
        }
        long intrinsicGas;
        try {
            intrinsicGas = IntrinsicGas.compute(tx.getPayload(), tx.isContractCreation());
        } catch (IllegalArgumentException e) {
            return validationFailure("oversized initcode", e.getMessage());
        }
        if (intrinsicGas > tx.getGasLimit()) {
            return validationFailure("intrinsic gas above tx gas limit", String.valueOf(intrinsicGas));
        }
        // Re-check the gas-price floor at execution — the pool enforces minGasPrice, but a hand-crafted
        // carrier can reference a tx that skipped the pool, so execution must not trust it. Rejecting
        // anything below minGasPrice (and explicitly zero, in case a network sets minGasPrice = 0)
        // stops an underpriced tx from buying near-free compute.
        BigInteger gasPriceWei = tx.getGasPrice().getAsBigInteger();
        if (gasPriceWei.signum() == 0 || gasPriceWei.compareTo(minGasPrice) < 0) {
            return validationFailure("gas price below minimum", tx.getGasPrice().toString());
        }
        Account senderAccount = root.getAccount(sender);
        long accountNonce = senderAccount == null ? 0L : senderAccount.getNonce();
        Wei balance = senderAccount == null ? Wei.ZERO : senderAccount.getBalance();
        if (tx.getNonce() != accountNonce) {
            return validationFailure("nonce mismatch",
                    tx.getNonce() + " vs account " + accountNonce);
        }
        // Gas settles in EVM wei (缺口2 / Path α): the sender must cover value + the maximum gas fee
        // (gasLimit * gasPrice). The full fee is debited upfront and the unused gas refunded after
        // execution, so a revert or out-of-gas still pays for the gas it burned. The net charge
        // (gasUsed * gasPrice) is burned, not credited to a coinbase (v1; ADR-007 routing is P2).
        BigInteger maxFee = gasPriceWei.multiply(BigInteger.valueOf(tx.getGasLimit()));
        if (balance.getAsBigInteger().compareTo(tx.getValue().getAsBigInteger().add(maxFee)) < 0) {
            return validationFailure("balance below value + gas fee", balance.toString());
        }
        SimpleBlockValues blockValues = new SimpleBlockValues();
        blockValues.setNumber(height);
        blockValues.setTimestamp(timestampSeconds);
        blockValues.setGasLimit(blockGasLimit);
        // Message execution gets whatever gas survives the intrinsic charge. The single most common
        // Ethereum tx — a 21000-gas value transfer — leaves exactly zero, which is legal: it moves
        // value to a codeless account with no opcodes to run. Anything that must run code (a CREATE,
        // or a CALL to a contract) needs at least one gas unit and fails out-of-gas at zero.
        long messageGas = tx.getGasLimit() - intrinsicGas;

        // Defense in depth (C1): the upfront gas debit, execution, AND the refund all run inside this
        // guard, so no balance-arithmetic or interpreter error can escape into setMain and abort native
        // consensus — any unexpected failure degrades to a status-0 receipt. The debit and refund are
        // provably non-underflowing (the affordability check above; the refund is additive), so the
        // reachable paths (success, revert, out-of-gas) behave exactly as before.
        try {
            adjustBalance(root, sender, maxFee.negate()); // upfront gas debit, charged even on revert/OOG
            EvmReceipt receipt;
            if (tx.isContractCreation()) {
                if (messageGas <= 0L) {
                    bumpNonce(root, sender); // Ethereum still burns the nonce on a failed create
                    receipt = failedReceipt(intrinsicGas);
                } else {
                    // deploy() bumps + commits the sender nonce itself, even when execution fails (S-24).
                    XdagExecutionResult result = executor.deploy(root.updater(), sender, tx.getPayload(),
                            tx.getValue(), messageGas, blockValues, Address.ZERO);
                    receipt = receiptOf(result, intrinsicGas);
                }
            } else {
                // CALL. Ethereum bumps the sender nonce before executing, and a revert keeps the bump:
                // apply it on the root journal so discarding the per-tx child cannot undo it.
                bumpNonce(root, sender);
                Address to = tx.getTo().orElseThrow();
                if (messageGas <= 0L) {
                    receipt = zeroGasCall(root, sender, to, tx.getValue(), intrinsicGas);
                } else {
                    XdagExecutionResult result = executor.call(root.updater(), sender, to, tx.getPayload(),
                            tx.getValue(), messageGas, blockValues, Address.ZERO);
                    receipt = receiptOf(result, intrinsicGas);
                }
            }
            // Refund the gas the tx did not consume; the sender's net gas cost is gasUsed * gasPrice.
            long gasUsed = Math.min(receipt.gasUsed(), tx.getGasLimit());
            BigInteger refund = gasPriceWei.multiply(BigInteger.valueOf(tx.getGasLimit() - gasUsed));
            if (refund.signum() > 0) {
                adjustBalance(root, sender, refund);
            }
            return receipt;
        } catch (RuntimeException e) {
            log.error("EVM execution threw for a tx at height {}; recording a failed receipt to "
                    + "protect native consensus", height, e);
            return failedReceipt(intrinsicGas);
        }
    }

    /**
     * Adds {@code deltaWei} (may be negative) to {@code sender}'s balance on the root journal. Callers
     * must ensure the result is non-negative (the executeOne affordability check does). If it is not,
     * this throws a descriptive error instead of {@code Wei.of}'s opaque one; because every caller runs
     * inside executeOne's C1 guard, that throw degrades to a status-0 receipt rather than aborting
     * native consensus.
     */
    private static void adjustBalance(RocksDbWorldUpdater root, Address sender, BigInteger deltaWei) {
        MutableAccount account = root.getOrCreate(sender);
        BigInteger updated = account.getBalance().getAsBigInteger().add(deltaWei);
        if (updated.signum() < 0) {
            throw new IllegalStateException("EVM balance underflow for " + sender + ": "
                    + account.getBalance().getAsBigInteger() + " + (" + deltaWei + ")");
        }
        account.setBalance(Wei.of(updated));
    }

    private EvmReceipt validationFailure(String reason, String detail) {
        log.warn("EVM tx validation failed ({}): {}", reason, detail);
        return new EvmReceipt(0, 0L, Optional.empty(), List.of());
    }

    private static EvmReceipt receiptOf(XdagExecutionResult result, long intrinsicGas) {
        return new EvmReceipt(result.success() ? 1 : 0, intrinsicGas + result.gasUsed(),
                result.createdContract(), result.logs());
    }

    /** A failed message that still consumed intrinsic gas (no state, no contract, no logs). */
    private static EvmReceipt failedReceipt(long intrinsicGas) {
        return new EvmReceipt(0, intrinsicGas, Optional.empty(), List.of());
    }

    private static void bumpNonce(RocksDbWorldUpdater root, Address sender) {
        MutableAccount account = root.getOrCreate(sender);
        account.setNonce(account.getNonce() + 1);
    }

    /**
     * A message call with no gas left to run code: succeeds as a bare value transfer to a codeless
     * account, otherwise fails out-of-gas. The sender nonce was already bumped by the caller.
     */
    private EvmReceipt zeroGasCall(RocksDbWorldUpdater root, Address sender, Address to, Wei value,
                                   long intrinsicGas) {
        Account target = root.getAccount(to);
        boolean hasCode = target != null && target.getCode() != null && !target.getCode().isEmpty();
        if (hasCode) {
            return failedReceipt(intrinsicGas); // executing the code would need gas we don't have
        }
        WorldUpdater child = root.updater();
        MutableAccount from = child.getOrCreate(sender);
        MutableAccount recipient = child.getOrCreate(to);
        from.setBalance(from.getBalance().subtract(value));
        recipient.setBalance(recipient.getBalance().add(value));
        child.commit();
        return new EvmReceipt(1, intrinsicGas, Optional.empty(), List.of());
    }
}
