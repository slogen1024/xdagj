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
package io.xdag.chain.exec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.core.XdagLifecycle;
import io.xdag.db.rocksdb.KVSource;

/**
 * Node-local {@code CHAIN_EXEC}. Not part of the snapshot hash and not on the import write-behind
 * path. A missing key reads as {@code null}.
 *
 * <p>Prefixes: {@code 0x01} state, {@code 0x02} head ({@code seq u64 ‖ stateRoot}), {@code 0x03}
 * changeset, {@code 0x04} snapshot page ({@code chain ‖ seq ‖ page u32} — a seq has more than one
 * page, which the one-line table did not spell out), {@code 0x05} MMR blob, {@code 0x06} commitment
 * opening, {@code 0x07} receipt. Changeset, MMR, opening and receipt bytes are opaque here.
 */
public final class ChainExecStore implements XdagLifecycle {

    public static final byte STATE = 0x01;
    public static final byte HEAD = 0x02;
    public static final byte CHANGESET = 0x03;
    public static final byte SNAPSHOT = 0x04;
    public static final byte MMR = 0x05;
    public static final byte OPENING = 0x06;
    public static final byte RECEIPT = 0x07;

    public record Head(long seq, Bytes32 stateRoot) {
    }

    private final KVSource<byte[], byte[]> source;
    private volatile boolean running;

    public ChainExecStore(KVSource<byte[], byte[]> source) {
        this.source = source;
    }

    @Override
    public void start() {
        source.init();
        running = true;
    }

    @Override
    public void stop() {
        if (source.isAlive()) {
            source.close();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void putState(Bytes32 chain, Bytes32 key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("state value");
        }
        source.put(stateKey(chain, key), value.clone());
    }

    public byte[] getState(Bytes32 chain, Bytes32 key) {
        return source.get(stateKey(chain, key));
    }

    public void deleteState(Bytes32 chain, Bytes32 key) {
        source.delete(stateKey(chain, key));
    }

    /**
     * One seq's state writes. A null value deletes. Puts apply first, then deletes, per
     * {@link KVSource#batchWrite}.
     */
    public void applyState(Bytes32 chain, List<Pair<Bytes32, byte[]>> writes) {
        List<Pair<byte[], byte[]>> puts = new ArrayList<>();
        List<byte[]> deletes = new ArrayList<>();
        for (Pair<Bytes32, byte[]> write : writes) {
            byte[] key = stateKey(chain, write.getKey());
            if (write.getValue() == null) {
                deletes.add(key);
            } else {
                puts.add(Pair.of(key, write.getValue().clone()));
            }
        }
        source.batchWrite(puts, deletes);
    }

    public void putHead(Bytes32 chain, long seq, Bytes32 stateRoot) {
        checkSeq(seq);
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + Bytes32.SIZE);
        buf.putLong(seq);
        buf.put(stateRoot.toArray());
        source.put(headKey(chain), buf.array());
    }

    public Head getHead(Bytes32 chain) {
        byte[] raw = source.get(headKey(chain));
        if (raw == null) {
            return null;
        }
        if (raw.length != Long.BYTES + Bytes32.SIZE) {
            throw new IllegalStateException("chain exec head");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        return new Head(buf.getLong(), read32(buf));
    }

    public void putChangeSet(Bytes32 chain, long seq, byte[] body) {
        checkSeq(seq);
        source.put(keyed(CHANGESET, chain, seq), copy(body));
    }

    public byte[] getChangeSet(Bytes32 chain, long seq) {
        return source.get(keyed(CHANGESET, chain, seq));
    }

    public void putSnapshotPage(Bytes32 chain, long seq, int page, byte[] body) {
        checkSeq(seq);
        if (page < 0) {
            throw new IllegalArgumentException("snapshot page");
        }
        source.put(pageKey(chain, seq, page), copy(body));
    }

    public byte[] getSnapshotPage(Bytes32 chain, long seq, int page) {
        return source.get(pageKey(chain, seq, page));
    }

    public void putMmr(Bytes32 chain, Bytes32 dst, byte[] body) {
        source.put(dstKey(MMR, chain, dst), copy(body));
    }

    public byte[] getMmr(Bytes32 chain, Bytes32 dst) {
        return source.get(dstKey(MMR, chain, dst));
    }

    public void putOpening(Bytes32 chain, long seq, long k, byte[] body) {
        checkSeq(seq);
        if (k < 0) {
            throw new IllegalArgumentException("opening index");
        }
        source.put(openingKey(chain, seq, k), copy(body));
    }

    public byte[] getOpening(Bytes32 chain, long seq, long k) {
        return source.get(openingKey(chain, seq, k));
    }

    public void putReceipt(Bytes32 chain, Bytes32 blockHash, byte[] body) {
        source.put(dstKey(RECEIPT, chain, blockHash), copy(body));
    }

    public byte[] getReceipt(Bytes32 chain, Bytes32 blockHash) {
        return source.get(dstKey(RECEIPT, chain, blockHash));
    }

    static byte[] stateKey(Bytes32 chain, Bytes32 key) {
        byte[] raw = new byte[1 + Bytes32.SIZE + Bytes32.SIZE];
        raw[0] = STATE;
        System.arraycopy(chain.toArray(), 0, raw, 1, Bytes32.SIZE);
        System.arraycopy(key.toArray(), 0, raw, 1 + Bytes32.SIZE, Bytes32.SIZE);
        return raw;
    }

    static byte[] headKey(Bytes32 chain) {
        byte[] raw = new byte[1 + Bytes32.SIZE];
        raw[0] = HEAD;
        System.arraycopy(chain.toArray(), 0, raw, 1, Bytes32.SIZE);
        return raw;
    }

    static byte[] keyed(byte prefix, Bytes32 chain, long seq) {
        ByteBuffer buf = ByteBuffer.allocate(1 + Bytes32.SIZE + Long.BYTES);
        buf.put(prefix);
        buf.put(chain.toArray());
        buf.putLong(seq);
        return buf.array();
    }

    static byte[] pageKey(Bytes32 chain, long seq, int page) {
        ByteBuffer buf = ByteBuffer.allocate(1 + Bytes32.SIZE + Long.BYTES + Integer.BYTES);
        buf.put(SNAPSHOT);
        buf.put(chain.toArray());
        buf.putLong(seq);
        buf.putInt(page);
        return buf.array();
    }

    static byte[] dstKey(byte prefix, Bytes32 chain, Bytes32 dst) {
        byte[] raw = new byte[1 + Bytes32.SIZE + Bytes32.SIZE];
        raw[0] = prefix;
        System.arraycopy(chain.toArray(), 0, raw, 1, Bytes32.SIZE);
        System.arraycopy(dst.toArray(), 0, raw, 1 + Bytes32.SIZE, Bytes32.SIZE);
        return raw;
    }

    static byte[] openingKey(Bytes32 chain, long seq, long k) {
        ByteBuffer buf = ByteBuffer.allocate(1 + Bytes32.SIZE + Long.BYTES + Long.BYTES);
        buf.put(OPENING);
        buf.put(chain.toArray());
        buf.putLong(seq);
        buf.putLong(k);
        return buf.array();
    }

    private static void checkSeq(long seq) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq");
        }
    }

    private static byte[] copy(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body");
        }
        return body.clone();
    }

    private static Bytes32 read32(ByteBuffer buf) {
        byte[] raw = new byte[Bytes32.SIZE];
        buf.get(raw);
        return Bytes32.wrap(raw);
    }
}
