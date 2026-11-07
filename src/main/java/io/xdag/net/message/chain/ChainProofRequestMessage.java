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
import io.xdag.chain.p2p.ProofKind;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/**
 * {@code 0x1E CHAIN_PROOF_REQUEST}.
 * {@code requestId u64 ‖ chainId 32 ‖ seq u64 ‖ kind u8 ‖ keyLen u32 ‖ key}.
 */
public class ChainProofRequestMessage extends Message {

    private final long requestId;
    private final Bytes32 chainId;
    private final long seq;
    private final ProofKind kind;
    private final byte[] key;

    public ChainProofRequestMessage(long requestId, Bytes32 chainId, long seq, ProofKind kind, byte[] key) {
        super(MessageCode.CHAIN_PROOF_REQUEST, ChainProofReplyMessage.class);
        if (key.length > ChainCodec.MAX_KEY) {
            throw ChainCodec.malformed("proof key too large");
        }
        this.requestId = requestId;
        this.chainId = chainId;
        this.seq = seq;
        this.kind = kind;
        this.key = key.clone();
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + ChainCodec.CHAIN_ID_BYTES + Long.BYTES + 1
                + Integer.BYTES + key.length);
        buf.putLong(requestId);
        ChainCodec.chainId(buf, chainId);
        buf.putLong(seq);
        buf.put((byte) kind.code());
        buf.putInt(key.length);
        buf.put(this.key);
        this.body = buf.array();
    }

    public ChainProofRequestMessage(byte[] body) {
        super(MessageCode.CHAIN_PROOF_REQUEST, ChainProofReplyMessage.class);
        ByteBuffer buf = ByteBuffer.wrap(body);
        int header = Long.BYTES + ChainCodec.CHAIN_ID_BYTES + Long.BYTES + 1 + Integer.BYTES;
        if (buf.remaining() < header) {
            throw ChainCodec.malformed("proof request truncated");
        }
        this.requestId = buf.getLong();
        this.chainId = ChainCodec.chainId(buf);
        this.seq = buf.getLong();
        this.kind = ProofKind.of(buf.get() & 0xff);
        int len = buf.getInt();
        if (len < 0 || len > ChainCodec.MAX_KEY || buf.remaining() != len) {
            throw ChainCodec.malformed("proof key length");
        }
        this.key = new byte[len];
        buf.get(this.key);
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

    public ProofKind kind() {
        return kind;
    }

    public byte[] key() {
        return key.clone();
    }
}
