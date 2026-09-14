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

package io.xdag.lane;

import io.xdag.db.rocksdb.KVSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/** Sorted in-memory KVSource for unit tests. Keys and values are copied on the way in and out. */
public class InMemoryKVSource implements KVSource<byte[], byte[]> {

    private final TreeMap<Bytes, byte[]> map = new TreeMap<>();
    private String name = "memory";
    private boolean alive;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void init() {
        alive = true;
    }

    @Override
    public void close() {
        alive = false;
    }

    @Override
    public void reset() {
        map.clear();
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (val == null) {
            map.remove(Bytes.wrap(key));
        } else {
            map.put(Bytes.wrap(Arrays.copyOf(key, key.length)), Arrays.copyOf(val, val.length));
        }
    }

    @Override
    public byte[] get(byte[] key) {
        byte[] v = map.get(Bytes.wrap(key));
        return v == null ? null : Arrays.copyOf(v, v.length);
    }

    @Override
    public void delete(byte[] key) {
        map.remove(Bytes.wrap(key));
    }

    @Override
    public Set<byte[]> keys() {
        Set<byte[]> out = new HashSet<>();
        for (Bytes k : map.keySet()) {
            out.add(k.toArray());
        }
        return out;
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (Bytes k : map.keySet()) {
            if (startsWith(k, prefix)) {
                out.add(k.toArray());
            }
        }
        return out;
    }

    @Override
    public void fetchPrefix(byte[] prefix, Function<Pair<byte[], byte[]>, Boolean> func) {
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix) && func.apply(Pair.of(e.getKey().toArray(), e.getValue().clone()))) {
                return;
            }
        }
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix)) {
                out.add(e.getValue().clone());
            }
        }
        return out;
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] prefix) {
        List<Pair<byte[], byte[]>> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix)) {
                out.add(Pair.of(e.getKey().toArray(), e.getValue().clone()));
            }
        }
        return out;
    }

    private static boolean startsWith(Bytes key, byte[] prefix) {
        return key.size() >= prefix.length && key.slice(0, prefix.length).equals(Bytes.wrap(prefix));
    }
}
