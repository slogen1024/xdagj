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
package io.xdag.chain.ingest;

import io.xdag.chain.ext.Classified;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.crypto.keys.PublicKey;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Facts about a block computed outside the blockchain lock: its hash, the public keys that verify
 * its signatures ({@code Block.verifiedKeys()}, a pure function of the bytes), and its chain ext
 * classification. Never a verdict: a block whose pre-validation threw travels with {@code error}
 * set and {@code keys == null}, and the lock recomputes what it needs. {@code seq} is the arrival
 * order the pipeline commits in; -1 for an inline computation.
 */
public record PreValidated(long seq, BlockWrapper wrapper, Block block, Bytes32 hashLow,
                           List<PublicKey> keys, Classified classified, Throwable error) {

    public boolean hasKeys() {
        return error == null && keys != null;
    }

    /** A block whose pre-validation blew up: it still travels to the committer, carrying the cause. */
    static PreValidated failed(long seq, BlockWrapper wrapper, Throwable error) {
        return new PreValidated(seq, wrapper, wrapper.getBlock(), null, null, null, error);
    }
}
