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
package io.xdag.chain.exec.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes32;

/**
 * The mutable state map. {@link SparseMerkleTree} stays a pure function of a key set; this is the
 * {@code get/put/remove} in front of it. Insertion order does not change {@link #root()}.
 */
public final class StateTree {

    private final TreeMap<Bytes32, byte[]> map = new TreeMap<>();

    public byte[] get(Bytes32 key) {
        byte[] value = map.get(key);
        return value == null ? null : value.clone();
    }

    public boolean contains(Bytes32 key) {
        return map.containsKey(key);
    }

    public void put(Bytes32 key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("state value");
        }
        map.put(key, value.clone());
    }

    /** Absent key is a no-op. */
    public void remove(Bytes32 key) {
        map.remove(key);
    }

    public int size() {
        return map.size();
    }

    public Bytes32 root() {
        return SparseMerkleTree.root(entries());
    }

    public SparseMerkleTree.Proof proof(Bytes32 key) {
        return SparseMerkleTree.proof(key, entries());
    }

    public int nonEmptySiblings(Bytes32 key) {
        return SparseMerkleTree.nonEmptySiblings(proof(key));
    }

    public List<SparseMerkleTree.Kv> entries() {
        List<SparseMerkleTree.Kv> out = new ArrayList<>(map.size());
        for (Map.Entry<Bytes32, byte[]> e : map.entrySet()) {
            out.add(new SparseMerkleTree.Kv(e.getKey(), e.getValue().clone()));
        }
        return out;
    }
}
