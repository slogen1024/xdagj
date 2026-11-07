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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.runtime.TrapException;

import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.state.WitnessRecorder;
import io.xdag.chain.exec.wasm.HostContext;

/**
 * One frame. Storage writes land in {@link #layer}. A nested call that fails drops its layer, so
 * the caller's writes stay. {@code transfer_from_caller} debits {@link #payer} and credits this
 * frame's contract, capped by {@link #offered}.
 */
final class ExecHost implements HostContext {

    interface Calls {
        Result call(ExecHost caller, byte[] contract, int selector, byte[] args, long value);
    }

    record Result(int code, long gas, byte[] returnData) {
    }

    record Sent(byte[] dst, byte[] contract, int selector, byte[] args, long value, byte[] sender, long height) {
    }

    final ExecWorld world;
    final ExecWorld.Layer layer;
    final Calls calls;
    final WitnessRecorder witness;
    final Bytes chain;
    final byte[] self;
    final byte[] payer;
    final int selector;
    final byte[] args;
    final long offered;
    final long height;
    final long timestamp;
    final int depth;
    final boolean writesAllowed;
    final List<byte[]> events = new ArrayList<>();
    final List<Sent> sent = new ArrayList<>();

    private long claimed;
    private byte[] returnData = new byte[0];
    private byte[] lastReturn = new byte[0];
    private long lastGas;
    private LongSupplier gasLeft = () -> 0L;

    ExecHost(ExecWorld world, ExecWorld.Layer layer, Calls calls, WitnessRecorder witness, Bytes chain, byte[] self,
            byte[] payer, int selector, byte[] args, long offered, long height, long timestamp, int depth,
            boolean writesAllowed) {
        this.world = world;
        this.layer = layer;
        this.calls = calls;
        this.witness = witness;
        this.chain = chain;
        this.self = ChainLedger.address20(self);
        this.payer = ChainLedger.address20(payer);
        this.selector = selector;
        this.args = args == null ? new byte[0] : args.clone();
        this.offered = offered;
        this.height = height;
        this.timestamp = timestamp;
        this.depth = depth;
        this.writesAllowed = writesAllowed;
    }

    @Override
    public byte[] input() {
        return args.clone();
    }

    @Override
    public int selector() {
        return selector;
    }

    @Override
    public byte[] caller() {
        return payer.clone();
    }

    @Override
    public byte[] selfAddress() {
        return self.clone();
    }

    @Override
    public byte[] chainId() {
        return ChainLedger.address20(chain.toArray());
    }

    @Override
    public long value() {
        return offered;
    }

    @Override
    public long height() {
        return height;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public byte[] returnData() {
        return returnData.clone();
    }

    @Override
    public void setReturnData(byte[] data) {
        returnData = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] storageGet(byte[] key) {
        checkKey(key);
        touch(key, world.get(Bytes.wrap(self), key), false);
        byte[] visible = layer.get(Bytes.wrap(self), key);
        return visible == null ? null : visible.clone();
    }

    @Override
    public void storagePut(byte[] key, byte[] value) {
        checkKey(key);
        if (value.length > ChainLedger.MAX_VALUE_BYTES) {
            throw new TrapException("value length");
        }
        byte[] pre = world.get(Bytes.wrap(self), key);
        touch(key, pre, true);
        layer.put(Bytes.wrap(self), key, value);
    }

    @Override
    public void storageDelete(byte[] key) {
        checkKey(key);
        touch(key, world.get(Bytes.wrap(self), key), true);
        layer.remove(Bytes.wrap(self), key);
    }

    @Override
    public List<StorageEntry> storageScan(byte[] prefix, int max) {
        List<ExecWorld.HostView> hits = layer.scan(Bytes.wrap(self), prefix, max);
        List<StorageEntry> out = new ArrayList<>(hits.size());
        for (ExecWorld.HostView hit : hits) {
            touch(hit.key(), world.get(Bytes.wrap(self), hit.key()), false);
            out.add(new StorageEntry(hit.key(), hit.value()));
        }
        return out;
    }

    @Override
    public int nonEmptySiblings(byte[] key) {
        return SparseMerkleTree.nonEmptySiblings(world.proof(ChainLedger.smtKey(Bytes.wrap(self), key)));
    }

    @Override
    public long balanceOf(byte[] address) {
        return layer.balance(address);
    }

    @Override
    public int transfer(byte[] to, long amount) {
        if (amount < 0) {
            throw new TrapException("negative amount");
        }
        long from = layer.balance(self);
        if (from < amount) {
            return 1;
        }
        layer.setBalance(self, from - amount);
        layer.setBalance(to, Math.addExact(layer.balance(to), amount));
        return 0;
    }

    @Override
    public int transferFromCaller(long amount) {
        if (amount < 0) {
            throw new TrapException("negative amount");
        }
        if (amount > offered - claimed) {
            return 1;
        }
        long from = layer.balance(payer);
        if (from < amount) {
            return 1;
        }
        layer.setBalance(payer, from - amount);
        layer.setBalance(self, Math.addExact(layer.balance(self), amount));
        claimed += amount;
        return 0;
    }

    @Override
    public int withdraw(byte[] to, long amount) {
        return send(new byte[20], to, 0, new byte[0], amount);
    }

    @Override
    public int call(byte[] contract, int selector, byte[] args, long value) {
        Result result = calls.call(this, contract, selector, args, value);
        lastReturn = result.returnData();
        lastGas = result.gas();
        return result.code();
    }

    @Override
    public byte[] lastCallReturn() {
        return lastReturn.clone();
    }

    @Override
    public long lastCallGas() {
        return lastGas;
    }

    @Override
    public void bindGasLeft(LongSupplier left) {
        gasLeft = left == null ? () -> 0L : left;
    }

    long gasLeft() {
        return gasLeft.getAsLong();
    }

    @Override
    public int send(byte[] dstChain, byte[] contract, int selector, byte[] args, long value) {
        if (value < 0) {
            throw new TrapException("negative amount");
        }
        long from = layer.balance(self);
        if (from < value) {
            return 1;
        }
        layer.setBalance(self, from - value);
        layer.setInflight(self, Math.addExact(layer.inflight(self), value));
        sent.add(new Sent(ChainLedger.address20(dstChain), ChainLedger.address20(contract), selector,
                args == null ? new byte[0] : args.clone(), value, self.clone(), height));
        return 0;
    }

    @Override
    public void emit(byte[] topic, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + topic.length + data.length);
        buf.putInt(topic.length);
        buf.put(topic);
        buf.put(data);
        events.add(buf.array());
    }

    @Override
    public boolean writesAllowed() {
        return writesAllowed;
    }

    private void touch(byte[] userKey, byte[] pre, boolean write) {
        Bytes32 smt = ChainLedger.smtKey(Bytes.wrap(self), userKey);
        SparseMerkleTree.Proof proof = world.proof(smt);
        if (write) {
            witness.write(smt, pre, proof);
        } else {
            witness.read(smt, pre, proof);
        }
    }

    private static void checkKey(byte[] key) {
        if (key.length > ChainLedger.MAX_USER_KEY) {
            throw new TrapException("key length");
        }
    }
}
