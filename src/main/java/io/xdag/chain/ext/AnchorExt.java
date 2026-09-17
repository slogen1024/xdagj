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
 * ANCHOR (kind 4): a chain's periodic state commitment into L1. Header: b0 kind, b1 flags (must be 0
 * in v1 — reserved for a v2 "braided" cross-chain anchor format; enabling it is a hard fork, since v1
 * {@link #decode} rejects any non-zero flags byte), b2..21 chainId (20 bytes), b22..29 seq u64 (the
 * main-block height this chain's anchor chain has consumed up to), b30..31 zero. Payload: field[0]
 * stateRoot, field[1] outboxMapRoot, field[2] prevSeq u64 | inputCount u32 | segmentCount u32 |
 * eventsRootAgg (16 bytes, reserved, must be zero in v1). Links: [0] the previous anchor for this
 * chain, [1] the main block at height {@code seq}, [2] the head of the commitment chain covering the
 * inputs/segments folded into this anchor; all three are emitted by the block builder, not by
 * {@link #encodeHeader()}/{@link #encodePayload()}.
 *
 * <p>{@code seq} and {@code prevSeq} are raw little-endian 64-bit bit patterns (an anchor sequence
 * number), unconstrained by this record — see {@link ChainConfigExt#gasPriceNano()} for the same
 * convention; values at or above 2^63 come back as a negative {@code long} and must be compared with
 * {@link Long#compareUnsigned(long, long)}, never {@code <}/{@code >}. {@code inputCount} and
 * {@code segmentCount} are true u32 quantities and are range-checked accordingly.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code chainId} is non-null and exactly 20 bytes; {@code stateRoot}, {@code outboxMapRoot},
 * {@code prevAnchor}, {@code mainBlock} and {@code commitmentHead} are non-null; {@code inputCount}
 * and {@code segmentCount} fit a u32. It does NOT enforce the protocol-level rules that {@link #decode}
 * checks: the v1 all-zero flags byte, the reserved header/payload tails, nor the exact link count — the
 * negative tests deliberately build such out-of-range records through the record. {@code chainId},
 * {@code stateRoot}, {@code outboxMapRoot}, {@code prevAnchor}, {@code mainBlock} and
 * {@code commitmentHead} are defensively copied.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); the flags byte and header tail must be all zero
 * ({@code RESERVED_NONZERO}); the payload field count ({@code PAYLOAD_COUNT_MISMATCH}); the link count
 * ({@code MISSING_LINK} / {@code EXTRA_LINK}); a {@code null} payload element likewise fails with
 * {@code PAYLOAD_COUNT_MISMATCH}; and finally the reserved zero tail of the third payload field
 * ({@code RESERVED_NONZERO}).
 *
 * <p>{@link #encodeHeader()} cannot throw: {@code chainId} is already fixed at 20 bytes and {@code seq}
 * is an unconstrained raw u64 bit pattern. {@link #encodePayload()} throws
 * {@link IllegalArgumentException} only if {@code inputCount} or {@code segmentCount} do not fit their
 * u32 payload width; the compact constructor already rejects such values, so this cannot happen for an
 * instance built through the public constructor.
 */
public record AnchorExt(Bytes chainId, long seq, Bytes32 stateRoot, Bytes32 outboxMapRoot, long prevSeq,
                         long inputCount, long segmentCount, Bytes32 prevAnchor, Bytes32 mainBlock,
                         Bytes32 commitmentHead) {

    public AnchorExt {
        Objects.requireNonNull(chainId, "chainId");
        if (chainId.size() != 20) {
            throw new IllegalArgumentException("chainId must be 20 bytes: " + chainId.size());
        }
        Objects.requireNonNull(stateRoot, "stateRoot");
        Objects.requireNonNull(outboxMapRoot, "outboxMapRoot");
        if (inputCount < 0 || inputCount > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("inputCount out of u32 range: " + inputCount);
        }
        if (segmentCount < 0 || segmentCount > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("segmentCount out of u32 range: " + segmentCount);
        }
        Objects.requireNonNull(prevAnchor, "prevAnchor");
        Objects.requireNonNull(mainBlock, "mainBlock");
        Objects.requireNonNull(commitmentHead, "commitmentHead");
        chainId = Bytes.wrap(chainId.toArray());
        stateRoot = Bytes32.wrap(stateRoot.toArray());
        outboxMapRoot = Bytes32.wrap(outboxMapRoot.toArray());
        prevAnchor = Bytes32.wrap(prevAnchor.toArray());
        mainBlock = Bytes32.wrap(mainBlock.toArray());
        commitmentHead = Bytes32.wrap(commitmentHead.toArray());
    }

    /**
     * Decodes a header/payload/links triple into an {@link AnchorExt}; never throws. {@code links}
     * must be the block's {@code XDAG_FIELD_OUT} block references in field order
     * ({@code isAddress == false}); the link field's amount and type are the classifier's concern, not
     * this codec's.
     */
    public static ExtResult<AnchorExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
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
        if (ExtCodec.u8(h, 0) != ExtKind.ANCHOR.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        if (ExtCodec.u8(h, 1) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (payload.size() != 3) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 3) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 3) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        Bytes32 stateRoot = payload.get(0);
        Bytes32 outboxMapRoot = payload.get(1);
        Bytes32 p2Field = payload.get(2);
        if (stateRoot == null || outboxMapRoot == null || p2Field == null) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        byte[] p2 = p2Field.toArray();
        if (!ExtCodec.isZero(p2, 16, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new AnchorExt(
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)),
                ExtCodec.u64(h, 22),
                stateRoot,
                outboxMapRoot,
                ExtCodec.u64(p2, 0),
                ExtCodec.u32(p2, 8),
                ExtCodec.u32(p2, 12),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray()),
                Bytes32.wrap(links.get(2).getAddress().toArray())));
    }

    /** Encodes the 32-byte ANCHOR header (kind, zero flags, chainId, seq, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.ANCHOR.code();
        System.arraycopy(chainId.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, seq);
        return Bytes32.wrap(h);
    }

    /** Encodes stateRoot, outboxMapRoot, and prevSeq/inputCount/segmentCount as the third field. */
    public List<Bytes32> encodePayload() {
        byte[] p2 = new byte[32];
        ExtCodec.putU64(p2, 0, prevSeq);
        ExtCodec.putU32(p2, 8, inputCount);
        ExtCodec.putU32(p2, 12, segmentCount);
        return List.of(stateRoot, outboxMapRoot, Bytes32.wrap(p2));
    }
}
