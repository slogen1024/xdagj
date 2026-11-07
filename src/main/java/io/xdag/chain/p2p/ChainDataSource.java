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

import org.apache.tuweni.bytes.Bytes32;

/**
 * What this node can hand a peer who asked. {@code null} means the node does not have it.
 * Returning bytes does not check them against an anchor; that check is the requester's.
 *
 * <p>{@link #NONE} is the production source until the execution store and the proof builders
 * exist. It answers every request with absence rather than an invented page.
 */
public interface ChainDataSource {

    ChainDataSource NONE = new ChainDataSource() {
    };

    /**
     * @return the snapshot page, or {@code null} when this node has no page there
     */
    default byte[] snapshotPage(Bytes32 chainId, long seq, int page) {
        return null;
    }

    /**
     * @return the proof blob, or {@code null} when this node has no proof of that kind
     */
    default byte[] proof(Bytes32 chainId, long seq, ProofKind kind, byte[] key) {
        return null;
    }
}
