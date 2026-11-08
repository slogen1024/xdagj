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
package io.xdag.chain.exec;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.runtime.TrapException;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.WithdrawOutbox;
import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.WitnessRecorder;
import io.xdag.chain.exec.wasm.ExecResult;
import io.xdag.chain.exec.wasm.GasSchedule;
import io.xdag.chain.exec.wasm.InvalidModuleException;
import io.xdag.chain.exec.wasm.PreparedModule;
import io.xdag.chain.exec.wasm.WasmEngine;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.p2p.ChainRecords;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.chain.p2p.ProofKind;
import io.xdag.chain.p2p.SnapshotSync;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.XdagTime;

/**
 * One height of one chain. §7.2: credit the sender, precharge {@code gasLimit × gasPrice}, run the
 * frame, roll the frame back on a trap, then refund the unused gas either way. {@code INVALID_*}
 * inputs credit the value and do not run. Nested frames commit only when they return. Re-entering
 * a contract traps unless that selector is in the {@code xdag.reentrant} section.
 *
 * <p>Each applied seq stores a change-set so {@link #rewindTo} can put a chain back. That is what
 * {@code onSourceVoided} uses. Capping the log at K=128, snapshot pages and soft pre-exec stay
 * SP1-4. A seq is applied once; {@link #apply} rejects a seq that is not the successor of the head.
 */
public final class ChainExecutor implements ExecHost.Calls {

    public static final int MAX_CALL_DEPTH = 16;

    public record Tx(Bytes32 inputHash, ChainInput.Kind kind, ExecStatus forced, Bytes sender, Bytes contract,
            long value, long gasLimit, int selector, byte[] args, byte[] code) {
    }

    public record Outcome(int index, Bytes32 inputHash, ExecStatus status, long gasUsed, Bytes32 stateRoot,
            Bytes32 outboxRoot, Bytes32 eventsRoot, Bytes32 commitment, byte[] returnData) {
    }

    private final WasmEngine engine;
    private final ChainL1Store l1;
    private final ChainExecStore store;
    private final ChunkChain.RawBlockLookup blocks;
    private final int maxChunks;
    private final InputStreamBuilder inputs;
    private final Map<Bytes, Runtime> chains = new HashMap<>();
    private Runtime current;

    public ChainExecutor(WasmEngine engine, ChainL1Store l1, ChainExecStore store, ChunkChain.RawBlockLookup blocks,
            int maxChunks) {
        this.engine = engine;
        this.l1 = l1;
        this.store = store;
        this.blocks = blocks;
        this.maxChunks = maxChunks;
        this.inputs = new InputStreamBuilder(l1, store);
    }

    ChainExecStore store() {
        return store;
    }

    /** False while a deliverable source outbox is not in the exec store yet. */
    public boolean ready(Bytes chain, long seq) {
        return inputs.ready(chain, seq);
    }

    /**
     * Pull segment proofs for deliverable marks whose outbox is not local yet. HOLD leaves the
     * store alone. REJECT writes nothing. A verified segment becomes the outbox the next
     * {@link #ready} call sees.
     */
    public void fetchDeliveries(Bytes chain, long seq, SegmentProofs proofs) {
        if (proofs == null || store == null) {
            return;
        }
        for (TriggerList.Item mark : inputs.marks(chain, seq)) {
            AnchorBody body = anchor(mark.attester(), mark.seq());
            if (body == null) {
                continue;
            }
            Bytes32 src = chain32(mark.attester());
            Bytes32 dst = chain32(chain);
            if (store.outboxSize(src, dst, mark.seq()) >= 0 && store.outboxSize(src, dst, body.prevSeq()) >= 0) {
                continue;
            }
            SegmentProofs.Bodies got = proofs.pull(mark.attester(), mark.seq(), chain.toArray(), body.outboxMapRoot());
            if (got == null) {
                continue;
            }
            SegmentIntake.install(store, mark.attester(), chain.toArray(), mark.seq(), body.prevSeq(),
                    body.outboxMapRoot(), got.outbox(), got.segment());
        }
    }

    private AnchorBody anchor(byte[] src, long seq) {
        byte[] raw = l1.getAnchorRaw(Bytes.wrap(src), seq);
        if (raw == null) {
            return null;
        }
        AnchorRecord stored = AnchorRecord.decode(raw);
        if (stored == null || stored.status() == AnchorRecord.Status.VOIDED) {
            return null;
        }
        return AnchorBody.decode(raw);
    }

