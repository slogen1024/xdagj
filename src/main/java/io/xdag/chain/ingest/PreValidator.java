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
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.keys.PublicKey;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/** The pure part of import: hash, signature keys, ext classification. No store, no lock. */
public final class PreValidator {

    private PreValidator() {
    }

    /**
     * For callers that hand a block straight to the lock: the same facts, computed on the calling
     * thread, without the ext classification the lock-side import does not need there.
     *
     * <p>Deliberately <b>does not copy the block</b>, unlike {@link #compute}: the caller built or
     * parsed this instance itself and is the only thread that touches it, so the chain may import
     * it directly, exactly as it did before SP0b-2. Local mining, the repair tools, {@code
     * SyncManager.syncPopBlock}'s re-import and the tests all work that way.
     *
     * <p>The returned {@link PreValidated#wrapper()} is fabricated — ttl 0 and no peer — because
     * there is no arriving wrapper to carry. A caller that relays what it imports (as {@code
     * SyncManager.importBlock} does, reading the wrapper's ttl and peer) must use its own wrapper
     * and not this one.
     */
    public static PreValidated inline(Block block) {
        return facts(-1, new BlockWrapper(block, 0), block, false);
    }

    /**
     * The pipeline's pre-validation, on a pool thread: parses a <b>private copy</b> of the wrapper's
     * 512 bytes and computes the facts from it.
     *
     * <p>The copy is the whole point, and it is what {@code SyncManager.importBlock}'s re-parse used
     * to provide on this path. The chain mutates the block it imports — {@code setFee(ZERO)} on a
     * BI_EXTRA import, then {@code setFee}/{@code setHeight} from {@code setMain}/{@code unSetMain}/
     * {@code unApplyBlock} on the live {@code memOrphanPool} instance — while the relay thread
     * re-serializes {@code wrapper.getBlock()} with {@code toBytes()}. Sharing one instance would
     * gossip bytes that are not the bytes received (the fee sits inside the hashed, signed header),
     * and would race on non-final fields. Here the copy is parallel work rather than lock work.
     *
     * <p>Precondition, and it is {@code submit}'s contract rather than something checked here: the
     * wrapper's block already carries its 512 bytes. {@code getXdagBlock()} below is itself a lazy
     * mutator — it builds and caches them when {@code xdagBlock == null} — so a block that does not
     * would have this pool thread writing to the relay's own instance, which is exactly the race the
     * copy exists to end. Unreachable today: every block reaching the pipeline was parsed from bytes
     * and therefore holds them.
     */
    static PreValidated compute(long seq, BlockWrapper wrapper, boolean classify) {
        Block imported;
        try {
            imported = new Block(new XdagBlock(wrapper.getBlock().getXdagBlock().getData().toArray()));
        } catch (RuntimeException e) {
            // Not even copyable: travel with the received instance and let the lock reject it. It
            // cannot reach memOrphanPool, because the lock cannot make sense of those bytes either.
            return PreValidated.failed(seq, wrapper, e);
        }
        return facts(seq, wrapper, imported, classify);
    }

    /** The facts themselves, computed from whichever block the caller decided the chain imports. */
    private static PreValidated facts(long seq, BlockWrapper wrapper, Block block, boolean classify) {
        try {
            block.parse();
            // copy(): getHashLow() returns a MutableBytes32 view over the block's own cached
            // array, and a PreValidated must be immutable facts.
            Bytes32 hashLow = block.getHashLow().copy();
            List<Address> inputs = block.getInputs();
            // No inputs, nothing to verify: canUseInput returns true without ever reading the keys,
            // so computing them would be pure waste. Link and main blocks -- most of the traffic --
            // take this branch. keys stays null, which leaves the lock's own path unchanged.
            List<PublicKey> keys = inputs == null || inputs.isEmpty() ? null : List.copyOf(block.verifiedKeys());
            // classify() reads the block's own Address objects (getBlockLinks()) but keeps none of
            // them: every ext decoder copies links.get(i).getAddress().toArray() into a fresh
            // Bytes32, and a payload is a list of immutable Bytes32. It is still classified from
            // the very instance the chain imports, for two reasons that do hold: this pool thread
            // must not read the relay's own instance, and `classified` has to describe pv.block().
            Classified classified = classify ? ChainBlockClassifier.classify(block) : null;
            return new PreValidated(seq, wrapper, block, hashLow, keys, classified, null);
        } catch (RuntimeException e) {
            return PreValidated.failed(seq, wrapper, block, e);
        }
    }
}
