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
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.p2p.ChainCodec;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/**
 * {@code 0x1D CHAIN_SNAPSHOT_REPLY}. {@code requestId u64 ‖ status u8}, and on OK
 * {@code pageHash 32 ‖ len u32 ‖ bytes}. {@code pageHash} is sha256 of the bytes. The requester
 * still has to check the page against the anchor's state root; this layer only refuses a reply
 * whose hash does not match its own bytes.
 *
 * <p>Status 0 OK, 1 ABSENT (not subscribed, or no such page), 2 OVERSIZE (held, but larger than
 * one frame can carry).
 */
public class ChainSnapshotReplyMessage extends Message {

    private final long requestId;
    private final int status;
    private final byte[] page;

    private ChainSnapshotReplyMessage(long requestId, int status, byte[] page) {
        super(MessageCode.CHAIN_SNAPSHOT_REPLY, null);
        this.requestId = requestId;
        this.status = status;
        this.page = page;
        this.body = encode(requestId, status, page);
    }

    public static ChainSnapshotReplyMessage absent(long requestId) {
        return new ChainSnapshotReplyMessage(requestId, ChainCodec.STATUS_ABSENT, new byte[0]);
    }

    public static ChainSnapshotReplyMessage oversize(long requestId) {
        return new ChainSnapshotReplyMessage(requestId, ChainCodec.STATUS_OVERSIZE, new byte[0]);
    }

    public static ChainSnapshotReplyMessage ok(long requestId, byte[] page) {
        if (page.length > ChainCodec.MAX_PAYLOAD) {
            throw ChainCodec.malformed("snapshot page too large");
        }
        return new ChainSnapshotReplyMessage(requestId, ChainCodec.STATUS_OK, page.clone());
    }

    public ChainSnapshotReplyMessage(byte[] body) {
        super(MessageCode.CHAIN_SNAPSHOT_REPLY, null);
        Decoded decoded = decode(body);
        this.requestId = decoded.requestId;
        this.status = decoded.status;
        this.page = decoded.payload;
        this.body = body;
    }

    public long requestId() {
        return requestId;
    }

    public boolean ok() {
        return status == ChainCodec.STATUS_OK;
    }

    public boolean absent() {
        return status == ChainCodec.STATUS_ABSENT;
    }

    public boolean oversize() {
        return status == ChainCodec.STATUS_OVERSIZE;
    }

    /** The page bytes. Empty unless {@link #ok()}. */
    public byte[] page() {
        return page.clone();
    }

    static byte[] encode(long requestId, int status, byte[] payload) {
        int extra = status == ChainCodec.STATUS_OK
                ? ChainCodec.CHAIN_ID_BYTES + Integer.BYTES + payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + 1 + extra);
        buf.putLong(requestId);
        buf.put((byte) status);
        if (status == ChainCodec.STATUS_OK) {
            buf.put(HashUtils.sha256(org.apache.tuweni.bytes.Bytes.wrap(payload)).toArray());
            buf.putInt(payload.length);
            buf.put(payload);
        }
        return buf.array();
    }

    static Decoded decode(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < Long.BYTES + 1) {
            throw ChainCodec.malformed("reply truncated");
        }
        long requestId = buf.getLong();
        int status = buf.get() & 0xff;
        if (status == ChainCodec.STATUS_ABSENT || status == ChainCodec.STATUS_OVERSIZE) {
            ChainCodec.exhaust(buf);
            return new Decoded(requestId, status, new byte[0]);
        }
        if (status != ChainCodec.STATUS_OK) {
            throw ChainCodec.malformed("reply status");
        }
        if (buf.remaining() < ChainCodec.CHAIN_ID_BYTES + Integer.BYTES) {
            throw ChainCodec.malformed("reply payload truncated");
        }
        byte[] hash = new byte[ChainCodec.CHAIN_ID_BYTES];
        buf.get(hash);
        int len = buf.getInt();
        if (len < 0 || len > ChainCodec.MAX_PAYLOAD || buf.remaining() != len) {
            throw ChainCodec.malformed("reply payload length");
        }
        byte[] payload = new byte[len];
        buf.get(payload);
        Bytes32 actual = HashUtils.sha256(org.apache.tuweni.bytes.Bytes.wrap(payload));
        if (!Arrays.equals(hash, actual.toArray())) {
            throw ChainCodec.malformed("reply hash");
        }
        return new Decoded(requestId, status, payload);
    }

    record Decoded(long requestId, int status, byte[] payload) {
    }
}
