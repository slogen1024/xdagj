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
package io.xdag.net.message.chain;

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.p2p.ChainCodec;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/**
 * {@code 0x1C CHAIN_SNAPSHOT_REQUEST}. {@code requestId u64 ‖ chainId 32 ‖ seq u64 ‖ page u32}.
 * {@code requestId} only pairs the reply; it is not part of the page identity.
 */
public class ChainSnapshotRequestMessage extends Message {

    private final long requestId;
    private final Bytes32 chainId;
    private final long seq;
    private final int page;

    public ChainSnapshotRequestMessage(long requestId, Bytes32 chainId, long seq, int page) {
        super(MessageCode.CHAIN_SNAPSHOT_REQUEST, ChainSnapshotReplyMessage.class);
        if (page < 0) {
            throw ChainCodec.malformed("negative page");
        }
        this.requestId = requestId;
        this.chainId = chainId;
        this.seq = seq;
        this.page = page;
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + ChainCodec.CHAIN_ID_BYTES + Long.BYTES + Integer.BYTES);
        buf.putLong(requestId);
        ChainCodec.chainId(buf, chainId);
        buf.putLong(seq);
        buf.putInt(page);
        this.body = buf.array();
    }

    public ChainSnapshotRequestMessage(byte[] body) {
        super(MessageCode.CHAIN_SNAPSHOT_REQUEST, ChainSnapshotReplyMessage.class);
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < Long.BYTES + ChainCodec.CHAIN_ID_BYTES + Long.BYTES + Integer.BYTES) {
            throw ChainCodec.malformed("snapshot request truncated");
        }
        this.requestId = buf.getLong();
        this.chainId = ChainCodec.chainId(buf);
        this.seq = buf.getLong();
        this.page = buf.getInt();
        if (page < 0) {
            throw ChainCodec.malformed("negative page");
        }
        ChainCodec.exhaust(buf);
        this.body = body;
    }

    public long requestId() {
        return requestId;
    }

    public Bytes32 chainId() {
        return chainId;
    }

    public long seq() {
        return seq;
    }

    public int page() {
        return page;
    }
}
