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

package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * BOND (kind 5): stakes or unstakes a chain operator bond. Header: b0 kind, b1 flags (bit0 =
 * {@link #FLAG_UNBOND}), b2..21 chainId (20 bytes; all-zero means a global/unassigned bond — a
 * protocol-level convention, not enforced by this record), b22..29 amount u64 (the amount to unbond),
 * b30..31 zero. BOND carries no payload fields and no links.
 *
 * <p>{@code amount} is a raw little-endian 64-bit bit pattern, unconstrained by this record — see
 * {@link ChainConfigExt#gasPriceNano()} for the same convention; values at or above 2^63 come back as a
 * negative {@code long} and must be compared with {@link Long#compareUnsigned(long, long)}, never
 * {@code <}/{@code >}. It is meaningful only when {@link #FLAG_UNBOND} is set: for a stake
 * ({@code unbond == false}) this field is ignored, since the staked amount is instead carried by the
 * block's OUTPUT to the bond vault, not by this extension.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code chainId} is non-null and exactly 20 bytes. It does NOT enforce the protocol-level rules that
 * {@link #decode} checks: unknown flag bits nor the reserved header tail — the negative tests
 * deliberately build such out-of-range records through the record. {@code chainId} is defensively
 * copied.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); unknown flag bits and the reserved header tail
 * ({@code RESERVED_NONZERO}); the payload must be empty ({@code PAYLOAD_COUNT_MISMATCH} otherwise); and
 * finally the links must be empty ({@code EXTRA_LINK} otherwise).
 *
 * <p>{@link #encodeHeader()} cannot throw: {@code chainId} is already fixed at 20 bytes and
 * {@code amount} is an unconstrained raw u64 bit pattern.
 */
public record BondExt(boolean unbond, Bytes chainId, long amount) {

    /** Header flag bit0: this BOND withdraws (rather than stakes) the bond. */
    public static final int FLAG_UNBOND = 0x01;

    public BondExt {
        Objects.requireNonNull(chainId, "chainId");
        if (chainId.size() != 20) {
            throw new IllegalArgumentException("chainId must be 20 bytes: " + chainId.size());
        }
        chainId = Bytes.wrap(chainId.toArray());
    }

    /**
     * Decodes a header/payload/links triple into a {@link BondExt}; never throws. {@code links} must
     * be the block's {@code XDAG_FIELD_OUT} block references in field order ({@code isAddress == false});
     * the link field's amount and type are the classifier's concern, not this codec's.
     */
    public static ExtResult<BondExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
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
        if (ExtCodec.u8(h, 0) != ExtKind.BOND.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_UNBOND) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (!payload.isEmpty()) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (!links.isEmpty()) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        return ExtResult.ok(new BondExt((flags & FLAG_UNBOND) != 0,
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)), ExtCodec.u64(h, 22)));
    }

    /** Encodes the 32-byte BOND header (kind, flags, chainId, amount, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.BOND.code();
        h[1] = (byte) (unbond ? FLAG_UNBOND : 0);
        System.arraycopy(chainId.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, amount);
        return Bytes32.wrap(h);
    }
}
