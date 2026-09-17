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

package io.xdag.chain;

import io.xdag.config.spec.ChainSpec;
import io.xdag.core.XdagStats;
import java.util.Objects;

/**
 * Single place that answers "is the chain protocol active at this main height".
 *
 * <p>Per the SP0a design (principle P2), channel semantics only ever activate inside
 * {@code setMain}/{@code applyBlock}, gated by the confirmed main-block height reaching
 * {@link ChainSpec#getChainActivationHeight()}. Below that height every chain hook is a
 * no-op and {@code CHAIN_L1} stays empty; {@code tryToConnect} (raw L1 block validity)
 * is never affected either way (principle P1).
 *
 * <p>This class is the single consensus predicate for that activation check.
 * {@code ChainL1Processor} (Task 13) gates {@code onSetMainBegin} through
 * {@link #isActive(long)}; wiring into {@code BlockchainImpl} arrives in Task 14.
 */
public final class ChainActivation {

    private final ChainSpec spec;

    /**
     * @param spec the chain protocol parameters to consult, notably the activation height
     */
    public ChainActivation(ChainSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /**
     * @param mainHeight a confirmed main-block height
     * @return {@code true} if {@code mainHeight >= spec.getChainActivationHeight()}, i.e.
     *     the chain protocol is active at exactly the activation height and every height
     *     after it
     */
    public boolean isActive(long mainHeight) {
        return mainHeight >= spec.getChainActivationHeight();
    }

    /**
     * Convenience overload that reads the current confirmed main-block height off
     * {@link XdagStats#nmain}.
     *
     * <p>Not for apply/unwind paths — those must pass the height of the block being
     * (un)confirmed to {@link #isActive(long)} (inside {@code setMain} the block being
     * confirmed is {@code nmain + 1}); this overload gates RPC/wallet views against the
     * current tip.
     *
     * @param stats the current chain stats
     * @return {@code true} if the chain protocol is active at {@code stats.nmain}
     */
    public boolean isActiveNow(XdagStats stats) {
        return isActive(stats.nmain);
    }
}