    /**
     * Install a snapshot of {@code seq} when its rebuilt state root is the anchor's. A pending pull
     * throws. A missing or disagreeing snapshot returns false and leaves no head.
     */
    public boolean bootstrap(Bytes chain, long seq, Bytes32 stateRoot, IntFunction<ChainSnapshotReplyMessage> pages) {
        if (store == null) {
            return false;
        }
        List<SparseMerkleTree.Kv> kvs;
        try {
            kvs = SnapshotSync.gather(pages);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            return false;
        }
        Bytes32 id = chain32(chain);
        try {
            for (SparseMerkleTree.Kv kv : kvs) {
                store.putState(id, kv.key(), kv.value());
            }
            store.putHead(id, seq, stateRoot);
            chains.remove(chain);
            Runtime rt = runtime(chain);
            return rt.seq == seq && rt.world.rootHash().equals(stateRoot);
        } catch (RuntimeException e) {
            for (SparseMerkleTree.Kv kv : kvs) {
                store.deleteState(id, kv.key());
            }
            store.deleteHead(id);
            chains.remove(chain);
            return false;
        }
    }

    /** Snapshot pages and outbox proofs a subscriber can pull for this chain's applied seq. */
    public void publish(Bytes chain, ChainRecords records, List<Bytes32> dests) {
        if (store == null || records == null || appliedSeq(chain) < 0) {
            return;
        }
        Runtime rt = runtime(chain);
        Bytes32 id = chain32(chain);
        List<SparseMerkleTree.Kv> kvs = new ArrayList<>();
        for (Pair<Bytes32, byte[]> row : store.readState(id)) {
            kvs.add(new SparseMerkleTree.Kv(row.getKey(), row.getValue()));
        }
        records.putSnapshot(id, rt.seq, kvs);
        Set<Bytes32> all = new java.util.LinkedHashSet<>();
        if (dests != null) {
            all.addAll(dests);
        }
        all.addAll(rt.entries.keySet());
        for (Bytes32 dst : all) {
            if (dst.equals(id)) {
                continue;
            }
            List<byte[]> list = rt.entries.getOrDefault(dst, List.of());
            records.putProof(id, rt.seq, ProofKind.OUTBOX_MAP, dst.toArray(),
                    ProofBodies.encodeOutbox(rt.outbox.proof(dst)));
            if (list.isEmpty()) {
                continue;
            }
            Mmr mmr = new Mmr();
            for (byte[] raw : list) {
                mmr.append(HashUtils.sha256(Bytes.wrap(raw)));
            }
            records.putProof(id, rt.seq, ProofKind.SEGMENT, ProofBodies.segmentKey(dst, 0, list.size()),
                    ProofBodies.encodeSegment(dst, 0, list.size(), mmr, list));
            int prev = rt.seq == 0L ? 0 : store.outboxSize(id, dst, rt.seq - 1L);
            if (prev > 0 && prev < list.size()) {
                records.putProof(id, rt.seq, ProofKind.SEGMENT, ProofBodies.segmentKey(dst, prev, list.size()),
                        ProofBodies.encodeSegment(dst, prev, list.size(), mmr, list.subList(prev, list.size())));
            }
        }
    }

    public List<Outcome> step(Bytes chain, long seq, long timestamp) {
        ChainRecord record = l1.getChain(chain);
        if (record == null) {
            throw new IllegalArgumentException("chain");
        }
        if (!inputs.ready(chain, seq)) {
            throw new IllegalStateException("outbox");
        }
        List<Tx> txs = new ArrayList<>();
        for (ChainInput input : inputs.inputs(chain, seq)) {
            txs.add(resolve(chain, input, record.maxCallGas()));
        }
        return apply(chain, seq, record.gasPriceNano(), record.maxCallGas(), timestamp, txs);
    }

