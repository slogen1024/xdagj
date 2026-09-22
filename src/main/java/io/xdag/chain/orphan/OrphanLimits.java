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

    private OrphanLimits(int poolLimit, int[] categoryLimits) {
        this.poolLimit = poolLimit;
        this.categoryLimits = categoryLimits;
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

    @Override
    public String toString() {
        return "OrphanLimits{pool=" + poolLimit + " accountTx=" + limit(OrphanCategory.ACCOUNT_TX)
                + " mtx=" + limit(OrphanCategory.MTX) + " chunk=" + limit(OrphanCategory.CHUNK)
                + " link=" + limit(OrphanCategory.LINK) + "}";
    }

    /**
     * Names one cap at a time. Everything not named stays {@link #UNLIMITED}.
     *
     * <p>Later tiers — the per-peer and per-chain chunk quotas, the chunk TTL — get their own
     * methods here as they arrive, which is why this is a builder and not a constructor with a row
     * of same-typed ints waiting to be transposed.
     */
    public static final class Builder {

        private int poolLimit = UNLIMITED;
        private final int[] categoryLimits = new int[OrphanCategory.values().length];

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

        public OrphanLimits build() {
            return new OrphanLimits(poolLimit, categoryLimits.clone());
        }

        private Builder category(OrphanCategory category, String name, int limit) {
            categoryLimits[category.ordinal()] = requirePositive(name, limit);
            return this;
        }

        /**
         * A cap of zero would mean "admit nothing, ever", which no caller wants and a typo reaches
         * easily; the configuration layer floors each of these keys at one for the same reason.
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
