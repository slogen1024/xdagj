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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.orphan.ChainOrphanPool.AccountLane;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * Acceptance criterion 3, measured rather than argued: <b>one removal must not get dearer as the
 * pool gets bigger</b>.
 *
 * <h2>What the BEFORE measurement said</h2>
 *
 * <p>{@code docs/benchmarks/2026-09-19-orphan-pool.md} took this on the wall clock, on the shipping
 * code, before SP0b-3 touched anything: bucket depth &times;4 (125 &rarr; 500) cost &times;1.85
 * (5.4 &rarr; 10.0 µs), which decomposes into a fixed ≈ 3.9 µs plus ≈ 1.5 µs per 125 entries of
 * depth — about <b>12 ns per queue element</b>. That slope is the thing SP0b-3 had to delete, and
 * it is the thing this class exists to show is gone.
 *
 * <h2>Why this counts probes and does not time anything</h2>
 *
 * <p>A wall clock cannot answer the question at the resolution it is asked. Tens of nanoseconds per
 * element is under the jitter of a parallel build, and a timing threshold tight enough to catch the
 * slope would fail on a loaded machine while one loose enough to be stable would catch nothing.
 * What is being asserted is a complexity claim, so the measurement is a deterministic count: the
 * same fixture gives the same number on every machine, and a failure is a real regression rather
 * than a busy CI box.
 *
 * <h2>Why the probe is on the entries and not on the pool</h2>
 *
 * <p>The plan sketched a comparison counter on {@link ChainOrphanPool}. It cannot live there, for
 * two reasons that only show up once you try to write it.
 *
 * <p><b>The pool does not do the comparing.</b> Removal from a category set is
 * {@code TreeSet.remove}, and the comparisons happen inside the JDK, driven by the pool's
 * {@code static final} comparators. A counter on the pool instance is never in that call path at
 * all; making the comparators increment a static would put an unconditional write on every
 * comparison in production — the one thing "test-only instrumentation" must not do.
 *
 * <p><b>And a comparison counter would look away from the regression it exists to catch.</b> The
 * structure SP0b-3 replaced was a {@code PriorityBlockingQueue}, whose {@code remove(Object)} finds
 * its target with {@code indexOf} — a linear walk calling {@code equals}, <em>not</em> the
 * comparator. Reverting to it would leave a comparator-only counter roughly where it was (the
 * sift-down is still logarithmic) while the real cost went linear. So the probe counts every way a
 * removal can touch a pooled entry: the four fields the comparators read, and {@code equals}. Any
 * implementation that walks the pool has to do one or the other per element it walks, whatever
 * collection it walks with.
 *
 * <p>The cost of that in production is exactly zero: {@link CountingMeta} is a test class, nothing
 * in {@code src/main} knows it exists, and the pool is unmodified.
 *
 * <h2>The fixture is the worst case, deliberately</h2>
 *
 * <p>Task 1 found that the natural fixture proves nothing. Removing in workload order means
 * removing nonce-ascending per sender, which is exactly the account comparator's own order, so
 * every removal hits the head of the bucket and the whole thing is an O(log n) sift with no scan
 * to expose. This fixture has the three properties that take that away — <b>few senders</b>
 * ({@link #SENDERS}, so the buckets are deep rather than many), <b>deep queues</b> (one quarter of
 * the pool each), and a <b>fixed-seed shuffle</b> so the removal order has nothing to do with the
 * comparator — and it covers {@code mainRef}, which {@code deleteFromQueue} walks on every removal
 * regardless of category.
 */
public class OrphanRemovalComplexityTest {

    /** Few senders, so the pool's depth lands in the buckets instead of spreading across them. */
    private static final int SENDERS = 4;

    /**
     * How many entries sit in the handed-out deque while the removals are measured. The same number
     * at both pool sizes, which is the honest shape and not a convenience: see
     * {@link #whatIsStillLinearIsTheHandedOutDequeAndOnlyItsOwnLength}.
     */
    private static final int MAIN_REF_DEPTH = 128;

    /** Removals averaged over, per measurement. See {@link #probesPerRemoval}. */
    private static final int SAMPLE = 64;

    private static final long SHUFFLE_SEED = 20260923L;

    /**
     * The headroom the assertion allows a hundredfold pool. A {@code TreeSet} removal grows by
     * {@code log2(100)} ≈ 6.6 comparisons, and the account comparator reads two fields per
     * comparison, so the honest expectation is about <b>14 extra probes</b> — and that is what is
     * measured (see the numbers in {@link #removalCostDoesNotGrowLinearlyWithPoolSize}).
     *
     * <p>64 is five times that, which is loose enough that nothing but a complexity change can
     * reach it and tight enough that a complexity change cannot hide under it. Putting the linear
     * scan back — finding the entry with an {@code equals} walk, the way
     * {@code PriorityBlockingQueue.remove} did — was measured at <b>254 &rarr; 11,927</b> probes,
     * an increase of 11,673; even a square-root law would add ~300. The plan sketched
     * {@code large < small * 4}; a ratio is the wrong shape here because the constant
     * {@code mainRef} term is most of both numbers, so a ratio of 4 would quietly tolerate hundreds
     * of extra probes in the part actually being measured.
     */
    private static final long LOG_HEADROOM = 64;

    /**
     * The measurement. A hundredfold pool may cost a few more probes per removal — that is what
     * logarithmic means — and must not cost a hundredfold more.
     *
     * <p>Measured on the tree this subproject installed: <b>1,000 entries &rarr; 149 probes</b>,
     * <b>100,000 entries &rarr; 163 probes</b>. Fourteen extra probes for a hundred times the pool.
     * Of those 149, {@link #MAIN_REF_DEPTH} = 128 are the handed-out deque and only ~21 are the
     * removal proper.
     */
    @Test
    public void removalCostDoesNotGrowLinearlyWithPoolSize() {
        long small = probesPerRemoval(1_000);
        long large = probesPerRemoval(100_000);
        assertTrue("removal went linear: a hundredfold pool cost " + small + " -> " + large
                        + " probes per removal (+" + (large - small) + "), and a logarithmic"
                        + " structure may only cost a few more; putting the linear scan back"
                        + " measured 254 -> 11927",
                large - small <= LOG_HEADROOM);
    }

    /**
     * The part that is <b>still</b> linear, named rather than hidden: {@code deleteFromQueue} calls
     * {@code pool.mainRefRemove} on every removal, whatever category the entry is, and
     * {@code mainRef} is a {@link java.util.LinkedList} that has no order to search by — a miss
     * walks all of it. So a removal costs one probe per entry currently handed out to a main block.
     *
     * <p><b>Why that is not the slope acceptance criterion 3 is about.</b> {@code mainRef} does not
     * hold the pool; it holds what selection has already given away and the chain has not yet
     * confirmed. Its length is set by mining cadence — at most the reference budget of a main block
     * per unconfirmed main block — and entries leave it as the blocks that named them are imported
     * ({@code deleteFromQueue}) or as they expire. A flood makes the <em>pool</em> sixty thousand
     * deep; it cannot make {@code mainRef} sixty thousand deep, because nothing puts an entry there
     * but this node's own block production. That is why the measurement above holds it fixed: a
     * fixture that grew it with the pool would be measuring a shape production cannot produce.
     *
     * <p>This test asserts the bound rather than the linearity, so that indexing {@code mainRef}
     * one day is an improvement and not a failure: what must stay true is that the walk costs about
     * one probe per handed-out entry and nothing worse.
     */
    @Test
    public void whatIsStillLinearIsTheHandedOutDequeAndOnlyItsOwnLength() {
        long shallow = probesPerRemoval(10_000, 16);
        long deep = probesPerRemoval(10_000, 1_024);
        long extra = deep - shallow;
        assertTrue("walking the handed-out deque must cost about one probe per entry parked in it,"
                        + " not more: 16 -> 1024 entries cost " + shallow + " -> " + deep
                        + " probes per removal (+" + extra + ") for 1008 more entries",
                extra <= 2 * (1_024 - 16));
        assertTrue("and it really is walked -- a deque 1008 entries deeper that costs nothing more"
                        + " means the removal stopped consulting it: " + shallow + " -> " + deep,
                extra >= (1_024 - 16));
    }

    /**
     * The two halves of {@code deleteFromQueue}'s in-memory work, in its order, for one entry that
     * is pooled and is not in the handed-out deque — which is the ordinary case and the worst one
     * for the deque, since a miss walks the whole of it.
     *
     * <p>Averaged over {@link #SAMPLE} removals in shuffled order rather than taken from one, whose
     * count depends on where in the tree its key happens to land. The sample is a small fraction of
     * even the small pool, so the depth barely moves while it is taken.
     */
    private static long probesPerRemoval(int poolSize) {
        return probesPerRemoval(poolSize, MAIN_REF_DEPTH);
    }

    private static long probesPerRemoval(int poolSize, int mainRefDepth) {
        ChainOrphanPool pool = new ChainOrphanPool(OrphanLimits.builder().build());
        List<Bytes32> pooled = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            Bytes32 hashlow = hash(i);
            // Nonce ascending within each sender, which is what a real sender's traffic looks like
            // and what makes the bucket's order the one a naive removal order would follow.
            OrphanEntry entry = OrphanEntry.accountTx(
                    meta(hashlow, true, i / SENDERS, i, 0L, address(i % SENDERS)), null);
            assertEquals(OrphanAdmission.ADMITTED, pool.add(entry, AccountLane.REGULAR));
            pooled.add(hashlow);
        }
        // Parked, never pooled: selection takes an entry out of the pool before handing it to a
        // main block, so nothing is ever in both. These therefore stand for blocks this node has
        // already referenced, and every removal below misses on all of them.
        for (int i = 0; i < mainRefDepth; i++) {
            pool.mainRefAdd(OrphanEntry.link(meta(handedOut(i), false, 0L, i, 0L, address(0)), null));
        }
        Collections.shuffle(pooled, new Random(SHUFFLE_SEED));

        // Counted rather than asserted inside the loop: an assertion there would read a field
        // through the probe and add itself to every measurement.
        int found = 0;
        CountingMeta.probes = 0;
        for (int i = 0; i < SAMPLE; i++) {
            Bytes32 hashlow = pooled.get(i);
            // deleteFromQueue's body, in its order: the deque first, then the pool. The database
            // half lives in deleteByKey and is deliberately not here -- the BEFORE measurement's
            // removeWorst rows excluded it for the same reason, so the two are comparable.
            pool.mainRefRemove(hashlow);
            if (pool.remove(hashlow) != null) {
                found++;
            }
        }
        long probes = CountingMeta.probes;
        assertEquals("every removal must find the entry it is being measured on", SAMPLE, found);
        long perRemoval = probes / SAMPLE;
        // The floor is on the part being measured, not on the total, and that is the point. The
        // deque walk is a known constant -- one probe per parked entry -- so everything above it is
        // the tree search. A probe that went PARTIALLY blind (a comparator gains a field
        // CountingMeta does not override) shrinks BOTH measurements, and the assertion the two feed
        // is a difference, so going blind would make that assertion easier to pass, not harder: the
        // claim in CountingMeta's header would become false and the test would stay green. A floor
        // here is what makes that impossible. At a 128-deep deque the tree search is 21 probes, so
        // 8 is ample margin and still fires the moment the search stops being counted.
        assertTrue("the probe stopped seeing the tree search: " + perRemoval + " probes against a "
                        + mainRefDepth + "-deep deque means a comparator is reading a field"
                        + " CountingMeta does not override -- add it there",
                perRemoval > mainRefDepth + 8);
        return perRemoval;
    }

    // ---- fixture -------------------------------------------------------------------------

    /**
     * An {@link OrphanMeta} that counts every read a removal can make of it.
     *
     * <p>The four getters are the fields the pool's comparators sort on, so a tree search shows up
     * here; {@code equals} is what a linear {@code remove(Object)} scan calls, so a scan shows up
     * here too. Between them there is no way to touch a pooled entry without being counted, which
     * is what makes the number a complexity measurement rather than a measurement of one particular
     * collection's internals.
     *
     * <p><b>That claim is only true while this class overrides every field the comparators read,
     * and nothing here enforces it — so {@link #probesPerRemoval} does.</b> A comparator that
     * gained a fifth field would stop that work being counted, which shrinks both measurements and
     * therefore makes the difference they feed <em>easier</em> to satisfy: the claim above would be
     * false and the test would still be green. The floor on the tree-search part of each
     * measurement is what turns that into a failure with instructions.
     */
    private static final class CountingMeta extends OrphanMeta {

        static long probes;

        @Override
        public Bytes32 getHashlow() {
            probes++;
            return super.getHashlow();
        }

        @Override
        public long getTime() {
            probes++;
            return super.getTime();
        }

        @Override
        public long getNonce() {
            probes++;
            return super.getNonce();
        }

        @Override
        public long getFee() {
            probes++;
            return super.getFee();
        }

        @Override
        public boolean equals(Object other) {
            probes++;
            return super.equals(other);
        }

        /**
         * Deliberately not counted, in a class whose whole subject is what counts. The pool's
         * hashlow index is keyed by {@code Bytes}, not by a meta, so a meta's {@code hashCode} is
         * not on the removal path at all; counting it would add noise from whatever else happens to
         * hash one. Overridden rather than left out so that a reader sees the decision.
         */
        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    private static OrphanMeta meta(Bytes32 hashlow, boolean isTx, long nonce, long time, long fee,
            byte[] address) {
        CountingMeta meta = new CountingMeta();
        meta.setHashlow(hashlow);
        meta.setTx(isTx);
        meta.setNonce(nonce);
        meta.setTime(time);
        meta.setFee(fee);
        meta.setAddress(address);
        return meta;
    }

    private static Bytes32 hash(int seed) {
        byte[] raw = new byte[32];
        raw[28] = (byte) (seed >>> 24);
        raw[29] = (byte) (seed >>> 16);
        raw[30] = (byte) (seed >>> 8);
        raw[31] = (byte) seed;
        return Bytes32.wrap(raw);
    }

    /** A hash for an entry parked in the handed-out deque, marked so it cannot collide with a pooled one. */
    private static Bytes32 handedOut(int seed) {
        byte[] raw = hash(seed).toArray();
        raw[0] = (byte) 0xd1;
        return Bytes32.wrap(raw);
    }

    private static byte[] address(int seed) {
        byte[] raw = new byte[20];
        raw[18] = (byte) (seed >>> 8);
        raw[19] = (byte) seed;
        return raw;
    }
}