    public List<Outcome> apply(Bytes chain, long seq, long gasPrice, long maxCallGas, long timestamp, List<Tx> txs) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq");
        }
        Runtime rt = runtime(chain);
        if (rt.seq >= 0 && seq != rt.seq + 1) {
            throw new IllegalStateException("seq " + seq + " after " + rt.seq);
        }
        long prevSeq = rt.seq;
        Bytes32 prevRoot = rt.world.rootHash();
        current = rt;
        List<Outcome> out = new ArrayList<>(txs.size());
        try {
            for (int k = 0; k < txs.size(); k++) {
                out.add(one(rt, chain, seq, gasPrice, maxCallGas, timestamp, k, txs.get(k)));
            }
            rt.seq = seq;
            flush(rt, chain, seq, out, prevSeq, prevRoot);
            return out;
        } finally {
            current = null;
        }
    }

    /**
     * Drop every seq above {@code seq}. {@code -1} clears the chain. The change-set of each dropped
     * seq must still be in the exec store.
     */
    public void rewindTo(Bytes chain, long seq) {
        if (seq < -1L) {
            throw new IllegalArgumentException("seq");
        }
        Runtime rt = runtime(chain);
        Bytes32 id = chain32(chain);
        while (rt.seq > seq) {
            ExecChangeSet change = ExecChangeSet.decode(store.getChangeSet(id, rt.seq));
            if (change == null) {
                throw new IllegalStateException("changeset " + rt.seq);
            }
            List<Pair<Bytes32, byte[]>> writes = new ArrayList<>(change.state.size());
            java.util.Map<Bytes32, byte[]> preimages = new java.util.HashMap<>();
            for (Pair<Bytes32, byte[]> row : change.state) {
                byte[] value = row.getValue();
                writes.add(Pair.of(row.getKey(), value));
                preimages.put(row.getKey(), value == null ? new byte[0] : value);
            }
            if (!writes.isEmpty()) {
                store.applyState(id, writes);
            }
            rt.world.undo(preimages);
            for (ExecChangeSet.MmrCut cut : change.mmr) {
                List<byte[]> list = rt.entries.computeIfAbsent(cut.dst(), k -> new ArrayList<>());
                while (list.size() > cut.oldSize()) {
                    list.remove(list.size() - 1);
                }
                rebuild(rt, id, cut.dst(), list);
            }
            for (Bytes32 dst : change.sized) {
                store.deleteOutboxSize(id, dst, rt.seq);
            }
            for (int k = 0; k < change.openings; k++) {
                store.deleteOpening(id, rt.seq, k);
            }
            for (Bytes32 hash : change.receipts) {
                store.deleteReceipt(id, hash);
            }
            store.deleteChangeSet(id, rt.seq);
            if (!rt.world.rootHash().equals(change.prevRoot)) {
                throw new IllegalStateException("rewind root");
            }
            if (change.prevSeq < 0L) {
                store.deleteHead(id);
                rt.seq = -1L;
            } else {
                store.putHead(id, change.prevSeq, change.prevRoot);
                rt.seq = change.prevSeq;
            }
        }
    }

    /** Last applied seq, or {@code -1} when this chain has no head. */
    public long appliedSeq(Bytes chain) {
        return runtime(chain).seq;
    }

    public long balance(Bytes chain, byte[] address) {
        return runtime(chain).world.root().balance(address);
    }

    public long feePool(Bytes chain) {
        return runtime(chain).world.root().feePool();
    }

    public Bytes32 stateRoot(Bytes chain) {
        return runtime(chain).world.rootHash();
    }

    public Bytes32 outboxRoot(Bytes chain) {
        return runtime(chain).outbox.root();
    }

    public byte[] storage(Bytes chain, Bytes contract, byte[] userKey) {
        return runtime(chain).world.get(contract, userKey);
    }

    /**
     * Read-only {@code query}. Writes trap. The layer is discarded. {@code null} when the contract
     * has no code loaded or the call traps.
     */
    public byte[] query(Bytes chain, Bytes contract, int selector, byte[] args) {
        Runtime rt = runtime(chain);
        if (!rt.modules.containsKey(contract)) {
            return null;
        }
        current = rt;
        try {
            ExecHost host = host(rt, rt.world.root().child(), contract.toArray(), ChainLedger.address20(null), selector,
                    args, 0L, 0L, 0L, 1, false);
            ExecHost.Result result = run(rt, host, "query", GasSchedule.MAX_CALL_GAS);
            return result.code() == 0 ? result.returnData() : null;
        } finally {
            current = null;
        }
    }

    /**
     * Gas of one discarded {@code call} on the executed head. The layer is not committed. {@code 0}
     * when the contract has no code loaded.
     */
    public long estimate(Bytes chain, Bytes contract, int selector, byte[] args) {
        Runtime rt = runtime(chain);
        if (!rt.modules.containsKey(contract)) {
            return 0L;
        }
        current = rt;
        try {
            ExecHost host = host(rt, rt.world.root().child(), contract.toArray(), ChainLedger.address20(null), selector,
                    args == null ? new byte[0] : args, 0L, 0L, 0L, 1, true);
            return run(rt, host, "call", GasSchedule.MAX_CALL_GAS).gas();
        } finally {
            current = null;
        }
    }

    public SparseMerkleTree.Proof proof(Bytes chain, Bytes contract, byte[] userKey) {
        return runtime(chain).world.proof(ChainLedger.smtKey(contract, userKey == null ? new byte[0] : userKey));
    }

    public byte[] opening(Bytes chain, long seq, long index) {
        return store.getOpening(chain32(chain), seq, index);
    }

    public byte[] receiptByHash(Bytes32 blockHash) {
        return store.findReceipt(blockHash);
    }

    @Override
    public ExecHost.Result call(ExecHost caller, byte[] contract, int selector, byte[] args, long value) {
        if (caller.depth >= MAX_CALL_DEPTH) {
            return new ExecHost.Result(1, 0L, new byte[0]);
        }
        Bytes target = Bytes.wrap(ChainLedger.address20(contract));
        Set<Integer> marks = current.marks.getOrDefault(target, Set.of());
        if (onStack(current, target) && !marks.contains(selector)) {
            throw new TrapException("reentrant");
        }
        if (value > caller.layer.balance(caller.self)) {
            return new ExecHost.Result(1, 0L, new byte[0]);
        }
        if (!current.modules.containsKey(target)) {
            return new ExecHost.Result(1, 0L, new byte[0]);
        }
        long budget = Math.max(0L, caller.gasLeft());
        ExecHost child = new ExecHost(current.world, caller.layer.child(), this, caller.witness, current.chain,
                target.toArray(), caller.self, selector, args, value, caller.height, caller.timestamp,
                caller.depth + 1, true);
        ExecHost.Result result = run(current, child, "call", budget);
        if (result.code() == 0) {
            child.layer.commit();
            caller.events.addAll(child.events);
            caller.sent.addAll(child.sent);
        }
        return result;
    }

    private Outcome one(Runtime rt, Bytes chain, long seq, long gasPrice, long maxCallGas, long timestamp, int index,
            Tx tx) {
        ExecWorld.Layer input = rt.world.root();
        if (tx.kind() == ChainInput.Kind.SYSTEM) {
            return reward(rt, chain, input, index, tx);
        }
        if (!credit(input, tx.sender().toArray(), tx.value())) {
            return finish(rt, chain, input, index, tx, ExecStatus.FAILED, 0L, List.of(), new byte[0]);
        }
        if (tx.forced() != null) {
            return finish(rt, chain, input, index, tx, tx.forced(), 0L, List.of(), new byte[0]);
        }
        if (tx.gasLimit() < 0 || tx.gasLimit() > maxCallGas || tx.gasLimit() > GasSchedule.MAX_CALL_GAS
                || gasPrice < 0) {
            return finish(rt, chain, input, index, tx, ExecStatus.FAILED, 0L, List.of(), new byte[0]);
        }
        long precharge;
        try {
            precharge = Math.multiplyExact(tx.gasLimit(), gasPrice);
        } catch (ArithmeticException e) {
            return finish(rt, chain, input, index, tx, ExecStatus.INSUFFICIENT_GAS, 0L, List.of(), new byte[0]);
        }
        if (input.balance(tx.sender().toArray()) < precharge) {
            return finish(rt, chain, input, index, tx, ExecStatus.INSUFFICIENT_GAS, 0L, List.of(), new byte[0]);
        }
        input.setBalance(tx.sender().toArray(), input.balance(tx.sender().toArray()) - precharge);
        if (!install(rt, tx.contract(), tx.code())) {
            input.setBalance(tx.sender().toArray(), input.balance(tx.sender().toArray()) + precharge);
            ExecStatus status = tx.kind() == ChainInput.Kind.DEPLOY ? ExecStatus.INVALID_CODE : ExecStatus.FAILED;
            return finish(rt, chain, input, index, tx, status, 0L, List.of(), new byte[0]);
        }
        String export = tx.kind() == ChainInput.Kind.DEPLOY ? "init"
                : tx.kind() == ChainInput.Kind.MESSAGE ? "recv" : "call";
        WitnessRecorder witness = new WitnessRecorder();
        ExecHost host = new ExecHost(rt.world, input.child(), this, witness, chain, tx.contract().toArray(),
                tx.sender().toArray(), tx.selector(), tx.args(), tx.value(), seq, timestamp, 1, true);
        ExecHost.Result result = run(rt, host, export, tx.gasLimit());
        boolean ok = result.code() == 0;
        if (ok) {
            host.layer.commit();
        }
        long used = Math.min(result.gas(), tx.gasLimit());
        input.setBalance(tx.sender().toArray(),
                input.balance(tx.sender().toArray()) + (tx.gasLimit() - used) * gasPrice);
        input.setFee(input.feePool() + used * gasPrice);
        return finish(rt, chain, input, index, tx, ok ? ExecStatus.OK : ExecStatus.FAILED, used,
                ok ? host.sent : List.of(), ok ? result.returnData() : new byte[0], ok ? host.events : List.of());
    }

    private Outcome finish(Runtime rt, Bytes chain, ExecWorld.Layer input, int index, Tx tx, ExecStatus status,
            long gasUsed, List<ExecHost.Sent> sent, byte[] returnData) {
        return finish(rt, chain, input, index, tx, status, gasUsed, sent, returnData, List.of());
    }

    private Outcome finish(Runtime rt, Bytes chain, ExecWorld.Layer input, int index, Tx tx, ExecStatus status,
            long gasUsed, List<ExecHost.Sent> sent, byte[] returnData, List<byte[]> events) {
        for (ExecHost.Sent s : sent) {
            append(rt, chain, s);
        }
        input.commit();
        Bytes32 state = rt.world.rootHash();
        Bytes32 outbox = rt.outbox.root();
        Bytes32 eventsRoot = eventsRoot(events);
        Bytes32 commitment = ProofBodies.commitment(tx.inputHash(), state, outbox, eventsRoot, status.code(), gasUsed);
        return new Outcome(index, tx.inputHash(), status, gasUsed, state, outbox, eventsRoot, commitment, returnData);
    }

    private ExecHost host(Runtime rt, ExecWorld.Layer layer, byte[] self, byte[] payer, int selector, byte[] args,
            long offered, long height, long timestamp, int depth, boolean writes) {
        return new ExecHost(rt.world, layer, this, new WitnessRecorder(), rt.chain, self, payer, selector, args,
                offered, height, timestamp, depth, writes);
    }

    private ExecHost.Result run(Runtime rt, ExecHost host, String export, long gas) {
        Bytes self = Bytes.wrap(host.self);
        if (!rt.modules.containsKey(self)) {
            return new ExecHost.Result(1, 0L, new byte[0]);
        }
        rt.stack.addLast(self);
        try {
            ExecResult result = engine.execute(rt.modules.get(self), export, host, gas);
            if (result.trapped()) {
                return new ExecHost.Result(1, result.gasUsed(), new byte[0]);
            }
            return new ExecHost.Result(0, result.gasUsed(), host.returnData());
        } finally {
            rt.stack.removeLast();
        }
    }

    private boolean install(Runtime rt, Bytes contract, byte[] code) {
        if (rt.modules.containsKey(contract)) {
            return true;
        }
        if (code == null) {
            return false;
        }
        try {
            Set<Integer> marks = ReentrantMarks.selectors(code);
            Bytes32 hash = HashUtils.sha256(Bytes.wrap(code));
            rt.modules.put(contract, engine.prepare(hash, Bytes.wrap(code)));
            rt.marks.put(contract, marks);
            return true;
        } catch (InvalidModuleException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean onStack(Runtime rt, Bytes contract) {
        for (Bytes frame : rt.stack) {
            if (frame.equals(contract)) {
                return true;
            }
        }
        return false;
    }

    private static boolean credit(ExecWorld.Layer layer, byte[] address, long value) {
        if (value < 0) {
            return false;
        }
        try {
            layer.setBalance(address, Math.addExact(layer.balance(address), value));
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private void append(Runtime rt, Bytes chain, ExecHost.Sent sent) {
        Bytes32 dst = chain32(sent.dst());
        List<byte[]> list = rt.entries.computeIfAbsent(dst, k -> new ArrayList<>());
        rt.mmrBefore.putIfAbsent(dst, list.size());
        byte[] body = WithdrawOutbox.send(chain, sent.dst(), sent.sender(), sent.contract(), sent.selector(),
                sent.args(), sent.value(), sent.height(), list.size());
        list.add(body);
        Mmr mmr = new Mmr();
        for (byte[] entry : list) {
            mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
        }
        rt.outbox.put(dst, mmr.root(), mmr.size());
        rt.dirtyDst.add(dst);
    }

    private static Bytes32 eventsRoot(List<byte[]> events) {
        Mmr mmr = new Mmr();
        for (byte[] event : events) {
            ByteBuffer buf = ByteBuffer.allocate(1 + event.length);
            buf.put((byte) 0x04);
            buf.put(event);
            mmr.append(HashUtils.sha256(Bytes.wrap(buf.array())));
        }
        return mmr.root();
    }

    private Outcome reward(Runtime rt, Bytes chain, ExecWorld.Layer input, int index, Tx tx) {
        long fees = input.feePool();
        if (fees != 0L) {
            try {
                input.setBalance(tx.sender().toArray(), Math.addExact(input.balance(tx.sender().toArray()), fees));
            } catch (ArithmeticException e) {
                return finish(rt, chain, input, index, tx, ExecStatus.FAILED, 0L, List.of(), new byte[0]);
            }
            input.setFee(0L);
        }
        return finish(rt, chain, input, index, tx, ExecStatus.OK, 0L, List.of(), new byte[0]);
    }

    private Tx resolve(Bytes chain, ChainInput input, long maxCallGas) {
        if (input.kind() == ChainInput.Kind.SYSTEM) {
            return new Tx(input.inputHash(), ChainInput.Kind.SYSTEM, null, input.contract(), input.contract(), 0L, 0L,
                    0, new byte[0], null);
        }
        if (input.kind() == ChainInput.Kind.MESSAGE) {
            ChainInput.Message message = input.message();
            if (message == null) {
                return new Tx(input.inputHash(), ChainInput.Kind.MESSAGE, ExecStatus.INVALID_FORMAT, Bytes.wrap(new byte[20]),
                        input.contract(), 0L, 0L, 0, new byte[0], null);
            }
            ContractRecord recorded = l1.getContract(input.contract());
            byte[] code = recorded == null ? null : bytes(l1.getCode(recorded.codeHash()));
            ExecStatus forced = code == null ? ExecStatus.FAILED : forced(input.l1Status());
            return new Tx(input.inputHash(), ChainInput.Kind.MESSAGE, forced, Bytes.wrap(message.sender()),
                    input.contract(), message.value(), maxCallGas, message.selector(), message.args(), code);
        }
        Block block = blocks == null ? null : blocks.get(input.inputHash());
        byte[] sender = new byte[20];
        long value = 0L;
        if (block != null) {
            for (Address in : block.getInputs()) {
                if (in.getIsAddress() && in.getType() == XDAG_FIELD_INPUT) {
                    sender = ChainIdsAddress(in);
                    break;
                }
            }
            for (Address out : block.getOutputs()) {
                if (out.getIsAddress() && out.getType() == XDAG_FIELD_OUTPUT
                        && chain.equals(ChainIdsAddressBytes(out))) {
                    value = Long.parseLong(out.getAmount().toString());
                    break;
                }
            }
        }
        ExecStatus forced = forced(input.l1Status());
        if (block == null) {
            return new Tx(input.inputHash(), input.kind(), ExecStatus.INVALID_FORMAT, Bytes.wrap(sender),
                    input.contract(), value, 0L, 0, new byte[0], null);
        }
        if (forced != null || input.kind() == ChainInput.Kind.OTHER) {
            ExecStatus status = forced == null ? ExecStatus.INVALID_FORMAT : forced;
            return new Tx(input.inputHash(), input.kind(), status, Bytes.wrap(sender), input.contract(), value, 0L, 0,
                    new byte[0], null);
        }
        Classified classified = ChainBlockClassifier.classify(block);
        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        if (input.kind() == ChainInput.Kind.CALL && classified.kind() == io.xdag.chain.ext.ExtKind.CALL
                && classified.isOk()) {
            CallExt call = classified.as(CallExt.class);
            byte[] args = call.argsByChain() ? assemble(call.argsChainHead(), minEpoch) : call.inlineArgs().toArray();
            if (args == null) {
                return new Tx(input.inputHash(), input.kind(), ExecStatus.FAILED, Bytes.wrap(sender), input.contract(),
                        value, 0L, 0, new byte[0], null);
            }
            ContractRecord contract = l1.getContract(input.contract());
            byte[] code = contract == null ? null : bytes(l1.getCode(contract.codeHash()));
            return new Tx(input.inputHash(), input.kind(), code == null ? ExecStatus.FAILED : null, Bytes.wrap(sender),
                    input.contract(), value, call.gasLimit(), call.selector(), args, code);
        }
        if (input.kind() == ChainInput.Kind.DEPLOY && classified.kind() == io.xdag.chain.ext.ExtKind.DEPLOY
                && classified.isOk()) {
            DeployExt deploy = classified.as(DeployExt.class);
            byte[] args = deploy.argsByChain() ? assemble(deploy.argsChainHead(), minEpoch)
                    : deploy.inlineArgs().toArray();
            if (args == null) {
                return new Tx(input.inputHash(), input.kind(), ExecStatus.FAILED, Bytes.wrap(sender), input.contract(),
                        value, 0L, 0, new byte[0], null);
            }
            byte[] code = bytes(l1.getCode(deploy.codeHash()));
            return new Tx(input.inputHash(), input.kind(), code == null ? ExecStatus.INVALID_CODE : null,
                    Bytes.wrap(sender), input.contract(), value, deploy.gasLimit(), 0, args, code);
        }
        return new Tx(input.inputHash(), input.kind(), ExecStatus.INVALID_FORMAT, Bytes.wrap(sender), input.contract(),
                value, 0L, 0, new byte[0], null);
    }

    private byte[] assemble(Bytes32 head, long minEpoch) {
        if (blocks == null) {
            return null;
        }
        var assembled = ChunkChain.assemble(head, blocks, maxChunks, minEpoch);
        return assembled.isOk() ? assembled.value().toArray() : null;
    }

    private static ExecStatus forced(InputStatus status) {
        return switch (status) {
            case OK -> null;
            case INVALID_FORMAT -> ExecStatus.INVALID_FORMAT;
            case INVALID_FEE -> ExecStatus.INVALID_FEE;
            case CODE_TOO_LARGE -> ExecStatus.CODE_TOO_LARGE;
        };
    }

    private Runtime runtime(Bytes chain) {
        return chains.computeIfAbsent(chain, this::load);
    }

    private Runtime load(Bytes chain) {
        Runtime rt = new Runtime(chain);
        Bytes32 id = chain32(chain);
        ChainExecStore.Head head = store.getHead(id);
        if (head != null) {
            rt.world.load(store.readState(id));
            if (!rt.world.rootHash().equals(head.stateRoot())) {
                throw new IllegalStateException("state root");
            }
            rt.seq = head.seq();
        }
        for (Pair<Bytes32, byte[]> row : store.readMmr(id)) {
            List<byte[]> entries = decodeMmr(row.getValue());
            rt.entries.put(row.getKey(), entries);
            Mmr mmr = new Mmr();
            for (byte[] entry : entries) {
                mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
            }
            rt.outbox.put(row.getKey(), mmr.root(), mmr.size());
        }
        return rt;
    }

    private void flush(Runtime rt, Bytes chain, long seq, List<Outcome> outcomes, long prevSeq, Bytes32 prevRoot) {
        Bytes32 id = chain32(chain);
        List<Pair<Bytes32, byte[]>> preimage = new ArrayList<>();
        for (java.util.Map.Entry<Bytes32, byte[]> entry : rt.world.preimages().entrySet()) {
            byte[] value = entry.getValue();
            preimage.add(Pair.of(entry.getKey(), value == null || value.length == 0 ? null : value));
        }
        List<ExecChangeSet.MmrCut> cuts = new ArrayList<>();
        for (java.util.Map.Entry<Bytes32, Integer> entry : rt.mmrBefore.entrySet()) {
            cuts.add(new ExecChangeSet.MmrCut(entry.getKey(), entry.getValue()));
        }
        List<Bytes32> sized = new ArrayList<>(rt.entries.keySet());
        List<Bytes32> receipts = new ArrayList<>(outcomes.size());
        for (Outcome outcome : outcomes) {
            receipts.add(outcome.inputHash());
        }
        byte[] change = new ExecChangeSet(prevSeq, prevRoot, preimage, cuts, sized, outcomes.size(), receipts).encode();
        List<Pair<Bytes32, byte[]>> writes = new ArrayList<>(rt.world.dirtyPuts());
        for (Bytes32 key : rt.world.dirtyDeletes()) {
            writes.add(Pair.of(key, null));
        }
        if (!writes.isEmpty()) {
            store.applyState(id, writes);
        }
        rt.world.clean();
        rt.mmrBefore.clear();
        store.putHead(id, seq, rt.world.rootHash());
        for (Bytes32 dst : rt.dirtyDst) {
            store.putMmr(id, dst, encodeMmr(rt.entries.get(dst)));
        }
        rt.dirtyDst.clear();
        for (java.util.Map.Entry<Bytes32, List<byte[]>> entry : rt.entries.entrySet()) {
            store.putOutboxSize(id, entry.getKey(), seq, entry.getValue().size());
        }
        store.putChangeSet(id, seq, change);
        for (Outcome outcome : outcomes) {
            store.putOpening(id, seq, outcome.index(), ProofBodies.encodeCommitment(new ProofBodies.Opened(
                    outcome.inputHash(), outcome.stateRoot(), outcome.outboxRoot(), outcome.eventsRoot(),
                    outcome.status().code(), outcome.gasUsed())));
            store.putReceipt(id, outcome.inputHash(), receipt(outcome));
        }
    }

    private void rebuild(Runtime rt, Bytes32 chain, Bytes32 dst, List<byte[]> list) {
        Mmr mmr = new Mmr();
        for (byte[] entry : list) {
            mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
        }
        rt.outbox.put(dst, mmr.root(), mmr.size());
        store.putMmr(chain, dst, encodeMmr(list));
    }

    private static byte[] receipt(Outcome outcome) {
        byte[] ret = outcome.returnData();
        ByteBuffer buf = ByteBuffer.allocate(1 + Long.BYTES + Bytes32.SIZE + Integer.BYTES + ret.length);
        buf.put((byte) outcome.status().code());
        buf.putLong(outcome.gasUsed());
        buf.put(outcome.commitment().toArray());
        buf.putInt(ret.length);
        buf.put(ret);
        return buf.array();
    }

    public static byte[] encodeMmr(List<byte[]> entries) {
        int size = Integer.BYTES;
        for (byte[] entry : entries) {
            size += Integer.BYTES + entry.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(entries.size());
        for (byte[] entry : entries) {
            buf.putInt(entry.length);
            buf.put(entry);
        }
        return buf.array();
    }

    public static int mmrCount(byte[] blob) {
        if (blob == null || blob.length < Integer.BYTES) {
            return 0;
        }
        int n = ByteBuffer.wrap(blob).getInt();
        return n < 0 ? 0 : n;
    }

    public static List<byte[]> decodeMmr(byte[] blob) {
        ByteBuffer buf = ByteBuffer.wrap(blob);
        if (buf.remaining() < Integer.BYTES) {
            throw new IllegalStateException("mmr");
        }
        int n = buf.getInt();
        if (n < 0) {
            throw new IllegalStateException("mmr");
        }
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (buf.remaining() < Integer.BYTES) {
                throw new IllegalStateException("mmr");
            }
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw new IllegalStateException("mmr");
            }
            byte[] entry = new byte[len];
            buf.get(entry);
            out.add(entry);
        }
        return out;
    }

    public static Bytes32 chain32(Bytes chain) {
        return chain32(chain.toArray());
    }

    public static Bytes32 chain32(byte[] raw) {
        byte[] out = new byte[32];
        byte[] address = ChainLedger.address20(raw);
        System.arraycopy(address, 0, out, 12, 20);
        return Bytes32.wrap(out);
    }

    private static byte[] ChainIdsAddress(Address address) {
        return io.xdag.chain.l1.ChainIds.address20(address).toArray();
    }

    private static Bytes ChainIdsAddressBytes(Address address) {
        return io.xdag.chain.l1.ChainIds.address20(address);
    }

    private static byte[] bytes(Bytes value) {
        return value == null ? null : value.toArray();
    }

    private static final class Runtime {
        final Bytes chain;
        final ExecWorld world = new ExecWorld();
        final OutboxMap outbox = new OutboxMap();
        final Map<Bytes32, List<byte[]>> entries = new HashMap<>();
        final Map<Bytes32, Integer> mmrBefore = new HashMap<>();
        final Set<Bytes32> dirtyDst = new java.util.HashSet<>();
        final Map<Bytes, PreparedModule> modules = new HashMap<>();
        final Map<Bytes, Set<Integer>> marks = new HashMap<>();
        final ArrayDeque<Bytes> stack = new ArrayDeque<>();
        long seq = -1L;

        Runtime(Bytes chain) {
            this.chain = chain;
        }
    }
}
