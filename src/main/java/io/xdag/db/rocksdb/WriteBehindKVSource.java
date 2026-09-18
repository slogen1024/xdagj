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

package io.xdag.db.rocksdb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * A {@link KVSource} whose writes are queued on a shared {@link WriteBehindQueue} and whose reads
 * see them immediately through a pending map. Iteration-style reads flush the queue first; they
 * are not on the import hot path. An optional LRU read cache (INDEX only in practice) serves
 * repeated point reads; every write path, queued or direct, updates it, so it is never stale.
 */
public final class WriteBehindKVSource implements KVSource<byte[], byte[]> {

    private static final byte[] TOMBSTONE = new byte[0];

    private record Pending(byte[] value, long version) {
        boolean isDelete() {
            return value == TOMBSTONE;
        }
    }

    private final KVSource<byte[], byte[]> delegate;
    private final WriteBehindQueue queue;
    private final ConcurrentHashMap<Bytes, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Bytes, byte[]> readCache; // null when disabled

    public WriteBehindKVSource(KVSource<byte[], byte[]> delegate, WriteBehindQueue queue, int readCacheEntries) {
        this.delegate = delegate;
        this.queue = queue;
        this.readCache = readCacheEntries <= 0 ? null : Collections.synchronizedMap(
                new LinkedHashMap<>(readCacheEntries * 4 / 3 + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Bytes, byte[]> eldest) {
                        return size() > readCacheEntries;
                    }
                });
    }

    KVSource<byte[], byte[]> delegate() {
        return delegate;
    }

    private void cachePut(byte[] key, byte[] value) {
        if (readCache != null) {
            readCache.put(Bytes.wrap(key), value);
        }
    }

    private void cacheRemove(byte[] key) {
        if (readCache != null) {
            readCache.remove(Bytes.wrap(key));
        }
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (queue.isBypass()) {
            delegate.put(key, val);
            cachePut(key, val);
            return;
        }
        long version = queue.nextVersion();
        pending.put(Bytes.wrap(key), new Pending(val, version));
        cachePut(key, val);
        queue.enqueue(new WriteBehindQueue.Entry(this, key, val, version));
    }

    @Override
    public void delete(byte[] key) {
        if (queue.isBypass()) {
            delegate.delete(key);
            cacheRemove(key);
            return;
        }
        long version = queue.nextVersion();
        pending.put(Bytes.wrap(key), new Pending(TOMBSTONE, version));
        cacheRemove(key);
        queue.enqueue(new WriteBehindQueue.Entry(this, key, null, version));
    }

    @Override
    public byte[] get(byte[] key) {
        Bytes k = Bytes.wrap(key);
        Pending p = pending.get(k);
        if (p != null) {
            return p.isDelete() ? null : p.value();
        }
        if (readCache != null) {
            byte[] cached = readCache.get(k);
            if (cached != null) {
                return cached;
            }
        }
        byte[] value = delegate.get(key);
        if (value != null) {
            cachePut(key, value);
        }
        return value;
    }

    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (queue.isBypass()) {
            delegate.batchWrite(puts, deletes);
            for (Pair<byte[], byte[]> p : puts) {
                cachePut(p.getKey(), p.getValue());
            }
            for (byte[] d : deletes) {
                cacheRemove(d);
            }
            return;
        }
        for (Pair<byte[], byte[]> p : puts) {
            put(p.getKey(), p.getValue());
        }
        for (byte[] d : deletes) {
            delete(d);
        }
    }

    /** Called by the queue once the write with this version is on disk. */
    void completed(byte[] key, long version) {
        pending.computeIfPresent(Bytes.wrap(key), (k, p) -> p.version() == version ? null : p);
    }

    @Override
    public Set<byte[]> keys() throws RuntimeException {
        queue.flushSync();
        return delegate.keys();
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixKeyLookup(key);
    }

    @Override
    public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
        queue.flushSync();
        delegate.fetchPrefix(key, func);
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixValueLookup(key);
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixKeyAndValueLookup(key);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void setName(String name) {
        delegate.setName(name);
    }

    @Override
    public boolean isAlive() {
        return delegate.isAlive();
    }

    @Override
    public void init() {
        delegate.init();
    }

    @Override
    public void close() {
        if (queue.failure() == null) {
            queue.flushSync();
        }
        delegate.close();
    }

    @Override
    public void reset() {
        queue.flushSync();
        pending.clear();
        if (readCache != null) {
            readCache.clear();
        }
        delegate.reset();
    }
}
