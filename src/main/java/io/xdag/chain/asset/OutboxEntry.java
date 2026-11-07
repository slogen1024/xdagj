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
package io.xdag.chain.asset;

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.ChainLedger;
import io.xdag.crypto.hash.HashUtils;

/**
 * One outbox MMR leaf body. The leaf hash is {@code sha256(body)} with no type tag.
 *
 * <pre>
 * src20 ‖ dst20 ‖ index u64 BE ‖ sender20 ‖ contract20 ‖ selector u32 BE ‖ value u64 BE ‖ height u64 BE ‖ args
 * </pre>
 *
 * A withdraw uses 20 zero bytes as {@code dst} and the L1 recipient as {@code contract}. A
 * {@code send} uses the destination chain as {@code dst}. Integer fields are big-endian, the same
 * bytes {@code ChainExecutor} has appended since SP1-3.
 */
public record OutboxEntry(byte[] src, byte[] dst, long index, byte[] sender, byte[] contract, int selector,
        long value, long height, byte[] args) {

    public static final int HEAD = 20 + 20 + Long.BYTES + 20 + 20 + Integer.BYTES + Long.BYTES + Long.BYTES;

    public OutboxEntry {
        src = ChainLedger.address20(src);
        dst = ChainLedger.address20(dst);
        sender = ChainLedger.address20(sender);
        contract = ChainLedger.address20(contract);
        args = args == null ? new byte[0] : args.clone();
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(HEAD + args.length);
        buf.put(src);
        buf.put(dst);
        buf.putLong(index);
        buf.put(sender);
        buf.put(contract);
        buf.putInt(selector);
        buf.putLong(value);
        buf.putLong(height);
        buf.put(args);
        return buf.array();
    }

    public Bytes32 leaf() {
        return HashUtils.sha256(Bytes.wrap(encode()));
    }

    public static OutboxEntry decode(byte[] raw) {
        if (raw == null || raw.length < HEAD) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte[] src = new byte[20];
        byte[] dst = new byte[20];
        byte[] sender = new byte[20];
        byte[] contract = new byte[20];
        buf.get(src);
        buf.get(dst);
        long index = buf.getLong();
        buf.get(sender);
        buf.get(contract);
        int selector = buf.getInt();
        long value = buf.getLong();
        long height = buf.getLong();
        byte[] args = new byte[buf.remaining()];
        buf.get(args);
        return new OutboxEntry(src, dst, index, sender, contract, selector, value, height, args);
    }
}
