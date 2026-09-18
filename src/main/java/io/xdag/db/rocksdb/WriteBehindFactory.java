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

import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Wraps a node's {@link DatabaseFactory} so that the four databases the import path writes —
 * INDEX, BLOCK, TIME, ORPHANIND — go through one {@link WriteBehindQueue}. ADDRESS, CHAIN_L1 and
 * TXHISTORY are handed out untouched: they are written only on the apply path, which runs in
 * direct mode anyway. {@code BlockStoreImpl.forNode} and the orphan store need no change.
 */
public final class WriteBehindFactory implements DatabaseFactory {

    private static final EnumSet<DatabaseName> WRAPPED =
            EnumSet.of(DatabaseName.INDEX, DatabaseName.BLOCK, DatabaseName.TIME, DatabaseName.ORPHANIND);

    private final DatabaseFactory delegate;
    private final WriteBehindQueue queue;
    private final int readCacheEntries;
    private final EnumMap<DatabaseName, WriteBehindKVSource> wrapped = new EnumMap<>(DatabaseName.class);

    public WriteBehindFactory(DatabaseFactory delegate, WriteBehindQueue queue, int readCacheEntries) {
        this.delegate = delegate;
        this.queue = queue;
        this.readCacheEntries = readCacheEntries;
    }

    public WriteBehindQueue queue() {
        return queue;
    }

    @Override
    public synchronized KVSource<byte[], byte[]> getDB(DatabaseName name) {
        if (!WRAPPED.contains(name)) {
            return delegate.getDB(name);
        }
        return wrapped.computeIfAbsent(name, n -> new WriteBehindKVSource(delegate.getDB(n), queue,
                n == DatabaseName.INDEX ? readCacheEntries : 0));
    }

    @Override
    public void close() {
        queue.stop();
        delegate.close();
    }
}
