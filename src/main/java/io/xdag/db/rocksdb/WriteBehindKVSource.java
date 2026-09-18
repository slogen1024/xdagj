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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * A {@link KVSource} whose writes are queued on a shared {@link WriteBehindQueue} and whose reads
 * see them immediately through a pending map. Iteration-style reads flush the queue first; they
 * are not on the import hot path. An optional LRU read cache (INDEX only in practice) serves
 * repeated point reads; every write path, queued or direct, updates it, so it is never stale.
 *
 * <p>Keys and values are retained by reference (pending map, read cache, queue) and must not be
 * mutated after they are handed in. A {@code put} with a {@code null} value is a delete, as on
 * {@link RocksdbKVSource}. Direct-mode writes ({@link WriteBehindQueue#direct}) go to the delegate
 * and clear the key's pending entry; see {@link io.xdag.db.PersistControl#direct} for the
 * single-writer precondition that makes that sound.
 */
@Slf4j
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

    /**
     * Called by the queue under its lock, in the critical section that assigns {@code version} and
     * the stream position: the pending map and the read cache see the write before any later one.
     */
    void recordPending(byte[] key, byte[] value, long version) {
        if (value == null) {
            pending.put(Bytes.wrap(key), new Pending(TOMBSTONE, version));
            cacheRemove(key);
        } else {
            pending.put(Bytes.wrap(key), new Pending(value, version));
            cachePut(key, value);
        }
    }

    /** Called by the queue once the write with this version is in the database. */
    void completed(byte[] key, long version) {
        pending.computeIfPresent(Bytes.wrap(key), (k, p) -> p.version() == version ? null : p);
    }

    private void directWrite(byte[] key, byte[] value) {
        pending.remove(Bytes.wrap(key));
        if (value == null) {
            cacheRemove(key);
        } else {
            cachePut(key, value);
        }
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (queue.isBypass()) {
            delegate.put(key, val);
            directWrite(key, val);
            return;
        }
        queue.enqueue(this, key, val);
    }

    @Override
    public void delete(byte[] key) {
        if (queue.isBypass()) {
            delegate.delete(key);
            directWrite(key, null);
            return;
        }
        queue.enqueue(this, key, null);
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

    /** As on {@link KVSource}: a {@link Pair} with a {@code null} value is a delete. */
    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (queue.isBypass()) {
            delegate.batchWrite(puts, deletes);
            for (Pair<byte[], byte[]> p : puts) {
                directWrite(p.getKey(), p.getValue());
            }
            for (byte[] d : deletes) {
                directWrite(d, null);
            }
            return;
        }
        for (Pair<byte[], byte[]> p : puts) {
            if (p.getValue() == null) {
                delete(p.getKey());
            } else {
                put(p.getKey(), p.getValue());
            }
        }
        for (byte[] d : deletes) {
            delete(d);
        }
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

    /** Flushes, then closes the delegate — also after a write-behind failure (logged, not hidden). */
    @Override
    public void close() {
        try {
            queue.flushSync();
        } catch (IllegalStateException e) {
            log.error("closing '{}' after a write-behind failure: queued writes were not flushed", getName(), e);
        } finally {
            delegate.close();
        }
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
