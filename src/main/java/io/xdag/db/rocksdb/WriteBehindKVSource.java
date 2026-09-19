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

import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.Arrays;
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
 * see them immediately through a pending map. Prefix scans merge the pending map over the
 * delegate's scan instead of draining the queue — a peer can ask for arbitrarily many of them on a
 * netty event loop (a blocks request is one scan per 16-second bucket), and a drain per scan both
 * stalls that loop and defeats the batching this layer exists for. {@link #keys()}, which no
 * wrapped database is scanned with in production, still flushes. An optional LRU read cache (INDEX
 * only in practice) serves repeated point reads; every write path, queued or direct, updates it,
 * so it is never stale.
 *
 * <p>Keys and values are retained by reference (pending map, read cache, queue) and must not be
 * mutated after they are handed in. A {@code put} with a {@code null} value is a delete, as on
 * {@link RocksdbKVSource}. Direct-mode writes ({@link WriteBehindQueue#direct}) drain the stream
 * once -- the scope's drain happens here, at its first write -- then go to the delegate and clear
 * the key's pending entry; see {@link io.xdag.db.PersistControl#direct} for the single-writer
 * precondition that makes that sound.
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
    /**
     * Bumped under the queue lock by every write that touches the pending map or the read cache.
     * A read miss repopulates the cache only if no such write happened while it was reading the
     * delegate: otherwise the value it holds may be older than what the writer just recorded.
     */
    private volatile long writes;

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
        writes++;
    }

    /** Called by the queue once the write with this version is in the database. */
    void completed(byte[] key, long version) {
        pending.computeIfPresent(Bytes.wrap(key), (k, p) -> p.version() == version ? null : p);
    }

    /** Bookkeeping for a write that already went to the delegate; must run under the queue lock. */
    private void recordDirect(byte[] key, byte[] value) {
        pending.remove(Bytes.wrap(key));
        if (value == null) {
            cacheRemove(key);
        } else {
            cachePut(key, value);
        }
        writes++;
    }

    private void directWrite(byte[] key, byte[] value) {
        queue.runLocked(() -> recordDirect(key, value));
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (queue.isBypass()) {
            queue.flushSync(); // the direct scope's one drain, on its first write (no-op afterwards)
            delegate.put(key, val);
            directWrite(key, val);
            return;
        }
        queue.enqueue(this, key, val);
    }

    @Override
    public void delete(byte[] key) {
        if (queue.isBypass()) {
            queue.flushSync(); // as in put(): drain the stream before the first direct write
            delegate.delete(key);
            directWrite(key, null);
            return;
        }
        queue.enqueue(this, key, null);
    }

    @Override
    public byte[] get(byte[] key) {
        // Invariant: seen must be read before every lookup below. A write that lands after the
        // pending/cache misses but before the sample would otherwise leave writes == seen, and once
        // completed() retires its pending entry the miss path would cache the pre-write disk value.
        long seen = writes;
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
        if (value != null && readCache != null) {
            // Repopulate under the queue lock, and only if nothing was written to this source
            // meanwhile: a queued write left a pending entry (and put its value in the cache), a
            // direct write updated or evicted the cache — either would be overwritten by the
            // possibly stale value read from the delegate, and served until the next write.
            queue.runLocked(() -> {
                if (writes == seen && !pending.containsKey(k)) {
                    cachePut(key, value);
                }
            });
        }
        return value;
    }

    /** As on {@link KVSource}: a {@link Pair} with a {@code null} value is a delete. */
    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (queue.isBypass()) {
            queue.flushSync(); // as in put(): drain the stream before the first direct write
            delegate.batchWrite(puts, deletes);
            queue.runLocked(() -> {
                for (Pair<byte[], byte[]> p : puts) {
                    recordDirect(p.getKey(), p.getValue());
                }
                for (byte[] d : deletes) {
                    recordDirect(d, null);
                }
            });
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

    /**
     * Still drains: unlike the prefix scans, {@code keys()} is not reached on a wrapped database
     * from anywhere in the node (the callers scan TXHISTORY, CHAIN_L1 and SNAPSHOT, none of which
     * this layer wraps), so it is never on a remote-reachable path; and its {@link Set} of
     * {@code byte[]} is identity-hashed, so a merge would have to dedupe by value and hand back
     * exactly one array per key for a caller that does not exist. A full-database scan with a
     * barrier in front of it is a boot/offline-tool cost, not a hot one.
     */
    @Override
    public Set<byte[]> keys() throws RuntimeException {
        queue.flushSync();
        return delegate.keys();
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] key) {
        List<byte[]> keys = new ArrayList<>();
        fetchPrefix(key, pair -> {
            keys.add(pair.getKey());
            return Boolean.FALSE;
        });
        return keys;
    }

    /**
     * The delegate's scan merged with the keys this source had queued when the scan started: the
     * delegate's entries minus the ones a pending tombstone hides, plus the pending keys it cannot
     * see yet, all in the delegate's key order so that a caller cannot tell the merge from a scan
     * of a drained database.
     *
     * <p>The overlay is snapshotted <em>before</em> the delegate is read, and that order is what
     * closes the retire race: a key queued before the scan began is in the snapshot whatever the
     * writer thread does next, so it cannot fall between a delegate read that predates its write
     * and a pending read that postdates {@link #completed}'s retiring of its entry. The snapshot's
     * values are deliberately not kept — every overlay key is resolved against the live pending map
     * and, failing that, the delegate (see {@link #resolveScan}), so a scan can never serve a value
     * older than one it could have read itself.
     *
     * <p>Keys and values are handed to {@code func} by reference, as everywhere else here.
     */
    @Override
    public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
        if (queue.isBypass()) {
            queue.flushSync(); // as in put(): the direct scope's one drain, on its first access
        }
        ScanMerge merge = new ScanMerge(pendingPrefix(key), func);
        delegate.fetchPrefix(key, merge::onDelegateEntry);
        merge.finish();
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] key) {
        List<byte[]> values = new ArrayList<>();
        fetchPrefix(key, pair -> {
            values.add(pair.getValue());
            return Boolean.FALSE;
        });
        return values;
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
        List<Pair<byte[], byte[]>> pairs = new ArrayList<>();
        fetchPrefix(key, pair -> {
            if (pair.getValue() != null) { // as on RocksdbKVSource; the merge never emits a null value
                pairs.add(pair);
            }
            return Boolean.FALSE;
        });
        return pairs;
    }

    /**
     * The pending keys matching {@code prefix}, in the delegate's key order (unsigned lexicographic,
     * RocksDB's default comparator). Bounded by the queue's backpressure limit, so this is a few
     * thousand hash-table slots at worst — cheap next to the drain it replaces. A key enqueued
     * after this returns is concurrent with the scan and need not be seen by it.
     */
    private List<byte[]> pendingPrefix(byte[] prefix) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<byte[]> keys = new ArrayList<>();
        for (Bytes k : pending.keySet()) {
            byte[] raw = k.toArrayUnsafe();
            if (BytesUtils.keyStartsWith(raw, prefix)) {
                keys.add(raw);
            }
        }
        keys.sort(Arrays::compareUnsigned);
        return keys;
    }

    /**
     * The current value of a key that was pending when the scan started, or {@code null} if it has
     * none. Its pending entry if it still has one — a queued value is always newer than the
     * delegate's, because the entry is recorded before the write is enqueued and retired only after
     * the write has landed. Otherwise the delegate's: a retired entry is on disk, and a direct write
     * that removed the entry drained the queue before it touched the delegate, so either way the
     * delegate is authoritative and at least as new as the snapshot was.
     */
    private byte[] resolveScan(byte[] key) {
        Pending p = pending.get(Bytes.wrap(key));
        if (p != null) {
            return p.isDelete() ? null : p.value();
        }
        return delegate.get(key);
    }

    /**
     * One prefix scan in flight: walks the delegate's ordered entries and the overlay snapshot
     * together, emitting each key once and letting the overlay win wherever both have it.
     */
    private final class ScanMerge {

        private final List<byte[]> overlay;
        private final Function<Pair<byte[], byte[]>, Boolean> func;
        private int next;
        private boolean stopped;

        ScanMerge(List<byte[]> overlay, Function<Pair<byte[], byte[]>, Boolean> func) {
            this.overlay = overlay;
            this.func = func;
        }

        /** @return {@code true} to stop the delegate's iteration, as {@link KVSource#fetchPrefix} defines it. */
        Boolean onDelegateEntry(Pair<byte[], byte[]> pair) {
            while (next < overlay.size()) {
                int cmp = Arrays.compareUnsigned(overlay.get(next), pair.getKey());
                if (cmp > 0) {
                    break;
                }
                boolean supersedes = cmp == 0;
                if (emit(overlay.get(next++))) {
                    return Boolean.TRUE;
                }
                if (supersedes) {
                    return Boolean.FALSE; // the pending entry replaced (or hid) this delegate entry
                }
            }
            if (Boolean.TRUE.equals(func.apply(pair))) {
                stopped = true;
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }

        /** Emits the overlay keys that sort after everything the delegate had. */
        void finish() {
            while (!stopped && next < overlay.size()) {
                if (emit(overlay.get(next++))) {
                    return;
                }
            }
        }

        private boolean emit(byte[] key) {
            byte[] value = resolveScan(key);
            if (value == null) {
                return false; // a tombstone, or a delete that has already landed: nothing to emit
            }
            if (Boolean.TRUE.equals(func.apply(Pair.of(key, value)))) {
                stopped = true;
                return true;
            }
            return false;
        }
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

    /**
     * Flushes, wipes the delegate and forgets everything cached. Shares {@link WriteBehindQueue#direct}'s
     * single-writer precondition: no other thread may write this source during the reset.
     */
    @Override
    public void reset() {
        queue.flushSync();
        queue.runLocked(() -> {
            pending.clear();
            if (readCache != null) {
                readCache.clear();
            }
            writes++;
        });
        delegate.reset();
        queue.runLocked(() -> {
            // Again after the wipe: a read miss that sampled the epoch before the first bump could
            // otherwise cache a pre-reset value it read from the old delegate.
            if (readCache != null) {
                readCache.clear();
            }
            writes++;
        });
    }
}
