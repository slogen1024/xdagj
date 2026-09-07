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
import io.xdag.evm.bridge.BridgeConstants;
import io.xdag.evm.bridge.BridgeContract;
import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.bridge.BridgeWithdrawal;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.EvmStateSchema;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.evm.tx.IntrinsicGas;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
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
 * the sender is debited gasLimit*effectiveGasPrice upfront, refunded the unused gas, and the net
 * gasUsed*effectiveGasPrice is burned (no coinbase credit yet — P2); a ref whose blob is absent is
 * deterministically skipped;
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
    /** Height at which type-2 (EIP-1559) txs become executable (defect-1 fork gate). */
    private final long type2ActivationHeight;
    /** Height at which the XDAG<->EVM bridge activates; MAX_VALUE = not scheduled (no seeding). */
    private final long bridgeActivationHeight;
    /** Height at which EIP-3529 net storage refunds apply; MAX_VALUE = not scheduled (no refund). */
    private final long eip3529ActivationHeight;
    /** Audit round 2 P3 gate: from this height validation-failed refs are dropped, not receipted. */
    private final long invalidTxSkipActivationHeight;
    private final KVSource<byte[], byte[]> stateStore;
    private final EvmTxStore txStore;
    private final EvmMetaStore metaStore;
    /** Genesis pre-funding seeded once into EVM_STATE by {@link #seedGenesisIfAbsent()} (on-ramp). */
    private final List<GenesisAllocEntry> genesisAlloc;
    /** Reverse-delta journal for bounded historical state (C4); null disables history capture. */
    private final EvmStateJournal journal;
    /** How many recent heights of reverse journals to retain (C4 window). */
    private final int historyWindow;
    /** Floor for the matured-entry archive retention (C1) when no history window is configured. */
    private static final int MIN_ARCHIVE_RETENTION = 64;
    /** Optional late-bound observer for WebSocket subscriptions (C6); null when no WS server runs. */
    private volatile EvmSubscriptionSink subscriptionSink;

    /**
     * Domain-separated marker folded into a committed-skip height's chained root (ADR-015 / G2-T1b) in
     * place of the per-tx digest entries, so a skipped height's root is deterministic and distinct from
     * an executed or empty height. A fixed keccak of an ASCII domain string — identical on every node.
     */
    private static final Bytes DA_SKIP_SENTINEL = org.hyperledger.besu.crypto.Hash.keccak256(
            Bytes.wrap("XDAG-EVM-DA-SKIP-v1".getBytes(StandardCharsets.US_ASCII)));

    public void setSubscriptionSink(EvmSubscriptionSink sink) {
        this.subscriptionSink = sink;
    }

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
        this(config, stateStore, txStore, metaStore, activationHeight, genesisAlloc, null, 0);
    }

    public EvmBlockProcessor(EvmConfig config, KVSource<byte[], byte[]> stateStore,
                             EvmTxStore txStore, EvmMetaStore metaStore, long activationHeight,
                             List<GenesisAllocEntry> genesisAlloc, EvmStateJournal journal,
                             int historyWindow) {
        this.executor = new XdagEvmExecutor(config);
        this.chainId = config.chainId();
        this.blockGasLimit = config.maxGasLimit();
        this.minGasPrice = config.minGasPrice();
        this.activationHeight = activationHeight;
        this.type2ActivationHeight = config.type2ActivationHeight();
        this.bridgeActivationHeight = config.bridgeActivationHeight();
        this.eip3529ActivationHeight = config.eip3529ActivationHeight();
        this.invalidTxSkipActivationHeight = config.invalidTxSkipActivationHeight();
        this.stateStore = stateStore;
        this.txStore = txStore;
        this.metaStore = metaStore;
        this.genesisAlloc = List.copyOf(genesisAlloc);
        this.journal = journal;
        this.historyWindow = historyWindow;
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
     * Seeds the bridge withdrawal contract's fixed runtime bytecode at its protocol address once
     * per chain lifetime (spec §3.1), marker-guarded exactly like the genesis allocation and, like
     * it, OUTSIDE the chained root (protocol state agreed out-of-band). No-op unless the bridge is
     * scheduled — an always-on seed would plant contract code into every unrelated test state.
     * rollbackTo's reset wipes marker + code; the restore call re-seeds before replay.
     */
    public synchronized void seedBridgeContractIfAbsent() {
        if (bridgeActivationHeight == Long.MAX_VALUE) {
            return;
        }
        if (stateStore.get(EvmStateSchema.bridgeContractMarkerKey()) != null) {
            return;
        }
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateStore);
        world.getOrCreate(BridgeContract.ADDRESS).setCode(BridgeContract.RUNTIME_BYTECODE);
        world.commit();
        stateStore.put(EvmStateSchema.bridgeContractMarkerKey(), new byte[]{1});
    }

    /** Deposit-less entry: a confirmed main block that carries only EVM tx refs. */
    public synchronized BigInteger processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds,
                                              Bytes32 blockHash) {
        return processMainBlock(txRefs, height, timestampSeconds, blockHash, List.of());
    }

    /**
     * Called from {@code setMain} for each confirmed main block that carries EVM tx refs and/or
     * confirmed bridge deposits.
     *
     * <p>Execution is strictly in height order and requires every referenced blob to be present. If
     * a blob is missing — or an earlier height is already stalled waiting for one — the block is
     * <b>deferred</b> (persisted to the pending queue), NOT skipped. Skipping would let a node that
     * has the blob and one that doesn't produce different state forever; deferring instead leaves
     * the blob-less node merely <i>behind</i>, and {@link #onBlobsAvailable()} resumes execution in
     * order once the blobs arrive. Native consensus is unaffected either way (I4). Deposits are
     * persisted to EVM_META 0x06 BEFORE any defer, so a stalled height still mints them when it
     * later drains (spec §2.2).
     */
    public synchronized BigInteger processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds,
                                              Bytes32 blockHash, List<BridgeDeposit> deposits) {
        List<Bytes32> refs = txRefs == null ? List.of() : txRefs;
        boolean hasRefs = !refs.isEmpty();
        int depositCount = deposits == null ? 0 : deposits.size();
        boolean hasDeposits = depositCount > 0;
        // A committed-skip height (G2-T1b) must reach executeAndCheckpoint to fold its SKIP_SENTINEL,
        // so it is not swallowed by this empty-payload early-return. In production the skip path enters
        // via skipMaturedHeight -> executeAndCheckpoint (never here) and always passes empty refs; this
        // guard only keeps the direct processMainBlock entry point coherent for a marked height. Note a
        // marked height carrying non-empty refs whose blob is missing would defer below rather than skip
        // (harmless today: production skips carry no refs; do NOT route marked+refs heights here).
        boolean skipMarked = metaStore.isSkipped(height);
        if (!hasRefs && !hasDeposits && !skipMarked) {
            return BigInteger.ZERO;
        }
        if (height < activationHeight) {
            // Before the EVM hard fork nothing here has consensus meaning (spec §3.1). The caller
            // gates deposits by its own bridgeActivationHeight; this guard only covers a bridge
            // scheduled before the EVM itself — a nonsensical config; ignoring is deterministic.
            log.warn("Ignoring EVM payload ({} ref(s), {} deposit(s)) in pre-activation main block "
                    + "at height {} (activates at {})", refs.size(), depositCount, height, activationHeight);
            return BigInteger.ZERO;
        }
        if (hasDeposits) {
            // Write-once per height, BEFORE any defer: a stalled height must still mint its
            // deposits when it later drains (executeList reads the 0x06 record).
            metaStore.putDeposits(height, deposits);
        }
        Expansion exp = expandRefs(refs); // an empty refs list expands to a complete, all-empty Expansion
        if (!metaStore.pendingHeights().isEmpty() || !exp.complete()) {
            metaStore.putPending(height, blockHash, timestampSeconds, refs);
            log.warn("Deferring EVM execution of main block at height {} ({} ref(s), {} deposit(s)) "
                    + "until blobs arrive", height, refs.size(), depositCount);
            return BigInteger.ZERO;
        }
        return executeAndCheckpoint(exp.flat(), height, timestampSeconds, blockHash);
    }

    /**
     * Gate 2 (G2-T1a/T1b): the setMain entry point under delta-lagged execution. Buffers this confirmed
     * main block's EVM execution inputs and, if some earlier height has now reached finality depth
     * {@code lag}, executes that MATURED height ({@code confirmedHeight - lag + 1}) via the unchanged
     * {@link #processMainBlock}. EVM therefore touches only heights that are {@code lag - 1}
     * confirmations deep, so shallow reorgs never rewrite executed EVM state and the lagged
     * {@code root(N - lag)} an honest miner anchored is always reproducible. At {@code lag == 1} this
     * reduces to immediate execution, byte-identical to the pre-Gate-2 path.
     *
     * <p>MUST be called for every confirmed main block (even payload-free ones) so buffered heights
     * actually mature. Pre-activation heights preserve the old behavior exactly (a payload-bearing
     * pre-activation block is handed straight to {@code processMainBlock}, which warns and returns).
     * A genuinely-missing blob at maturity still defers through the existing pending queue.
     *
     * <p>The {@code daSkip} bit (block-committed for the matured height) is honored: when true the
     * matured height is skipped (no tx execution, deposits still mint, SKIP_SENTINEL checkpoint)
     * unconditionally; when false it executes as before. This ensures a node lacking the blob and one
     * holding it converge on the same skipped root (ADR-015 / G2-T1b).
     *
     * <p>Note: {@code activationHeight} here is the EVM hard-fork height ({@code evm.activationHeight}),
     * which gates whether EVM executes at all — distinct from the state-root <i>anchor</i> activation
     * ({@code evm.stateRootActivationHeight}) that gates anchor verification in {@code BlockchainImpl.setMain}.
     */
    public synchronized BigInteger processConfirmedBlock(List<Bytes32> refs, long confirmedHeight,
            long timestampSeconds, Bytes32 blockHash, List<BridgeDeposit> deposits, long lag,
            boolean daSkip) {
        List<Bytes32> safeRefs = refs == null ? List.of() : refs;
        List<BridgeDeposit> safeDeposits = deposits == null ? List.of() : deposits;
        boolean hasPayload = !safeRefs.isEmpty() || !safeDeposits.isEmpty();
        if (confirmedHeight < activationHeight) {
            // Pre-activation: identical to the old direct call (processMainBlock warns + returns).
            if (hasPayload) {
                return processMainBlock(safeRefs, confirmedHeight, timestampSeconds, blockHash, safeDeposits);
            }
            return BigInteger.ZERO;
        }
        if (hasPayload) {
            metaStore.putMaturityEntry(confirmedHeight, blockHash, timestampSeconds, safeRefs, safeDeposits);
        }
        long matured = maturedEvmHeight(confirmedHeight, lag);
        if (matured < activationHeight) {
            return BigInteger.ZERO; // still filling the initial lag-1 window (or below genesis)
        }
        Optional<EvmMetaStore.MaturityEntry> entryOpt = metaStore.getMaturityEntry(matured);
        if (entryOpt.isEmpty()) {
            return BigInteger.ZERO;
        }
        EvmMetaStore.MaturityEntry entry = entryOpt.get();
        // C1: archive (not delete) the matured inputs so a reorg that unwinds THIS deciding block can
        // re-open the height for the replacement block's decision (rollbackForReorg). Prune archives
        // older than any re-openable reorg.
        metaStore.archiveMaturityEntry(matured);
        metaStore.removeArchivedAtOrBelow(matured - archiveRetention());
        if (daSkip) {
            skipMaturedHeight(matured, entry);
            return BigInteger.ZERO; // a skipped height executes no txs -> no fee
        }
        return processMainBlock(entry.refs(), matured, entry.timestampSeconds(), entry.blockHash(),
                entry.deposits());
    }

    /**
     * Honors a block-committed skip (ADR-015 / G2-T1b) for a matured height: the buffered txs do NOT
     * execute (regardless of whether this node holds their blob), but the height's deposits still mint
     * and it checkpoints with the canonical SKIP_SENTINEL root. Persisting the skip marker BEFORE the
     * checkpoint makes {@code executeList} fold the sentinel, and makes {@code rollbackTo} replay
     * reproduce it. Called only from {@link #processConfirmedBlock}.
     */
    private void skipMaturedHeight(long height, EvmMetaStore.MaturityEntry entry) {
        if (!entry.deposits().isEmpty()) {
            metaStore.putDeposits(height, entry.deposits()); // deposits mint on skip (native facts)
        }
        metaStore.putSkipMarker(height);
        if (!metaStore.pendingHeights().isEmpty()) {
            // Audit round 2, C2: an earlier height is still blob-deferred. Checkpointing now would chain
            // this height from latestRoot() AHEAD of it (and mint this height's deposits before the
            // earlier height's txs run). Queue it with empty refs instead -- the same ordering gate the
            // include path applies -- so onBlobsAvailable drains it in height order and
            // executeAndCheckpoint folds the SKIP_SENTINEL from the marker exactly as replay does.
            metaStore.putPending(height, entry.blockHash(), entry.timestampSeconds(), List.of());
            log.warn("Deferring committed-skip checkpoint of main block at height {} behind blob-deferred "
                    + "height(s) {}", height, metaStore.pendingHeights());
            return;
        }
        // The buffered refs are ignored (List.of()): executeAndCheckpoint sees the skip marker and
        // folds SKIP_SENTINEL instead of executing.
        executeAndCheckpoint(List.of(), height, entry.timestampSeconds(), entry.blockHash());
    }

    /**
     * Resumes deferred execution after new blobs are stored (e.g. an EVM_TX_REPLY over P2P). Runs
     * stalled heights in ascending order for as long as each one's blobs are all present, stopping
     * at the first still-incomplete height so ordering is never violated.
     */
    public synchronized List<DrainedHeight> onBlobsAvailable() {
        List<DrainedHeight> drained = new ArrayList<>();
        for (long height : metaStore.pendingHeights()) {
            EvmMetaStore.PendingBlock pending = metaStore.getPending(height).orElse(null);
            if (pending == null) {
                continue;
            }
            Expansion exp = expandRefs(pending.refs());
            if (!exp.complete()) {
                break; // the lowest incomplete height blocks everything above it
            }
            java.math.BigInteger feeWei =
                    executeAndCheckpoint(exp.flat(), height, pending.timestampSeconds(), pending.blockHash());
            metaStore.removePending(height);
            drained.add(new DrainedHeight(height, feeWei));
        }
        return drained;
    }

    /** Dual-lookup expansion of raw 0x0F refs (spec §7). */
    private record Expansion(List<Bytes32> flat, List<Bytes32> unknownRefs,
                             List<Bytes32> missingTxBlobs) {
        boolean complete() {
            return unknownRefs.isEmpty() && missingTxBlobs.isEmpty();
        }
    }

    /**
     * Interprets each ref by content (spec §2): a stored batch body expands to its ordered tx
     * hashes; otherwise a stored tx blob is a legacy single-tx ref. A ref matching neither store
     * is "unknown" — it may be either kind, so the P2P retry asks for both. Deterministic across
     * nodes because a 32-byte value cannot be both keccak(tx RLP) and keccak(batch body).
     */
    private Expansion expandRefs(List<Bytes32> txRefs) {
        List<Bytes32> flat = new ArrayList<>();
        List<Bytes32> unknown = new ArrayList<>();
        List<Bytes32> missingTxBlobs = new ArrayList<>();
        for (Bytes32 ref : txRefs) {
            Hash asHash = Hash.wrap(ref);
            Optional<List<Bytes32>> batch = txStore.getBatch(asHash);
            if (batch.isEmpty()) {
                // Audit round 2, P1 (defense in depth): classification is by content. Bytes stored under
                // this ref in the tx keyspace that parse as a batch body ARE that batch (a signed tx can
                // never parse as one), whatever path put them there.
                batch = txStore.get(asHash).flatMap(EvmTxStore::decodeBatchBody);
            }
            if (batch.isPresent()) {
                for (Bytes32 member : batch.get()) {
                    flat.add(member);
                    if (!txStore.contains(Hash.wrap(member))) {
                        missingTxBlobs.add(member);
                    }
                }
            } else if (txStore.contains(asHash)) {
                flat.add(ref);
            } else {
                unknown.add(ref);
            }
        }
        return new Expansion(flat, unknown, missingTxBlobs);
    }

    /**
     * The distinct tx hashes (and ambiguous refs) that deferred heights still lack, for EVM_TX_REQUEST
     * retry. Includes expanded member hashes whose blobs are absent, plus unknown refs that may be
     * either a legacy single tx or a batch commitment. Lowest height first, in ref order.
     * Empty when nothing is pending or all blobs are present.
     */
    public synchronized List<Bytes32> pendingMissingBlobHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachPendingExpansion(exp -> {
            exp.missingTxBlobs().forEach(h -> { if (seen.add(h)) missing.add(h); });
            exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); }); // may be a legacy single tx
        });
        return missing;
    }

    /** True if some deferred (blob-stalled) height at or below {@code height} is still unexecuted. */
    public synchronized boolean hasUnexecutedHeightAtOrBelow(long height) {
        for (long pending : metaStore.pendingHeights()) {
            if (pending <= height) {
                return true;
            }
        }
        return false;
    }

    /** Ambiguous refs that may be batch commitments, for EVM_BATCH_REQUEST retry. */
    public synchronized List<Bytes32> pendingMissingBatchHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachPendingExpansion(exp ->
                exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); }));
        return missing;
    }

    private void forEachPendingExpansion(Consumer<Expansion> fn) {
        for (long height : metaStore.pendingHeights()) {
            metaStore.getPending(height).ifPresent(p -> fn.accept(expandRefs(p.refs())));
        }
    }

    private void forEachBufferedExpansion(Consumer<Expansion> fn) {
        for (long height : metaStore.maturityHeights()) {
            metaStore.getMaturityEntry(height).ifPresent(e -> fn.accept(expandRefs(e.refs())));
        }
    }

    /**
     * Distinct tx hashes (and ambiguous refs) that BUFFERED (in-delta-window, not-yet-matured) heights
     * lack, for proactive EVM_TX_REQUEST fetch inside the delta window (G2-T2). Mirrors {@link
     * #pendingMissingBlobHashes()} over the maturity buffer. Ascending height, ref order, deduped
     * within this buffered scan (a ref may also appear in {@link #pendingMissingBlobHashes()} — the
     * retry tick unions the two).
     */
    public synchronized List<Bytes32> bufferedMissingBlobHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachBufferedExpansion(exp -> {
            exp.missingTxBlobs().forEach(h -> { if (seen.add(h)) missing.add(h); });
            exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); });
        });
        return missing;
    }

    /** Ambiguous (possibly-batch) refs a buffered height lacks, for proactive EVM_BATCH_REQUEST (G2-T2). */
    public synchronized List<Bytes32> bufferedMissingBatchHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachBufferedExpansion(exp ->
                exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); }));
        return missing;
    }

    /**
     * True if some pending (deferred) OR buffered (in-delta-window) height still lacks this tx blob
     * (or has it as an unknown ref). The ingest gate (only store a solicited blob): broadened in
     * G2-T2 to accept a buffered height's blob so a proactively-fetched reply is not dropped.
     */
    public synchronized boolean isAwaitingBlob(Hash txHash) {
        Bytes32 target = Bytes32.wrap(txHash.getBytes());
        return anyAwaitingExpansion(
                exp -> exp.missingTxBlobs().contains(target) || exp.unknownRefs().contains(target));
    }

    /** True if some pending OR buffered height has this hash as an ambiguous (possibly-batch) ref. */
    public synchronized boolean isAwaitingBatch(Hash batchHash) {
        Bytes32 target = Bytes32.wrap(batchHash.getBytes());
        return anyAwaitingExpansion(exp -> exp.unknownRefs().contains(target));
    }

    /** Tests {@code test} against the expansion of every pending then buffered height's refs. */
    private boolean anyAwaitingExpansion(Predicate<Expansion> test) {
        for (long height : metaStore.pendingHeights()) {
            EvmMetaStore.PendingBlock p = metaStore.getPending(height).orElse(null);
            if (p != null && test.test(expandRefs(p.refs()))) {
                return true;
            }
        }
        for (long height : metaStore.maturityHeights()) {
            EvmMetaStore.MaturityEntry e = metaStore.getMaturityEntry(height).orElse(null);
            if (e != null && test.test(expandRefs(e.refs()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes a confirmed (or drained) main block's expanded refs and writes its EVM_META checkpoint.
     * {@code flatRefs} must already be the fully-expanded flat list (batch members inlined, no unknown
     * refs) — callers are responsible for running {@link #expandRefs} and gating on
     * {@link Expansion#complete()} before invoking this method. A height with deposits checkpoints
     * even when it has no executable candidates.
     */
    private BigInteger executeAndCheckpoint(List<Bytes32> flatRefs, long height, long timestampSeconds,
                                      Bytes32 blockHash) {
        seedGenesisIfAbsent(); // fund the genesis accounts before the first tx reads their balance
        seedBridgeContractIfAbsent(); // and (when scheduled) the bridge contract before any tx calls it
        List<Hash> candidates = new ArrayList<>(flatRefs.size());
        Set<Hash> seen = new HashSet<>();
        for (Bytes32 refBytes : flatRefs) {
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
                // Guarded against by the expandRefs(...).complete() gate in the caller; only reachable
                // if the blob was evicted between the completeness check and here.
                log.error("EVM tx blob vanished for ref {} at height {}; skipping", txHash, height);
            }
        }
        // A deposit-carrying or skip-marked height MUST checkpoint even with zero executable txs
        // (spec §2.2 and ADR-015): a deposit is a state change; a skip folds the SKIP_SENTINEL so
        // the chained root is deterministic and distinct from an executed or empty height.
        boolean hasDeposits = !metaStore.getDeposits(height).isEmpty();
        boolean skipped = metaStore.isSkipped(height);
        if (candidates.isEmpty() && !hasDeposits && !skipped) {
            return BigInteger.ZERO;
        }
        List<Hash> toExecute = skipped ? List.of() : candidates;
        ExecutionOutcome outcome = executeList(toExecute, height, timestampSeconds, latestRoot());
        if (outcome.executed().isEmpty() && !hasDeposits && !skipped) {
            // Everything was over-budget — no state changed, no checkpoint.
            return BigInteger.ZERO;
        }
        metaStore.putHeightRecord(height, outcome.root(), blockHash, outcome.executed().size(),
                timestampSeconds);
        metaStore.putTxList(height, outcome.executed());
        // C6: deliver this height's logs to the WS subscription layer (removed=false). Read the sink
        // into a local so a concurrent setSubscriptionSink(null) cannot NPE mid-method.
        EvmSubscriptionSink sink = subscriptionSink;
        if (sink != null) {
            List<EvmSubscriptionSink.LogRecord> records = collectLogRecords(height);
            if (!records.isEmpty()) {
                sink.onLogs(height, blockHash, records, false);
            }
        }
        log.info("EVM main block {}: executed {} tx(s), root {}", height, outcome.executed().size(),
                outcome.root());
        return outcome.netFeeWei();
    }

    /**
     * All of {@code height}'s emitted logs with their (txHash, txIndex, logIndex) coordinates. The
     * numbering (txIndex per-tx, logIndex per-height across all logs) MUST match
     * {@code EthRequestHandler.getLogs} so a WS {@code logs} subscription and {@code eth_getLogs} agree.
     * Unlike getLogs this collects unconditionally — the SubscriptionManager applies the per-sub filter.
     */
    private List<EvmSubscriptionSink.LogRecord> collectLogRecords(long height) {
        List<EvmSubscriptionSink.LogRecord> records = new ArrayList<>();
        List<Hash> txHashes = metaStore.getTxList(height);
        int logIndex = 0;
        for (int i = 0; i < txHashes.size(); i++) {
            Hash txHash = txHashes.get(i);
            EvmReceipt receipt = metaStore.getReceipt(txHash).orElse(null);
            if (receipt == null) {
                continue;
            }
            for (Log log : receipt.logs()) {
                records.add(new EvmSubscriptionSink.LogRecord(log, txHash, i, logIndex));
                logIndex++;
            }
        }
        return records;
    }

    /**
     * Reorg handling (Option A): truncate EVM_META above {@code height}, wipe the world state, and
     * replay every remaining checkpointed height from the EVM_TX blobs. A replayed chained root that
     * differs from its checkpoint indicates nondeterminism or corruption and is logged loudly.
     */
    public synchronized void rollbackTo(long height) {
        rollbackTo(height, height);
    }

    /**
     * Reorg entry point under delta-lagged execution (audit round 2, C1). Height M's outcome (execute
     * or skip) is DECIDED by block M+lag-1. When main blocks {@code >= lowestUnwoundMainHeight} are
     * unwound, every height they decided -- {@code (lowestUnwound - lag, lowestUnwound - 1]}, all still
     * canonical natively -- must be un-executed and re-buffered so the replacement chain's blocks
     * re-decide them under their own committed daSkip bits. Pre-fix, rolling back to
     * {@code lowestUnwound - 1} left the orphaned block's decision pinned: nodes that had imported the
     * orphan diverged forever from nodes that had not. At lag=1 this is exactly the legacy
     * {@code rollbackTo(lowestUnwound - 1)}. The caller (BlockchainImpl.unWindMain) reverses the native
     * fee credits of the re-opened heights BEFORE calling this (their 0x0B journals are then gone).
     */
    public synchronized void rollbackForReorg(long lowestUnwoundMainHeight, long lag) {
        if (lag < 1) {
            throw new IllegalArgumentException("evm.stateRootLag must be >= 1 (got " + lag + ")");
        }
        long nativeBoundary = lowestUnwoundMainHeight - 1;
        long executionBoundary = lowestUnwoundMainHeight - lag;
        long highestBefore = metaStore.highestHeight().orElse(-1L);
        rollbackTo(executionBoundary, nativeBoundary);
        for (long h = executionBoundary + 1; h <= nativeBoundary; h++) {
            if (metaStore.restoreMaturityEntry(h)) {
                log.info("EVM reorg re-opened matured height {} (decided by unwound block {}); the "
                        + "replacement chain re-decides it", h, h + lag - 1);
            } else if (highestBefore >= h && h + archiveRetention() <= highestBefore) {
                log.error("CRITICAL: EVM reorg re-opened height {} but its matured-entry archive was "
                        + "pruned (retention {} heights); if that height carried an EVM payload this node "
                        + "cannot re-mature it and must re-sync", h, archiveRetention());
            }
        }
    }

    /** Archived matured entries are kept for this many heights: the deepest re-openable reorg. */
    private long archiveRetention() {
        return Math.max(historyWindow, MIN_ARCHIVE_RETENTION);
    }

    private void rollbackTo(long executionBoundary, long nativeBoundary) {
        long height = executionBoundary;
        log.info("EVM rollback to main height {} (native journals kept through {})", height, nativeBoundary);
        // C6: re-emit each reorged-out height's logs with removed=true, sourced from EVM_META BEFORE the
        // wipe below deletes them, so re-filtering reproduces exactly the delivered set. Read the sink
        // into a local first so a concurrent setSubscriptionSink(null) cannot NPE mid-method.
        EvmSubscriptionSink sink = subscriptionSink;
        if (sink != null) {
            for (long h : metaStore.txListHeights()) {
                if (h <= height) {
                    continue;
                }
                List<EvmSubscriptionSink.LogRecord> records = collectLogRecords(h);
                if (!records.isEmpty()) {
                    Bytes32 revertedHash = metaStore.getHeightRecord(h)
                            .map(EvmMetaStore.HeightRecord::blockHash).orElse(Bytes32.ZERO);
                    sink.onLogs(h, revertedHash, records, true);
                }
            }
        }
        metaStore.removeAbove(executionBoundary, nativeBoundary);
        stateStore.reset();
        if (journal != null) {
            journal.clear(); // Option A: replay below repopulates the window from genesis
        }
        seedGenesisIfAbsent(); // the reset wiped the marker + funded balances; restore before replay
        seedBridgeContractIfAbsent(); // likewise the bridge contract's code (its marker was wiped too)
        Bytes32 previousRoot = genesisRoot();
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

    /** The chained commitment of the most recent checkpoint, or the genesis origin before any EVM tx. */
    private Bytes32 latestRoot() {
        return metaStore.highestHeight()
                .flatMap(metaStore::getHeightRecord)
                .map(EvmMetaStore.HeightRecord::stateRoot)
                .orElse(genesisRoot());
    }

    /**
     * The chain origin: a deterministic keccak over the configured genesis allocation (each entry as
     * address(20) ‖ balance(32), sorted by address). A node misconfigured with a different {@code
     * evm.alloc} gets a different origin, so every subsequent chained root diverges immediately — the
     * genesis is now committed, not merely reflected once an account is touched. Pure function of the
     * config (independent of the store); an empty alloc yields keccak of empty, a fixed constant.
     */
    private Bytes32 genesisRoot() {
        List<Bytes> parts = new ArrayList<>(genesisAlloc.size());
        genesisAlloc.stream()
                .sorted((a, b) -> Arrays.compareUnsigned(a.address().getBytes().toArray(),
                        b.address().getBytes().toArray()))
                .forEach(e -> parts.add(Bytes.concatenate(e.address().getBytes(),
                        Bytes32.leftPad(e.balance().toBytes()))));
        return org.hyperledger.besu.crypto.Hash.keccak256(Bytes.concatenate(parts.toArray(new Bytes[0])));
    }

    /**
     * G2-T1c (miner): whether this node can make the EVM payload of the height that a block confirming
     * at {@code confirmedHeight} would mature ({@code confirmedHeight - lag + 1}) available -- i.e.
     * every buffered ref of that height expands to a present blob (no unknown or missing-blob refs).
     * Used at pack time to set the block's
     * committed {@code daSkip} bit: available -> include ({@code daSkip=false}); unavailable -> skip
     * ({@code daSkip=true}). Returns true (include) when nothing is buffered at the matured height:
     * there is nothing to skip. Lag-agnostic -- it has no lag=1 branch; the "lag=1 never skips"
     * property is the caller's, where at lag=1 the matured height is the unconfirmed block being mined
     * and is therefore never in the buffer.
     */
    public synchronized boolean maturedPayloadAvailable(long confirmedHeight, long lag) {
        long matured = maturedEvmHeight(confirmedHeight, lag);
        EvmMetaStore.MaturityEntry entry = metaStore.getMaturityEntry(matured).orElse(null);
        if (entry == null) {
            return true; // nothing buffered to skip -> include trivially
        }
        return expandRefs(entry.refs()).complete();
    }

    /**
     * The EVM height that a block confirming at {@code confirmedHeight} matures under delta-lagged
     * execution (Gate 2, G2-T1a): {@code confirmedHeight - lag + 1}. This is the unique index that
     * keeps G1's anchor semantics (block N commits root(N - lag)) while making that root exist at mine
     * time (see the Gate 2 design doc, section 1). {@code lag} must be >= 1 (config-enforced; mirrors
     * {@link io.xdag.core.BlockchainImpl#computeStateRootAnchor}). Callers must additionally guard the
     * result against activation/genesis (a result below the activation height matures nothing).
     */
    public static long maturedEvmHeight(long confirmedHeight, long lag) {
        if (lag < 1) {
            throw new IllegalArgumentException("evm.stateRootLag must be >= 1 (got " + lag + ")");
        }
        return confirmedHeight - lag + 1;
    }

    /**
     * The chained EVM state root as of {@code height}: the root of the highest checkpoint at height
     * {@code <= height}, or the genesis origin root if there is none. Deterministic given the node's
     * checkpoints — the miner (G1-T2) commits this at H-delta and the validator (G1-T3) recomputes the
     * same value. Empty/deposit-only heights inherit the prior checkpoint (the chained root only
     * advances on checkpointed heights).
     *
     * <p><b>Validator contract (G1-T3):</b> a block's {@code EvmStateAnchor.height} is the REQUESTED
     * lag height H-delta, which may be an empty/deposit-only height whose root actually comes from a
     * lower floor checkpoint. A validator MUST therefore recompute the anchor via {@code
     * chainedRootAt(anchor.height())} (same floor semantics), NEVER by looking up the checkpoint at
     * exactly that height -- otherwise honest empty/deposit-only lag heights would spuriously DIVERGE.
     */
    public synchronized Bytes32 chainedRootAt(long height) {
        return metaStore.highestHeightAtMost(height)
                .flatMap(metaStore::getHeightRecord)
                .map(EvmMetaStore.HeightRecord::stateRoot)
                .orElse(genesisRoot());
    }

    /** A height drained by {@link #onBlobsAvailable()} and the net EVM fee its execution settled. */
    public record DrainedHeight(long height, java.math.BigInteger netFeeWei) {
    }

    /** The world root plus the txs that actually executed (survived the per-block gas budget). */
    private record ExecutionOutcome(Bytes32 root, List<Hash> executed, BigInteger netFeeWei) {
    }

    /**
     * One tx's receipt plus the wei actually removed from the sender (0 when no debit occurred).
     * {@code invalid} marks a VALIDATION failure (nothing executed, no state touched, no gas charged) as
     * opposed to an executed-but-failed tx (revert / out-of-gas: gas charged, nonce bumped).
     */
    private record TxOutcome(EvmReceipt receipt, BigInteger netFeeWei, boolean invalid) {
        TxOutcome(EvmReceipt receipt, BigInteger netFeeWei) {
            this(receipt, netFeeWei, false);
        }
    }

    /**
     * Executes one height's txs on a fresh root updater, commits, writes receipts, and returns the
     * chained root plus the executed subset. Mints the height's bridge deposits (EVM_META 0x06)
     * before the first tx — same commit, same replay path. A deterministic per-main-block gas budget
     * bounds the total work: once the sum of tx gas limits would exceed {@code blockGasLimit},
     * further refs are skipped (no receipt, absent from the tx list) so they stay executable in a
     * later block. This caps the synchronous EVM work one block can force onto the import thread.
     */
    private ExecutionOutcome executeList(List<Hash> txHashes, long height, long timestampSeconds,
                                         Bytes32 previousRoot) {
        RocksDbWorldUpdater root = new RocksDbWorldUpdater(stateStore);
        // Bridge deposits mint FIRST (spec §2.2): a same-height tx may spend deposited funds, and the
        // mints land on the SAME root updater so the height's state delta covers mints and executions
        // in one commit. Reading from EVM_META (not a parameter) makes replay identical to live
        // execution — rollbackTo re-runs this method and re-mints from the surviving 0x06 records.
        for (BridgeDeposit deposit : metaStore.getDeposits(height)) {
            MutableAccount account = root.getOrCreate(deposit.target());
            Wei minted = Wei.of(BigInteger.valueOf(deposit.amountNano())
                    .multiply(BridgeConstants.WEI_PER_NANO));
            account.setBalance(account.getBalance().add(minted));
        }
        List<Bytes> digest = new ArrayList<>(txHashes.size() + 2);
        digest.add(previousRoot);
        digest.add(Bytes.ofUnsignedLong(height)); // Phase 2: commit which height executed, not just outcomes
        List<Hash> executed = new ArrayList<>(txHashes.size());
        LogsBloomFilter.Builder bloomBuilder = LogsBloomFilter.builder();
        List<BridgeWithdrawal> burns = new ArrayList<>();
        BigInteger netFeeWei = BigInteger.ZERO;
        long gasBudget = blockGasLimit;
        if (metaStore.isSkipped(height)) {
            // Committed-skip (ADR-015 / G2-T1b): no txs execute. Deposits already minted above; fold a
            // canonical sentinel where the per-tx entries would go so this root is deterministic and
            // distinct from an executed/empty height. `executed` stays empty -> putTxList(height, []).
            digest.add(DA_SKIP_SENTINEL);
        } else {
            for (Hash txHash : txHashes) {
                Bytes blob = txStore.get(txHash).orElse(null);
                if (blob == null) {
                    // Only reachable in replay if EVM_TX was externally damaged; keep the trace honest.
                    log.error("EVM tx blob vanished for {} at height {}", txHash, height);
                    continue;
                }
                long txGasLimit = peekGasLimit(blob, height); // -1 when undecodable (executeOne records the failure)
                if (txGasLimit > gasBudget) {
                    log.warn("EVM tx {} gas limit {} exceeds remaining block budget {} at height {}; skipping",
                            txHash, txGasLimit, gasBudget, height);
                    continue;
                }
                if (txGasLimit > 0) {
                    gasBudget -= txGasLimit;
                }
                TxOutcome exec = executeOne(root, blob, height, timestampSeconds);
                if (exec.invalid() && height >= invalidTxSkipActivationHeight) {
                    // Audit round 2, P3: a validation failure touches no state and charges no gas, so
                    // it is dropped from the block outright -- no receipt (the hash stays executable
                    // in a later block once its precondition holds, e.g. the earlier nonce lands), no
                    // digest entry, not in the tx list, and its reserved budget is returned. Recording
                    // it as a status-0 receipt let any miner burn a pending tx at zero cost by
                    // referencing it out of order. Deterministic: given the same pre-state every node
                    // reaches the same validation verdict.
                    if (txGasLimit > 0) {
                        gasBudget += txGasLimit;
                    }
                    continue;
                }
                EvmReceipt receipt = exec.receipt();
                netFeeWei = netFeeWei.add(exec.netFeeWei());
                metaStore.putReceipt(txHash, receipt);
                receipt.logs().forEach(bloomBuilder::insertLog);
                collectBridgeBurns(receipt.logs(), burns, height);
                executed.add(txHash);
                digest.add(Bytes.concatenate(txHash.getBytes(),
                        Bytes.of((byte) receipt.status()),
                        Bytes.ofUnsignedLong(receipt.gasUsed())));
            }
        }
        // Fold the persisted world-state delta into the chained root (Phase 1): the digest is a keccak
        // over exactly the (puts, deletes) this commit writes, so two nodes diverging on any balance or
        // storage — even with identical (txHash, status, gasUsed) — now produce different roots, which
        // the replay self-check in rollbackTo can detect.
        List<EvmStateJournal.Entry> journalEntries = journal == null ? null : new ArrayList<>();
        digest.add(root.commitAndDigest(journalEntries));
        if (journal != null) {
            // Persist this height's reverse delta and drop journals older than the retained window.
            // Runs on normal execution AND reorg replay, so replayed heights regenerate journals.
            journal.putHeightJournal(height, journalEntries);
            long lowestRetained = height - historyWindow + 1; // inclusive: keep [lowestRetained, height]
            journal.pruneBelow(lowestRetained);
        }
        // C5: persist this height's logs bloom so eth_getLogs can skip it without reading receipts.
        // Runs on normal execution AND reorg replay (like receipts), so replayed heights regenerate it.
        metaStore.putHeightBloom(height, bloomBuilder.build().getBytes());
        // Phase 3b: record this height's bridge burns (spec §3.2). Same lifecycle as the bloom —
        // regenerated on replay, so release/reversal always see what THIS execution produced.
        if (!burns.isEmpty()) {
            metaStore.putWithdrawals(height, burns);
        }
        Bytes32 chainedRoot =
                org.hyperledger.besu.crypto.Hash.keccak256(Bytes.concatenate(digest.toArray(new Bytes[0])));
        return new ExecutionOutcome(chainedRoot, executed, netFeeWei);
    }

    /**
     * Scans one executed receipt's logs for the bridge contract's Withdrawal events (spec §3.2) and
     * appends them to {@code burns} in emission order. Sourced from the exact receipt logs that feed
     * the height bloom, so the burn record and the bloom always agree on what this execution emitted.
     * Events that do not match the Withdrawal shape, or whose amount is not nano-divisible or exceeds
     * supply bounds, are skipped deterministically (error-logged) rather than aborting the block.
     */
    private void collectBridgeBurns(List<Log> logs, List<BridgeWithdrawal> burns, long height) {
        for (Log evmLog : logs) {
            if (!BridgeContract.ADDRESS.equals(evmLog.getLogger()) || evmLog.getTopics().size() < 2
                    || evmLog.getData().size() < 32
                    || !BridgeContract.WITHDRAWAL_TOPIC0.equals(
                            Bytes32.wrap(evmLog.getTopics().get(0).getBytes()))) {
                continue;
            }
            Bytes target20 = evmLog.getTopics().get(1).getBytes().slice(0, 20);
            BigInteger wei = evmLog.getData().slice(0, 32).toUnsignedBigInteger();
            BigInteger[] div = wei.divideAndRemainder(BridgeConstants.WEI_PER_NANO);
            if (div[1].signum() != 0 || div[0].bitLength() > 62) {
                // Unreachable via the contract (it enforces divisibility; supply bounds the size) —
                // deterministic skip keeps a crafted-state surprise from aborting consensus.
                log.error("Skipping malformed bridge burn at height {}: {} wei", height, wei);
                continue;
            }
            burns.add(new BridgeWithdrawal(target20, div[0].longValueExact()));
        }
    }

    /**
     * Decodes just the gas limit for budget accounting; -1 if the blob cannot be decoded.
     * A type-2 blob below the activation height also reads -1 — a non-upgraded node cannot
     * decode it at all, and budget accounting must match byte-for-byte pre-activation (spec §4),
     * not just the receipt (which the executeOne gate already equalizes).
     */
    private long peekGasLimit(Bytes blob, long height) {
        try {
            EvmTransaction tx = EvmTransaction.decode(blob);
            if (type2Gated(tx, height)) {
                return -1L;
            }
            return tx.getGasLimit();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /** Defect-1 fork gate: type-2 (EIP-1559) txs are not executable below the activation height. */
    private boolean type2Gated(EvmTransaction tx, long height) {
        return tx.getType() == EvmTransaction.TYPE_EIP1559 && height < type2ActivationHeight;
    }

    /**
     * Executes a single tx. Validation failure (undecodable, type-2 before its activation height,
     * wrong chain, bad nonce, value not covered, gas out of bounds) produces an {@code invalid}
     * outcome with a status-0 / zero-gas receipt and no state change. Below
     * {@code invalidTxSkipActivationHeight} the caller records that receipt (legacy: the tx stays part
     * of consensus history); from that height on the caller drops the ref entirely (audit round 2, P3).
     */
    private TxOutcome executeOne(RocksDbWorldUpdater root, Bytes rawRlp, long height,
                                  long timestampSeconds) {
        EvmTransaction tx;
        try {
            tx = EvmTransaction.decode(rawRlp);
        } catch (RuntimeException e) {
            return validationFailure("undecodable blob", e.getMessage());
        }
        if (type2Gated(tx, height)) {
            // Pre-activation, this receipt is byte-identical to the "undecodable blob" receipt a
            // non-upgraded node produces (receipts carry no reason), so the chained roots agree
            // across the upgrade window (spec §4).
            return validationFailure("type-2 before activation", "height " + height);
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
            intrinsicGas = IntrinsicGas.compute(tx.getPayload(), tx.isContractCreation(), tx.getAccessList());
        } catch (IllegalArgumentException e) {
            return validationFailure("oversized initcode", e.getMessage());
        }
        // Also the backstop for an adversarial negative decoded gasLimit (readLongScalar accepts
        // 2^63..2^64-1): it bypasses the earlier > comparisons but always fails here, deterministically
        // on every node.
        if (intrinsicGas > tx.getGasLimit()) {
            return validationFailure("intrinsic gas above tx gas limit", String.valueOf(intrinsicGas));
        }
        // Re-check the gas-price floor at execution — the pool enforces minGasPrice, but a hand-crafted
        // carrier can reference a tx that skipped the pool, so execution must not trust it. Rejecting
        // anything below minGasPrice (and explicitly zero, in case a network sets minGasPrice = 0)
        // stops an underpriced tx from buying near-free compute.
        BigInteger effectiveGasPrice = tx.getEffectiveGasPrice().getAsBigInteger();
        if (effectiveGasPrice.signum() == 0 || effectiveGasPrice.compareTo(minGasPrice) < 0) {
            return validationFailure("gas price below minimum", tx.getEffectiveGasPrice().toString());
        }
        Account senderAccount = root.getAccount(sender);
        long accountNonce = senderAccount == null ? 0L : senderAccount.getNonce();
        Wei balance = senderAccount == null ? Wei.ZERO : senderAccount.getBalance();
        if (tx.getNonce() != accountNonce) {
            return validationFailure("nonce mismatch",
                    tx.getNonce() + " vs account " + accountNonce);
        }
        // Gas settles in EVM wei (缺口2 / Path α). Affordability is validated against value +
        // feeCap * gasLimit (the Ethereum rule — for type-2 the cap is maxFeePerGas, which survives
        // a future baseFee > 0), while the actual debit/refund/burn run at the EFFECTIVE price:
        // effectiveGasPrice * gasLimit is debited upfront and the unused gas refunded after
        // execution, so a revert or out-of-gas still pays for the gas it burned. The net charge
        // (gasUsed * effectiveGasPrice) is burned, not credited to a coinbase (v1; ADR-007 is P2).
        BigInteger maxGasFee = tx.getFeeCapPerGas().getAsBigInteger()
                .multiply(BigInteger.valueOf(tx.getGasLimit()));
        if (balance.getAsBigInteger().compareTo(tx.getValue().getAsBigInteger().add(maxGasFee)) < 0) {
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

        // Affordability was checked above, so this debit cannot underflow; the net fee we surface is
        // the wei actually removed from the sender = upfront debit minus the refund actually applied.
        BigInteger upfrontGasFee = effectiveGasPrice.multiply(BigInteger.valueOf(tx.getGasLimit()));
        // Defense in depth (C1): the upfront gas debit, execution, AND the refund all run inside this
        // guard, so no balance-arithmetic or interpreter error can escape into setMain and abort native
        // consensus — any unexpected failure degrades to a status-0 receipt. The debit and refund are
        // provably non-underflowing (the affordability check above; the refund is additive), so the
        // reachable paths (success, revert, out-of-gas) behave exactly as before.
        try {
            adjustBalance(root, sender, upfrontGasFee.negate()); // upfront gas debit, charged even on revert/OOG
            long rawStorageRefund = 0L;
            EvmReceipt receipt;
            if (tx.isContractCreation()) {
                if (messageGas <= 0L) {
                    bumpNonce(root, sender); // Ethereum still burns the nonce on a failed create
                    receipt = failedReceipt(intrinsicGas);
                } else {
                    // deploy() bumps + commits the sender nonce itself, even when execution fails (S-24).
                    XdagExecutionResult result = executor.deploy(root.updater(), sender, tx.getPayload(),
                            tx.getValue(), messageGas, blockValues, Address.ZERO);
                    rawStorageRefund = result.gasRefund();
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
                    rawStorageRefund = result.gasRefund();
                    receipt = receiptOf(result, intrinsicGas);
                }
            }
            // EIP-3529 storage refund (G3-T2). Consensus-gated: the chained root folds receipt.gasUsed,
            // so below the activation height we keep charging gross (byte-identical to a non-upgraded
            // node). A refund is earned only by successful execution; Besu already produced the reduced
            // Shanghai clear-refund amounts, so we only cap (min with gasUsed/quotient) and apply.
            long grossGasUsed = Math.min(receipt.gasUsed(), tx.getGasLimit());
            long netGasUsed = grossGasUsed;
            if (height >= eip3529ActivationHeight && receipt.status() == 1) {
                long refundedGas =
                        cappedStorageRefund(grossGasUsed, rawStorageRefund, executor.maxRefundQuotient());
                if (refundedGas > 0L) {
                    netGasUsed = grossGasUsed - refundedGas;
                    receipt = new EvmReceipt(receipt.status(), netGasUsed,
                            receipt.contractAddress(), receipt.logs());
                }
            }
            // Refund the unused gas at the effective price; the sender's net gas cost is
            // netGasUsed * effectiveGasPrice.
            BigInteger gasFeeRefund =
                    effectiveGasPrice.multiply(BigInteger.valueOf(tx.getGasLimit() - netGasUsed));
            BigInteger refundApplied = BigInteger.ZERO;
            if (gasFeeRefund.signum() > 0) {
                adjustBalance(root, sender, gasFeeRefund);
                refundApplied = gasFeeRefund;
            }
            return new TxOutcome(receipt, upfrontGasFee.subtract(refundApplied));
        } catch (RuntimeException e) {
            log.error("EVM execution threw for a tx at height {}; recording a failed receipt to "
                    + "protect native consensus", height, e);
            // The upfront debit already ran and no refund was applied -> the full upfront fee settled.
            return new TxOutcome(failedReceipt(intrinsicGas), upfrontGasFee);
        }
    }

    /**
     * EIP-3529 storage-refund cap: a tx is credited at most {@code grossGasUsed / maxRefundQuotient}
     * (a fifth on London+/Shanghai) of the refund its execution accumulated. Returns 0 for a
     * non-positive raw refund or quotient. Besu already applies the reduced Shanghai clear-refund
     * amounts, so this only caps what the frame reported. Valid only while the executor's gas
     * calculator returns {@code selfDestructRefundAmount() == 0} (London+/Shanghai), so the frame's
     * accumulated refund is the complete execution refund; a pre-London fork would have to add the
     * selfdestruct term here to match Besu.
     */
    static long cappedStorageRefund(long grossGasUsed, long rawRefund, long maxRefundQuotient) {
        if (rawRefund <= 0L || maxRefundQuotient <= 0L || grossGasUsed <= 0L) {
            return 0L;
        }
        return Math.min(rawRefund, grossGasUsed / maxRefundQuotient);
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

    private TxOutcome validationFailure(String reason, String detail) {
        log.warn("EVM tx validation failed ({}): {}", reason, detail);
        return new TxOutcome(new EvmReceipt(0, 0L, Optional.empty(), List.of()), BigInteger.ZERO, true);
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
