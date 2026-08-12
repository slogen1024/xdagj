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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The EVM_STATE_JOURNAL store (sub-project C4): a reverse-delta undo journal keyed by main height.
 *
 * <p>Each executed height records, for every raw {@link EvmStateSchema} key its root commit wrote or
 * deleted, that key's <b>prior</b> value (or a tombstone when the key was absent before the commit).
 * {@link HistoricalStateReader} overlays these onto the latest store to reconstruct the world at any
 * retained height. Node-local: never gossiped, never in a block, never folded into the state root.
 *
 * <p>Value layout for one height: {@code count(4 BE)} then, per entry,
 * {@code keyLen(4 BE) | key | present(1) | [valueLen(4 BE) | value]} — {@code present == 0} is a
 * tombstone (no value bytes follow). Its own dedicated column family, so keys are bare 8-byte heights.
 */
public class EvmStateJournal {

    /** A single reverse-delta entry: {@code priorValue == null} means the key was absent (tombstone). */
    public record Entry(byte[] key, byte[] priorValue) {
    }

    private final KVSource<byte[], byte[]> store;

    public EvmStateJournal(KVSource<byte[], byte[]> store) {
        this.store = store;
    }

    private static byte[] heightKey(long height) {
        byte[] key = new byte[8];
        for (int i = 0; i < 8; i++) {
            key[i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }

    private static long heightFromKey(byte[] key) {
        long height = 0;
        for (int i = 0; i < 8; i++) {
            height = (height << 8) | (key[i] & 0xFFL);
        }
        return height;
    }

    public void putHeightJournal(long height, List<Entry> entries) {
        int size = 4;
        for (Entry e : entries) {
            size += 4 + e.key().length + 1 + (e.priorValue() == null ? 0 : 4 + e.priorValue().length);
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(entries.size());
        for (Entry e : entries) {
            buf.putInt(e.key().length);
            buf.put(e.key());
            if (e.priorValue() == null) {
                buf.put((byte) 0);
            } else {
                buf.put((byte) 1);
                buf.putInt(e.priorValue().length);
                buf.put(e.priorValue());
            }
        }
        store.put(heightKey(height), buf.array());
    }

    public Optional<List<Entry>> getHeightJournal(long height) {
        byte[] raw = store.get(heightKey(height));
        if (raw == null) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        try {
            int count = buf.getInt();
            // Each entry is at least keyLen(4) + present(1) bytes, so count can never exceed the
            // remaining bytes; reject anything larger before it drives a huge ArrayList allocation.
            if (count < 0 || count > buf.remaining()) {
                throw new IllegalStateException(
                        "corrupt EVM_STATE_JOURNAL entry at height " + height + ": bad entry count " + count);
            }
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readFramed(buf, height);
                byte present = buf.get();
                byte[] value = present == 1 ? readFramed(buf, height) : null;
                entries.add(new Entry(key, value));
            }
            return Optional.of(entries);
        } catch (BufferUnderflowException e) {
            throw new IllegalStateException(
                    "corrupt EVM_STATE_JOURNAL entry at height " + height + ": truncated buffer", e);
        }
    }

    /** Reads a {@code len(4 BE) | bytes} frame, rejecting a length that overruns the remaining buffer. */
    private static byte[] readFramed(ByteBuffer buf, long height) {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            throw new IllegalStateException(
                    "corrupt EVM_STATE_JOURNAL entry at height " + height + ": bad field length " + len);
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    /** Deletes every journal below {@code minHeightInclusive}; that height and everything above it stays (window prune). */
    public void pruneBelow(long minHeightInclusive) {
        for (byte[] key : store.keys()) {
            if (heightFromKey(key) < minHeightInclusive) {
                store.delete(key);
            }
        }
    }

    /** Deletes every journal whose height is strictly above {@code height} (retained for incremental reorg). */
    public void truncateAbove(long height) {
        for (byte[] key : store.keys()) {
            if (heightFromKey(key) > height) {
                store.delete(key);
            }
        }
    }

    /** Drops all journals — used by reorg Option A before a full replay repopulates them. */
    public void clear() {
        store.reset();
    }
}
