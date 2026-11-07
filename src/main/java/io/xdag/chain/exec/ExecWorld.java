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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.state.StateTree;

/**
 * One chain's committed KV plus a nested overlay. The SMT moves only when an overlay commits, so a
 * proof taken during a call is the pre-call proof. A discarded overlay leaves the tree alone.
 *
 * <p>The stored blob is {@code contract20 ‖ u32 userKeyLen ‖ userKey ‖ value}. The tree value is
 * just {@code value}; the user key is kept so a prefix scan can be rebuilt after a restart.
 */
final class ExecWorld {

    private final StateTree tree = new StateTree();
    private final TreeMap<Bytes, byte[]> slots = new TreeMap<>();
    private final Set<Bytes32> dirty = new HashSet<>();
    private final Set<Bytes32> removed = new HashSet<>();

    Layer root() {
        return new Layer(null);
    }

    Bytes32 rootHash() {
        return tree.root();
    }

    SparseMerkleTree.Proof proof(Bytes32 smtKey) {
        return tree.proof(smtKey);
    }

    byte[] get(Bytes contract, byte[] userKey) {
        return slots.get(ChainLedger.slot(contract, userKey));
    }

    void load(List<Pair<Bytes32, byte[]>> rows) {
        for (Pair<Bytes32, byte[]> row : rows) {
            Stored stored = decode(row.getValue());
            Bytes32 expect = ChainLedger.smtKey(stored.contract(), stored.userKey());
            if (!expect.equals(row.getKey())) {
                throw new IllegalStateException("state key");
            }
            put(stored.contract(), stored.userKey(), stored.value(), false);
        }
        dirty.clear();
        removed.clear();
    }

