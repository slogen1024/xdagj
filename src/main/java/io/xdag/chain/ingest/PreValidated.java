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
 *
 * <p><b>{@code block} is the instance the chain imports, and on the pipeline's path it is a private
 * copy</b> parsed from {@code wrapper}'s 512 bytes — never {@code wrapper.getBlock()}, which the
 * relay re-serializes with {@code toBytes()} while the chain is still mutating what it imported
 * (fee, height, flags). {@code wrapper} keeps pointing at the block as received, so the node relays
 * the bytes it was given. On {@link PreValidator#inline}'s path the two are the same instance by
 * design: the caller built it and is the only thread that touches it.
 *
 * <p>{@code keys == null} means the lock must work the signatures out for itself, which is either
 * because pre-validation failed or — much more often — because the block has no inputs at all and
 * {@code canUseInput} never looks at them.
 */
public record PreValidated(long seq, BlockWrapper wrapper, Block block, Bytes32 hashLow,
                           List<PublicKey> keys, Classified classified, Throwable error) {

    public boolean hasKeys() {
        return keys != null;
    }

    /**
     * A block whose pre-validation blew up before there was a private copy to carry: it still
     * travels to the committer, carrying the cause.
     *
     * <p>This is the last factory that aliases — the {@code block} it returns <b>is</b> {@code
     * wrapper.getBlock()}, the instance the relay re-serializes. A committer must therefore re-parse
     * what this one produces instead of handing it to the chain, which is what {@code
     * SyncManager.importPreValidated} does with every {@code pv} carrying an error.
     */
    static PreValidated failed(long seq, BlockWrapper wrapper, Throwable error) {
        return failed(seq, wrapper, wrapper.getBlock(), error);
    }

    /**
     * As above, for a failure that happened after the private copy was made: the copy travels on,
     * so even a failed pre-validation does not hand the chain the instance the relay re-serializes.
     */
    static PreValidated failed(long seq, BlockWrapper wrapper, Block block, Throwable error) {
        return new PreValidated(seq, wrapper, block, null, null, null, error);
    }
}
