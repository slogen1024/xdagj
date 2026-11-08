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
package io.xdag.chain.abi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.crypto.hash.HashUtils;

/**
 * Compact little-endian argument codec. A selector is {@code sha256(signature)[0..4]} as a signed
 * int. An event is {@code sha256(signature) ‖ body}. Lengths are {@code u32} LE.
 */
public final class AbiCodec {

    private AbiCodec() {
    }

    public static int selector(String signature) {
        byte[] hash = HashUtils.sha256(Bytes.wrap(signature.getBytes(StandardCharsets.US_ASCII))).toArray();
        return ((hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
    }

    public static Bytes32 eventTopic(String signature) {
        return HashUtils.sha256(Bytes.wrap(signature.getBytes(StandardCharsets.US_ASCII)));
    }

    public static byte[] event(String signature, byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        byte[] out = new byte[32 + raw.length];
        System.arraycopy(eventTopic(signature).toArray(), 0, out, 0, 32);
        System.arraycopy(raw, 0, out, 32, raw.length);
        return out;
    }

    public static byte[] u32(long value) {
        if (value < 0 || value > 0xffffffffL) {
            throw new IllegalArgumentException("u32");
        }
        byte[] raw = new byte[4];
        raw[0] = (byte) value;
        raw[1] = (byte) (value >>> 8);
        raw[2] = (byte) (value >>> 16);
        raw[3] = (byte) (value >>> 24);
        return raw;
    }

    public static byte[] u64(long value) {
        byte[] raw = new byte[8];
        long v = value;
        for (int i = 0; i < 8; i++) {
            raw[i] = (byte) v;
            v >>>= 8;
        }
        return raw;
    }

    public static byte[] bool(boolean value) {
        return new byte[] {(byte) (value ? 1 : 0)};
    }

    public static byte[] address(byte[] raw) {
        if (raw == null || raw.length != 20) {
            throw new IllegalArgumentException("address");
        }
        return raw.clone();
    }

    public static byte[] bytes(byte[] raw) {
        byte[] body = raw == null ? new byte[0] : raw;
        byte[] out = new byte[4 + body.length];
        System.arraycopy(u32(body.length), 0, out, 0, 4);
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    public static byte[] string(String value) {
        return bytes(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        for (byte[] part : parts) {
            buf.put(part);
        }
        return buf.array();
    }
}
