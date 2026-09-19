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

import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.Classified;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.crypto.keys.PublicKey;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/** The pure part of import: hash, signature keys, ext classification. No store, no lock. */
public final class PreValidator {

    private PreValidator() {
    }

    /** For callers that hand a block straight to the lock: same facts, computed on the calling thread; no classification. */
    public static PreValidated inline(Block block) {
        return compute(-1, new BlockWrapper(block, 0), false);
    }

    static PreValidated compute(long seq, BlockWrapper wrapper, boolean classify) {
        Block block = wrapper.getBlock();
        try {
            block.parse();
            Bytes32 hashLow = Bytes32.wrap(block.getHashLow());
            List<PublicKey> keys = List.copyOf(block.verifiedKeys());
            Classified classified = classify ? ChainBlockClassifier.classify(block) : null;
            return new PreValidated(seq, wrapper, block, hashLow, keys, classified, null);
        } catch (RuntimeException e) {
            return PreValidated.failed(seq, wrapper, e);
        }
    }
}
