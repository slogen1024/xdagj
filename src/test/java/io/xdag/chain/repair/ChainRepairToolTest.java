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

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.config.Constants.BI_MAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagStats;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.DatabaseName;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Test;

/**
 * The offline repair core: {@link ChainRepairTool#repair} acting on the stores the boot check
 * refused to start on, against a real blockchain rebuilt from those same stores in repair mode.
 */
public class ChainRepairToolTest extends ChainL1TestBase {

    /**
     * A deliberately tiny consistency window. {@code chain.consistency.window} has no setter by
     * design (node-local, read once from the conf), and the "target too far below the tip" refusal
     * is only reachable on a chain longer than the window — which at the 128-block default would
     * mean mining 130 main blocks per test. Overriding the getter on the config the fixture builds
     * the kernel from is the cheapest way to reach that branch; every other test here is written so
     * the narrow window changes nothing about what it asserts.
     */
    private static final int WINDOW = 2;

    private static final Bytes BOND_CHAIN = Bytes.repeat((byte) 0x33, 20);

    /** Throws on the first BOND it is handed once armed, which is how a failed setMain is produced. */
    private static final class ExplodingHandler implements ChainKindHandler {
        volatile boolean armed;

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("simulated failure inside the applyBlock DFS");
            }
        }

        @Override
        public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        }
    }

    private final ExplodingHandler handler = new ExplodingHandler();

    /** Every blockchain built on top of the fixture's own, so the @After can stop its threads. */
    private final List<BlockchainImpl> restarts = new ArrayList<>();

    /** Everything the tool printed, so a test can assert on what the operator was told. */
    private final List<String> said = new ArrayList<>();

    public ChainRepairToolTest() {
        config = new DevnetConfig() {
            @Override
            public int getChainConsistencyWindow() {
                return WINDOW;
            }
        };
    }

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.getChainKindHandlers().put(ExtKind.BOND, handler);
    }

    /**
     * A "restart" on the same stores: a fresh blockchain loads the persisted stats and runs the boot
     * check, which in repair mode records its report instead of throwing.
     *
     * <p>Registered for shutdown: the constructor starts a non-daemon cleaner thread even in repair
     * mode, and {@code stopCheckMain} is what stops it.
     */
    private MockBlockchain restartInRepairMode() {
        kernel.enterRepairMode();
        MockBlockchain restarted = new MockBlockchain(kernel);
        restarts.add(restarted);
        return restarted;
    }

    /** The tool under test, with its output captured instead of printed. */
    private ChainRepairTool.Outcome repair(BlockchainImpl chain, boolean dryRun, boolean force) {
        return ChainRepairTool.repair(kernel, chain, new ChainRepairTool.Options(dryRun, force), said::add);
    }

    private boolean saidSomethingAbout(String fragment) {
        return said.stream().anyMatch(line -> line.contains(fragment));
    }

    @After
    public void stopRestarts() {
        for (BlockchainImpl b : restarts) {
            b.stopCheckMain();
        }
        restarts.clear();
    }

    private void mine(int n) {
        for (int i = 0; i < n; i++) {
            mineMain(List.of());
        }
    }

    /** A link-less BOND block: the only thing the exploding handler is ever handed. */
    private Block bondBlock() {
        Block b = new Block(config, txTime(), null, null, false, null, null, -1, XAmount.ZERO, null,
                List.of(new BondExt(false, BOND_CHAIN, 0L).encodeHeader()));
        return new Block(new XdagBlock(b.toBytes()));
    }

    /**
     * The shape the whole feature exists for: a {@code setMain} that threw inside the apply DFS,
     * after part of that height's CHAIN_L1 records had already been committed. The repair unwinds
     * the height, which must restore CHAIN_L1 exactly — and the node must then re-confirm the very
     * same block at the very same height, producing the very same records.
     */
    @Test
    public void incompleteSetMainIsUnwoundAndReconfirmedIdentically() {
        mine(6);
        // a DEPLOY confirmed before the failure must survive the repair untouched
        ChainBlockBuilder.Built deploy = deployNewChain(payload(600, 41), payload(10, 42));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertTrue(chainStore.hasChain(chainId));
        Bytes32 before = chainStore.stateHash();

        // the failing height: a CALL and a BOND under the same main block; the BOND handler explodes
        ChainBlockBuilder.Built call = call(chainId, ChainIds.contractIdOf(deploy.block().getHash()),
                payload(20, 43), FEE);
        importBuilt(call);
        Block bond = bondBlock();
        assertImported(bond);
        // setMain runs one main block behind mineMain's own top (ChainL1TestBase §0.5), so this call
        // only links the two blocks; the next one is the setMain that descends into them.
        Block failing = mineMain(List.of(hashLow(call.block()), hashLow(bond)));
        handler.armed = true;
        assertThrows(IllegalStateException.class, () -> mine(1));
        long failed = blockchain.getXdagStats().nmain;
        assertEquals(failed, heightOf(failing));
        assertEquals("the CALL was recorded before the BOND blew up", 1L, chainStore.getCallCount(chainId, failed));
        kernel.getBlockStore().saveXdagStatus(blockchain.getXdagStats()); // as a later import would have

        MockBlockchain repair = restartInRepairMode();
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertFalse(report.describe(), report.clean());
        assertEquals(failed - 1, report.repairTarget());

        ChainRepairTool.Outcome dry = repair(repair, true, false);
        assertEquals(ChainRepairTool.Status.PLANNED, dry.status());
        assertNull(dry.reason());
        assertEquals(failed - 1, dry.target());
        assertEquals("a dry run writes nothing", failed, repair.getXdagStats().nmain);
        assertEquals(1L, chainStore.getCallCount(chainId, failed));

        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REPAIRED, done.status());
        assertNull(done.reason());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals(failed - 1, repair.getXdagStats().nmain);
        assertEquals(failed - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals("the unwind supersedes the in-flight record", -1L, kernel.getBlockStore().getMainInFlight());
        assertEquals("records of the failed height are gone, earlier ones intact", before, chainStore.stateHash());
        assertEquals(0L, chainStore.getCallCount(chainId, failed));

        // The node resumes on the restarted blockchain. Mining continues from the last main block
        // the store actually carries: the block mined by the throwing call was still an extra block
        // living in the first instance's orphan pool, so the restart never saw it (a real node
        // re-receives such a block from its peers; this fixture has no peers).
        blockchain = repair;
        topRef = hashLow(failing);
        confirm(call.block());
        assertEquals(failed, heightOf(failing));
        assertEquals(1L, chainStore.getCallCount(chainId, failed));
        assertEquals(failed, kernel.getBlockStore().getLastCompletedMain());
        ChainConsistencyCheck.Report resumed = ChainConsistencyCheck.run(kernel.getBlockStore(),
                repair.getXdagStats(), config.getChainSpec(), WINDOW);
        assertTrue(resumed.describe(), resumed.clean());
    }

    /**
     * The pre-SP0b-1 shape: a confirmed main block whose ref was never written, so {@code unSetMain}
     * would have skipped it and it could never be unwound. The tool patches the ref first, which is
     * what makes the unwind that follows reach it at all.
     */
    @Test
    public void legacyStuckBlockIsPatchedAndUnwound() {
        mine(6);
        long h = blockchain.getXdagStats().nmain - 1;
        Block stuck = blockchain.getBlockByHeight(h);
        assertNotNull(stuck);
        blockchain.updateBlockRef(stuck, null);
        assertNull(kernel.getBlockStore().getBlockByHeight(h).getInfo().getRef());

        MockBlockchain repair = restartInRepairMode();
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertFalse(report.describe(), report.clean());
        assertEquals(h - 1, report.repairTarget());

        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REPAIRED, done.status());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals(h - 1, repair.getXdagStats().nmain);
        assertEquals(h - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
    }

    /**
     * Failure shape 3: {@code setMain} flagged and saved its main block, then died before its stats
     * write landed (or the write was torn). The persisted tip is one below a block that is still
     * confirmed — and the boot pins the top to the persisted tip, so that block sits ABOVE anything
     * {@code unWindMain} can walk. Without the reconciliation the repair would unwind nothing, claim
     * success, and leave the chain exactly as broken as it found it.
     */
    @Test
    public void mainBlockAboveThePersistedTipIsReconciledThenUnwound() {
        mine(6);
        long tip = blockchain.getXdagStats().nmain;
        assertEquals("the marker agrees with the tip before the crash is simulated", tip,
                kernel.getBlockStore().getLastCompletedMain());
        XdagStats torn = new XdagStats(blockchain.getXdagStats());
        torn.nmain = tip - 1;
        kernel.getBlockStore().saveXdagStatus(torn);

        MockBlockchain repair = restartInRepairMode();
        assertEquals("the restart boots on the torn stats", tip - 1, repair.getXdagStats().nmain);
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertFalse(report.describe(), report.clean());
        assertEquals(tip - 1, report.repairTarget());

        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REPAIRED, done.status());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals(tip - 1, repair.getXdagStats().nmain);
        assertEquals(tip - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals("the block above the torn tip is no longer confirmed", 0,
                kernel.getBlockStore().getBlockByHeight(tip).getInfo().getFlags() & BI_MAIN);
        assertTrue("the operator is told the tip was reconciled", saidSomethingAbout("reconciled the persisted tip"));
        assertTrue("and what it costs the local balance figure", saidSomethingAbout("xdagStats.balance"));
    }

    /** Unwinding further below the tip than the consistency window is an operator's decision. */
    @Test
    public void deepTargetNeedsForce() {
        mine(6);
        long nmain = blockchain.getXdagStats().nmain;
        assertTrue("the fixture must mine more main blocks than the window", nmain > WINDOW + 1);
        kernel.getBlockStore().saveLastCompletedMain(1L);

        MockBlockchain repair = restartInRepairMode();
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertEquals(1L, report.repairTarget());

        ChainRepairTool.Outcome refused = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REFUSED, refused.status());
        assertNotNull(refused.reason());
        assertEquals("a refusal writes nothing", nmain, repair.getXdagStats().nmain);

        ChainRepairTool.Outcome forced = repair(repair, false, true);
        assertEquals(ChainRepairTool.Status.REPAIRED, forced.status());
        assertTrue(forced.after().describe(), forced.after().clean());
        assertEquals(1L, repair.getXdagStats().nmain);
        assertEquals(1L, kernel.getBlockStore().getLastCompletedMain());
    }

    /** The same refusal, on a dry run: the plan an operator asks for has to include what blocks it. */
    @Test
    public void dryRunSurfacesTheForceRefusal() {
        mine(6);
        long nmain = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(1L);

        MockBlockchain repair = restartInRepairMode();
        ChainRepairTool.Outcome dry = repair(repair, true, false);
        assertEquals(ChainRepairTool.Status.REFUSED, dry.status());
        assertTrue(dry.reason(), dry.reason().contains("--force"));
        assertEquals("a dry run writes nothing either", nmain, repair.getXdagStats().nmain);
        assertEquals(1L, kernel.getBlockStore().getLastCompletedMain());
    }

    /**
     * A crashed {@code unSetMain} that had not started reversing anything yet: the block still
     * carries {@code BI_MAIN}, so the interrupted unwind is simply run to completion. Unwinding
     * further would not have helped — that is the whole reason this branch exists.
     */
    @Test
    public void interruptedUnwindThatNeverStartedIsFinished() {
        mine(6);
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveMainInFlight(tip, BlockStore.IN_FLIGHT_UNSET_MAIN);

        MockBlockchain repair = restartInRepairMode();
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertTrue(report.describe(), report.inFlightUnwind());

        ChainRepairTool.Outcome dry = repair(repair, true, false);
        assertEquals(ChainRepairTool.Status.PLANNED, dry.status());
        assertEquals("a dry run writes nothing", tip, repair.getXdagStats().nmain);
        assertEquals(tip, kernel.getBlockStore().getMainInFlight());

        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REPAIRED, done.status());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals(tip - 1, repair.getXdagStats().nmain);
        assertEquals(tip - 1, done.target());
        assertEquals(tip - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
        assertEquals("the height is no longer a main block", 0,
                kernel.getBlockStore().getBlockByHeight(tip).getInfo().getFlags() & BI_MAIN);
    }

    /**
     * The same interrupted unwind, on the legacy shape whose ref was never written. {@code
     * unApplyBlock} returns immediately on a null ref, so finishing the unwind without patching it
     * first would clear {@code BI_MAIN}, drop {@code nmain} — and reverse none of the height's
     * CHAIN_L1 records. The state hash is what proves the children really were unapplied.
     */
    @Test
    public void interruptedUnwindOfARefLessBlockStillUnappliesItsChildren() {
        mine(6);
        ChainBlockBuilder.Built deploy = deployNewChain(payload(600, 41), payload(10, 42));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes32 before = chainStore.stateHash();

        ChainBlockBuilder.Built call = call(chainId, ChainIds.contractIdOf(deploy.block().getHash()),
                payload(20, 43), FEE);
        importBuilt(call);
        Block applying = mineMain(List.of(hashLow(call.block())));
        mine(1); // the setMain that descends into the CALL
        long h = blockchain.getXdagStats().nmain;
        assertEquals(h, heightOf(applying));
        assertEquals(1L, chainStore.getCallCount(chainId, h));
        assertNotEquals("the CALL really was recorded", before, chainStore.stateHash());

        blockchain.updateBlockRef(blockchain.getBlockByHeight(h), null);
        kernel.getBlockStore().saveMainInFlight(h, BlockStore.IN_FLIGHT_UNSET_MAIN);

        MockBlockchain repair = restartInRepairMode();
        assertTrue(kernel.getConsistencyReport().describe(), kernel.getConsistencyReport().inFlightUnwind());

        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.REPAIRED, done.status());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals("the children of the unwound main block were unapplied", before, chainStore.stateHash());
        assertEquals(0L, chainStore.getCallCount(chainId, h));
        assertEquals(h - 1, repair.getXdagStats().nmain);
        assertEquals(h - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
    }

    /**
     * The same record, but the block no longer carries {@code BI_MAIN}: an unknown part of the
     * reversal already landed. Nothing automated can finish that idempotently, so the tool refuses
     * — and {@code --force} does not override it.
     */
    @Test
    public void interruptedUnwindThatStartedReversingIsRefused() {
        mine(6);
        long tip = blockchain.getXdagStats().nmain;
        blockchain.updateBlockFlag(blockchain.getBlockByHeight(tip), BI_MAIN, false);
        kernel.getBlockStore().saveMainInFlight(tip, BlockStore.IN_FLIGHT_UNSET_MAIN);

        MockBlockchain repair = restartInRepairMode();
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertTrue(report.describe(), report.inFlightUnwind());

        ChainRepairTool.Outcome refused = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.UNREPAIRABLE, refused.status());
        assertEquals(ChainRepairTool.INTERRUPTED_UNWIND_REASON, refused.reason());
        assertTrue(refused.reason().contains("restore the block store from a snapshot"));

        ChainRepairTool.Outcome forced = repair(repair, false, true);
        assertEquals("a partial reversal cannot be forced through", ChainRepairTool.Status.UNREPAIRABLE,
                forced.status());
        assertEquals(ChainRepairTool.INTERRUPTED_UNWIND_REASON, forced.reason());
        assertEquals("nothing was written", tip, repair.getXdagStats().nmain);
        assertEquals(tip, kernel.getBlockStore().getMainInFlight());
    }

    /**
     * An unwind that cannot reach its target because the raw bytes it walks are gone. The walk ends
     * silently, so only the postcondition catches it — and the completion marker must NOT be moved
     * down behind a repair that did not happen.
     */
    @Test
    public void unwindThatCannotReachItsTargetIsUnrepairable() {
        mine(6);
        long nmain = blockchain.getXdagStats().nmain;
        long h = nmain - 1;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(h), null); // something to repair
        long marker = kernel.getBlockStore().getLastCompletedMain();
        Block tip = blockchain.getBlockByHeight(nmain);
        assertNotNull(tip);
        dbFactory.getDB(DatabaseName.BLOCK).delete(tip.getHashLow().toArray());

        MockBlockchain repair = restartInRepairMode();
        ChainRepairTool.Outcome done = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.UNREPAIRABLE, done.status());
        assertTrue(done.reason(), done.reason().contains("block data is incomplete"));
        assertEquals("the marker is evidence, not a casualty", marker,
                kernel.getBlockStore().getLastCompletedMain());
        assertEquals("and nothing new was put in flight", -1L, kernel.getBlockStore().getMainInFlight());
    }

    /** A repair that worked leaves nothing to repair: the second run re-reads the stores and stops. */
    @Test
    public void aSecondRepairFindsNothingToDo() {
        mine(6);
        long h = blockchain.getXdagStats().nmain - 1;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(h), null);

        MockBlockchain repair = restartInRepairMode();
        assertEquals(ChainRepairTool.Status.REPAIRED, repair(repair, false, false).status());
        long marker = kernel.getBlockStore().getLastCompletedMain();
        assertEquals(h - 1, marker);

        ChainRepairTool.Outcome second = repair(repair, false, false);
        assertEquals(ChainRepairTool.Status.CLEAN, second.status());
        assertTrue(second.after().describe(), second.after().clean());
        assertEquals("a clean store is written to by nobody", marker,
                kernel.getBlockStore().getLastCompletedMain());
        assertEquals(-1L, kernel.getBlockStore().getMainInFlight());
        assertEquals(h - 1, repair.getXdagStats().nmain);
    }

    /** The tool only ever runs against a kernel nothing else can confirm a block on. */
    @Test
    public void repairRefusesToRunOutsideRepairMode() {
        mine(1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> repair(blockchain, true, false));
        assertTrue(e.getMessage(), e.getMessage().contains("repair mode"));
    }
}
