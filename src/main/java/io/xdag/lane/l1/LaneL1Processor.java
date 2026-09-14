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
package io.xdag.lane.l1;

import io.xdag.config.spec.LaneSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.lane.LaneActivation;
import io.xdag.lane.ext.CallExt;
import io.xdag.lane.ext.ChunkChain;
import io.xdag.lane.ext.Classified;
import io.xdag.lane.ext.DeployExt;
import io.xdag.lane.ext.ExtKind;
import io.xdag.lane.ext.ExtResult;
import io.xdag.lane.ext.LaneBlockClassifier;
import io.xdag.utils.XdagTime;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * L1 semantics of lane blocks, evaluated in {@code applyBlock} DFS order after value settlement.
 * Only active while a context exists (main height at or above the lane activation height, per
 * {@link LaneActivation}); outside that, every hook is inert (SP0a principle P2). One
 * {@code LANE_L1} batch is committed per applied or unapplied block (principle P5), and every write
 * {@link #onBlockApplied} makes has its inverse in {@link #onBlockUnapplied} (principle P3).
 *
 * <p><b>What it records.</b> A block that pays into a lane vault (an address-type output whose
 * 20-byte address is a registered lane) or that creates a lane (a new-lane DEPLOY) produces one
 * {@link InputRecord} per attributed lane, indexed by {@code (laneId, height, index)} with
 * {@code index} running from the lane's current {@link LaneL1Store#getCallCount} at that height.
 * The record's {@link InputStatus} is the L1 verdict; SP1's execution engine consumes this ordered
 * stream. A DEPLOY that passes every check also registers the lane, the contract and the code blob.
 * Payments into a vault that the block's extension does not explain — a plain transfer, a CALL to
 * an unknown contract, a malformed extension — are still recorded, as {@code INVALID_FORMAT}, so
 * the value that entered the vault is accounted for.
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
 * {@code LANE_L1} state at that point of the DFS, the {@link LaneSpec} parameters and the block
 * lookup. Nothing reads wall-clock time (the age rule uses the block's own timestamp) and nothing
 * depends on the order in which unrelated blocks arrived over the network.
 *
 * <p><b>Raw blocks required.</b> Both hooks and the {@code lookup} must be handed blocks parsed
 * from their 512 bytes — see {@link LaneL1Hooks}.
 */
@Slf4j
public final class LaneL1Processor implements LaneL1Hooks {

    /**
     * Global upper bound for a lane's {@code maxCallGas} (master spec §12: 10,000,000, and it may
     * only ever be lowered). A lane declaring more would make fraud-proof arbitration unbounded;
     * a lane declaring {@code 0} could never execute a call at all. Both are rejected at DEPLOY.
     */
    public static final long MAX_CALL_GAS_CAP = 10_000_000L;

    /** Kinds this class implements itself; a {@link LaneKindHandler} may not claim them. */
    private static final Set<ExtKind> BUILT_IN = EnumSet.of(ExtKind.CALL, ExtKind.DEPLOY, ExtKind.CHUNK);

    private final LaneL1Store store;
    private final LaneSpec spec;
    private final LaneActivation activation;
    private final ChunkChain.RawBlockLookup lookup;
    private final Map<ExtKind, LaneKindHandler> handlers = new EnumMap<>(ExtKind.class);
    private ApplyContext ctx;

    public LaneL1Processor(LaneL1Store store, LaneSpec spec, ChunkChain.RawBlockLookup lookup) {
        this.store = Objects.requireNonNull(store, "store");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.activation = new LaneActivation(this.spec);
    }

    /**
     * Registers the SP2/SP3 semantics of one extension kind.
     *
     * @throws NullPointerException     if {@code kind} or {@code handler} is {@code null}
     * @throws IllegalArgumentException if {@code kind} is {@code CALL}, {@code DEPLOY} or
     *                                  {@code CHUNK} — those are built in and not pluggable
     */
    public void registerHandler(ExtKind kind, LaneKindHandler handler) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(handler, "handler");
        if (BUILT_IN.contains(kind)) {
            throw new IllegalArgumentException("built-in kind is not pluggable: " + kind);
        }
        handlers.put(kind, handler);
    }

    /** Current apply context, null outside an activated setMain. Exposed for tests. */
    public ApplyContext context() {
        return ctx;
    }

    @Override
    public void onSetMainBegin(long height, Block mainBlock) {
        if (ctx != null) {
            // An unbalanced call from the caller (a missing onSetMainEnd), not a consensus event.
            log.warn("lane apply context already open at height {}, overwriting with height {}", ctx.height(), height);
        }
        ctx = activation.isActive(height) ? new ApplyContext(height, mainBlock.getHash()) : null;
    }

    @Override
    public void onSetMainEnd(long height, Block mainBlock) {
        ctx = null;
    }

    @Override
    public void onUnsetMain(long height, Block mainBlock) {
        ctx = null;
    }

    @Override
    public void onBlockApplied(Block block) {
        if (ctx == null) {
            return;
        }
        Classified c = LaneBlockClassifier.classify(block);
        List<Bytes> vaults = vaultOutputs(block);
        if (c.kind() == null && vaults.isEmpty()) {
            return;
        }
        Bytes32 blockHash = block.getHash();
        long minEpoch = minEpoch(block);
        LaneL1Batch batch = new LaneL1Batch();
        List<InputRef> refs = new ArrayList<>();
        // Per-lane next input index within this block; because LaneL1Batch keeps only the last write
        // per key, the repeated putCallCount for one (lane, height) converges on the final count.
        Map<Bytes, Long> counts = new HashMap<>();
        boolean vaultConsumed = false;

        if (c.kind() == ExtKind.DEPLOY && c.isOk()) {
            DeployExt d = c.as(DeployExt.class);
            if (d.newLane()) {
                applyDeploy(block, d, LaneIds.laneIdOf(blockHash), true, minEpoch, batch, refs, counts);
            } else if (vaults.size() == 1 && vaults.get(0).equals(d.laneId())) {
                vaultConsumed = true;
                applyDeploy(block, d, d.laneId(), false, minEpoch, batch, refs, counts);
            }
        } else if (c.kind() == ExtKind.CALL && c.isOk()) {
            CallExt call = c.as(CallExt.class);
            if (vaults.size() == 1) {
                ContractRecord target = store.getContract(call.contract());
                if (target != null && target.laneId().equals(vaults.get(0))) {
                    vaultConsumed = true;
                    int chunks = chainCount(call.argsChainHead(), minEpoch);
                    InputStatus status = feeCovers(block, chunks) ? InputStatus.OK : InputStatus.INVALID_FEE;
                    record(batch, refs, counts, vaults.get(0), blockHash, ExtKind.CALL, status, call.contract());
                }
            }
        } else if (c.kind() != null && c.isOk()) {
            LaneKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onApplied(block, c, ctx);
            }
        }

        for (int i = 0; i < vaults.size(); i++) {
            if (i == 0 && vaultConsumed) {
                continue;
            }
            record(batch, refs, counts, vaults.get(i), blockHash, null, InputStatus.INVALID_FORMAT,
                    LaneIds.ZERO_ADDRESS);
        }
        if (refs.isEmpty()) {
            // Nothing was attributed, so nothing was written either: record() is the only entry
            // point that populates the batch. Never commit an empty batch.
            return;
        }
        batch.putReverse(blockHash, refs);
        store.commit(batch);
        log.debug("lane inputs recorded: block={} height={} refs={}", blockHash, ctx.height(), refs.size());
    }

    /**
     * Undoes exactly what {@link #onBlockApplied} wrote for this block, reading the reverse index
     * rather than re-deriving anything. It deliberately never consults {@link #ctx}: unwinding runs
     * outside any {@code setMain}, so the height comes from the recorded {@link InputRef}s.
     */
    @Override
    public void onBlockUnapplied(Block block) {
        Bytes32 blockHash = block.getHash();
        Classified c = LaneBlockClassifier.classify(block);
        if (c.kind() != null && c.isOk() && !BUILT_IN.contains(c.kind())) {
            LaneKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onUnapplied(block, c);
            }
        }
        List<InputRef> refs = store.getReverse(blockHash);
        if (refs.isEmpty()) {
            return;
        }
        LaneL1Batch batch = new LaneL1Batch();
        Map<Bytes, Long> counts = new HashMap<>();
        long height = refs.get(0).height();
        for (int i = refs.size() - 1; i >= 0; i--) {
            InputRef r = refs.get(i);
            InputRecord in = store.getInput(r.laneId(), r.height(), r.index());
            batch.deleteInput(r.laneId(), r.height(), r.index());
            long remaining = counts.computeIfAbsent(r.laneId(), l -> store.getCallCount(l, r.height())) - 1;
            counts.put(r.laneId(), remaining);
            if (in != null && in.kind() == ExtKind.DEPLOY && in.status() == InputStatus.OK) {
                undoDeploy(blockHash, r.laneId(), in.contract(), batch);
            }
        }
        for (Map.Entry<Bytes, Long> e : counts.entrySet()) {
            if (e.getValue() <= 0) {
                batch.deleteCallCount(e.getKey(), height);
            } else {
                batch.putCallCount(e.getKey(), height, e.getValue());
            }
        }
        batch.deleteReverse(blockHash);
        store.commit(batch);
        log.debug("lane inputs removed: block={} height={} refs={}", blockHash, height, refs.size());
    }

    /**
     * Evaluates one DEPLOY and records its input; on {@link InputStatus#OK} also writes the lane
     * (created or bumped), the contract and the code reference.
     *
     * <p>Check order: the lane config first (cheap, decided by the block's own bytes alone, and a
     * doomed lane is not worth assembling a code chain for), then the code (assembly, size, hash),
     * then the chunk fee. So {@code INVALID_FORMAT} and {@code CODE_TOO_LARGE} both take precedence
     * over {@code INVALID_FEE}, which is only ever evaluated on an otherwise-valid DEPLOY.
     */
    private void applyDeploy(Block block, DeployExt d, Bytes laneId, boolean newLane, long minEpoch,
                             LaneL1Batch batch, List<InputRef> refs, Map<Bytes, Long> counts) {
        Bytes32 blockHash = block.getHash();
        Bytes contract = LaneIds.contractIdOf(blockHash);
        InputStatus status = InputStatus.OK;
        Bytes code = null;
        int chunks = 0;
        if (newLane && (d.config().maxCallGas() == 0 || d.config().maxCallGas() > MAX_CALL_GAS_CAP)) {
            status = InputStatus.INVALID_FORMAT;
        }
        if (status == InputStatus.OK) {
            if (d.codeByChain()) {
                chunks += chainCount(d.codeChainHead(), minEpoch);
                ExtResult<Bytes> assembled = ChunkChain.assemble(d.codeChainHead(), lookup,
                        spec.getLaneMaxChunksPerChain(), minEpoch);
                if (!assembled.isOk()) {
                    status = InputStatus.INVALID_FORMAT;
                } else if (assembled.value().size() > spec.getLaneMaxWasmBytes()) {
                    status = InputStatus.CODE_TOO_LARGE;
                } else if (!HashUtils.sha256(assembled.value()).equals(d.codeHash())) {
                    status = InputStatus.INVALID_FORMAT;
                } else {
                    code = assembled.value();
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
        record(batch, refs, counts, laneId, blockHash, ExtKind.DEPLOY, status, contract);
        if (status != InputStatus.OK) {
            return;
        }
        if (newLane) {
            batch.putLane(laneId, new LaneRecord(ctx.height(), blockHash, d.config().gasPriceNano(),
                    d.config().deliveryDelayD(), d.config().maxCallGas(), 1L));
        } else {
            LaneRecord lane = store.getLane(laneId);
            batch.putLane(laneId, lane.withContractCount(lane.contractCount() + 1));
        }
        batch.putContract(contract, new ContractRecord(laneId, d.codeHash(), ctx.height(), blockHash));
        if (store.hasCode(d.codeHash())) {
            // Bump the refcount only: content addressing (the sha256 match above) guarantees the
            // stored blob already equals this block's code, so rewriting it would be pure cost.
            batch.putCodeRef(d.codeHash(), store.getCodeRefCount(d.codeHash()) + 1);
        } else {
            batch.putCode(d.codeHash(), 1L, code);
        }
    }

    /** The exact inverse of the OK branch of {@link #applyDeploy}. */
    private void undoDeploy(Bytes32 blockHash, Bytes laneId, Bytes contract, LaneL1Batch batch) {
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
        LaneRecord lane = store.getLane(laneId);
        if (lane != null) {
            if (lane.createBlockHash().equals(blockHash)) {
                batch.deleteLane(laneId);
            } else {
                batch.putLane(laneId, lane.withContractCount(lane.contractCount() - 1));
            }
        }
    }

    private void record(LaneL1Batch batch, List<InputRef> refs, Map<Bytes, Long> counts, Bytes laneId,
                        Bytes32 blockHash, ExtKind kind, InputStatus status, Bytes contract) {
        long index = counts.computeIfAbsent(laneId, l -> store.getCallCount(l, ctx.height()));
        counts.put(laneId, index + 1);
        batch.putInput(laneId, ctx.height(), index, new InputRecord(blockHash, kind, status, contract));
        batch.putCallCount(laneId, ctx.height(), index + 1);
        refs.add(new InputRef(laneId, ctx.height(), index));
    }

    /** Address-type outputs whose address is a registered lane vault, in field order. */
    private List<Bytes> vaultOutputs(Block block) {
        List<Bytes> out = new ArrayList<>();
        for (Address o : block.getOutputs()) {
            if (o.getIsAddress()) {
                Bytes a = LaneIds.address20(o);
                if (store.hasLane(a)) {
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
     * too-old or non-chunk hop. For a chain that {@link ChunkChain#assemble} accepted this is
     * exactly the chain's length; on a chain assembly rejected it is an upper bound the fee check
     * never reaches, because a rejected chain has already forced a non-OK status.
     */
    private int chainCount(Bytes32 head, long minEpoch) {
        return head == null ? 0 : ChunkChain.countLenient(head, lookup, spec.getLaneMaxChunksPerChain(), minEpoch);
    }

    /** Consensus re-check of the chunk fee rule: header fee field &gt;= chunkFee x chunks. */
    private boolean feeCovers(Block block, int chunks) {
        if (chunks == 0) {
            return true;
        }
        return headerFee(block).compareTo(spec.getLaneChunkFee().multiply(chunks)) >= 0;
    }

    /**
     * The fee field of the block header, read from the raw 512 bytes exactly as {@code Block.parse}
     * does. {@code BlockInfo.fee} is overwritten with the collected fees while the block is applied,
     * so {@code Block.getFee()} would not answer "what did this block declare".
     */
    static XAmount headerFee(Block block) {
        XdagBlock raw = block.getXdagBlock();
        Bytes32 header = Bytes32.wrap(raw.getField(0).getData());
        return XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG);
    }
}
