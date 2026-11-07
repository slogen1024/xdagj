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

import java.nio.ByteBuffer;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Big-endian fields for the chain messages ({@code 0x1B}–{@code 0x1F}). Lengths are fixed so a
 * body either decodes completely or is rejected; there is no trailing junk.
 *
 * <p>A snapshot page and a proof blob have to fit in one frame. {@code net.max.frame.body.size}
 * is 128 KiB including the message code, so the payload cap sits under that. A larger blob is a
 * distinct reply status, not a truncated OK.
 */
public final class ChainCodec {

    public static final int CHAIN_ID_BYTES = 32;
    public static final int MAX_SUBSCRIPTIONS = 4096;
    /** Under the 128 KiB frame body, leaving room for the code byte and the reply header. */
    public static final int MAX_PAYLOAD = 96 * 1024;
    public static final int MAX_KEY = 1024;

    public static final int STATUS_OK = 0;
    public static final int STATUS_ABSENT = 1;
    public static final int STATUS_OVERSIZE = 2;

    private ChainCodec() {
    }

    public static Bytes32 chainId(ByteBuffer buf) {
        if (buf.remaining() < CHAIN_ID_BYTES) {
            throw malformed("truncated chain id");
        }
        byte[] raw = new byte[CHAIN_ID_BYTES];
        buf.get(raw);
        return Bytes32.wrap(raw);
    }

    public static void chainId(ByteBuffer buf, Bytes32 id) {
        buf.put(id.toArray());
    }

    public static void exhaust(ByteBuffer buf) {
        if (buf.hasRemaining()) {
            throw malformed("trailing bytes");
        }
    }

    public static IllegalArgumentException malformed(String detail) {
        return new IllegalArgumentException("chain message: " + detail);
    }
}
