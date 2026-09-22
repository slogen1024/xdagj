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

import java.util.Arrays;

/**
 * What a {@link ChainOrphanPool} is allowed to hold: one cap per {@link OrphanCategory} and one
 * over the pool as a whole.
 *
 * <h2>Injected, never read from a global</h2>
 *
 * <p>The pool is handed these; it does not reach for a {@code Config}. That keeps {@code
 * io.xdag.chain.orphan} free of any dependency on the configuration packages, and it is what lets a
 * test drive the boundary of a sixty-thousand-entry cap with two entries instead of sixty thousand.
 * The real numbers live in {@code ChainSpec} and are passed in when the store starts delegating.
 *
 * <h2>This type carries no production defaults</h2>
 *
 * <p>A cap left unset is {@link #UNLIMITED} — not enforced at all, not quietly replaced by a
 * shipping default. Deliberate: a test names only the caps it is exercising, and a silent default
 * would make those tests depend on numbers they never mention. The other side of that bargain is
 * that the wiring has to name <em>every</em> cap, because a forgotten one is an unbounded category
 * rather than a conservative one.
 */
public final class OrphanLimits {

    /** A cap that is not enforced. {@link Integer#MAX_VALUE}, so the comparison stays a plain int. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final int poolLimit;

    /** Indexed by {@link OrphanCategory#ordinal()}, so a lookup on the admission path is an array read. */
    private final int[] categoryLimits;

    private final int chunkPerPeer;

    private final int chunkPerChain;

    private final int chunkTtlEpochs;

    private OrphanLimits(int poolLimit, int[] categoryLimits, int chunkPerPeer, int chunkPerChain,
            int chunkTtlEpochs) {
        this.poolLimit = poolLimit;
        this.categoryLimits = categoryLimits;
        this.chunkPerPeer = chunkPerPeer;
        this.chunkPerChain = chunkPerChain;
        this.chunkTtlEpochs = chunkTtlEpochs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The most orphans the pool may hold across every category together. */
    public int poolLimit() {
        return poolLimit;
    }

    /** The most orphans of this one category the pool may hold. */
    public int limit(OrphanCategory category) {
        return categoryLimits[category.ordinal()];
    }

    /**
     * The most {@link OrphanCategory#CHUNK} entries one source peer may hold at once.
     *
     * <p>A second tier under the chunk cap, and chunk-only. The category cap on its own stops a
     * chunk flood from starving the other three categories but does nothing about one flooder
     * starving every other <em>peer</em> of the chunk category: one source can fill all of it.
     * Sized below the category cap on purpose, so the category is only reachable by several
     * independent sources together.
     */
    public int chunkPerPeer() {
        return chunkPerPeer;
    }

    /**
     * The most {@link OrphanCategory#CHUNK} entries one chunk chain may hold at once, across every
     * source together. The per-peer tier alone still lets a set of sources pile onto one chain.
     */
    public int chunkPerChain() {
        return chunkPerChain;
    }

    /**
     * How many epochs a {@link OrphanCategory#CHUNK} entry is retained for, counted in epochs of
     * the block's own timestamp — never of the moment this node received it. See
     * {@link ChainOrphanPool#evictExpired} for what the number means and why it is the one limit
     * here that is not purely node-local.
     *
     * <p><b>There is no floor of two here, and that is deliberate.</b> The floor belongs to the
     * configuration layer, which refuses {@code chain.orphan.chunkTtlEpochs} below {@code
     * ChainSpec.MIN_ORPHAN_CHUNK_TTL_EPOCHS} at startup rather than clamping it. Repeating it here
     * would give a reader two places to loosen and one of them with no fail-fast attached; this
     * type's whole contract is that it enforces what it was handed and nothing else, which is also
     * what lets a test drive a boundary with numbers no shipping node would use.
     */
    public int chunkTtlEpochs() {
        return chunkTtlEpochs;
    }

    @Override
    public String toString() {
        return "OrphanLimits{pool=" + poolLimit + " accountTx=" + limit(OrphanCategory.ACCOUNT_TX)
                + " mtx=" + limit(OrphanCategory.MTX) + " chunk=" + limit(OrphanCategory.CHUNK)
                + " link=" + limit(OrphanCategory.LINK) + " chunkPerPeer=" + chunkPerPeer
                + " chunkPerChain=" + chunkPerChain + " chunkTtlEpochs=" + chunkTtlEpochs + "}";
    }

    /**
     * Names one cap at a time. Everything not named stays {@link #UNLIMITED}.
     *
     * <p>Each tier got its own method here as it arrived, which is why this is a builder and not a
     * constructor with a row of same-typed ints waiting to be transposed: the per-peer and
     * per-chain chunk quotas came that way, and so did the chunk TTL.
     */
    public static final class Builder {

        private int poolLimit = UNLIMITED;
        private final int[] categoryLimits = new int[OrphanCategory.values().length];
        private int chunkPerPeer = UNLIMITED;
        private int chunkPerChain = UNLIMITED;
        private int chunkTtlEpochs = UNLIMITED;

        private Builder() {
            Arrays.fill(categoryLimits, UNLIMITED);
        }

        public Builder poolLimit(int limit) {
            this.poolLimit = requirePositive("poolLimit", limit);
            return this;
        }

        public Builder accountTx(int limit) {
            return category(OrphanCategory.ACCOUNT_TX, "accountTx", limit);
        }

        public Builder mtx(int limit) {
            return category(OrphanCategory.MTX, "mtx", limit);
        }

        public Builder chunk(int limit) {
            return category(OrphanCategory.CHUNK, "chunk", limit);
        }

        public Builder link(int limit) {
            return category(OrphanCategory.LINK, "link", limit);
        }

        public Builder chunkPerPeer(int limit) {
            this.chunkPerPeer = requirePositive("chunkPerPeer", limit);
            return this;
        }

        public Builder chunkPerChain(int limit) {
            this.chunkPerChain = requirePositive("chunkPerChain", limit);
            return this;
        }

        /**
         * Unset, a chunk is never evicted by age — {@link #UNLIMITED} epochs of retention, the same
         * "not enforced at all" this type gives every other unset cap. The shipping value and its
         * hard floor of two live in {@code ChainSpec}; see {@link OrphanLimits#chunkTtlEpochs()}
         * for why the floor is not repeated here.
         */
        public Builder chunkTtlEpochs(int epochs) {
            this.chunkTtlEpochs = requirePositive("chunkTtlEpochs", epochs);
            return this;
        }

        public OrphanLimits build() {
            return new OrphanLimits(poolLimit, categoryLimits.clone(), chunkPerPeer, chunkPerChain,
                    chunkTtlEpochs);
        }

        private Builder category(OrphanCategory category, String name, int limit) {
            categoryLimits[category.ordinal()] = requirePositive(name, limit);
            return this;
        }

        /**
         * A cap of zero would mean "admit nothing, ever", which no caller wants and a typo reaches
         * easily; the configuration layer floors each of these keys at one for the same reason. For
         * {@code chunkTtlEpochs} zero reads as "evict every chunk the moment it arrives", which is
         * the same typo wearing different clothes — its real floor is two, and it is enforced at
         * startup.
         */
        private static int requirePositive(String name, int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "Invalid orphan limit " + name + ": " + limit + " (must be >= 1)");
            }
            return limit;
        }
    }
}
