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

/** Structural errors of extension blocks and chunk chains. Decoding never throws; it reports one of these. */
public enum ExtError {
    // Append new constants at the end; never reorder.
    /** The block carries no extension header field. */
    NO_EXT,
    /** The extension header's kind byte is not one of the assigned {@link ExtKind} codes. */
    UNKNOWN_KIND,
    /** A byte that must be zero (padding, reserved, or trailing) is non-zero. */
    RESERVED_NONZERO,
    /** A declared length is inconsistent with the data actually available. */
    BAD_LENGTH,
    /** A required link (e.g. to a continuation or referenced block) is absent. */
    MISSING_LINK,
    /** A link is present where the format requires none. */
    EXTRA_LINK,
    /** Inline arguments exceed the space reserved for them in the header. */
    INLINE_ARGS_TOO_LONG,
    /** The number of 32-byte payload fields does not match the declared/expected length. */
    PAYLOAD_COUNT_MISMATCH,
    /** A CHUNK's sequence number does not immediately follow the previous chunk's. */
    CHUNK_SEQ_GAP,
    /** The chunk chain's declared total does not match the sum actually observed. */
    CHUNK_TOTAL_MISMATCH,
    /** The chunk chain exceeds the maximum number of chunks allowed. */
    CHUNK_TOO_MANY,
    /** The last (tail) chunk in a chain carries a link to a further chunk. */
    CHUNK_TAIL_HAS_LINK,
    /** Following chunk links revisits a block already seen in the chain. */
    CHUNK_CYCLE,
    /** The referenced block is not a CHUNK extension block. */
    NOT_A_CHUNK,
    /** Reserved for the code store / SP1; not produced by the SP0a codecs. */
    CODE_TOO_LARGE,
    /** Reserved for the code store / SP1; not produced by the SP0a codecs. */
    CODE_HASH_MISMATCH,
    /** Reserved for the code store / SP1; not produced by the SP0a codecs. */
    CODE_UNKNOWN,
    /** A chunk block older than the paying block's previous epoch; snapshot nodes cannot be expected to hold its raw bytes. */
    CHUNK_TOO_OLD
}
