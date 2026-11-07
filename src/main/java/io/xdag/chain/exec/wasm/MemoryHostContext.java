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

package io.xdag.chain.exec.wasm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;

import com.dylibso.chicory.runtime.TrapException;

/**
 * In-memory {@link HostContext}. Scan order is unsigned byte order of the key. {@code call}
 * returns 1 and does not run a callee; the executor lands in SP1-3.
 */
public final class MemoryHostContext implements HostContext {

    private byte[] input = new byte[0];
    private int selector;
    private byte[] caller = new byte[GasSchedule.ADDRESS_BYTES];
    private byte[] self = new byte[GasSchedule.ADDRESS_BYTES];
    private byte[] chainId = new byte[GasSchedule.ADDRESS_BYTES];
    private long value;
    private long claimed;
    private long height;
    private long timestamp;
    private byte[] returnData = new byte[0];
    private boolean writesAllowed = true;
    private final Map<Bytes, byte[]> storage = new TreeMap<>();
    private final Map<Bytes, Integer> siblings = new TreeMap<>();
    private final Map<Bytes, Long> balances = new TreeMap<>();
    private final List<Withdrawal> withdrawals = new ArrayList<>();
    private final List<Outbound> outbox = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    public MemoryHostContext setInput(byte[] input) {
        this.input = input == null ? new byte[0] : input.clone();
        return this;
    }

    public MemoryHostContext setSelector(int selector) {
        this.selector = selector;
        return this;
    }

    public MemoryHostContext setCaller(byte[] caller) {
        this.caller = address(caller);
        return this;
    }

    public MemoryHostContext setSelf(byte[] self) {
        this.self = address(self);
        return this;
    }

    public MemoryHostContext setChainId(byte[] chainId) {
        this.chainId = address(chainId);
        return this;
    }

    public MemoryHostContext setValue(long value) {
        this.value = value;
        return this;
    }

    public MemoryHostContext setHeight(long height) {
        this.height = height;
        return this;
    }

    public MemoryHostContext setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public MemoryHostContext setWritesAllowed(boolean writesAllowed) {
        this.writesAllowed = writesAllowed;
        return this;
    }

    public MemoryHostContext setSiblings(byte[] key, int count) {
        siblings.put(Bytes.wrap(key), count);
        return this;
    }

    public MemoryHostContext setBalance(byte[] address, long balance) {
        balances.put(Bytes.wrap(address(address)), balance);
        return this;
    }

    public List<Withdrawal> withdrawals() {
        return withdrawals;
    }

    public List<Outbound> outbox() {
        return outbox;
    }

    public List<Event> events() {
        return events;
    }

    @Override
    public byte[] input() {
        return input.clone();
    }

    @Override
    public int selector() {
        return selector;
    }

    @Override
    public byte[] caller() {
        return caller.clone();
    }

    @Override
    public byte[] selfAddress() {
        return self.clone();
    }

    @Override
    public byte[] chainId() {
        return chainId.clone();
    }

    @Override
    public long value() {
        return value;
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
        this.returnData = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] storageGet(byte[] key) {
        byte[] value = storage.get(Bytes.wrap(key));
        return value == null ? null : value.clone();
    }

    @Override
    public void storagePut(byte[] key, byte[] value) {
        storage.put(Bytes.wrap(key), value.clone());
    }

    @Override
    public void storageDelete(byte[] key) {
        storage.remove(Bytes.wrap(key));
    }

    @Override
    public List<StorageEntry> storageScan(byte[] prefix, int max) {
        Bytes p = Bytes.wrap(prefix);
        List<StorageEntry> hits = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : storage.entrySet()) {
            if (hits.size() == max) {
                break;
            }
            if (e.getKey().size() >= p.size() && e.getKey().slice(0, p.size()).equals(p)) {
                hits.add(new StorageEntry(e.getKey().toArray(), e.getValue().clone()));
            }
        }
        hits.sort(Comparator.comparing(a -> Bytes.wrap(a.key())));
        return hits;
    }

    @Override
    public int nonEmptySiblings(byte[] key) {
        return siblings.getOrDefault(Bytes.wrap(key), 0);
    }

    @Override
    public long balanceOf(byte[] address) {
        return balances.getOrDefault(Bytes.wrap(address(address)), 0L);
    }

    @Override
    public int transfer(byte[] to, long amount) {
        byte[] dst = address(to);
        MemoryHostContext.rejectNegative(amount);
        long from = balanceOf(self);
        if (from < amount) {
            return 1;
        }
        credit(self, from - amount);
        try {
            credit(dst, Math.addExact(balanceOf(dst), amount));
        } catch (ArithmeticException e) {
            throw new TrapException("balance overflow");
        }
        return 0;
    }

    @Override
    public int transferFromCaller(long amount) {
        MemoryHostContext.rejectNegative(amount);
        if (amount > value - claimed) {
            return 1;
        }
        claimed += amount;
        try {
            credit(self, Math.addExact(balanceOf(self), amount));
        } catch (ArithmeticException e) {
            throw new TrapException("balance overflow");
        }
        return 0;
    }

    @Override
    public int withdraw(byte[] to, long amount) {
        MemoryHostContext.rejectNegative(amount);
        long from = balanceOf(self);
        if (from < amount) {
            return 1;
        }
        credit(self, from - amount);
        withdrawals.add(new Withdrawal(address(to), amount));
        return 0;
    }

    @Override
    public int call(byte[] contract, int selector, byte[] args, long value) {
        return 1;
    }

    @Override
    public int send(byte[] dstChain, byte[] contract, int selector, byte[] args, long value) {
        outbox.add(new Outbound(dstChain.clone(), address(contract), selector, args.clone(), value));
        return 0;
    }

    @Override
    public void emit(byte[] topic, byte[] data) {
        events.add(new Event(topic.clone(), data.clone()));
    }

    @Override
    public boolean writesAllowed() {
        return writesAllowed;
    }

    private void credit(byte[] address, long balance) {
        balances.put(Bytes.wrap(address(address)), balance);
    }

    static byte[] address(byte[] raw) {
        byte[] out = new byte[GasSchedule.ADDRESS_BYTES];
        if (raw != null) {
            System.arraycopy(raw, 0, out, 0, Math.min(out.length, raw.length));
        }
        return out;
    }

    static void rejectNegative(long amount) {
        if (amount < 0) {
            throw new TrapException("negative amount");
        }
    }

    public record Withdrawal(byte[] to, long amount) {
    }

    public record Outbound(byte[] dstChain, byte[] contract, int selector, byte[] args, long value) {
    }

    public record Event(byte[] topic, byte[] data) {
    }
}
