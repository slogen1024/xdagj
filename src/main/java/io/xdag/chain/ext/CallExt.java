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
import io.xdag.core.XdagBlock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CALL (kind 1): a channel/contract call. Header: b0 kind, b1 flags (bit0 = {@link #FLAG_ARGS_CHAIN}:
 * args are carried by a chunk chain rather than inline), b2..21 contract (20 bytes), b22..25 selector
 * u32, b26..29 gasLimit u32, b30..31 argsLen u16. When {@link #FLAG_ARGS_CHAIN} is clear, the call
 * arguments are carried inline in the payload fields (at most {@link #MAX_INLINE_ARGS} bytes) and
 * {@code argsLen} gives their length; {@code argsChainHead} is {@code null} and there must be no link.
 * When the flag is set, {@code argsLen} is 0, the payload is empty, and link[0] is the head of the chunk
 * chain carrying the arguments; {@code argsChainHead} holds that head hashlow. Inline args and the chain
 * head are mutually exclusive: exactly one of them is populated, matching the flag. The args chain link
 * (link[0]) is emitted by the block builder, not by {@link #encodeHeader()}/{@link #encodePayload()}.
 *
 * <p>{@code selector} is the little-endian reading of {@code sha256(methodSignature)[0..4]}, so header
 * byte 22 holds {@code sha256(...)[0]}; SDKs must encode it the same way.
 *
 * <p>The compact constructor only enforces that the record can round-trip through the wire format:
 * {@code flags} fits a u8; {@code contract} is non-null and exactly 20 bytes; {@code inlineArgs} is
 * non-null; {@code gasLimit} fits a u32 and {@code argsLen} a u16; {@code inlineArgs.size() == argsLen};
 * {@code FLAG_ARGS_CHAIN} being set in {@code flags} agrees with {@code argsChainHead} being non-null;
 * and a set {@code FLAG_ARGS_CHAIN} forces {@code argsLen == 0} (and hence empty inline args, since
 * {@code inlineArgs.size() == argsLen} is already enforced). {@code contract}, {@code inlineArgs} and
 * {@code argsChainHead} are defensively copied. It does NOT enforce the protocol-level range
 * {@code argsLen <= MAX_INLINE_ARGS} nor reject unknown (non-{@code FLAG_ARGS_CHAIN}) flag bits —
 * {@link #decode} enforces those, and the negative tests deliberately build such out-of-range records
 * through the record.
 *
 * <p>{@link #decode} validates, in order: the header is non-null (else {@code NO_EXT}); a {@code null}
 * {@code payload} fails with {@code PAYLOAD_COUNT_MISMATCH}; a {@code null} {@code links} list is
 * treated as empty, and any {@code null} element in {@code links} fails with {@code MISSING_LINK}; the
 * kind byte ({@code UNKNOWN_KIND} otherwise); unknown flag bits ({@code RESERVED_NONZERO}); when
 * {@code FLAG_ARGS_CHAIN} is set, {@code argsLen == 0} and an empty payload ({@code BAD_LENGTH} /
 * {@code PAYLOAD_COUNT_MISMATCH} otherwise), then exactly one link ({@code MISSING_LINK} /
 * {@code EXTRA_LINK}); otherwise {@code argsLen <= MAX_INLINE_ARGS} ({@code INLINE_ARGS_TOO_LONG}), no
 * link ({@code EXTRA_LINK}), and the payload field count and zero padding.
 *
 * <p>{@link #encodeHeader()} throws {@link IllegalArgumentException} if {@code gasLimit} or
 * {@code argsLen} do not fit their header width; the compact constructor already rejects such values,
 * so this cannot happen for an instance built through the public constructor.
 */
public record CallExt(int flags, Bytes contract, int selector, long gasLimit, int argsLen, Bytes inlineArgs,
                       Bytes32 argsChainHead) {

    /** Header flag bit0: call arguments are carried by a chunk chain rather than inline. */
    public static final int FLAG_ARGS_CHAIN = 0x01;
    /** Maximum number of inline argument bytes carried directly in the payload fields. */
    public static final int MAX_INLINE_ARGS = (XdagBlock.XDAG_BLOCK_FIELDS - 8) * ExtCodec.FIELD;
    // header, nonce, INPUT, OUTPUT, pubkey, two SIGN_OUT, ext header

    public CallExt {
        if (flags < 0 || flags > 0xFF) {
            throw new IllegalArgumentException("flags out of u8 range: " + flags);
        }
        Objects.requireNonNull(contract, "contract");
        if (contract.size() != 20) {
            throw new IllegalArgumentException("contract must be 20 bytes: " + contract.size());
        }
        Objects.requireNonNull(inlineArgs, "inlineArgs");
        if (gasLimit < 0 || gasLimit > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("gasLimit out of u32 range: " + gasLimit);
        }
        if (argsLen < 0 || argsLen > 0xFFFF) {
            throw new IllegalArgumentException("argsLen out of u16 range: " + argsLen);
        }
        if (inlineArgs.size() != argsLen) {
            throw new IllegalArgumentException("argsLen " + argsLen + " != inlineArgs size " + inlineArgs.size());
        }
        if (((flags & FLAG_ARGS_CHAIN) != 0) != (argsChainHead != null)) {
            throw new IllegalArgumentException("FLAG_ARGS_CHAIN and argsChainHead must agree");
        }
        if (((flags & FLAG_ARGS_CHAIN) != 0) && argsLen != 0) {
            throw new IllegalArgumentException("chained args must have argsLen 0");
        }
        contract = Bytes.wrap(contract.toArray());
        inlineArgs = Bytes.wrap(inlineArgs.toArray());
        argsChainHead = argsChainHead == null ? null : Bytes32.wrap(argsChainHead.toArray());
    }

    /** Returns true when the call arguments are carried by a chunk chain rather than inline. */
    public boolean argsByChain() {
        return (flags & FLAG_ARGS_CHAIN) != 0;
    }

    /** Decodes a header/payload/links triple into a {@link CallExt}; never throws. */
    public static ExtResult<CallExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
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
        if (ExtCodec.u8(h, 0) != ExtKind.CALL.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_ARGS_CHAIN) != 0) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        Bytes contract = Bytes.wrap(Arrays.copyOfRange(h, 2, 22));
        int selector = (int) ExtCodec.u32(h, 22);
        long gasLimit = ExtCodec.u32(h, 26);
        int argsLen = ExtCodec.u16(h, 30);

        if ((flags & FLAG_ARGS_CHAIN) != 0) {
            if (argsLen != 0) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            if (!payload.isEmpty()) {
                return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
            }
            if (links.isEmpty()) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
            if (links.size() > 1) {
                return ExtResult.fail(ExtError.EXTRA_LINK);
            }
            Bytes32 head = Bytes32.wrap(links.get(0).getAddress().toArray());
            return ExtResult.ok(new CallExt(flags, contract, selector, gasLimit, 0, Bytes.EMPTY, head));
        }
        if (argsLen > MAX_INLINE_ARGS) {
            return ExtResult.fail(ExtError.INLINE_ARGS_TOO_LONG);
        }
        if (!links.isEmpty()) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        ExtResult<Bytes> args = ExtCodec.readBytes(payload, argsLen);
        if (!args.isOk()) {
            return ExtResult.fail(args.error());
        }
        return ExtResult.ok(new CallExt(flags, contract, selector, gasLimit, argsLen, args.value(), null));
    }

    /** Encodes the 32-byte CALL header (kind, flags, contract, selector, gasLimit, argsLen). */
    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CALL.code();
        h[1] = (byte) flags;
        System.arraycopy(contract.toArray(), 0, h, 2, 20);
        ExtCodec.putU32(h, 22, selector & 0xffffffffL);
        ExtCodec.putU32(h, 26, gasLimit);
        ExtCodec.putU16(h, 30, argsLen);
        return Bytes32.wrap(h);
    }

    /** Encodes the inline args as zero-padded payload fields, or none when args are chained. */
    public List<Bytes32> encodePayload() {
        return argsByChain() ? List.of() : ExtCodec.writeBytes(inlineArgs);
    }
}
