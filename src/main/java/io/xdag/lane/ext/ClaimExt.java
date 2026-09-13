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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CLAIM (kind 7): releases a channel outbox entry to its recipient. Header: b0 kind, b1 flags (bit0 =
 * {@link #FLAG_TO_LANE_VAULT}: the recipient is a lane vault rather than an L1 address), b2..21
 * srcLane (20 bytes), b22..29 seq u64 (the source anchor's sequence number), b30..31 zero. Payload:
 * field[0] index u64 | amount u64 | 16 zero; field[1] recipient (20 bytes) | 12 zero. Links: [0] the
 * anchor named by {@code seq}, [1] the head of the proof chain backing the claim; both are emitted by
 * the block builder, not by {@link #encodeHeader()}/{@link #encodePayload()}.
 *
 * <p>{@code seq}, {@code index} and {@code amount} are raw little-endian 64-bit bit patterns,
 * unconstrained by this record — see {@link LaneConfigExt#gasPriceNano()} for the same convention;
 * values at or above 2^63 come back as a negative {@code long} and must be compared with
 * {@link Long#compareUnsigned(long, long)}, never {@code <}/{@code >}.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code srcLane} and {@code recipient} are non-null and exactly 20 bytes; {@code anchor} and
 * {@code proofHead} are non-null. It does NOT enforce the protocol-level rules that {@link #decode}
 * checks: unknown flag bits, the reserved header/payload tails, nor the exact link count — the negative
 * tests deliberately build such out-of-range records through the record. {@code srcLane},
 * {@code recipient}, {@code anchor} and {@code proofHead} are defensively copied.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); unknown flag bits and the reserved header tail
 * ({@code RESERVED_NONZERO}); the payload field count ({@code PAYLOAD_COUNT_MISMATCH}); the link count
 * ({@code MISSING_LINK} / {@code EXTRA_LINK}); a {@code null} payload element likewise fails with
 * {@code PAYLOAD_COUNT_MISMATCH}; and finally the reserved zero tails of both payload fields
 * ({@code RESERVED_NONZERO}).
 *
 * <p>{@link #encodeHeader()} and {@link #encodePayload()} cannot throw: {@code srcLane} and
 * {@code recipient} are already fixed at 20 bytes and {@code seq}/{@code index}/{@code amount} are
 * unconstrained raw u64 bit patterns.
 */
public record ClaimExt(boolean toLaneVault, Bytes srcLane, long seq, long index, long amount, Bytes recipient,
                        Bytes32 anchor, Bytes32 proofHead) {

    /** Header flag bit0: the recipient is a lane vault rather than an L1 address. */
    public static final int FLAG_TO_LANE_VAULT = 0x01;

    public ClaimExt {
        Objects.requireNonNull(srcLane, "srcLane");
        if (srcLane.size() != 20) {
            throw new IllegalArgumentException("srcLane must be 20 bytes: " + srcLane.size());
        }
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.size() != 20) {
            throw new IllegalArgumentException("recipient must be 20 bytes: " + recipient.size());
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(proofHead, "proofHead");
        srcLane = Bytes.wrap(srcLane.toArray());
        recipient = Bytes.wrap(recipient.toArray());
        anchor = Bytes32.wrap(anchor.toArray());
        proofHead = Bytes32.wrap(proofHead.toArray());
    }

    /** Decodes a header/payload/links triple into a {@link ClaimExt}; never throws. */
    public static ExtResult<ClaimExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
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
        if (ExtCodec.u8(h, 0) != ExtKind.CLAIM.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_TO_LANE_VAULT) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (payload.size() != 2) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 2) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 2) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        Bytes32 p0Field = payload.get(0);
        Bytes32 p1Field = payload.get(1);
        if (p0Field == null || p1Field == null) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        byte[] p0 = p0Field.toArray();
        byte[] p1 = p1Field.toArray();
        if (!ExtCodec.isZero(p0, 16, 32) || !ExtCodec.isZero(p1, 20, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new ClaimExt((flags & FLAG_TO_LANE_VAULT) != 0,
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)), ExtCodec.u64(h, 22),
                ExtCodec.u64(p0, 0), ExtCodec.u64(p0, 8), Bytes.wrap(Arrays.copyOfRange(p1, 0, 20)),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray())));
    }

    /** Encodes the 32-byte CLAIM header (kind, flags, srcLane, seq, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CLAIM.code();
        h[1] = (byte) (toLaneVault ? FLAG_TO_LANE_VAULT : 0);
        System.arraycopy(srcLane.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, seq);
        return Bytes32.wrap(h);
    }

    /** Encodes index/amount as the first payload field and recipient as the second. */
    public List<Bytes32> encodePayload() {
        byte[] p0 = new byte[32];
        ExtCodec.putU64(p0, 0, index);
        ExtCodec.putU64(p0, 8, amount);
        byte[] p1 = new byte[32];
        System.arraycopy(recipient.toArray(), 0, p1, 0, 20);
        return List.of(Bytes32.wrap(p0), Bytes32.wrap(p1));
    }
}
