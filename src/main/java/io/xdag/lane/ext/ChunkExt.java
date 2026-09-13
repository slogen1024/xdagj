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

package io.xdag.lane.ext;

import io.xdag.core.Address;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CHUNK (kind 3): one 352-byte slice of a larger payload. link[0] is the next chunk (absent on the tail).
 * Header: b0 kind, b1..4 seq u32, b5..8 totalLen u32, b9..10 dataLen u16, b11..31 zero.
 */
public record ChunkExt(long seq, long totalLen, int dataLen, Bytes32 next, Bytes data) {

    /** header + next link + ext header + two zero SIGN_OUT fields leave 11 payload fields. */
    public static final int MAX_PAYLOAD_FIELDS = 11;
    public static final int MAX_DATA_LEN = MAX_PAYLOAD_FIELDS * ExtCodec.FIELD;

    public static ExtResult<ChunkExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.CHUNK.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        long seq = ExtCodec.u32(h, 1);
        long totalLen = ExtCodec.u32(h, 5);
        int dataLen = ExtCodec.u16(h, 9);
        if (!ExtCodec.isZero(h, 11, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (dataLen == 0 || dataLen > MAX_DATA_LEN || dataLen > totalLen) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        if (links.size() > 1) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        ExtResult<Bytes> data = ExtCodec.readBytes(payload, dataLen);
        if (!data.isOk()) {
            return ExtResult.fail(data.error());
        }
        Bytes32 next = links.isEmpty() ? null : Bytes32.wrap(links.get(0).getAddress().toArray());
        return ExtResult.ok(new ChunkExt(seq, totalLen, dataLen, next, data.value()));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CHUNK.code();
        ExtCodec.putU32(h, 1, seq);
        ExtCodec.putU32(h, 5, totalLen);
        ExtCodec.putU16(h, 9, dataLen);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        return ExtCodec.writeBytes(data);
    }
}
