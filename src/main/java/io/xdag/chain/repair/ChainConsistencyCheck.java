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
package io.xdag.chain.repair;

import static io.xdag.config.Constants.BI_MAIN;

import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Block;
import io.xdag.core.XdagStats;
import io.xdag.db.BlockStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Startup check that the main chain is in a state the chain hooks can trust: every confirmed main
 * block points at itself (so it can be unwound) and the last setMain ran to completion (so every
 * CHAIN_L1 record it should have produced exists). Read-only; the caller decides whether a
 * non-clean report is fatal (normal boot) or merely recorded (repair mode).
 *
 * <p>Four failure shapes are detected: (1) a {@code MAIN_IN_FLIGHT} record is present — a
 * {@code setMain} or an {@code unSetMain} was entered and never observed to finish, which is the
 * only evidence a half-done {@code unSetMain} leaves behind (the record says which of the two it
 * was, see {@link Report#inFlightUnwind()}); (2) the {@code LAST_COMPLETED_MAIN} marker is behind
 * the persisted tip — a setMain that did not finish; (3) a main block is stored above the persisted
 * tip — a setMain that crashed before its stats were saved; (4) a main block inside the window
 * carries {@code BI_MAIN} but no ref — the shape a pre-SP0b-1 crash left behind (SP0b-1 sets the
 * ref before the DFS, so new crashes no longer produce it). A store without a marker (first boot
 * after upgrading) is initialized to the tip with a warning: its history cannot be verified.
 *
 * <p>Rules (3) and (4) are statements about the MAIN CHAIN, not about the chain protocol, so the
 * scan is never clamped to the chain activation height: a {@code BI_MAIN} block that carries no ref
 * can never be unwound on any network, and that legacy shape is exactly what exists on the shared
 * nets — whose activation height is still {@link Long#MAX_VALUE}, which used to zero the scan on
 * precisely the stores that need it. Only {@code window} bounds how far back the scan reaches.
 *
 * <p>At most one entry is reported per height: the first rule to reach a height owns it, so the
 * most direct evidence (an in-flight record, then an incomplete setMain) is what an operator reads.
 */
@Slf4j
public final class ChainConsistencyCheck {

    public static final String REPAIR_HINT =
            "; the node refuses to start. Run `xdag.sh --repairchain --dry-run` to see the repair plan and "
                    + "`xdag.sh --repairchain` to unwind to the last complete height";

    /** How far above the persisted tip stored main blocks are looked for. */
    static final int ABOVE_TIP_SCAN = 64;

    public record Stuck(long height, Bytes32 hash, String reason) {
    }

    public record Report(long nmain, long marker, List<Stuck> stuck, boolean markerInitialized,
                         boolean inFlightUnwind) {

        public Report {
            Objects.requireNonNull(stuck, "stuck");
            stuck = List.copyOf(stuck);
        }

        public boolean clean() {
            return stuck.isEmpty();
        }

        /** Human-readable summary, one stuck block per line. Says what is wrong, not what to do. */
        public String describe() {
            StringBuilder sb = new StringBuilder("main chain consistency: nmain=" + nmain + ", lastCompletedMain="
                    + marker + (markerInitialized ? " (initialized on this boot)" : ""));
            if (clean()) {
                return sb.append(", clean").toString();
            }
            sb.append(", ").append(stuck.size()).append(" stuck main block(s):");
            for (Stuck s : stuck) {
                sb.append("\n  height ").append(s.height()).append(" ")
                        .append(s.hash() == null ? "?" : s.hash().toHexString()).append(": ").append(s.reason());
            }
            return sb.toString();
        }

        /**
         * {@link #describe()} plus the operator hint, for the boot path that refuses to start on a
         * non-clean report. A clean report gets no hint: there is nothing to repair.
         */
        public String describeForBoot() {
            return clean() ? describe() : describe() + REPAIR_HINT;
        }

        /**
         * Height to unwind to: everything above it is suspect. The earliest stuck height is itself
         * suspect — including an in-flight one, which may be a half-done {@code unSetMain} — so the
         * target sits one below it, and never above the completion marker.
         *
         * <p>A half-finished {@code unSetMain} ({@link #inFlightUnwind()}) is the one shape this
         * target cannot express: unwinding further does not finish an unwind that was already under
         * way, so the repair tool has to treat that case separately.
         */
        public long repairTarget() {
            long earliestStuck = stuck.stream().mapToLong(Stuck::height).min().orElse(nmain + 1);
            return Math.max(0, Math.min(marker, earliestStuck - 1));
        }
    }

    private ChainConsistencyCheck() {
    }

    /**
     * Runs the check against a store. {@code spec} is not consulted by the scan — the rules are
     * about the main chain, not about the chain protocol — but it is kept in the signature because
     * every caller already holds it and the boot-time policy built on top of this report is
     * expected to consult it.
     */
    public static Report run(BlockStore blockStore, XdagStats stats, ChainSpec spec, int window) {
        long nmain = stats.nmain;
        long marker = blockStore.getLastCompletedMain();
        boolean initialized = false;
        if (marker < 0) {
            log.warn("LAST_COMPLETED_MAIN marker absent: initializing to nmain={}; history before this boot "
                    + "cannot be verified", nmain);
            marker = nmain;
            initialized = true;
        }
        // One entry per height, first reason wins: rules below overlap by design (a height that is
        // in flight is usually also above the marker), and an operator wants one line per height.
        Map<Long, Stuck> stuck = new LinkedHashMap<>();

        // (1) a setMain/unSetMain was entered and never finished. Checked first: it names the exact
        // height the node died in, and unlike the marker it also catches a half-done unSetMain
        // (which leaves marker > nmain, a shape that on its own is only a warning).
        long inFlight = blockStore.getMainInFlight();
        boolean inFlightUnwind = false;
        if (inFlight >= 0) {
            inFlightUnwind = blockStore.getMainInFlightOp() == BlockStore.IN_FLIGHT_UNSET_MAIN;
            add(stuck, new Stuck(inFlight, hashAt(blockStore, inFlight),
                    (inFlightUnwind ? "unSetMain" : "setMain") + " in flight when the node stopped"));
        }

        // (2) the last setMain did not finish. Bounded by the window like the scan below, so a
        // marker left far behind (or never written by an old store) cannot enumerate the whole
        // chain; the guard on marker also keeps marker + 1 from overflowing.
        for (long h = Math.max(marker + 1, nmain - window); h <= nmain && marker < nmain; h++) {
            add(stuck, new Stuck(h, hashAt(blockStore, h), "setMain incomplete (completion marker " + marker
                    + " is behind persisted nmain " + nmain + ")"));
        }
        if (marker > nmain + ABOVE_TIP_SCAN) {
            log.warn("LAST_COMPLETED_MAIN marker {} is far above persisted nmain {}: stats lag suspected", marker,
                    nmain);
        }

        // (3) main blocks above the persisted tip; (4) main blocks in the window without a self reference
        long from = Math.max(1, nmain - window);
        for (long h = from; h <= nmain + ABOVE_TIP_SCAN; h++) {
            Block b = blockStore.getBlockByHeight(h);
            if (b == null) {
                if (h > nmain) {
                    break; // no stored main block above this point
                }
                continue;
            }
            if ((b.getInfo().getFlags() & BI_MAIN) == 0) {
                continue;
            }
            if (b.getInfo().getHeight() != h) {
                // A stale height index entry: saveBlockInfo never deletes the old key, so a block
                // unwound at h and re-confirmed lower down is still reachable from key(h) with
                // BI_MAIN set. A genuinely stuck block always agrees with its index height —
                // setMain writes setHeight(mainNumber) before the DFS it may die in.
                continue;
            }
            if (h > nmain) {
                add(stuck, new Stuck(h, hashLow(b), "main block above persisted stats (setMain crashed before "
                        + "stats were saved)"));
            } else if (b.getInfo().getRef() == null) {
                add(stuck, new Stuck(h, hashLow(b), "main block without ref (unwindable only after repair)"));
            }
        }
        List<Stuck> sorted = new ArrayList<>(stuck.values());
        sorted.sort(Comparator.comparingLong(Stuck::height));
        return new Report(nmain, marker, sorted, initialized, inFlightUnwind);
    }

    private static void add(Map<Long, Stuck> stuck, Stuck s) {
        stuck.putIfAbsent(s.height(), s);
    }

    private static Bytes32 hashAt(BlockStore store, long h) {
        Block b = store.getBlockByHeight(h);
        return b == null ? null : hashLow(b);
    }

    private static Bytes32 hashLow(Block b) {
        return Bytes32.wrap(b.getHashLow().toArray());
    }
}
