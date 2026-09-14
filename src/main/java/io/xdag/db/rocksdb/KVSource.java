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

public interface KVSource<K, V> {

    String getName();

    void setName(String name);

    boolean isAlive();

    void init();

    void close();

    void reset();

    void put(K key, V val);

    V get(K key);

    void delete(K key);

    Set<byte[]> keys() throws RuntimeException;

    List<K> prefixKeyLookup(byte[] key);

    void fetchPrefix(byte[] key, Function<Pair<K, V>, Boolean> func);

    List<V> prefixValueLookup(byte[] key);

    List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key);

    /**
     * Applies all puts and then all deletes. A {@link Pair} whose value is {@code null} is a delete;
     * puts are applied in list order (so a later put on the same key wins), and deletes are applied
     * after all puts (so deleting a key that was just put removes it). The default implementation
     * applies them one by one and is not atomic; transactional backends override it so the whole
     * batch commits as a single atomic write.
     */
    default void batchWrite(List<Pair<K, V>> puts, List<K> deletes) {
        for (Pair<K, V> p : puts) {
            put(p.getKey(), p.getValue());
        }
        for (K k : deletes) {
            delete(k);
        }
    }

}
