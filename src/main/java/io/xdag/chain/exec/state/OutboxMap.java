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
package io.xdag.chain.exec.state;

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes32;

/**
 * outboxMap: one SMT entry per destination. Key is the destination chain id. Value is
 * {@code mmrRoot 32 ‖ size u64}, 40 bytes. {@link #root()} is the anchor's {@code outboxMapRoot}.
 */
public final class OutboxMap {

    public static final int VALUE_BYTES = Bytes32.SIZE + Long.BYTES;

    public record Entry(Bytes32 mmrRoot, long size) {
    }

    private final StateTree tree = new StateTree();

    public void put(Bytes32 dst, Bytes32 mmrRoot, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("outbox size");
        }
        ByteBuffer buf = ByteBuffer.allocate(VALUE_BYTES);
        buf.put(mmrRoot.toArray());
        buf.putLong(size);
        tree.put(dst, buf.array());
    }

    public Entry get(Bytes32 dst) {
        byte[] raw = tree.get(dst);
        if (raw == null) {
            return null;
        }
        if (raw.length != VALUE_BYTES) {
            throw new IllegalStateException("outbox value");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte[] root = new byte[Bytes32.SIZE];
        buf.get(root);
        return new Entry(Bytes32.wrap(root), buf.getLong());
    }

    public void remove(Bytes32 dst) {
        tree.remove(dst);
    }

    public Bytes32 root() {
        return tree.root();
    }

    public SparseMerkleTree.Proof proof(Bytes32 dst) {
        return tree.proof(dst);
    }

    public StateTree tree() {
        return tree;
    }
}
