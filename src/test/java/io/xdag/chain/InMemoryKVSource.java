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

package io.xdag.chain;

import com.google.common.primitives.UnsignedBytes;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;

/**
 * In-memory KVSource for unit tests, sorted by unsigned lexicographic byte order, matching
 * RocksDB's default comparator; not thread-safe (tests run under the consensus lock or
 * single-threaded). Keys and values are copied on the way in and out.
 */
public class InMemoryKVSource implements KVSource<byte[], byte[]> {

    private final TreeMap<byte[], byte[]> map = new TreeMap<>(UnsignedBytes.lexicographicalComparator());
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
        alive = true;
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (val == null) {
            map.remove(key);
        } else {
            map.put(Arrays.copyOf(key, key.length), Arrays.copyOf(val, val.length));
        }
    }

    @Override
    public byte[] get(byte[] key) {
        byte[] v = map.get(key);
        return v == null ? null : Arrays.copyOf(v, v.length);
    }

    @Override
    public void delete(byte[] key) {
        map.remove(key);
    }

    @Override
    public Set<byte[]> keys() {
        Set<byte[]> out = new HashSet<>();
        for (byte[] k : map.keySet()) {
            out.add(k.clone());
        }
        return out;
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] k : map.keySet()) {
            if (BytesUtils.keyStartsWith(k, prefix)) {
                out.add(k.clone());
            }
        }
        return out;
    }

    @Override
    public void fetchPrefix(byte[] prefix, Function<Pair<byte[], byte[]>, Boolean> func) {
        for (Map.Entry<byte[], byte[]> e : map.entrySet()) {
            if (BytesUtils.keyStartsWith(e.getKey(), prefix)
                    && func.apply(Pair.of(e.getKey().clone(), e.getValue().clone()))) {
                return;
            }
        }
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : map.entrySet()) {
            if (BytesUtils.keyStartsWith(e.getKey(), prefix)) {
                out.add(e.getValue().clone());
            }
        }
        return out;
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] prefix) {
        List<Pair<byte[], byte[]>> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : map.entrySet()) {
            if (BytesUtils.keyStartsWith(e.getKey(), prefix)) {
                out.add(Pair.of(e.getKey().clone(), e.getValue().clone()));
            }
        }
        return out;
    }
}
