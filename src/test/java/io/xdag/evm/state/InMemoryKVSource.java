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
package io.xdag.evm.state;

import io.xdag.db.rocksdb.KVSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/** Hermetic in-memory {@link KVSource} for tests (content-equality byte[] keys, no native RocksDB). */
public class InMemoryKVSource implements KVSource<byte[], byte[]> {

    private String name = "in-memory";
    private final Map<Bytes, byte[]> map = new LinkedHashMap<>();

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
        return true;
    }

    @Override
    public void init() {
    }

    @Override
    public void close() {
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
            map.put(Bytes.wrap(key), val);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        return map.get(Bytes.wrap(key));
    }

    @Override
    public void delete(byte[] key) {
        map.remove(Bytes.wrap(key));
    }

    @Override
    public Set<byte[]> keys() {
        return map.keySet().stream().map(Bytes::toArray).collect(Collectors.toSet());
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] key) {
        Bytes prefix = Bytes.wrap(key);
        return map.keySet().stream().filter(k -> startsWith(k, prefix)).map(Bytes::toArray).toList();
    }

    @Override
    public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
        Bytes prefix = Bytes.wrap(key);
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix) && Boolean.TRUE.equals(func.apply(Pair.of(e.getKey().toArray(), e.getValue())))) {
                return;
            }
        }
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] key) {
        Bytes prefix = Bytes.wrap(key);
        return map.entrySet().stream().filter(e -> startsWith(e.getKey(), prefix)).map(Map.Entry::getValue).toList();
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
        Bytes prefix = Bytes.wrap(key);
        List<Pair<byte[], byte[]>> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix)) {
                out.add(Pair.of(e.getKey().toArray(), e.getValue()));
            }
        }
        return out;
    }

    private static boolean startsWith(Bytes value, Bytes prefix) {
        return value.size() >= prefix.size() && value.slice(0, prefix.size()).equals(prefix);
    }
}
