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

import io.xdag.chain.p2p.ChainCodec;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/**
 * {@code 0x1F CHAIN_PROOF_REPLY}. Same header as the snapshot reply. On OK the payload is the
 * proof blob, opaque to this layer, and its sha256 is checked the same way.
 */
public class ChainProofReplyMessage extends Message {

    private final long requestId;
    private final int status;
    private final byte[] proof;

    private ChainProofReplyMessage(long requestId, int status, byte[] proof) {
        super(MessageCode.CHAIN_PROOF_REPLY, null);
        this.requestId = requestId;
        this.status = status;
        this.proof = proof;
        this.body = ChainSnapshotReplyMessage.encode(requestId, status, proof);
    }

    public static ChainProofReplyMessage absent(long requestId) {
        return new ChainProofReplyMessage(requestId, ChainCodec.STATUS_ABSENT, new byte[0]);
    }

    public static ChainProofReplyMessage oversize(long requestId) {
        return new ChainProofReplyMessage(requestId, ChainCodec.STATUS_OVERSIZE, new byte[0]);
    }

    public static ChainProofReplyMessage ok(long requestId, byte[] proof) {
        if (proof.length > ChainCodec.MAX_PAYLOAD) {
            throw ChainCodec.malformed("proof too large");
        }
        return new ChainProofReplyMessage(requestId, ChainCodec.STATUS_OK, proof.clone());
    }

    public ChainProofReplyMessage(byte[] body) {
        super(MessageCode.CHAIN_PROOF_REPLY, null);
        ChainSnapshotReplyMessage.Decoded decoded = ChainSnapshotReplyMessage.decode(body);
        this.requestId = decoded.requestId();
        this.status = decoded.status();
        this.proof = decoded.payload();
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

    /** The proof blob. Empty unless {@link #ok()}. */
    public byte[] proof() {
        return proof.clone();
    }
}
