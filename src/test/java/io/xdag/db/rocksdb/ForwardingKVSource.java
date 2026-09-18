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

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Test helper: a {@link KVSource} that forwards everything to a delegate. Tests subclass it to
 * observe, slow down or sabotage single methods (typically {@link #batchWrite}).
 */
class ForwardingKVSource implements KVSource<byte[], byte[]> {

    protected final KVSource<byte[], byte[]> delegate;

    ForwardingKVSource(KVSource<byte[], byte[]> delegate) {
        this.delegate = delegate;
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
        delegate.close();
    }

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public void put(byte[] key, byte[] val) {
        delegate.put(key, val);
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public void delete(byte[] key) {
        delegate.delete(key);
    }

    @Override
    public Set<byte[]> keys() throws RuntimeException {
        return delegate.keys();
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] key) {
        return delegate.prefixKeyLookup(key);
    }

    @Override
    public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
        delegate.fetchPrefix(key, func);
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] key) {
        return delegate.prefixValueLookup(key);
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
        return delegate.prefixKeyAndValueLookup(key);
    }

    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        delegate.batchWrite(puts, deletes);
    }
}
