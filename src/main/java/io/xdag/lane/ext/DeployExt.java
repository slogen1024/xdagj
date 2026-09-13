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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DEPLOY (kind 2): creates or targets a channel/contract lane. Header: b0 kind, b1 flags (bit0 =
 * {@link #FLAG_NEW_LANE}, bit1 = {@link #FLAG_CODE_CHAIN}, bit2 = {@link #FLAG_ARGS_CHAIN}), b2..21
 * laneId (20 bytes; must be all-zero when {@link #FLAG_NEW_LANE} is set — a {@link #decode} rule, not
 * a constructor rule), b22..25 gasLimit u32, b26..27 argsLen u16, b28..31 zero. Payload: field[0] is
 * always {@code codeHash}; field[1] is the {@link LaneConfigExt} when {@link #FLAG_NEW_LANE} is set
 * (absent otherwise); the remaining fields carry the inline init args when {@link #FLAG_ARGS_CHAIN} is
 * clear (at most {@link CallExt#MAX_INLINE_ARGS} bytes, per {@code argsLen}), or nothing when the args
 * are chained. Links: the code chain head first when {@link #FLAG_CODE_CHAIN} is set, then the args
 * chain head when {@link #FLAG_ARGS_CHAIN} is set; both links are emitted by the block builder, not by
 * {@link #encodeHeader()}/{@link #encodePayload()}.
 *
 * <p>Inline args and a chained args head are mutually exclusive: exactly one of them is populated,
 * matching {@link #FLAG_ARGS_CHAIN}. Likewise a chained code head is populated exactly when
 * {@link #FLAG_CODE_CHAIN} is set, and {@code config} is non-null exactly when {@link #FLAG_NEW_LANE}
 * is set.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code laneId}, {@code codeHash} and {@code inlineArgs} are non-null; {@code laneId.size() == 20};
 * {@code flags} fits a u8; {@code gasLimit} fits a u32 and {@code argsLen} a u16;
 * {@code inlineArgs.size() == argsLen}; {@link #codeByChain()} agrees with {@code codeChainHead} being
 * non-null; {@link #argsByChain()} agrees with {@code argsChainHead} being non-null; a set
 * {@link #argsByChain()} forces {@code argsLen == 0}; and {@link #newLane()} agrees with {@code config}
 * being non-null. {@code laneId}, {@code codeHash}, {@code inlineArgs}, {@code codeChainHead} and
 * {@code argsChainHead} are defensively copied. It does NOT enforce the protocol-level rules that
 * {@link #decode} checks: unknown flag bits, a non-zero {@code laneId} together with
 * {@link #FLAG_NEW_LANE}, nor {@code argsLen <= CallExt#MAX_INLINE_ARGS} — the negative tests
 * deliberately build such out-of-range records through the record.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); unknown flag bits and the reserved header tail
 * ({@code RESERVED_NONZERO}); a non-zero {@code laneId} together with {@link #FLAG_NEW_LANE}
 * ({@code RESERVED_NONZERO}); a non-empty payload carrying {@code codeHash} ({@code BAD_LENGTH}
 * otherwise); when {@link #FLAG_NEW_LANE} is set, a second payload field decoded as a
 * {@link LaneConfigExt} ({@code BAD_LENGTH} / that decode's error otherwise); when
 * {@link #FLAG_ARGS_CHAIN} is set, {@code argsLen == 0} and no remaining payload fields
 * ({@code BAD_LENGTH} / {@code PAYLOAD_COUNT_MISMATCH} otherwise), otherwise
 * {@code argsLen <= CallExt#MAX_INLINE_ARGS} ({@code INLINE_ARGS_TOO_LONG}) and the remaining payload
 * decoded as inline args; and finally the expected number of links (code head, then args head)
 * ({@code MISSING_LINK} / {@code EXTRA_LINK} otherwise).
 *
 * <p>{@link #encodeHeader()} throws {@link IllegalArgumentException} if {@code gasLimit} or
 * {@code argsLen} do not fit their header width; the compact constructor already rejects such values,
 * so this cannot happen for an instance built through the public constructor. {@link #encodePayload()}
 * can throw only if {@code config}'s own fields (already range-checked by {@link LaneConfigExt}) do
 * not fit, which likewise cannot happen for a validly constructed instance.
 */
public record DeployExt(int flags, Bytes laneId, long gasLimit, int argsLen, Bytes32 codeHash, LaneConfigExt config,
                         Bytes inlineArgs, Bytes32 codeChainHead, Bytes32 argsChainHead) {

    /** Header flag bit0: this DEPLOY creates a new lane (laneId must be zero on the wire). */
    public static final int FLAG_NEW_LANE = 0x01;
    /** Header flag bit1: the code is carried by a chunk chain rather than referenced by hash alone. */
    public static final int FLAG_CODE_CHAIN = 0x02;
    /** Header flag bit2: the init args are carried by a chunk chain rather than inline. */
    public static final int FLAG_ARGS_CHAIN = 0x04;
    private static final int FLAGS_MASK = FLAG_NEW_LANE | FLAG_CODE_CHAIN | FLAG_ARGS_CHAIN;

    public DeployExt {
        if (flags < 0 || flags > 0xFF) {
            throw new IllegalArgumentException("flags out of u8 range: " + flags);
        }
        Objects.requireNonNull(laneId, "laneId");
        if (laneId.size() != 20) {
            throw new IllegalArgumentException("laneId must be 20 bytes: " + laneId.size());
        }
        if (gasLimit < 0 || gasLimit > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("gasLimit out of u32 range: " + gasLimit);
        }
        if (argsLen < 0 || argsLen > 0xFFFF) {
            throw new IllegalArgumentException("argsLen out of u16 range: " + argsLen);
        }
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(inlineArgs, "inlineArgs");
        if (inlineArgs.size() != argsLen) {
            throw new IllegalArgumentException("argsLen " + argsLen + " != inlineArgs size " + inlineArgs.size());
        }
        if (((flags & FLAG_CODE_CHAIN) != 0) != (codeChainHead != null)) {
            throw new IllegalArgumentException("FLAG_CODE_CHAIN and codeChainHead must agree");
        }
        if (((flags & FLAG_ARGS_CHAIN) != 0) != (argsChainHead != null)) {
            throw new IllegalArgumentException("FLAG_ARGS_CHAIN and argsChainHead must agree");
        }
        if (((flags & FLAG_ARGS_CHAIN) != 0) && argsLen != 0) {
            throw new IllegalArgumentException("chained args must have argsLen 0");
        }
        if (((flags & FLAG_NEW_LANE) != 0) != (config != null)) {
            throw new IllegalArgumentException("FLAG_NEW_LANE and config must agree");
        }
        laneId = Bytes.wrap(laneId.toArray());
        codeHash = Bytes32.wrap(codeHash.toArray());
        inlineArgs = Bytes.wrap(inlineArgs.toArray());
        codeChainHead = codeChainHead == null ? null : Bytes32.wrap(codeChainHead.toArray());
        argsChainHead = argsChainHead == null ? null : Bytes32.wrap(argsChainHead.toArray());
    }

    /** Returns true when this DEPLOY creates a new lane (rather than targeting an existing one). */
    public boolean newLane() {
        return (flags & FLAG_NEW_LANE) != 0;
    }

    /** Returns true when the code is carried by a chunk chain rather than referenced by hash alone. */
    public boolean codeByChain() {
        return (flags & FLAG_CODE_CHAIN) != 0;
    }

    /** Returns true when the init args are carried by a chunk chain rather than inline. */
    public boolean argsByChain() {
        return (flags & FLAG_ARGS_CHAIN) != 0;
    }

    /** Decodes a header/payload/links triple into a {@link DeployExt}; never throws. */
    public static ExtResult<DeployExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
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
        if (ExtCodec.u8(h, 0) != ExtKind.DEPLOY.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAGS_MASK) != 0 || !ExtCodec.isZero(h, 28, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        boolean newLane = (flags & FLAG_NEW_LANE) != 0;
        boolean codeChain = (flags & FLAG_CODE_CHAIN) != 0;
        boolean argsChain = (flags & FLAG_ARGS_CHAIN) != 0;
        if (newLane && !ExtCodec.isZero(h, 2, 22)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        Bytes laneId = Bytes.wrap(Arrays.copyOfRange(h, 2, 22));
        long gasLimit = ExtCodec.u32(h, 22);
        int argsLen = ExtCodec.u16(h, 26);

        if (payload.isEmpty()) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Bytes32 codeHash = payload.get(0);
        if (codeHash == null) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        int idx = 1;
        LaneConfigExt config = null;
        if (newLane) {
            if (payload.size() < 2) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            ExtResult<LaneConfigExt> c = LaneConfigExt.decode(payload.get(1));
            if (!c.isOk()) {
                return ExtResult.fail(c.error());
            }
            config = c.value();
            idx = 2;
        }
        List<Bytes32> argsFields = payload.subList(idx, payload.size());
        Bytes inline = Bytes.EMPTY;
        if (argsChain) {
            if (argsLen != 0) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            if (!argsFields.isEmpty()) {
                return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
            }
        } else {
            if (argsLen > CallExt.MAX_INLINE_ARGS) {
                return ExtResult.fail(ExtError.INLINE_ARGS_TOO_LONG);
            }
            ExtResult<Bytes> a = ExtCodec.readBytes(argsFields, argsLen);
            if (!a.isOk()) {
                return ExtResult.fail(a.error());
            }
            inline = a.value();
        }
        int expectedLinks = (codeChain ? 1 : 0) + (argsChain ? 1 : 0);
        if (links.size() < expectedLinks) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > expectedLinks) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        Bytes32 codeHead = codeChain ? Bytes32.wrap(links.get(0).getAddress().toArray()) : null;
        Bytes32 argsHead = argsChain ? Bytes32.wrap(links.get(codeChain ? 1 : 0).getAddress().toArray()) : null;
        return ExtResult.ok(new DeployExt(flags, laneId, gasLimit, argsLen, codeHash, config, inline, codeHead, argsHead));
    }

    /** Encodes the 32-byte DEPLOY header (kind, flags, laneId, gasLimit, argsLen, zero padding). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.DEPLOY.code();
        h[1] = (byte) flags;
        System.arraycopy(laneId.toArray(), 0, h, 2, 20);
        ExtCodec.putU32(h, 22, gasLimit);
        ExtCodec.putU16(h, 26, argsLen);
        return Bytes32.wrap(h);
    }

    /** Encodes codeHash, the optional lane config, and the inline args (when not chained). */
    public List<Bytes32> encodePayload() {
        List<Bytes32> out = new ArrayList<>();
        out.add(codeHash);
        if (newLane()) {
            out.add(config.encode());
        }
        if (!argsByChain()) {
            out.addAll(ExtCodec.writeBytes(inlineArgs));
        }
        return out;
    }
}
