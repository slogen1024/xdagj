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
package io.xdag.chain.p2p;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.state.SparseMerkleTree.Kv;

/**
 * One snapshot is the full state KV at a seq, paged so each page fits in a chain frame.
 * {@code count u32 ‖ (key 32 ‖ valueLen u32 ‖ value)*}, keys strictly increasing inside a page
 * and across pages. An empty state is one page with {@code count} 0; a missing snapshot is no
 * page at all. The state root is the SMT root of every pair, checked by the requester against
 * the anchor, never taken from the peer.
 */
public final class SnapshotPages {

    private SnapshotPages() {
    }

    public static List<byte[]> split(List<Kv> kvs, int maxBytes) {
        List<Kv> sorted = new ArrayList<>(kvs);
        sorted.sort((a, b) -> a.key().compareTo(b.key()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).key().equals(sorted.get(i - 1).key())) {
                throw new IllegalArgumentException("duplicate state key");
            }
        }
        List<byte[]> pages = new ArrayList<>();
        List<Kv> batch = new ArrayList<>();
        int size = Integer.BYTES;
        for (Kv kv : sorted) {
            int n = entrySize(kv);
            if (Integer.BYTES + n > maxBytes) {
                throw new IllegalArgumentException("snapshot entry exceeds one page");
            }
            if (size + n > maxBytes) {
                pages.add(encode(batch));
                batch = new ArrayList<>();
                size = Integer.BYTES;
            }
            batch.add(kv);
            size += n;
        }
        pages.add(encode(batch));
        return List.copyOf(pages);
    }

    public static byte[] encode(List<Kv> kvs) {
        int size = Integer.BYTES;
        for (Kv kv : kvs) {
            size += entrySize(kv);
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(kvs.size());
        for (Kv kv : kvs) {
            buf.put(kv.key().toArray());
            buf.putInt(kv.value().length);
            buf.put(kv.value());
        }
        return buf.array();
    }

    public static List<Kv> decode(byte[] page) {
        ByteBuffer buf = ByteBuffer.wrap(page);
        if (buf.remaining() < Integer.BYTES) {
            throw ChainCodec.malformed("snapshot page truncated");
        }
        int count = buf.getInt();
        if (count < 0) {
            throw ChainCodec.malformed("snapshot page count");
        }
        List<Kv> out = new ArrayList<>(count);
        Bytes32 prev = null;
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < ChainCodec.CHAIN_ID_BYTES + Integer.BYTES) {
                throw ChainCodec.malformed("snapshot page truncated");
            }
            Bytes32 key = ChainCodec.chainId(buf);
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw ChainCodec.malformed("snapshot value length");
            }
            if (prev != null && prev.compareTo(key) >= 0) {
                throw ChainCodec.malformed("snapshot page not strictly increasing");
            }
            byte[] value = new byte[len];
            buf.get(value);
            out.add(new Kv(key, value));
            prev = key;
        }
        ChainCodec.exhaust(buf);
        return out;
    }

    public static Bytes32 root(List<Kv> kvs) {
        return SparseMerkleTree.root(kvs);
    }

    private static int entrySize(Kv kv) {
        return ChainCodec.CHAIN_ID_BYTES + Integer.BYTES + kv.value().length;
    }
}
