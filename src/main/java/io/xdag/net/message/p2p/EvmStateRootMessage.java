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
package io.xdag.net.message.p2p;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A node's EVM state commitment — the chained state root it computed at a given executed main height —
 * gossiped to peers so they can detect cross-node divergence (Phase 2). Fire-and-forget: the receiver
 * compares the claim against its own {@code HeightRecord} and never replies. The block field-type space
 * is exhausted, so the commitment travels as a P2P message rather than a native block field.
 */
public class EvmStateRootMessage extends Message {

    private final long height;
    private final Bytes32 root;

    public EvmStateRootMessage(long height, Bytes32 root) {
        super(MessageCode.EVM_STATE_ROOT, null);
        this.height = height;
        this.root = root;
        SimpleEncoder enc = new SimpleEncoder();
        enc.writeLong(height);
        enc.writeBytes(root.toArray());
        this.body = enc.toBytes();
    }

    public EvmStateRootMessage(byte[] body) {
        super(MessageCode.EVM_STATE_ROOT, null);
        SimpleDecoder dec = new SimpleDecoder(body);
        this.height = dec.readLong();
        this.root = Bytes32.wrap(dec.readBytes());
        this.body = body;
    }

    public long getHeight() {
        return height;
    }

    public Bytes32 getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return "EvmStateRootMessage [height=" + height + ", root=" + root.toHexString() + "]";
    }
}
