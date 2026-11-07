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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree.Kv;

/**
 * What this node can serve for the chains it subscribes to. Empty until something that actually
 * executed the chain calls {@link #putSnapshot} or {@link #putProof}. A miss is {@code null},
 * which the exchange turns into ABSENT — it does not invent a page.
 */
public final class ChainRecords implements ChainDataSource {

    private record Snap(Bytes32 chain, long seq) {
    }

    private record ProofKey(Bytes32 chain, long seq, ProofKind kind, Bytes key) {
    }

    private final ConcurrentHashMap<Snap, List<byte[]>> pages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Snap, Bytes32> roots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProofKey, byte[]> proofs = new ConcurrentHashMap<>();

    public void putSnapshot(Bytes32 chain, long seq, List<Kv> kvs) {
        List<byte[]> split = SnapshotPages.split(kvs, ChainCodec.MAX_PAYLOAD);
        Snap key = new Snap(chain, seq);
        pages.put(key, split);
        roots.put(key, SnapshotPages.root(kvs));
    }

    public Bytes32 snapshotRoot(Bytes32 chain, long seq) {
        return roots.get(new Snap(chain, seq));
    }

    public void putProof(Bytes32 chain, long seq, ProofKind kind, byte[] key, byte[] body) {
        if (body.length > ChainCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("proof exceeds one frame");
        }
        proofs.put(new ProofKey(chain, seq, kind, Bytes.wrap(key)), body.clone());
    }

    @Override
    public byte[] snapshotPage(Bytes32 chainId, long seq, int page) {
        List<byte[]> found = pages.get(new Snap(chainId, seq));
        if (found == null || page < 0 || page >= found.size()) {
            return null;
        }
        return found.get(page);
    }

    @Override
    public byte[] proof(Bytes32 chainId, long seq, ProofKind kind, byte[] key) {
        return proofs.get(new ProofKey(chainId, seq, kind, Bytes.wrap(key)));
    }
}
