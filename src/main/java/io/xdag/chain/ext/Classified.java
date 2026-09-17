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

/**
 * Result of classifying a {@link io.xdag.core.Block} by {@link ChainBlockClassifier#classify}: the
 * block's extension {@link ExtKind}, the decoded record (one of the {@code *Ext} records, e.g.
 * {@link CallExt} or {@link ChunkExt}) when decoding succeeded, and a structural {@link ExtError}
 * otherwise. Building one is a pure computation: it never touches storage and never throws.
 *
 * <p>Exactly one of {@code value}/{@code error} is set on a decoded result, mirroring
 * {@link ExtResult}; {@link #kind()} is {@code null} only for {@link #NONE} (no extension field at
 * all) and for {@link ExtError#UNKNOWN_KIND} (an extension header whose kind byte is not assigned).
 */
public record Classified(ExtKind kind, Object value, ExtError error) {

    /** The block carries no extension header field at all. */
    public static final Classified NONE = new Classified(null, null, ExtError.NO_EXT);

    public Classified {
        if ((value == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of value/error must be set");
        }
    }

    public boolean isOk() {
        return error == null;
    }

    /**
     * Casts {@link #value()} to the given {@code *Ext} record type. Call {@link #isOk()} first: this
     * throws when classification failed rather than returning a cast of {@code null}.
     *
     * @throws IllegalStateException if {@link #error()} is set
     */
    public <T> T as(Class<T> type) {
        if (error != null) {
            throw new IllegalStateException("classification failed: " + error);
        }
        return type.cast(value);
    }
}
