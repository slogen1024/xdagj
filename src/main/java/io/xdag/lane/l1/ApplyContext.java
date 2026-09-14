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
package io.xdag.lane.l1;

import java.util.Objects;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Lives from {@code onSetMainBegin} to {@code onSetMainEnd} of one confirmed main block: the height
 * every input applied during that {@code applyBlock} DFS is attributed to, and the main block's own
 * hash.
 *
 * <p>Immutable. {@code mainBlockHash} is not defensively copied because {@link Bytes32} is itself
 * immutable.
 */
public final class ApplyContext {

    private final long height;
    private final Bytes32 mainBlockHash;

    public ApplyContext(long height, Bytes32 mainBlockHash) {
        this.height = height;
        this.mainBlockHash = Objects.requireNonNull(mainBlockHash, "mainBlockHash");
    }

    public long height() {
        return height;
    }

    public Bytes32 mainBlockHash() {
        return mainBlockHash;
    }
}
