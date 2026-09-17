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

package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import io.xdag.utils.BytesUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Key layout of the CHAIN_L1 RocksDB instance. Every key is a one-byte prefix followed by a
 * fixed-width suffix (never a delimiter, so keys sharing a prefix never collide):
 *
 * <pre>
 * 0x00 META           -&gt; schema version u32                              (singleton, {@link #META_KEY})
 * 0x01 CHAIN           -&gt; chainId 20                       -&gt; {@link ChainRecord}
 * 0x02 CONTRACT       -&gt; contract address 20              -&gt; {@link ContractRecord}
 * 0x03 CODE           -&gt; codeHash 32                       -&gt; raw code bytes (blob only)
 * 0x04 BOND           -&gt; reserved for SP2/SP3
 * 0x05 ANCHOR_HEAD    -&gt; reserved for SP2/SP3
 * 0x06 ANCHOR         -&gt; reserved for SP2/SP3
 * 0x07 CALL_COUNT     -&gt; chainId 20 | height u64            -&gt; count u32
 * 0x08 CLAIMED        -&gt; reserved for SP2/SP3
 * 0x09 HEIGHT_TRIGGER -&gt; reserved for SP2/SP3
 * 0x0A CHALLENGE      -&gt; reserved for SP2/SP3
 * 0x0B SEGMENT_CURSOR -&gt; reserved for SP2/SP3
 * 0x0C INPUT          -&gt; chainId 20 | height u64 | index u32 -&gt; {@link InputRecord}
 * 0x0D CODE_REF       -&gt; codeHash 32                       -&gt; refCount u32
 * 0x0E REVERSE        -&gt; block hash 32                     -&gt; {@link InputRef} list
 * 0xFF SNAPSHOT_HASH  -&gt; state hash 32                      (snapshot database / import marker)
 * </pre>
 *
 * <p>{@code CODE} and {@code CODE_REF} are split so that bumping a shared contract's reference
 * count only ever rewrites four bytes, never the (possibly large) code blob itself.
 *
 * <p>Prefixes {@code 0x04}..{@code 0x0B} are reserved for SP2/SP3 chain features (bonds, anchors,
 * claims, height triggers, challenges, segment cursors) so this task does not allocate them; SP0a
 * only writes {@code META}, {@code CHAIN}, {@code CONTRACT}, {@code CODE}, {@code CODE_REF},
 * {@code CALL_COUNT}, {@code INPUT} and {@code REVERSE}. {@code SNAPSHOT_HASH} is written by
 * {@link ChainL1Store#exportSnapshot} into a standalone snapshot {@link io.xdag.db.rocksdb.KVSource}
 * and copied by {@link ChainL1Store#importSnapshot} into the live CHAIN_L1 database as the one-shot
 * import marker read back by {@link ChainL1Store#importedSnapshotHash()}; it is the one key the
 * state hash never covers, so a node that imported a snapshot and one that replayed the same
 * history still hash identically.
 */
public final class ChainL1Keys {

    public static final byte META = 0x00;
    public static final byte CHAIN = 0x01;
    public static final byte CONTRACT = 0x02;
    public static final byte CODE = 0x03;
    public static final byte BOND = 0x04;
    public static final byte ANCHOR_HEAD = 0x05;
    public static final byte ANCHOR = 0x06;
    public static final byte CALL_COUNT = 0x07;
    public static final byte CLAIMED = 0x08;
    public static final byte HEIGHT_TRIGGER = 0x09;
    public static final byte CHALLENGE = 0x0A;
    public static final byte SEGMENT_CURSOR = 0x0B;
    public static final byte INPUT = 0x0C;
    public static final byte CODE_REF = 0x0D;
    public static final byte REVERSE = 0x0E;
    /** The state hash of everything else: recorded in a snapshot, then kept as the import marker. */
    public static final byte SNAPSHOT_HASH = (byte) 0xFF;

    public static final byte[] META_KEY = {META};
    public static final byte[] SNAPSHOT_HASH_KEY = {SNAPSHOT_HASH};

    private ChainL1Keys() {
    }

    public static byte[] chain(Bytes chainId) {
        require20(chainId);
        return BytesUtils.merge(CHAIN, chainId.toArray());
    }

    public static byte[] contract(Bytes contract) {
        require20(contract);
        return BytesUtils.merge(CONTRACT, contract.toArray());
    }

    public static byte[] code(Bytes32 codeHash) {
        return BytesUtils.merge(CODE, codeHash.toArray());
    }

    public static byte[] codeRef(Bytes32 codeHash) {
        return BytesUtils.merge(CODE_REF, codeHash.toArray());
    }

    public static byte[] callCount(Bytes chainId, long height) {
        require20(chainId);
        byte[] h = new byte[8];
        ExtCodec.putU64(h, 0, height);
        return BytesUtils.merge(new byte[]{CALL_COUNT}, chainId.toArray(), h);
    }

    public static byte[] input(Bytes chainId, long height, long index) {
        require20(chainId);
        byte[] tail = new byte[12];
        ExtCodec.putU64(tail, 0, height);
        ExtCodec.putU32(tail, 8, index);
        return BytesUtils.merge(new byte[]{INPUT}, chainId.toArray(), tail);
    }

    public static byte[] reverse(Bytes32 blockHash) {
        return BytesUtils.merge(REVERSE, blockHash.toArray());
    }

    private static void require20(Bytes id) {
        if (id == null || id.size() != 20) {
            throw new IllegalArgumentException(
                    "expected a 20-byte id, got " + (id == null ? "null" : id.size() + " bytes"));
        }
    }
}
