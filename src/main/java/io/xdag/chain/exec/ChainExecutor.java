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

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.runtime.TrapException;

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
import io.xdag.chain.p2p.ProofBodies;
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
 * <p>Change-set retention and rewind are SP1-4. A seq is applied once; {@link #apply} rejects a
 * seq that is not the successor of the stored head.
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
        this.inputs = new InputStreamBuilder(l1);
    }

    public List<Outcome> step(Bytes chain, long seq, long timestamp) {
        ChainRecord record = l1.getChain(chain);
        if (record == null) {
            throw new IllegalArgumentException("chain");
        }
        List<Tx> txs = new ArrayList<>();
        for (ChainInput input : inputs.inputs(chain, seq)) {
            txs.add(resolve(chain, input));
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
        current = rt;
        List<Outcome> out = new ArrayList<>(txs.size());
        try {
            for (int k = 0; k < txs.size(); k++) {
                out.add(one(rt, chain, seq, gasPrice, maxCallGas, timestamp, k, txs.get(k)));
            }
            rt.seq = seq;
            flush(rt, chain, seq, out);
            return out;
        } finally {
            current = null;
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
        String export = tx.kind() == ChainInput.Kind.DEPLOY ? "init" : "call";
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
        byte[] body = outboxEntry(chain, sent.dst(), sent.sender(), sent.contract(), sent.selector(), sent.args(),
                sent.value(), sent.height(), list.size());
        list.add(body);
        Mmr mmr = new Mmr();
        for (byte[] entry : list) {
            mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
        }
        rt.outbox.put(dst, mmr.root(), mmr.size());
        rt.dirtyDst.add(dst);
    }

    static byte[] outboxEntry(Bytes src, byte[] dst, byte[] sender, byte[] contract, int selector, byte[] args,
            long value, long height, long index) {
        byte[] d = ChainLedger.address20(dst);
        byte[] s = ChainLedger.address20(sender);
        byte[] c = ChainLedger.address20(contract);
        byte[] a = args == null ? new byte[0] : args;
        ByteBuffer buf = ByteBuffer.allocate(20 + 20 + Long.BYTES + 20 + 20 + Integer.BYTES + Long.BYTES + Long.BYTES
                + a.length);
        buf.put(ChainLedger.address20(src.toArray()));
        buf.put(d);
        buf.putLong(index);
        buf.put(s);
        buf.put(c);
        buf.putInt(selector);
        buf.putLong(value);
        buf.putLong(height);
        buf.put(a);
        return buf.array();
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

    private Tx resolve(Bytes chain, ChainInput input) {
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
            List<byte[]> entries = decodeEntries(row.getValue());
            rt.entries.put(row.getKey(), entries);
            Mmr mmr = new Mmr();
            for (byte[] entry : entries) {
                mmr.append(HashUtils.sha256(Bytes.wrap(entry)));
            }
            rt.outbox.put(row.getKey(), mmr.root(), mmr.size());
        }
        return rt;
    }

    private void flush(Runtime rt, Bytes chain, long seq, List<Outcome> outcomes) {
        Bytes32 id = chain32(chain);
        List<Pair<Bytes32, byte[]>> writes = new ArrayList<>(rt.world.dirtyPuts());
        for (Bytes32 key : rt.world.dirtyDeletes()) {
            writes.add(Pair.of(key, null));
        }
        if (!writes.isEmpty()) {
            store.applyState(id, writes);
        }
        rt.world.clean();
        store.putHead(id, seq, rt.world.rootHash());
        for (Bytes32 dst : rt.dirtyDst) {
            store.putMmr(id, dst, encodeEntries(rt.entries.get(dst)));
        }
        rt.dirtyDst.clear();
        for (Outcome outcome : outcomes) {
            store.putOpening(id, seq, outcome.index(), ProofBodies.encodeCommitment(new ProofBodies.Opened(
                    outcome.inputHash(), outcome.stateRoot(), outcome.outboxRoot(), outcome.eventsRoot(),
                    outcome.status().code(), outcome.gasUsed())));
            store.putReceipt(id, outcome.inputHash(), receipt(outcome));
        }
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

    private static byte[] encodeEntries(List<byte[]> entries) {
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

    private static List<byte[]> decodeEntries(byte[] blob) {
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
