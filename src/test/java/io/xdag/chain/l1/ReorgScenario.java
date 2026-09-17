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

package io.xdag.chain.l1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Pure data for the G10 reorg property ({@link ChainL1ReorgPropertyTest}): a seeded random
 * sequence of chain operations grouped by main height, plus the bound on the competing branch's
 * extra main blocks. The test replays the same heights on a competing branch and checks that
 * unwind + re-apply is the identity on {@code CHAIN_L1} (stateHash) and on the balances/nonces.
 * Generating the same seed twice yields equal scenarios, so a failure message carrying
 * {@link #toString()} is enough to reproduce a run with {@code -Dxdag.reorg.seeds=<seed>}.
 *
 * <p>Step order within a height IS the link order: the test links a height's paying blocks in the
 * order given here, and {@code applyBlock} enforces strict per-sender nonce sequencing along that
 * order, so the steps of a height are emitted stably sorted by sender. A chain-targeting step's
 * {@code chainRef} indexes the chains deployed (by {@link Op#DEPLOY_NEW}) before it in that same
 * order — never a chain that only exists later in the height.
 *
 * <p>Feasibility by construction (never relaxed by the test): every step's sender is one of
 * {@link #SENDERS} keys funded with 500 XDAG, an operation costs at most ~1.3 XDAG, and a scenario
 * has at most {@code 8 * 6 = 48} operations, so no sender can run dry; every height links at most
 * {@link #MAX_OPS} paying blocks, well inside a main block's link capacity.
 */
public final class ReorgScenario {

    public enum Op { DEPLOY_NEW, DEPLOY_JOIN, CALL_HIT, CALL_MISS, PLAIN, CALL_LOWFEE, DEPLOY_BADGAS }

    /** One operation: which sender key index and, for chain-targeting ops, which earlier chain (index into created chains). */
    public record Step(Op op, int sender, int chainRef, int payloadSeed) {
    }

    /** Number of distinct sender keys a scenario draws from. */
    public static final int SENDERS = 4;
    /** Upper bound (inclusive) on paying blocks per height. */
    public static final int MAX_OPS = 6;

    public final long seed;
    public final List<List<Step>> heights;   // ops per main height, in link order
    /** Upper bound on the EXTRA empty main blocks the competing branch may need after replaying the heights. */
    public final int branchLength;

    private ReorgScenario(long seed, List<List<Step>> heights, int branchLength) {
        this.seed = seed;
        this.heights = heights;
        this.branchLength = branchLength;
    }

    private static final Op[] WEIGHTED = {
            Op.DEPLOY_NEW, Op.DEPLOY_NEW, Op.DEPLOY_JOIN, Op.DEPLOY_JOIN,
            Op.CALL_HIT, Op.CALL_HIT, Op.CALL_HIT, Op.CALL_HIT, Op.CALL_MISS, Op.CALL_MISS,
            Op.PLAIN, Op.PLAIN, Op.PLAIN, Op.CALL_LOWFEE, Op.DEPLOY_BADGAS };

    private static boolean targetsChain(Op op) {
        return op == Op.DEPLOY_JOIN || op == Op.CALL_HIT || op == Op.CALL_MISS || op == Op.CALL_LOWFEE;
    }

    public static ReorgScenario generate(long seed) {
        Random r = new Random(seed);
        int n = 3 + r.nextInt(6);                 // 3..8 heights
        List<List<Step>> hs = new ArrayList<>();
        int chains = 0;
        int payload = 1;
        for (int h = 0; h < n; h++) {
            int ops = r.nextInt(MAX_OPS + 1);     // 0..6
            // Draw the raw (op, sender) pairs first, then settle the execution order (stable sort by
            // sender) and only then resolve "is there a chain to target yet" and the chain refs, so
            // both are decided in the order the steps really execute.
            List<Op> drawnOps = new ArrayList<>();
            List<Integer> drawnSenders = new ArrayList<>();
            for (int i = 0; i < ops; i++) {
                drawnOps.add(WEIGHTED[r.nextInt(WEIGHTED.length)]);
                drawnSenders.add(r.nextInt(SENDERS));
            }
            List<Integer> order = IntStream.range(0, ops).boxed()
                    .sorted(Comparator.comparingInt(drawnSenders::get)).toList();
            List<Step> steps = new ArrayList<>();
            for (int idx : order) {
                Op op = drawnOps.get(idx);
                if (chains == 0 && targetsChain(op)) {
                    op = Op.DEPLOY_NEW;           // nothing to target yet
                }
                int ref = chains == 0 ? -1 : r.nextInt(chains);
                steps.add(new Step(op, drawnSenders.get(idx), ref, payload++));
                if (op == Op.DEPLOY_NEW) {
                    chains++;
                }
            }
            hs.add(steps);
        }
        // The fork point sits below every scenario height, so the whole scenario is "above the fork".
        // Branch A above the fork point has one main per height plus its confirmations (normally one
        // each, at most six); with the fake-PoW band [2^46, 2^47) a branch of 2N+2 mains is strictly
        // heavier than one of N, so 2*(n+6)+2 extra empty mains after replaying the same heights on
        // the competing branch are always enough to flip the top (the test also lower-bounds this by
        // the number of mains branch A actually mined).
        int branch = 2 * (n + 6) + 2;
        return new ReorgScenario(seed, hs, branch);
    }

    @Override
    public String toString() {
        return "ReorgScenario{seed=" + seed + ", heights=" + heights + ", branch=" + branchLength + "}";
    }
}
