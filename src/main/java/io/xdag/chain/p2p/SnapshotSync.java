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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree.Kv;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;

/**
 * Pull every page of one snapshot and accept it only when the rebuilt SMT root is the anchor's
 * {@code stateRoot}. Page 0 ABSENT means this peer has no snapshot. A later ABSENT ends the
 * snapshot. OVERSIZE cannot be reassembled from one frame, so it fails the pull.
 *
 * <p>Pages are retained only up to {@link #DEFAULT_MAX_BYTES}. A peer that never sends ABSENT
 * stops there, before the SMT root is built. The root still needs every retained pair, so the
 * cap is what bounds the memory; raise it at the call for a chain whose state is known to be
 * larger.
 */
public final class SnapshotSync {

    static final int MAX_PAGES = 65_536;
    /** Node-local. Not a consensus limit. */
    public static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;

    private SnapshotSync() {
    }

    public static List<Kv> pull(Bytes32 expectedRoot, IntFunction<ChainSnapshotReplyMessage> fetch) {
        return pull(expectedRoot, fetch, DEFAULT_MAX_BYTES);
    }

    public static List<Kv> pull(Bytes32 expectedRoot, IntFunction<ChainSnapshotReplyMessage> fetch, int maxBytes) {
        List<Kv> all = gather(fetch, maxBytes);
        if (!SnapshotPages.root(all).equals(expectedRoot)) {
            throw new IllegalArgumentException("state root");
        }
        return all;
    }

    /**
     * Every page, without comparing {@link SnapshotPages#root} to an anchor. A null reply is still
     * in flight. Page 0 ABSENT is no snapshot. The caller checks the anchor root itself when the
     * page bytes are the exec-store blobs rather than the SMT leaves.
     */
    public static List<Kv> gather(IntFunction<ChainSnapshotReplyMessage> fetch) {
        return gather(fetch, DEFAULT_MAX_BYTES);
    }

    public static List<Kv> gather(IntFunction<ChainSnapshotReplyMessage> fetch, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("snapshot byte cap");
        }
        List<Kv> all = new ArrayList<>();
        Bytes32 prev = null;
        boolean ended = false;
        long used = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            ChainSnapshotReplyMessage reply = fetch.apply(page);
            if (reply == null) {
                throw new IllegalStateException("snapshot pending");
            }
            if (reply.absent()) {
                if (page == 0) {
                    throw new IllegalArgumentException("no snapshot");
                }
                ended = true;
                break;
            }
            if (reply.oversize() || !reply.ok()) {
                throw new IllegalArgumentException("snapshot page oversize");
            }
            byte[] raw = reply.page();
            used += raw.length;
            if (used > maxBytes) {
                throw new IllegalArgumentException("snapshot byte cap");
            }
            List<Kv> kvs = SnapshotPages.decode(raw);
            for (Kv kv : kvs) {
                if (prev != null && prev.compareTo(kv.key()) >= 0) {
                    throw new IllegalArgumentException("snapshot pages out of order");
                }
                prev = kv.key();
                all.add(kv);
            }
        }
        if (!ended) {
            throw new IllegalArgumentException("snapshot page cap");
        }
        return all;
    }
}