    List<Pair<Bytes32, byte[]>> dirtyPuts() {
        List<Pair<Bytes32, byte[]>> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : slots.entrySet()) {
            Bytes32 smt = ChainLedger.smtKey(Bytes.wrap(e.getKey().slice(0, 20)), e.getKey().slice(20).toArray());
            if (dirty.contains(smt)) {
                out.add(Pair.of(smt, encode(e.getKey(), e.getValue())));
            }
        }
        return out;
    }

    List<Bytes32> dirtyDeletes() {
        return List.copyOf(removed);
    }

    void clean() {
        dirty.clear();
        removed.clear();
    }

    private void put(Bytes contract, byte[] userKey, byte[] value, boolean track) {
        Bytes id = ChainLedger.slot(contract, userKey);
        Bytes32 smt = ChainLedger.smtKey(contract, userKey);
        slots.put(id, value.clone());
        tree.put(smt, value);
        if (track) {
            dirty.add(smt);
            removed.remove(smt);
        }
    }

    private void delete(Bytes contract, byte[] userKey) {
        Bytes id = ChainLedger.slot(contract, userKey);
        Bytes32 smt = ChainLedger.smtKey(contract, userKey);
        if (slots.remove(id) == null && !tree.contains(smt)) {
            return;
        }
        tree.remove(smt);
        dirty.remove(smt);
        removed.add(smt);
    }

    private static byte[] encode(Bytes id, byte[] value) {
        byte[] userKey = id.slice(20).toArray();
        ByteBuffer buf = ByteBuffer.allocate(20 + Integer.BYTES + userKey.length + value.length);
        buf.put(id.slice(0, 20).toArray());
        buf.putInt(userKey.length);
        buf.put(userKey);
        buf.put(value);
        return buf.array();
    }

    private static Stored decode(byte[] blob) {
        ByteBuffer buf = ByteBuffer.wrap(blob);
        if (buf.remaining() < 20 + Integer.BYTES) {
            throw new IllegalStateException("state blob");
        }
        byte[] contract = new byte[20];
        buf.get(contract);
        int n = buf.getInt();
        if (n < 0 || n > buf.remaining()) {
            throw new IllegalStateException("state blob");
        }
        byte[] userKey = new byte[n];
        buf.get(userKey);
        byte[] value = new byte[buf.remaining()];
        buf.get(value);
        return new Stored(Bytes.wrap(contract), userKey, value);
    }

    private record Stored(Bytes contract, byte[] userKey, byte[] value) {
    }

    final class Layer {
        private final Layer parent;
        private final Map<Bytes, byte[]> writes = new TreeMap<>();
        private final Set<Bytes> deleted = new HashSet<>();

        private Layer(Layer parent) {
            this.parent = parent;
        }

        Layer child() {
            return new Layer(this);
        }

        byte[] get(Bytes contract, byte[] userKey) {
            Bytes id = ChainLedger.slot(contract, userKey);
            if (deleted.contains(id)) {
                return null;
            }
            if (writes.containsKey(id)) {
                byte[] v = writes.get(id);
                return v == null ? null : v.clone();
            }
            if (parent != null) {
                return parent.get(contract, userKey);
            }
            byte[] v = slots.get(id);
            return v == null ? null : v.clone();
        }

        void put(Bytes contract, byte[] userKey, byte[] value) {
            Bytes id = ChainLedger.slot(contract, userKey);
            deleted.remove(id);
            writes.put(id, value.clone());
        }

        void remove(Bytes contract, byte[] userKey) {
            Bytes id = ChainLedger.slot(contract, userKey);
            writes.remove(id);
            deleted.add(id);
        }

        /** Fold into the parent overlay. The root overlay folds into the tree. */
        void commit() {
            if (parent != null) {
                for (Bytes id : deleted) {
                    parent.writes.remove(id);
                    parent.deleted.add(id);
                }
                for (Map.Entry<Bytes, byte[]> e : writes.entrySet()) {
                    parent.deleted.remove(e.getKey());
                    parent.writes.put(e.getKey(), e.getValue());
                }
                return;
            }
            for (Bytes id : deleted) {
                delete(Bytes.wrap(id.slice(0, 20)), id.slice(20).toArray());
            }
            for (Map.Entry<Bytes, byte[]> e : writes.entrySet()) {
                ExecWorld.this.put(Bytes.wrap(e.getKey().slice(0, 20)), e.getKey().slice(20).toArray(), e.getValue(),
                        true);
            }
        }

        List<HostView> scan(Bytes contract, byte[] prefix, int max) {
            TreeMap<Bytes, byte[]> merged = new TreeMap<>();
            collect(contract, merged);
            List<HostView> out = new ArrayList<>();
            Bytes pre = Bytes.wrap(prefix);
            for (Map.Entry<Bytes, byte[]> e : merged.entrySet()) {
                if (out.size() == max) {
                    break;
                }
                byte[] userKey = e.getKey().slice(20).toArray();
                Bytes u = Bytes.wrap(userKey);
                if (u.size() >= pre.size() && u.slice(0, pre.size()).equals(pre)) {
                    out.add(new HostView(userKey, e.getValue().clone()));
                }
            }
            return out;
        }

        private void collect(Bytes contract, TreeMap<Bytes, byte[]> merged) {
            if (parent != null) {
                parent.collect(contract, merged);
            } else {
                Bytes c = Bytes.wrap(contract.toArray());
                for (Map.Entry<Bytes, byte[]> e : slots.entrySet()) {
                    if (e.getKey().size() >= 20 && e.getKey().slice(0, 20).equals(c)) {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
            }
            Bytes c = Bytes.wrap(contract.toArray());
            for (Bytes id : deleted) {
                if (id.size() >= 20 && id.slice(0, 20).equals(c)) {
                    merged.remove(id);
                }
            }
            for (Map.Entry<Bytes, byte[]> e : writes.entrySet()) {
                if (e.getKey().size() >= 20 && e.getKey().slice(0, 20).equals(c)) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }

        long balance(byte[] address) {
            return ChainLedger.decode(get(ChainLedger.SYSTEM, ChainLedger.balanceKey(address)));
        }

        void setBalance(byte[] address, long value) {
            setU64(ChainLedger.balanceKey(address), value);
        }

        long feePool() {
            return ChainLedger.decode(get(ChainLedger.SYSTEM, ChainLedger.feeKey()));
        }

        void setFee(long value) {
            setU64(ChainLedger.feeKey(), value);
        }

        long inflight(byte[] address) {
            return ChainLedger.decode(get(ChainLedger.SYSTEM, ChainLedger.inflightKey(address)));
        }

        void setInflight(byte[] address, long value) {
            setU64(ChainLedger.inflightKey(address), value);
        }

        private void setU64(byte[] userKey, long value) {
            if (value == 0L) {
                remove(ChainLedger.SYSTEM, userKey);
            } else {
                put(ChainLedger.SYSTEM, userKey, ChainLedger.encode(value));
            }
        }
    }

    record HostView(byte[] key, byte[] value) {
    }
}
