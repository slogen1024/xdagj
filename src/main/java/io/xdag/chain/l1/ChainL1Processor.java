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
package io.xdag.chain.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.chain.ChainActivation;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ExtResult;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.XdagTime;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * L1 semantics of chain blocks, evaluated in {@code applyBlock} DFS order after value settlement.
 * Only active while a context exists (main height at or above the chain activation height, per
 * {@link ChainActivation}); outside that, every hook is inert except the handler dispatch in
 * {@link #onBlockUnapplied} (SP0a principle P2). One {@code CHAIN_L1} batch is committed per applied
 * or unapplied block (principle P5), and every write {@link #onBlockApplied} makes has its inverse
 * in {@link #onBlockUnapplied} (principle P3).
 *
 * <p><b>What it records.</b> A block that pays into a chain vault (an {@code XDAG_FIELD_OUTPUT}
 * address-type output whose 20-byte address is a registered chain — never a {@code COINBASE} field,
 * which {@code applyBlock} never settles as a payment even though {@code Block.parse} also adds it
 * to {@code getOutputs()}) or that creates a chain (a new-chain DEPLOY) produces one
 * {@link InputRecord} per attributed vault output, indexed by {@code (chainId, height, index)} with
 * {@code index} running from the chain's current {@link ChainL1Store#getCallCount} at that height.
 * The record's {@link InputStatus} is the L1 verdict; SP1's execution engine consumes this ordered
 * stream. A DEPLOY that passes every check also registers the chain, the contract and the code blob.
 * Payments into a vault that the block's extension does not explain — a plain transfer, a CALL to
 * an unknown contract, a malformed extension, or a second {@code OUTPUT} to the same vault in one
 * block — are still recorded, as {@code INVALID_FORMAT}, so the value that entered the vault is
 * accounted for.
 *
 * <p><b>Chunk-chain age rule.</b> Every chunk chain a paying block references is walked with
 * {@code minEpoch = XdagTime.getEpoch(payingBlock.getTimestamp()) - 1} — the PAYING block's epoch,
 * not the main block's. A snapshot-bootstrapped node has no raw bytes for pre-snapshot blocks while
 * {@code tryToConnect}'s NO_PARENT rule is satisfied by a {@code BlockInfo} alone, so without an
 * age bound two honest nodes could disagree on whether a chain assembles. See {@link ChunkChain}'s
 * class documentation for the full argument. The 3-argument {@code assemble}/{@code countLenient}
 * overloads (no age bound) must never be used here.
 *
 * <p><b>Determinism.</b> Every decision depends only on the applied block's raw bytes, the
 * {@code CHAIN_L1} state at that point of the DFS, the {@link ChainSpec} parameters and the block
 * lookup. Nothing reads wall-clock time (the age rule uses the block's own timestamp) and nothing
 * depends on the order in which unrelated blocks arrived over the network.
 *
 * <p>The one node-local structure the lookup transits is the in-memory orphan pool
 * ({@code BlockchainImpl.getBlockByHash} consults {@code memOrphanPool} before the block store):
 * a block still sitting there can be evicted by {@code ORPHAN_REMOVE_REUSE} without ever being
 * persisted, so "is this chunk still retrievable" is not on its own a network-wide constant. It
 * is nonetheless safe here, because the processor only ever walks the chains of a block that is
 * being applied — and a block is only applied once it is linked. Connecting the paying block calls
 * {@code removeOrphan} on each of its links with {@code ORPHAN_REMOVE_NORMAL} (or
 * {@code ORPHAN_REMOVE_EXTRA} when the linking block is itself still extra); every action except
 * {@code ORPHAN_REMOVE_REUSE} writes the block out with {@code saveBlock} and then recurses into
 * that block's own links, so the whole chunk chain is persisted, on every node, before any main
 * block can confirm the block that pays for it.
 *
 * <p>Chunk blocks now take a second route to the same place, and it lands them there at the same
 * moment. A chunk is no longer written to the block store when it arrives: it is held in the orphan
 * pool's body store until something references it, and it is aged out in two epochs if nothing
 * ever does. What persists it is {@code BlockchainImpl.persistReferencedChunkChains}, which runs
 * while the referencing block is being connected and before that block's own links are un-orphaned
 * — so by the time any main block can confirm a paying block, every chunk on the chains it names is
 * on disk, exactly as before. A node that no longer holds a chain cannot import the paying block at
 * all ({@code NO_PARENT}), so it never reaches a verdict here on a chain it could not assemble.
 *
 * <p><b>Raw blocks required.</b> Both hooks and the {@code lookup} must be handed blocks parsed
 * from their 512 bytes — see {@link ChainL1Hooks}.
 */
@Slf4j
public final class ChainL1Processor implements ChainL1Hooks {

    /**
     * Global upper bound for a chain's {@code maxCallGas} (master spec §12: 10,000,000, and it may
     * only ever be lowered). A chain declaring more would make fraud-proof arbitration unbounded;
     * a chain declaring {@code 0} could never execute a call at all. Both are rejected at DEPLOY.
     */
    public static final long MAX_CALL_GAS_CAP = 10_000_000L;

    /** Kinds this class implements itself; a {@link ChainKindHandler} may not claim them. */
    private static final Set<ExtKind> BUILT_IN = EnumSet.of(ExtKind.CALL, ExtKind.DEPLOY, ExtKind.CHUNK);

    private final ChainL1Store store;
    private final ChainSpec spec;
    private final ChainActivation activation;
    private final ChunkChain.RawBlockLookup lookup;
    private final Map<ExtKind, ChainKindHandler> handlers = new EnumMap<>(ExtKind.class);
    private ApplyContext ctx;
    /**
     * Set on entry of every hook method; once true, {@link #registerHandler} is rejected. The
     * dispatch table must be stable before consensus processing begins — registering a handler
     * mid-stream would mean some already-applied blocks of that kind were never offered to it.
     */
    private boolean started;

    public ChainL1Processor(ChainL1Store store, ChainSpec spec, ChunkChain.RawBlockLookup lookup) {
        this.store = Objects.requireNonNull(store, "store");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.activation = new ChainActivation(this.spec);
    }

    /**
     * Registers the SP2/SP3 semantics of one extension kind. Must be called before the first hook
     * runs (any of {@link #onSetMainBegin}, {@link #onBlockApplied}, {@link #onSetMainEnd},
     * {@link #onBlockUnapplied} or {@link #onUnsetMain}); registering later would leave already
     * -processed blocks of that kind undispatched.
     *
     * <p>In production this is called from one place only: the {@code BlockchainImpl} constructor,
     * which drains {@code Kernel.getChainKindHandlers()} into the processor it has just built,
     * before starting the check-main loop. See {@link ChainKindHandler} for why there is no other
     * window.
     *
     * @throws NullPointerException     if {@code kind} or {@code handler} is {@code null}
     * @throws IllegalArgumentException if {@code kind} is {@code CALL}, {@code DEPLOY} or
     *                                  {@code CHUNK} — those are built in and not pluggable
     * @throws IllegalStateException    if a hook has already run
     */
    public void registerHandler(ExtKind kind, ChainKindHandler handler) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(handler, "handler");
        if (started) {
            throw new IllegalStateException("cannot register a handler after the first hook has run: " + kind);
        }
        if (BUILT_IN.contains(kind)) {
            throw new IllegalArgumentException("built-in kind is not pluggable: " + kind);
        }
        handlers.put(kind, handler);
    }

    @Override
    public void onSetMainBegin(long height, Block mainBlock) {
        started = true;
        if (ctx != null) {
            // An unbalanced call from the caller (a missing onSetMainEnd), not a consensus event.
            log.warn("chain apply context already open at height {}, overwriting with height {}", ctx.height(), height);
        }
        ctx = activation.isActive(height) ? new ApplyContext(height, mainBlock.getHash()) : null;
    }

    /**
     * Closes the apply context opened by {@link #onSetMainBegin} for this {@code height}. A
     * recorded context whose height disagrees with this call's height indicates an unbalanced
     * begin/end pair from the caller (a consensus-event bug, not a chain-layer one); logged rather
     * than thrown, since the caller's {@code setMain} must still be allowed to finish.
     */
    @Override
    public void onSetMainEnd(long height, Block mainBlock) {
        started = true;
        if (ctx != null && ctx.height() != height) {
            log.warn("chain apply context height mismatch at onSetMainEnd: open context height {}, closing height {}",
                    ctx.height(), height);
        }
        ctx = null;
    }

    /**
     * Closes the apply context on the unwind path. Unlike {@link #onSetMainEnd} this is not paired
     * with a specific {@link #onSetMainBegin} call for the same main block being unwound — the
     * context being closed may belong to whatever {@code setMain} last opened one — so no height
     * mismatch is checked here.
     */
    @Override
    public void onUnsetMain(long height, Block mainBlock) {
        started = true;
        ctx = null;
    }

    /**
     * Records this block's chain inputs. A pre-existing reverse-index entry for the same block means
     * it was applied twice with no unapply in between (a caller-side bookkeeping bug, since the
     * second apply's indices orphan the first's), which is logged as an error rather than thrown:
     * the write itself is still well-formed, and failing block application here would be worse.
     */
    @Override
    public void onBlockApplied(Block block) {
        started = true;
        if (ctx == null) {
            return;
        }
        Classified c = ChainBlockClassifier.classify(block);
        List<Bytes> vaults = vaultOutputs(block);
        if (c.kind() == null && vaults.isEmpty()) {
            return;
        }
        Bytes32 blockHash = block.getHash();
        long minEpoch = minEpoch(block);
        ChainL1Batch batch = new ChainL1Batch();
        List<InputRef> refs = new ArrayList<>();
        // Per-chain next input index within this block; because ChainL1Batch keeps only the last write
        // per key, the repeated putCallCount for one (chain, height) converges on the final count.
        // LinkedHashMap for deterministic iteration order below, matching ChainL1Batch's own ordering.
        Map<Bytes, Long> counts = new LinkedHashMap<>();
        boolean vaultConsumed = false;

        if (c.kind() == ExtKind.DEPLOY && c.isOk()) {
            DeployExt d = c.as(DeployExt.class);
            if (d.newChain()) {
                applyDeploy(block, d, ChainIds.chainIdOf(blockHash), true, minEpoch, batch, refs, counts);
            } else if (vaults.size() == 1 && vaults.get(0).equals(d.chainId())) {
                vaultConsumed = true;
                applyDeploy(block, d, d.chainId(), false, minEpoch, batch, refs, counts);
            }
        } else if (c.kind() == ExtKind.CALL && c.isOk()) {
            CallExt call = c.as(CallExt.class);
            if (vaults.size() == 1) {
                ContractRecord target = store.getContract(call.contract());
                if (target != null && target.chainId().equals(vaults.get(0))) {
                    vaultConsumed = true;
                    int chunks = chainCount(call.argsChainHead(), minEpoch);
                    InputStatus status = feeCovers(block, chunks) ? InputStatus.OK : InputStatus.INVALID_FEE;
                    record(batch, refs, counts, vaults.get(0), blockHash, ExtKind.CALL, status, call.contract());
                }
            }
        } else if (c.kind() != null && c.isOk() && !BUILT_IN.contains(c.kind())) {
            // The BUILT_IN guard mirrors onBlockUnapplied's; registerHandler already rejects those
            // kinds, so it can only ever be redundant here — which is exactly why both sides state
            // it, rather than one side relying on the other's invariant.
            ChainKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onApplied(block, c, ctx, batch);
            }
        }

        if (!vaultConsumed) {
            for (Bytes v : vaults) {
                record(batch, refs, counts, v, blockHash, null, InputStatus.INVALID_FORMAT, ChainIds.ZERO_ADDRESS);
            }
        }
        if (refs.isEmpty() && batch.isEmpty()) {
            // Nothing was attributed and no handler wrote anything either: record() is the only
            // entry point that populates refs, but a handler may still have written to the batch
            // directly. Never commit an empty batch.
            return;
        }
        if (!refs.isEmpty()) {
            if (!store.getReverse(blockHash).isEmpty()) {
                log.error("chain block {} applied twice without an intervening unapply; previous inputs at that height"
                        + " are now orphaned", blockHash);
            }
            batch.putReverse(blockHash, refs);
        }
        store.commit(batch);
        log.debug("chain inputs recorded: block={} height={} refs={}", blockHash, ctx.height(), refs.size());
    }

    /**
     * Undoes exactly what {@link #onBlockApplied} wrote for this block, reading the reverse index
     * rather than re-deriving anything, and dispatches the block's kind handler (if any) — see
     * {@link ChainKindHandler} for why this dispatch, unlike {@link #onBlockApplied}'s, may fire for
     * a block whose {@code onApplied} was never called. It deliberately never consults {@link #ctx}:
     * unwinding runs outside any {@code setMain}, so the height comes from the recorded
     * {@link InputRef}s.
     *
     * <p>The count arithmetic below is only correct when unapply runs in the exact reverse of apply
     * order: the block being unapplied must own the top indices of each (chain, height) it touched,
     * i.e. it must be the most recently applied block still standing at that (chain, height). See
     * {@link #undoDeploy}'s canary and the clamp below for what happens when that invariant is
     * violated instead of holding.
     *
     * <p>A corrupt {@code INPUT} record — {@link InputRecord#decode} or {@link InputStatus#fromCode}
     * throwing — is a deliberate fail-stop: this method does not catch it, since unwinding cannot
     * proceed correctly on a record it cannot even parse, and a silent skip would be worse than
     * surfacing the DB corruption.
     */
    @Override
    public void onBlockUnapplied(Block block) {
        started = true;
        Bytes32 blockHash = block.getHash();
        Classified c = ChainBlockClassifier.classify(block);
        ChainL1Batch batch = new ChainL1Batch();
        if (c.kind() != null && c.isOk() && !BUILT_IN.contains(c.kind())) {
            ChainKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onUnapplied(block, c, batch);
            }
        }
        List<InputRef> refs = store.getReverse(blockHash);
        if (!refs.isEmpty()) {
            // LinkedHashMap for deterministic iteration order below, matching ChainL1Batch's own ordering.
            Map<Bytes, Long> counts = new LinkedHashMap<>();
            // Every ref of one block was written by a single onBlockApplied, hence at a single
            // height; the count bookkeeping below keys on the chain alone and writes back at this
            // one height, so a ref claiming another height would have its count read at one height
            // and written at another. That cannot happen unless the reverse index is corrupt, so it
            // is reported and the stray entry left alone rather than half-undone.
            long height = refs.get(0).height();
            for (int i = refs.size() - 1; i >= 0; i--) {
                InputRef r = refs.get(i);
                if (r.height() != height) {
                    log.error("chain reverse index of block {} mixes heights {} and {} (chain={} index={}); "
                            + "skipping the stray entry", blockHash, height, r.height(), r.chainId(), r.index());
                    continue;
                }
                InputRecord in = store.getInput(r.chainId(), height, r.index());
                batch.deleteInput(r.chainId(), height, r.index());
                long remaining = counts.computeIfAbsent(r.chainId(), l -> store.getCallCount(l, height)) - 1;
                counts.put(r.chainId(), remaining);
                if (in != null && in.kind() == ExtKind.DEPLOY && in.status() == InputStatus.OK) {
                    undoDeploy(blockHash, r.chainId(), in.contract(), batch);
                }
            }
            for (Map.Entry<Bytes, Long> e : counts.entrySet()) {
                long remaining = e.getValue();
                if (remaining <= 0) {
                    if (remaining < 0) {
                        // Canary: unapply ran out of order (this block did not own the top indices
                        // of this (chain, height)). Clamp to the floor rather than write a negative
                        // count that could never have been produced by onBlockApplied.
                        log.error("chain call count went negative for chain={} height={}: {} (unapply out of order?)",
                                e.getKey(), height, remaining);
                    }
                    batch.deleteCallCount(e.getKey(), height);
                } else {
                    batch.putCallCount(e.getKey(), height, remaining);
                }
            }
            batch.deleteReverse(blockHash);
            log.debug("chain inputs removed: block={} height={} refs={}", blockHash, height, refs.size());
        }
        if (!batch.isEmpty()) {
            store.commit(batch);
        }
    }

    /**
     * Evaluates one DEPLOY and records its input; on {@link InputStatus#OK} also writes the chain
     * (created or bumped), the contract and the code reference.
     *
     * <p>Check order: the chain config first (cheap, decided by the block's own bytes alone, and a
     * doomed chain is not worth assembling a code chain for), then the code (assembly, size, hash),
     * then the chunk fee. So {@code INVALID_FORMAT} and {@code CODE_TOO_LARGE} both take precedence
     * over {@code INVALID_FEE}, which is only ever evaluated on an otherwise-valid DEPLOY.
     */
    private void applyDeploy(Block block, DeployExt d, Bytes chainId, boolean newChain, long minEpoch,
                             ChainL1Batch batch, List<InputRef> refs, Map<Bytes, Long> counts) {
        Bytes32 blockHash = block.getHash();
        Bytes contract = ChainIds.contractIdOf(blockHash);
        InputStatus status = InputStatus.OK;
        Bytes code = null;
        int chunks = 0;
        if (newChain && (d.config().maxCallGas() == 0 || d.config().maxCallGas() > MAX_CALL_GAS_CAP)) {
            status = InputStatus.INVALID_FORMAT;
        }
        if (status == InputStatus.OK) {
            if (d.codeByChain()) {
                ExtResult<Bytes> assembled = ChunkChain.assemble(d.codeChainHead(), lookup,
                        spec.getChainMaxChunksPerChain(), minEpoch);
                if (!assembled.isOk()) {
                    status = InputStatus.INVALID_FORMAT;
                } else if (assembled.value().size() > spec.getChainMaxWasmBytes()) {
                    status = InputStatus.CODE_TOO_LARGE;
                } else if (!HashUtils.sha256(assembled.value()).equals(d.codeHash())) {
                    status = InputStatus.INVALID_FORMAT;
                } else {
                    code = assembled.value();
                    // Only walk the chain a second time once assemble has actually accepted it:
                    // countLenient == N for a chain assemble accepts (see chainCount's Javadoc), so
                    // this is not merely an optimization — it makes the fee basis depend on assemble
                    // having succeeded, not on the lenient walk alone.
                    chunks += chainCount(d.codeChainHead(), minEpoch);
                }
            } else if (!store.hasCode(d.codeHash())) {
                status = InputStatus.INVALID_FORMAT;
            }
        }
        if (status == InputStatus.OK) {
            if (d.argsByChain()) {
                chunks += chainCount(d.argsChainHead(), minEpoch);
            }
            if (!feeCovers(block, chunks)) {
                status = InputStatus.INVALID_FEE;
            }
        }
        record(batch, refs, counts, chainId, blockHash, ExtKind.DEPLOY, status, contract);
        if (status != InputStatus.OK) {
            return;
        }
        if (newChain) {
            batch.putChain(chainId, new ChainRecord(ctx.height(), blockHash, d.config().gasPriceNano(),
                    d.config().deliveryDelayD(), d.config().maxCallGas(), 1L));
        } else {
            ChainRecord chain = store.getChain(chainId);
            batch.putChain(chainId, chain.withContractCount(chain.contractCount() + 1));
        }
        batch.putContract(contract, new ContractRecord(chainId, d.codeHash(), ctx.height(), blockHash));
        long codeRefCount = store.getCodeRefCount(d.codeHash());
        if (codeRefCount > 0) {
            // Bump the refcount only: content addressing (the sha256 match above) guarantees the
            // stored blob already equals this block's code, so rewriting it would be pure cost. A
            // refcount of 0 is never written (ChainL1Store#getCodeRefCount), so codeRefCount > 0 is
            // equivalent to store.hasCode(d.codeHash()) — one read instead of two.
            batch.putCodeRef(d.codeHash(), codeRefCount + 1);
        } else {
            batch.putCode(d.codeHash(), 1L, code);
        }
    }

    /** The exact inverse of the OK branch of {@link #applyDeploy}. */
    private void undoDeploy(Bytes32 blockHash, Bytes chainId, Bytes contract, ChainL1Batch batch) {
        ContractRecord cr = store.getContract(contract);
        batch.deleteContract(contract);
        if (cr != null) {
            long refCount = store.getCodeRefCount(cr.codeHash());
            if (refCount <= 1) {
                batch.deleteCode(cr.codeHash());
            } else {
                // Never write a refcount of 0; readers treat an absent CODE_REF as 0.
                batch.putCodeRef(cr.codeHash(), refCount - 1);
            }
        }
        ChainRecord chain = store.getChain(chainId);
        if (chain != null) {
            if (chain.createBlockHash().equals(blockHash)) {
                if (chain.contractCount() != 1) {
                    // Canary: the chain's creating DEPLOY is being unwound, so no other DEPLOY into
                    // it should still be standing (they must all have been unwound first, in exact
                    // reverse apply order). A count other than 1 here means that ordering was
                    // violated; logged rather than thrown so the unwind can still complete.
                    log.error("chain {} contractCount={} != 1 when unapplying its creating block {} "
                            + "(unapply out of order?)", chainId, chain.contractCount(), blockHash);
                }
                batch.deleteChain(chainId);
            } else {
                long newCount = chain.contractCount() - 1;
                if (newCount < 0) {
                    // Clamp instead of letting ChainRecord's u32 range check throw out of unSetMain.
                    log.error("chain {} contractCount would go negative on unapply of {}; clamping to 0",
                            chainId, blockHash);
                    newCount = 0;
                }
                batch.putChain(chainId, chain.withContractCount(newCount));
            }
        }
    }

    private void record(ChainL1Batch batch, List<InputRef> refs, Map<Bytes, Long> counts, Bytes chainId,
                        Bytes32 blockHash, ExtKind kind, InputStatus status, Bytes contract) {
        long index = counts.computeIfAbsent(chainId, l -> store.getCallCount(l, ctx.height()));
        counts.put(chainId, index + 1);
        batch.putInput(chainId, ctx.height(), index, new InputRecord(blockHash, kind, status, contract));
        batch.putCallCount(chainId, ctx.height(), index + 1);
        refs.add(new InputRef(chainId, ctx.height(), index));
    }

    /**
     * {@code XDAG_FIELD_OUTPUT} address-type outputs whose address is a registered chain vault, in
     * field order. {@code Block.getOutputs()} also contains the block's {@code COINBASE} field (if
     * any), parsed with {@code isAddress = true} by {@code Block.parse}; that field is deliberately
     * excluded here because {@code applyBlock} only ever settles {@code XDAG_FIELD_INPUT} and
     * {@code XDAG_FIELD_OUTPUT} value transfers, never a {@code COINBASE} field, so a COINBASE link
     * to a chain vault must not be recorded as a payment into it.
     */
    private List<Bytes> vaultOutputs(Block block) {
        List<Bytes> out = new ArrayList<>();
        for (Address o : block.getOutputs()) {
            if (o.getIsAddress() && o.getType() == XDAG_FIELD_OUTPUT) {
                Bytes a = ChainIds.address20(o);
                if (store.hasChain(a)) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    /**
     * Oldest epoch a chunk of a chain this block references may lie in: the PAYING block's epoch
     * minus one. See the class documentation for why the bound exists.
     */
    private static long minEpoch(Block block) {
        return XdagTime.getEpoch(block.getTimestamp()) - 1;
    }

    /**
     * Number of chunk blocks the chunk fee is charged for: only chunks the network actually stores
     * under the age rule count, since {@link ChunkChain#countLenient} stops at the first missing,
     * too-old or non-chunk hop.
     *
     * <p>For a DEPLOY's code chain — the only chain L1 itself ever assembles — {@link #applyDeploy}
     * only calls this after {@link ChunkChain#assemble} has already accepted the chain, at which
     * point this returns exactly the chain's length; a chain assembly rejects never reaches this
     * call at all, since the rejection has already forced a non-OK status.
     *
     * <p>For an args chain (a DEPLOY's init args, or a CALL's args) L1 never assembles it — SP1's
     * execution engine does, later, and a chain that fails to assemble there simply fails that
     * call/deploy in-chain with a refund. For those chains the lenient count returned here IS the
     * fee basis, by design: the fee charges only for the chunk blocks the network actually stores
     * under the age rule, whether or not the chain would ever assemble. This is a deliberate
     * divergence from {@link ChunkChain}'s class-level warning that a fee check built on
     * {@code countLenient} "must be evaluated together with, or strictly after," the
     * {@code assemble} verdict — that warning is about a chain L1 itself assembles; an args chain
     * has no L1 {@code assemble} verdict to evaluate against in the first place.
     */
    private int chainCount(Bytes32 head, long minEpoch) {
        return head == null ? 0 : ChunkChain.countLenient(head, lookup, spec.getChainMaxChunksPerChain(), minEpoch);
    }

    /** Consensus re-check of the chunk fee rule: header fee field &gt;= chunkFee x chunks. */
    private boolean feeCovers(Block block, int chunks) {
        if (chunks == 0) {
            return true;
        }
        return headerFee(block).compareTo(spec.getChainChunkFee().multiply(chunks)) >= 0;
    }

    /**
     * The fee field of the block header, read from the raw 512 bytes exactly as {@code Block.parse}
     * does. {@code BlockInfo.fee} is overwritten with the collected fees while the block is applied,
     * so {@code Block.getFee()} would not answer "what did this block declare".
     *
     * <p>Public because the ingest-side chunk fee gate ({@code ChunkFeePolicy}) compares against
     * this same field: it has to ask the same question of the same bytes this class does, or the two
     * answers drift.
     *
     * <p><b>Requires a raw block</b> (SP0a principle P6: the hooks are handed blocks parsed from
     * their 512 bytes). This reads {@code block.getXdagBlock()}, which for a block that carries no
     * raw bytes is re-encoded from the very {@code info} whose {@code fee} was just overwritten —
     * the declared fee would then be indistinguishable from the collected one, and the consensus
     * fee check would silently use the wrong number.
     */
    public static XAmount headerFee(Block block) {
        XdagBlock raw = block.getXdagBlock();
        Bytes32 header = Bytes32.wrap(raw.getField(0).getData());
        return XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG);
    }
}
