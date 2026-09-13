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
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CHALLENGE (kind 6): disputes one input of an anchor's commitment chain. Header: b0 kind, b1 flags
 * (must be 0 in v1), b2..5 inputIndex u32, b6..13 deposit u64, b14..31 zero. CHALLENGE carries no
 * payload fields. Links: [0] the anchor being disputed, [1] the head of the witness chain backing the
 * challenge; both are emitted by the block builder, not by {@link #encodeHeader()}.
 *
 * <p>{@code deposit} is a raw little-endian 64-bit bit pattern (the challenger's bonded deposit),
 * unconstrained by this record — see {@link LaneConfigExt#gasPriceNano()} for the same convention;
 * values at or above 2^63 come back as a negative {@code long} and must be compared with
 * {@link Long#compareUnsigned(long, long)}, never {@code <}/{@code >}. {@code inputIndex} is a true u32
 * quantity and is range-checked accordingly.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code inputIndex} fits a u32; {@code anchor} and {@code witnessHead} are non-null. It does NOT
 * enforce the protocol-level rules that {@link #decode} checks: the v1 all-zero flags byte, the
 * reserved header tail, nor the exact link count — the negative tests deliberately build such
 * out-of-range records through the record. {@code anchor} and {@code witnessHead} are defensively
 * copied.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); the flags byte and header tail must be all zero
 * ({@code RESERVED_NONZERO}); the payload must be empty ({@code PAYLOAD_COUNT_MISMATCH} otherwise); and
 * finally the link count ({@code MISSING_LINK} / {@code EXTRA_LINK} otherwise).
 *
 * <p>{@link #encodeHeader()} throws {@link IllegalArgumentException} if {@code inputIndex} does not fit
 * its header width; the compact constructor already rejects such values, so this cannot happen for an
 * instance built through the public constructor.
 */
public record ChallengeExt(long inputIndex, long deposit, Bytes32 anchor, Bytes32 witnessHead) {

    public ChallengeExt {
        if (inputIndex < 0 || inputIndex > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("inputIndex out of u32 range: " + inputIndex);
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(witnessHead, "witnessHead");
        anchor = Bytes32.wrap(anchor.toArray());
        witnessHead = Bytes32.wrap(witnessHead.toArray());
    }

    /** Decodes a header/payload/links triple into a {@link ChallengeExt}; never throws. */
    public static ExtResult<ChallengeExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        if (header == null) {
            return ExtResult.fail(ExtError.NO_EXT);
        }
        if (payload == null) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
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
        if (ExtCodec.u8(h, 0) != ExtKind.CHALLENGE.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        if (ExtCodec.u8(h, 1) != 0 || !ExtCodec.isZero(h, 14, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (!payload.isEmpty()) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 2) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 2) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        return ExtResult.ok(new ChallengeExt(ExtCodec.u32(h, 2), ExtCodec.u64(h, 6),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray())));
    }

    /** Encodes the 32-byte CHALLENGE header (kind, zero flags, inputIndex, deposit, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CHALLENGE.code();
        ExtCodec.putU32(h, 2, inputIndex);
        ExtCodec.putU64(h, 6, deposit);
        return Bytes32.wrap(h);
    }
}
