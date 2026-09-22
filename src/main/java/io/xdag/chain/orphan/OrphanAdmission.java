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

/**
 * What {@link ChainOrphanPool#add} did with an entry.
 *
 * <p>The full set of verdicts is declared here from the start so the callers written against the
 * pool never have to widen a switch later. Only {@link #ADMITTED} and {@link #DUPLICATE} can be
 * returned today: the three quota layers that produce the rest arrive with the quota work, and
 * until then no limit is ever reached.
 */
public enum OrphanAdmission {

    /** The entry is in the pool and holds whatever quota slots it needs. */
    ADMITTED,

    /** An entry with the same hashlow is already pooled; nothing was changed. */
    DUPLICATE,

    /** The entry's own category is at its cap. No other category is affected. */
    CATEGORY_FULL,

    /** The source peer already holds its share of the chunk budget. */
    PEER_FULL,

    /** The chunk's chain head already holds its share of the chunk budget. */
    CHAIN_FULL,

    /** The pool as a whole is at its cap, whatever the category. */
    POOL_FULL
}
