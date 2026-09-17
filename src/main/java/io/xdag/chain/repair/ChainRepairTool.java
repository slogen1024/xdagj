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

import io.xdag.Kernel;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.db.BlockStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Offline repair for a main chain {@link ChainConsistencyCheck} refused to start on: give every
 * stuck main block the self reference {@code setMain} would have written, unwind to the last
 * complete height with the ordinary fork machinery, then re-check. No block is ever deleted — the
 * node re-confirms the same blocks at the same heights on its own once it is restarted normally.
 *
 * <p>Runs against a {@code BlockchainImpl} built by a kernel in
 * {@link Kernel#enterRepairMode() repair mode}, so nothing can confirm a block behind this class's
 * back while it works. Every decision it takes, in order:
 *
 * <ol>
 *   <li><b>clean report</b> — nothing to do.</li>
 *   <li><b>a half-finished {@code unSetMain}</b> ({@link ChainConsistencyCheck.Report#inFlightUnwind()})
 *       — unwinding further cannot finish an unwind that was already under way. If the block at
 *       that height still carries {@code BI_MAIN} nothing was reversed before the crash, so the
 *       unwind is simply run to completion; otherwise the tool refuses (see
 *       {@link #INTERRUPTED_UNWIND_REASON}) — a partial reversal cannot be completed idempotently,
 *       and no {@code --force} overrides it.</li>
 *   <li><b>dry run</b> — the plan is printed and nothing is written.</li>
 *   <li><b>a target further below the tip than {@code chain.consistency.window}</b> — refused
 *       unless {@code force}, because unwinding that far is a decision for an operator.</li>
 *   <li><b>otherwise</b> — patch the missing refs, unwind, move the completion marker down, clear
 *       the in-flight record, re-check.</li>
 * </ol>
 */
@Slf4j
public final class ChainRepairTool {

    /**
     * Why an interrupted {@code unSetMain} that had already started reversing state is refused: the
     * reversal is not idempotent, so neither finishing nor redoing it is safe, and the only sound
     * recovery is a store the node did not die in the middle of.
     */
    public static final String INTERRUPTED_UNWIND_REASON =
            "a main block unwind was interrupted after it started reversing state; restore the block "
                    + "store from a snapshot";

    /**
     * What the repair did, or refused to do.
     *
     * @param dryRun  the call only planned; nothing was written
     * @param refused the tool declined to act — see {@code reason}; the CLI exits non-zero
     * @param reason  why it refused, or {@code null} when it did not
     * @param target  the main height everything above was (or would have been) unwound to
     * @param after   the report the repair leaves behind: the re-check on a completed repair, the
     *                unchanged input report otherwise
     */
    public record Outcome(boolean dryRun, boolean refused, String reason, long target,
                          ChainConsistencyCheck.Report after) {
    }

    private ChainRepairTool() {
    }

    /**
     * Repairs (or plans the repair of) the main chain {@code report} describes.
     *
     * @param kernel     the repair-mode kernel owning the stores; the fresh report is filed back on it
     * @param blockchain a blockchain built by that kernel, i.e. loaded from the same stores
     * @param report     the boot report to act on, normally {@link Kernel#getConsistencyReport()}
     * @param dryRun     print the plan and write nothing
     * @param force      unwind even further below the tip than the consistency window allows
     */
    public static Outcome repair(Kernel kernel, BlockchainImpl blockchain, ChainConsistencyCheck.Report report,
                                 boolean dryRun, boolean force) {
        BlockStore blockStore = kernel.getBlockStore();
        int window = kernel.getConfig().getChainSpec().getChainConsistencyWindow();
        long target = report.repairTarget();
        // describe(), not describeForBoot(): the "refuses to start" hint reads as nonsense while
        // the tool the hint points at is the thing running.
        say(report.describe());

        if (report.clean()) {
            say("nothing to repair");
            return new Outcome(dryRun, false, null, target, report);
        }

        if (report.inFlightUnwind()) {
            return finishInterruptedUnwind(kernel, blockchain, report, dryRun, window);
        }

        say("repair plan: unwind the main chain from " + report.nmain() + " to " + target + " ("
                + (report.nmain() - target) + " main block(s)), then re-check");
        if (dryRun) {
            return new Outcome(true, false, null, target, report);
        }
        if (target < report.nmain() - window && !force) {
            String reason = "target " + target + " is more than " + window + " height(s) below the tip "
                    + report.nmain() + "; pass --force to unwind that far";
            say("refusing: " + reason);
            return new Outcome(false, true, reason, target, report);
        }

        patchStuckRefs(blockStore, blockchain, target, report.nmain());
        blockchain.repairUnwindTo(target);
        blockStore.saveLastCompletedMain(target);
        // The unwind supersedes whatever was in flight. unSetMain clears the record itself for every
        // height it undoes, so this only matters when nothing had to be unwound — an in-flight
        // record whose height was already gone, say — but then it is the only thing that clears it.
        blockStore.clearMainInFlight();
        return recheck(kernel, blockchain, target, window);
    }

    /**
     * The {@code unSetMain} branch. The block at the in-flight height either still carries
     * {@code BI_MAIN} — the crash landed before {@code unSetMain} cleared the flag, so nothing it
     * does had been applied and running it now is exactly the operation that was interrupted — or
     * it does not, in which case an unknown part of the reversal already landed and no automated
     * step can tell how much.
     */
    private static Outcome finishInterruptedUnwind(Kernel kernel, BlockchainImpl blockchain,
                                                   ChainConsistencyCheck.Report report, boolean dryRun, int window) {
        BlockStore blockStore = kernel.getBlockStore();
        long height = blockStore.getMainInFlight();
        long target = report.repairTarget();
        Block stored = height < 0 ? null : blockStore.getBlockByHeight(height);
        boolean resumable = stored != null && stored.getInfo().getHeight() == height
                && (stored.getInfo().getFlags() & BI_MAIN) != 0;
        if (!resumable) {
            say("refusing: " + INTERRUPTED_UNWIND_REASON);
            return new Outcome(dryRun, true, INTERRUPTED_UNWIND_REASON, target, report);
        }
        say("repair plan: finish the interrupted unwind of the main block at height " + height
                + ", then re-check");
        if (dryRun) {
            return new Outcome(true, false, null, target, report);
        }
        // Raw: unApplyBlock reverses the block's links, and a block loaded as BlockInfo alone has none.
        Block raw = blockchain.getBlockByHash(Bytes32.wrap(stored.getHashLow().toArray()), true);
        if (raw == null) {
            say("refusing: " + INTERRUPTED_UNWIND_REASON);
            return new Outcome(false, true, INTERRUPTED_UNWIND_REASON, target, report);
        }
        log.info("repair: finishing the interrupted unwind of main block {} at height {}",
                raw.getHashLow().toHexString(), height);
        blockchain.unSetMain(raw);
        blockStore.clearMainInFlight();
        return recheck(kernel, blockchain, blockchain.getXdagStats().nmain, window);
    }

    /**
     * Writes the self ref {@code setMain} would have written onto every stored main block above
     * {@code target}, so {@code unApplyBlock} can reach it instead of skipping a {@code BI_MAIN_REF}
     * block whose ref is null (which could never be unwound at all).
     *
     * <p>The stored heights are walked rather than {@code report.stuck()}: the consistency window
     * bounds how far below the tip the check looks, so a ref-less main block between the completion
     * marker and {@code nmain - window} is unwound here without ever having been reported. Heights
     * above the persisted tip are included for the same reason the check scans them — a
     * {@code setMain} that died before its stats were saved leaves one there. A stored block whose
     * own height disagrees with the index key is a stale index entry left by an earlier unwind and
     * is skipped; it belongs to whatever height it really sits at.
     */
    private static void patchStuckRefs(BlockStore blockStore, BlockchainImpl blockchain, long target, long nmain) {
        for (long h = target + 1; h <= nmain + ChainConsistencyCheck.ABOVE_TIP_SCAN; h++) {
            Block stored = blockStore.getBlockByHeight(h);
            if (stored == null || stored.getInfo().getHeight() != h) {
                continue;
            }
            if ((stored.getInfo().getFlags() & BI_MAIN) == 0 || stored.getInfo().getRef() != null) {
                continue;
            }
            blockchain.updateBlockRef(stored, new Address(stored));
            say("patched the missing self ref on main block " + stored.getHashLow().toHexString()
                    + " at height " + h);
            log.info("repair: set self ref on stuck main block {} at height {}", stored.getHashLow().toHexString(), h);
        }
    }

    /** Re-runs the check on the repaired stores and files the result on the kernel. */
    private static Outcome recheck(Kernel kernel, BlockchainImpl blockchain, long target, int window) {
        ChainConsistencyCheck.Report after = ChainConsistencyCheck.run(kernel.getBlockStore(),
                blockchain.getXdagStats(), kernel.getConfig().getChainSpec(), window);
        kernel.recordConsistencyReport(after);
        say(after.describe());
        if (!after.clean()) {
            say("the main chain is still inconsistent after the repair; restore the block store from a snapshot");
        }
        return new Outcome(false, false, null, target, after);
    }

    /** The tool is a CLI command: its output belongs on the operator's terminal as well as in the log. */
    private static void say(String line) {
        System.out.println(line);
        log.info("{}", line);
    }
}
