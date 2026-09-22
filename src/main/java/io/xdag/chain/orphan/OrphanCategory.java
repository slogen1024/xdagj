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
package io.xdag.chain.orphan;

import io.xdag.chain.ext.ExtKind;
import io.xdag.utils.BytesUtils;

/**
 * The four kinds of orphan the pool keeps apart, so that a flood of one cannot starve another.
 *
 * <p>The first three mirror what {@code OrphanBlockStoreImpl.addOrphanToMemory} already decides
 * today — link block, main-address transaction, per-account transaction. {@link #CHUNK} is new:
 * chain chunk blocks are link blocks as far as the old routing is concerned, and that is exactly
 * how an unpaid chunk flood reaches the one queue every other link block has to share.
 */
public enum OrphanCategory {

    /** A transaction carrying a real 20-byte address: bucketed per account. */
    ACCOUNT_TX,

    /** A transaction whose address field is all zero — the shared main-address lane. */
    MTX,

    /** A chain chunk block ({@link ExtKind#CHUNK}); memory-only, never persisted to ORPHANIND. */
    CHUNK,

    /** Everything else: plain link blocks. */
    LINK;

    /**
     * The routing decision, in one place. {@code kind} may be null on paths that carry no
     * classification; a null kind can never be CHUNK, so it falls through to {@link #LINK} exactly
     * as today's {@code addOrphanToMemory} does.
     *
     * <p>The {@code isTx} / all-zero-address test is copied from that method verbatim, including
     * the order of the two branches: a transaction is never a chunk, and a non-transaction never
     * looks at the address.
     */
    public static OrphanCategory of(boolean isTx, byte[] address, ExtKind kind) {
        if (isTx) {
            return BytesUtils.isFullZero(address) ? MTX : ACCOUNT_TX;
        }
        return kind == ExtKind.CHUNK ? CHUNK : LINK;
    }
}
