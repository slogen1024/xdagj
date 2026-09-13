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
import io.xdag.core.XdagBlock;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CHUNK (kind 3): one 352-byte slice of a larger payload. link[0] is the next chunk (absent on the tail).
 * Header: b0 kind, b1..4 seq u32, b5..8 totalLen u32, b9..10 dataLen u16, b11..31 zero.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code seq}/{@code totalLen} must fit a u32, {@code dataLen} a u16, {@code data} must be non-null
 * and {@code data.size() == dataLen}; both {@code data} and {@code next} are defensively copied. It
 * does NOT enforce the protocol-level ranges (0 &lt; dataLen &le; {@link #MAX_DATA_LEN}, dataLen &le;
 * totalLen) — {@link #decode} enforces those, and the negative tests deliberately build such
 * out-of-range headers through the record.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code links} list is treated as empty, and any {@code null} element fails with {@code MISSING_LINK};
 * the kind byte ({@code UNKNOWN_KIND} otherwise); the reserved-zero header tail
 * ({@code RESERVED_NONZERO}); {@code 0 < dataLen <= MAX_DATA_LEN} and {@code dataLen <= totalLen}
 * ({@code BAD_LENGTH}); at most one link ({@code EXTRA_LINK} otherwise); and finally the payload field
 * count and zero padding. On success {@code next} is {@code null} for a tail chunk (no link supplied)
 * and the decoded {@code data.size() == dataLen}.
 *
 * <p>{@link #encodeHeader()} throws {@link IllegalArgumentException} if {@code seq}, {@code totalLen}
 * or {@code dataLen} do not fit their header width; the compact constructor already rejects such
 * values, so this cannot happen for an instance built through the public constructor.
 */
public record ChunkExt(long seq, long totalLen, int dataLen, Bytes32 next, Bytes data) {

    public static final int MAX_PAYLOAD_FIELDS = XdagBlock.XDAG_BLOCK_FIELDS - 5; // header, next link, ext header, two zero SIGN_OUT
    public static final int MAX_DATA_LEN = MAX_PAYLOAD_FIELDS * ExtCodec.FIELD;

    public ChunkExt {
        Objects.requireNonNull(data, "data");
        if (seq < 0 || seq > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("seq out of u32 range: " + seq);
        }
        if (totalLen < 0 || totalLen > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("totalLen out of u32 range: " + totalLen);
        }
        if (dataLen < 0 || dataLen > 0xFFFF) {
            throw new IllegalArgumentException("dataLen out of u16 range: " + dataLen);
        }
        if (data.size() != dataLen) {
            throw new IllegalArgumentException("dataLen " + dataLen + " != data size " + data.size());
        }
        data = Bytes.wrap(data.toArray());
        next = next == null ? null : Bytes32.wrap(next.toArray());
    }

    /** Decodes a header/payload/links triple into a {@link ChunkExt}; never throws. */
    public static ExtResult<ChunkExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        if (header == null) {
            return ExtResult.fail(ExtError.NO_EXT);
        }
        if (links == null) {
            links = List.of();
        }
        // Manual scan, not links.contains(null): List.of(...)'s contains/indexOf throws NPE on a
        // null query argument even when the list holds no null elements.
        for (Address link : links) {
            if (link == null) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
        }
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

    /** Encodes the 32-byte CHUNK header (kind, seq, totalLen, dataLen, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CHUNK.code();
        ExtCodec.putU32(h, 1, seq);
        ExtCodec.putU32(h, 5, totalLen);
        ExtCodec.putU16(h, 9, dataLen);
        return Bytes32.wrap(h);
    }

    /** Splits {@link #data} into zero-padded 32-byte payload fields. */
    public List<Bytes32> encodePayload() {
        return ExtCodec.writeBytes(data);
    }
}
