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
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Offline repair for a main chain {@link ChainConsistencyCheck} refused to start on: reconcile a
 * tip the store carries but the stats do not, give every stuck main block the self reference
 * {@code setMain} would have written, unwind to the last complete height with the ordinary fork
 * machinery, then re-check. No block is ever deleted — the node re-confirms the same blocks at the
 * same heights once new blocks from its peers push the top back above them (see below).
 *
 * <p>Runs against a {@code BlockchainImpl} built by a kernel in
 * {@link Kernel#enterRepairMode() repair mode}, so nothing can confirm a block behind this class's
 * back while it works. Every decision it takes, in order:
 *
 * <ol>
 *   <li><b>clean store</b> — nothing to do ({@link Status#CLEAN}). The report is taken from the
 *       stores at entry, never from the caller, so a second run after a successful repair sees the
 *       repaired chain and stops here.</li>
 *   <li><b>a half-finished {@code unSetMain}</b> ({@link ChainConsistencyCheck.Report#inFlightUnwind()})
 *       — unwinding further cannot finish an unwind that was already under way. If the block at
 *       that height still carries {@code BI_MAIN} nothing was reversed before the crash, so the
 *       unwind is simply run to completion; otherwise the chain is
 *       {@link Status#UNREPAIRABLE unrepairable} (see {@link #INTERRUPTED_UNWIND_REASON}) — a
 *       partial reversal cannot be completed idempotently, and no {@code --force} overrides it.</li>
 *   <li><b>a target further below the tip than {@code chain.consistency.window}</b> —
 *       {@link Status#REFUSED} unless {@code force}, because unwinding that far is a decision for
 *       an operator. Evaluated before the dry run returns, so {@code --dry-run} surfaces it.</li>
 *   <li><b>dry run</b> — the plan is printed and nothing is written ({@link Status#PLANNED}).</li>
 *   <li><b>otherwise</b> — reconcile the tip, patch the missing refs, unwind, move the completion
 *       marker down, re-check, and only then clear the in-flight record.</li>
 * </ol>
 *
 * <p><b>A dry run is not quite write-free.</b> Building the {@code BlockchainImpl} this tool acts on
 * is what runs the boot check, and a store carrying no {@code LAST_COMPLETED_MAIN} marker at all
 * (the first boot after upgrading) has one initialized to its tip by that constructor, before this
 * class is ever called. Nothing else is written on a dry run.
 *
 * <p><b>After a repair the node needs its peers.</b> The next boot pins the top to the block at
 * {@code nmain}, i.e. the target, which is itself main — so {@code checkNewMain}, which walks from
 * the top through NON-main candidates, never enters its loop and cannot see the unwound blocks
 * above. The node moves again only when genuinely new blocks arrive from peers and build on the
 * old head (re-sent copies of stored blocks return EXIST and never move the top); that is when the
 * unwound heights are confirmed again. A node repaired in isolation stays at the target.
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
     * Why an unwind that could not reach its target is refused — deliberately distinct from
     * {@link #INTERRUPTED_UNWIND_REASON}: nothing here was left half-done, the store simply does not
     * carry the raw block bytes the unwind has to walk (or the target height carries no main block
     * of its own), so the repair never got off the ground.
     */
    public static final String INCOMPLETE_BLOCK_DATA_REASON =
            "the main chain could not be unwound to the repair target: block data is incomplete; restore "
                    + "the block store from a snapshot";

    /** Why a repair that ran to completion but did not clean the store is still a failure. */
    public static final String STILL_INCONSISTENT_REASON =
            "the main chain is still inconsistent after the repair; restore the block store from a snapshot";

    /** Heights between two progress lines while the ref scan runs. */
    private static final int SCAN_PROGRESS_EVERY = 1000;

    /**
     * What the repair did, or refused to do. The CLI maps this onto its exit code: {@link #CLEAN},
     * {@link #REPAIRED} and {@link #PLANNED} exit 0, {@link #REFUSED} exits 1,
     * {@link #UNREPAIRABLE} exits 2, and an exception out of {@code repair} exits 4 (the CLI keeps
     * 3 for a command that could not start at all, before any store was opened).
     */
    public enum Status {
        /** The store needed no repair; nothing was written. */
        CLEAN,
        /** The repair ran and the re-check came out clean. */
        REPAIRED,
        /** A dry run that would have proceeded; nothing was written. */
        PLANNED,
        /** The repair needs {@code --force} to go that deep. Reported by a dry run too. */
        REFUSED,
        /**
         * The chain cannot be repaired from local state: an interrupted reversal that is not
         * resumable, missing raw block data, or a repair whose re-check is still not clean. The
         * store is left carrying every piece of evidence the tool found.
         */
        UNREPAIRABLE
    }

    /**
     * How the operator asked for the repair.
     *
     * @param dryRun print the plan and write nothing
     * @param force  unwind even further below the tip than the consistency window allows
     */
    public record Options(boolean dryRun, boolean force) {
    }

    /**
     * What the repair did, or refused to do.
     *
     * @param status what happened; the CLI's exit code is derived from it
     * @param reason why it could not proceed, or {@code null} when it did
     * @param target the main height everything above was (or would have been) unwound to
     * @param after  the report the repair leaves behind: the re-check on a completed repair, the
     *               entry report otherwise
     */
    public record Outcome(Status status, String reason, long target, ChainConsistencyCheck.Report after) {
    }

    private ChainRepairTool() {
    }

    /** {@link #repair(Kernel, BlockchainImpl, Options, Consumer)} printing to the operator's terminal. */
    public static Outcome repair(Kernel kernel, BlockchainImpl blockchain, Options options) {
        return repair(kernel, blockchain, options, System.out::println);
    }

    /**
     * Repairs (or plans the repair of) the main chain the stores behind {@code blockchain} carry.
     *
     * @param kernel     the repair-mode kernel owning the stores; the fresh report is filed back on it
     * @param blockchain a blockchain built by that kernel, i.e. loaded from the same stores
     * @param options    dry run / force
     * @param out        where the operator-facing lines go; every line is mirrored into the log
     * @throws IllegalStateException if {@code kernel} is not in repair mode
     */
    public static Outcome repair(Kernel kernel, BlockchainImpl blockchain, Options options, Consumer<String> out) {
        // I3: every write below assumes nothing else can confirm a block while the tool works, and
        // only repair mode guarantees that — no check-main loop, and a non-clean boot report recorded
        // rather than thrown. A kernel that is not in repair mode is a programming error on the way
        // in, not an operator error, so it fails loudly instead of repairing a moving chain.
        if (!kernel.isRepairMode()) {
            throw new IllegalStateException("ChainRepairTool requires a kernel in repair mode");
        }
        BlockStore blockStore = kernel.getBlockStore();
        int window = kernel.getConfig().getChainSpec().getChainConsistencyWindow();
        // I7: the stores are re-scanned instead of trusting a report handed in by the caller. A boot
        // report is a snapshot of a store this tool then writes to, so anything but a fresh scan
        // would let a second call act on a chain that no longer exists.
        ChainConsistencyCheck.Report report = ChainConsistencyCheck.run(blockStore, blockchain.getXdagStats(),
                kernel.getConfig().getChainSpec(), window);
        kernel.recordConsistencyReport(report);
        long target = report.repairTarget();
        // describe(), not describeForBoot(): the "refuses to start" hint reads as nonsense while
        // the tool the hint points at is the thing running.
        say(out, report.describe());

        if (report.clean()) {
            say(out, "nothing to repair");
            return new Outcome(Status.CLEAN, null, target, report);
        }

        if (report.inFlightUnwind()) {
            return finishInterruptedUnwind(kernel, blockchain, report, options, window, out);
        }

        // C1: a main block stored ABOVE the persisted tip is unreachable from the top the boot pinned
        // to getBlockByHeight(nmain), so the unwind would walk straight past it and report success.
        // Scanned here (read-only) and reconciled below, after every refusal has had its say.
        Block aboveTip = scanAboveTip(blockStore, report.nmain());
        long tip = aboveTip == null ? report.nmain() : aboveTip.getInfo().getHeight();

        say(out, "repair plan: " + (aboveTip == null ? ""
                : "reconcile the persisted tip " + report.nmain() + " up to the main block stored at " + tip
                        + ", then ")
                + "unwind the main chain from " + tip + " to " + target + " (" + (tip - target)
                + " main block(s)), then re-check");
        // I2: evaluated BEFORE the dry run returns, so an operator planning a repair learns that it
        // needs --force from the dry run rather than from the run they expected to work.
        if (target < tip - window && !options.force()) {
            String reason = "target " + target + " is more than " + window + " height(s) below the tip "
                    + tip + "; pass --force to unwind that far";
            say(out, "refusing: " + reason);
            return new Outcome(Status.REFUSED, reason, target, report);
        }
        if (options.dryRun()) {
            say(out, dryRunNote(kernel));
            return new Outcome(Status.PLANNED, null, target, report);
        }

        if (aboveTip != null) {
            blockchain.reconcileTipTo(tip, Bytes32.wrap(aboveTip.getHashLow().toArray()));
            say(out, "reconciled the persisted tip: nmain " + report.nmain() + " -> " + tip + " ("
                    + aboveTip.getHashLow().toHexString() + ")");
            // The honest residual of a torn stats write, spelled out where an operator reads it.
            say(out, "note: xdagStats.balance — a node-local statistic, not consensus — may now be short by "
                    + "one reward for every main block of ours in heights " + (report.nmain() + 1) + ".." + tip
                    + ": those rewards were never in the stats that were persisted, and the unwind below "
                    + "subtracts them anyway. It settles when the node re-confirms those heights.");
        }
        patchStuckRefs(blockStore, blockchain, target, tip, window, out);
        try {
            blockchain.repairUnwindTo(target);
        } catch (IllegalStateException e) {
            // C3/M2: the unwind did not reach the target — unWindMain walks raw blocks and ends its
            // walk silently at the first one whose bytes are missing. The completion marker and the
            // in-flight record are deliberately NOT written: they are the evidence the next attempt
            // (or the operator) reads, and moving the marker down would claim a completion that did
            // not happen.
            String reason = INCOMPLETE_BLOCK_DATA_REASON + " (" + e.getMessage() + ")";
            log.error("repair: the unwind to {} did not complete", target, e);
            say(out, "cannot repair: " + reason);
            return new Outcome(Status.UNREPAIRABLE, reason, target, report);
        }
        blockStore.saveLastCompletedMain(target);
        return recheck(kernel, blockchain, target, window, out);
    }

    /**
     * Declares the main chain sound: re-initializes {@code LAST_COMPLETED_MAIN} to the persisted
     * {@code nmain} and drops any {@code MAIN_IN_FLIGHT} record, without unwinding anything, then
     * re-checks and returns the fresh report.
     *
     * <p>This exists for one situation only — the downgrade trap. A node rolled back to a binary
     * from before SP0b-1 keeps confirming main blocks with the marker frozen where the last
     * SP0b-1 boot left it; on re-upgrade the gate sees a marker hundreds of heights behind the tip
     * and demands an unwind of everything above it, none of which is actually broken. Adopting the
     * tip is the right answer there and the wrong answer everywhere else: it VERIFIES NOTHING. The
     * operator, not this code, is asserting that every main block up to {@code nmain} was confirmed
     * completely.
     *
     * <p>What the re-check still reports is deliberately not suppressed: a main block above the
     * persisted tip, or a {@code BI_MAIN} block with no ref, survives this and leaves the report
     * non-clean, because moving the marker says nothing about either.
     *
     * <p>An in-flight {@code unSetMain} is refused, not adopted. That record is the only evidence of
     * an unwind the node died in the middle of — the shape {@link #repair} hands to its
     * interrupted-unwind branch, which either finishes the unwind or refuses it with
     * {@link #INTERRUPTED_UNWIND_REASON}. Clearing it here would leave the re-check clean and this
     * method vouching for a store it cannot. Nothing is written in that case; the entry report is
     * filed and returned, and it is non-clean. An in-flight {@code setMain} (op 1) IS discarded as
     * before: adopting the tip already asserts that every height up to it, that one included, was
     * confirmed completely.
     *
     * @param kernel     the repair-mode kernel owning the stores; the fresh report is filed back on it
     * @param blockchain a blockchain built by that kernel, i.e. loaded from the same stores
     * @param out        where the operator-facing lines go; every line is mirrored into the log
     * @return the report the re-check leaves behind (the entry report when refused); clean means the
     *         store is startable again
     * @throws IllegalStateException if {@code kernel} is not in repair mode
     */
    public static ChainConsistencyCheck.Report reinitMarker(Kernel kernel, BlockchainImpl blockchain,
                                                            Consumer<String> out) {
        // Same reason as repair(): these are writes to a main chain, and only repair mode guarantees
        // that nothing confirms a block while they happen.
        if (!kernel.isRepairMode()) {
            throw new IllegalStateException("ChainRepairTool requires a kernel in repair mode");
        }
        BlockStore blockStore = kernel.getBlockStore();
        int window = kernel.getConfig().getChainSpec().getChainConsistencyWindow();
        long nmain = blockchain.getXdagStats().nmain;
        long marker = blockStore.getLastCompletedMain();
        long inFlight = blockStore.getMainInFlight();
        // C1: decided before ANY write. An in-flight unSetMain is what repair() routes to
        // finishInterruptedUnwind — finished if the reversal never started, UNREPAIRABLE with
        // INTERRUPTED_UNWIND_REASON if it did. clearMainInFlight() below would erase the only record
        // of it, the re-check would come out clean, and the CLI would exit 0 on a store that a plain
        // --repairchain refuses to touch.
        if (inFlight >= 0 && blockStore.getMainInFlightOp() == BlockStore.IN_FLIGHT_UNSET_MAIN) {
            ChainConsistencyCheck.Report entry = ChainConsistencyCheck.run(blockStore, blockchain.getXdagStats(),
                    kernel.getConfig().getChainSpec(), window);
            kernel.recordConsistencyReport(entry);
            say(out, entry.describe());
            say(out, "refusing: an unSetMain is in flight at height " + inFlight + ", and moving the completion "
                    + "marker would erase the only record of it. That record is what the ordinary repair reads "
                    + "to finish the unwind, or to refuse it as: \"" + INTERRUPTED_UNWIND_REASON + "\". Run "
                    + "`--repairchain` with no argument to finish or diagnose the unwind, or re-seed from a "
                    + "snapshot.");
            log.warn("repair: reinit-marker refused, unSetMain in flight at height {}", inFlight);
            return entry;
        }
        say(out, "WARNING: --repairchain reinit-marker verifies nothing and unwinds nothing. It moves the "
                + "completion marker from " + marker + " to the persisted tip " + nmain
                + (inFlight < 0 ? "" : " and discards the in-flight setMain record at height " + inFlight)
                + ", i.e. YOU are asserting that every main block up to " + nmain + " was confirmed "
                + "completely. This is only ever right after a downgrade to a pre-SP0b-1 binary froze "
                + "the marker while the node kept confirming. If any of those heights really was "
                + "incomplete, its chain records are missing for good and no later repair can tell: "
                + "re-seed from a snapshot instead.");
        blockStore.saveLastCompletedMain(nmain);
        blockStore.clearMainInFlight();
        log.warn("repair: completion marker re-initialized from {} to nmain={} by operator assertion", marker, nmain);
        ChainConsistencyCheck.Report after = ChainConsistencyCheck.run(blockStore, blockchain.getXdagStats(),
                kernel.getConfig().getChainSpec(), window);
        kernel.recordConsistencyReport(after);
        say(out, after.describe());
        if (!after.clean()) {
            say(out, "the completion marker was re-initialized but the chain is still inconsistent: what is "
                    + "left is not something a marker can express, and an unwind (`--repairchain`) or a "
                    + "snapshot re-seed is what fixes it");
        }
        return after;
    }

    /**
     * The {@code unSetMain} branch. The block at the in-flight height either still carries
     * {@code BI_MAIN} — the crash landed before {@code unSetMain} cleared the flag, so nothing it
     * does had been applied and running it now is exactly the operation that was interrupted — or
     * it does not, in which case an unknown part of the reversal already landed and no automated
     * step can tell how much.
     *
     * <p>Only that one height is dealt with here. Any other shape the same crash left behind — a
     * main block above the persisted tip, say — is reported by the re-check rather than repaired,
     * and a second run picks it up on the ordinary path now that nothing is in flight any more.
     */
    private static Outcome finishInterruptedUnwind(Kernel kernel, BlockchainImpl blockchain,
                                                   ChainConsistencyCheck.Report report, Options options,
                                                   int window, Consumer<String> out) {
        BlockStore blockStore = kernel.getBlockStore();
        long height = blockStore.getMainInFlight();
        long target = report.repairTarget();
        Block stored = height < 0 ? null : blockStore.getBlockByHeight(height);
        boolean resumable = stored != null && stored.getInfo().getHeight() == height
                && (stored.getInfo().getFlags() & BI_MAIN) != 0;
        if (!resumable) {
            say(out, "cannot repair: " + INTERRUPTED_UNWIND_REASON);
            return new Outcome(Status.UNREPAIRABLE, INTERRUPTED_UNWIND_REASON, target, report);
        }
        say(out, "repair plan: finish the interrupted unwind of the main block at height " + height
                + ", then re-check");
        if (options.dryRun()) {
            say(out, dryRunNote(kernel));
            return new Outcome(Status.PLANNED, null, target, report);
        }
        // Raw: unApplyBlock reverses the block's links, and a block loaded as BlockInfo alone has none.
        Block raw = blockchain.getBlockByHash(Bytes32.wrap(stored.getHashLow().toArray()), true);
        if (raw == null) {
            String reason = INCOMPLETE_BLOCK_DATA_REASON + " (no raw block data for the main block at height "
                    + height + ")";
            say(out, "cannot repair: " + reason);
            return new Outcome(Status.UNREPAIRABLE, reason, target, report);
        }
        // C2: unApplyBlock skips a block whose ref is null, so finishing the unwind of a legacy
        // ref-less main block would clear BI_MAIN and decrement nmain while reversing nothing at all.
        // The same self ref patchStuckRefs writes, written here for the same reason.
        if (raw.getInfo().getRef() == null) {
            blockchain.updateBlockRef(raw, new Address(raw));
            say(out, "patched the missing self ref on main block " + raw.getHashLow().toHexString()
                    + " at height " + height);
            log.info("repair: set self ref on stuck main block {} at height {}", raw.getHashLow().toHexString(),
                    height);
        }
        log.info("repair: finishing the interrupted unwind of main block {} at height {}",
                raw.getHashLow().toHexString(), height);
        // unSetMain persists the stats, moves the completion marker down and clears the in-flight
        // record itself, on the one exit it has; nothing is cleared here on top of that.
        blockchain.unSetMain(raw);
        return recheck(kernel, blockchain, blockchain.getXdagStats().nmain, window, out);
    }

    /**
     * The highest main block the store carries above {@code nmain}, or {@code null} when there is
     * none — failure shape 3, a {@code setMain} that died between its own stats write and the
     * completion marker (or whose stats write was torn).
     *
     * <p>Read-only, so the caller can print and refuse before anything is written. The scan stops at
     * the first gap for the same reason the check's does: confirmed heights are contiguous, so the
     * first height without a genuine main block ends the run, and a stored block whose own height
     * disagrees with the index key is a stale index entry rather than a tip.
     */
    private static Block scanAboveTip(BlockStore blockStore, long nmain) {
        Block highest = null;
        for (long h = nmain + 1; h <= nmain + ChainConsistencyCheck.ABOVE_TIP_SCAN; h++) {
            Block stored = blockStore.getBlockByHeight(h);
            if (stored == null || stored.getInfo().getHeight() != h
                    || (stored.getInfo().getFlags() & BI_MAIN) == 0) {
                break;
            }
            highest = stored;
        }
        return highest;
    }

    /**
     * Writes the self ref {@code setMain} would have written onto every stored main block above
     * {@code target}, so {@code unApplyBlock} can reach it instead of skipping a {@code BI_MAIN_REF}
     * block whose ref is null (which could never be unwound at all).
     *
     * <p>The stored heights are walked rather than {@code report.stuck()}: a ref-less main block
     * between the completion marker and the bottom of the window is unwound here without ever having
     * been reported. Heights above the tip are included for the same reason the check scans them. A
     * stored block whose own height disagrees with the index key is a stale index entry left by an
     * earlier unwind and is skipped; it belongs to whatever height it really sits at.
     *
     * <p>M4: the scan starts no lower than {@code tip - window}, so a forced unwind to a very low
     * target cannot turn into millions of store reads. A ref-less main block below that bound is
     * left alone — exactly as the check leaves it, the window being the same bound on both — and
     * unwinding past it reverses its flags without reversing its state.
     */
    private static void patchStuckRefs(BlockStore blockStore, BlockchainImpl blockchain, long target, long tip,
                                       int window, Consumer<String> out) {
        long from = Math.max(target + 1, tip - window);
        long to = tip + ChainConsistencyCheck.ABOVE_TIP_SCAN;
        if (from > target + 1) {
            say(out, "scanning heights " + from + ".." + to + " for missing refs; the consistency window bounds "
                    + "the scan, so a ref-less main block below " + from + " is left as it is");
        }
        for (long h = from; h <= to; h++) {
            if (h > from && (h - from) % SCAN_PROGRESS_EVERY == 0) {
                say(out, "ref scan: " + (h - from) + " of " + (to - from + 1) + " height(s) done, at height " + h);
            }
            Block stored = blockStore.getBlockByHeight(h);
            if (stored == null || stored.getInfo().getHeight() != h) {
                continue;
            }
            if ((stored.getInfo().getFlags() & BI_MAIN) == 0 || stored.getInfo().getRef() != null) {
                continue;
            }
            blockchain.updateBlockRef(stored, new Address(stored));
            say(out, "patched the missing self ref on main block " + stored.getHashLow().toHexString()
                    + " at height " + h);
            log.info("repair: set self ref on stuck main block {} at height {}", stored.getHashLow().toHexString(), h);
        }
    }

    /**
     * Re-runs the check on the repaired stores, files the result on the kernel, and only then clears
     * the in-flight record.
     *
     * <p>C1: a record that outlives the repair is evidence, and a failed repair must keep every
     * piece of it. But an outstanding record is itself one of the shapes the check reports, so a
     * record left over from work the unwind has just reversed has to go before the re-check or a
     * successful repair could never come out clean. Only a record ABOVE the target qualifies — it
     * belongs to the range that was just unwound, and {@code unSetMain} clears its own as it goes,
     * so this fires only when that height carried no main block at all — and it is put back verbatim
     * the moment the re-check says the repair did not work.
     */
    private static Outcome recheck(Kernel kernel, BlockchainImpl blockchain, long target, int window,
                                   Consumer<String> out) {
        BlockStore blockStore = kernel.getBlockStore();
        long inFlight = blockStore.getMainInFlight();
        int inFlightOp = blockStore.getMainInFlightOp();
        boolean superseded = inFlight > target;
        if (superseded) {
            blockStore.clearMainInFlight();
        }
        ChainConsistencyCheck.Report after = ChainConsistencyCheck.run(blockStore, blockchain.getXdagStats(),
                kernel.getConfig().getChainSpec(), window);
        kernel.recordConsistencyReport(after);
        say(out, after.describe());
        if (after.clean()) {
            return new Outcome(Status.REPAIRED, null, target, after);
        }
        if (superseded) {
            blockStore.saveMainInFlight(inFlight, opByte(inFlightOp));
        }
        long kept = blockStore.getMainInFlight();
        say(out, "cannot repair: " + STILL_INCONSISTENT_REASON + (kept < 0 ? ""
                : "; the in-flight record is left in place as evidence (height " + kept + ", op "
                        + opName(blockStore.getMainInFlightOp()) + ")"));
        return new Outcome(Status.UNREPAIRABLE, STILL_INCONSISTENT_REASON, target, after);
    }

    /**
     * M7: what a dry run did not write, and the one thing the boot before it may have. The
     * initialization flag must come from the BOOT report (the constructor already wrote the marker,
     * so the entry re-scan can never report it), hence the kernel's copy is consulted.
     */
    private static String dryRunNote(Kernel kernel) {
        ChainConsistencyCheck.Report boot = kernel.getConsistencyReport();
        return "dry run: nothing was written" + (boot != null && boot.markerInitialized()
                ? ", except the completion marker this boot initialized to nmain because the store carried none"
                : "");
    }

    private static byte opByte(int op) {
        return op == BlockStore.IN_FLIGHT_UNSET_MAIN ? BlockStore.IN_FLIGHT_UNSET_MAIN
                : BlockStore.IN_FLIGHT_SET_MAIN;
    }

    private static String opName(int op) {
        return op == BlockStore.IN_FLIGHT_UNSET_MAIN ? "unSetMain" : "setMain";
    }

    /** The tool is a CLI command: its output belongs on the operator's terminal as well as in the log. */
    private static void say(Consumer<String> out, String line) {
        out.accept(line);
        log.info("{}", line);
    }
}
