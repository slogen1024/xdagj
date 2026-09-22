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

package io.xdag.chain.orphan;

import io.xdag.utils.BytesUtils;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * One ORPHANIND row, parsed: the six fields the orphan queues sort and match on.
 *
 * <p>This was a nested class of {@code OrphanBlockStoreImpl} until the pool started holding the
 * in-memory collections. It was lifted here unchanged — same fields, same {@code parse}, same
 * hashlow-only {@code equals}/{@code hashCode} — purely to break a package cycle: {@link
 * OrphanEntry} wraps it, and the store now depends on {@code io.xdag.chain.orphan}, so leaving it
 * in {@code io.xdag.db.rocksdb} would have had the two packages pointing at each other.
 *
 * <p>{@link #parse} still decodes the ORPHANIND row layout — key {@code 0x00 | hashlow(24) |
 * nonce(8) | isTx(1)}, value {@code time(8) | fee(8) | address(20)} — which is the one thing here
 * that is really about storage. It reads bytes and touches nothing in {@code io.xdag.db}, so the
 * move introduces no dependency in either direction; the row format and its reader simply now sit
 * one package apart, and the writer of those bytes ({@code OrphanBlockStoreImpl.addOrphan}) is the
 * only other place that knows the layout.
 *
 * <p><b>Treat an instance as frozen once it is pooled.</b> It is the sort key of a {@code TreeSet};
 * see {@link OrphanEntry} for what mutating one would do to the tree holding it. The setters are
 * Lombok's and are kept only because {@code parse} builds through them.
 */
@Getter
@Setter
@Accessors(chain = true)
public class OrphanMeta {
    private Bytes32 hashlow;
    private long nonce;
    private boolean isTx;
    private long time;
    private long fee;
    private byte[] address; // 20B

    public static OrphanMeta parse(Pair<byte[], byte[]> pair) {
        byte[] key = pair.getLeft();
        byte[] val = pair.getRight();

        return parse(key, val);
    }

    public static OrphanMeta parse(byte[] key, byte[] val) {

        byte[] fullhash = new byte[32];
        System.arraycopy(key, 1, fullhash, 8, 24);

        return new OrphanMeta()
                .setHashlow(Bytes32.wrap(fullhash))
                .setNonce(BytesUtils.bytesToLong(key, 25, false))
                .setTx(key[33] == 1)
                .setTime(BytesUtils.bytesToLong(val, 0, true))
                .setFee(UInt64.fromBytes(Bytes.wrap(val).slice(8, 8)).toLong())
                .setAddress(Arrays.copyOfRange(val, 16, 36));
    }

    @Override
    public boolean equals(Object meta) {
        if (meta == null || getClass() != meta.getClass()) return false;
        OrphanMeta m = (OrphanMeta) meta;
        return Arrays.equals(hashlow.toArray(), m.hashlow.toArray());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hashlow.toArray());
    }

}
