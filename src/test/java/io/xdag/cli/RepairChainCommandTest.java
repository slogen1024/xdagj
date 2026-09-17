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
package io.xdag.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import java.util.List;
import java.util.function.Function;
import org.junit.Test;

/**
 * SP0b-1, end to end: {@code xdag.sh --repairchain} on a real RocksDB store that a real chain was
 * mined into. Nothing is mocked below {@link XdagCli#repairChain(String)} — it opens the same
 * directories the fixture wrote, builds its own repair-mode kernel and blockchain, and what the
 * assertions read back is what is on disk after the command has closed everything again.
 *
 * <p>Only the wallet is stubbed: {@code loadWallet()} would otherwise construct a second
 * {@link io.xdag.Wallet} over the same file and {@code loadAndUnlockWallet()} would prompt for a
 * password on a terminal no test has.
 */
public class RepairChainCommandTest extends ChainL1TestBase {

    /** Enough main blocks that a marker three heights behind the tip is still above genesis. */
    private static final int MAIN_BLOCKS = 8;

    /** A CLI pointed at the fixture's store directory, with the fixture's unlocked wallet. */
    private XdagCli cli() {
        XdagCli cli = spy(new XdagCli());
        cli.setConfig(config);
        doReturn(wallet).when(cli).loadWallet();
        doReturn(wallet).when(cli).loadAndUnlockWallet();
        return cli;
    }

    /**
     * Hands the store directory over to the command: RocksDB is single-writer per directory, so the
     * fixture's own handles have to be gone before {@code repairChain} can open them. Nulled out
     * because {@code tearDownChain} closes whatever is left and tolerates nulls.
     */
    private void releaseStores() {
        blockchain.stopCheckMain();
        chainStore.stop();
        dbFactory.close();
        dbFactory = null;
        chainStore = null;
    }

    /** Reads the store back the way a later boot would, on handles of its own. */
    private <T> T readStore(Function<BlockStore, T> read) {
        DatabaseFactory factory = new RocksdbFactory(config);
        BlockStore blockStore = new BlockStoreImpl(
                factory.getDB(DatabaseName.INDEX),
                factory.getDB(DatabaseName.TIME),
                factory.getDB(DatabaseName.BLOCK),
                factory.getDB(DatabaseName.TXHISTORY));
        blockStore.start();
        try {
            return read.apply(blockStore);
        } finally {
            factory.close();
        }
    }

    private long marker() {
        return readStore(BlockStore::getLastCompletedMain);
    }

    private long persistedNmain() {
        return readStore(store -> store.getXdagStatus().nmain);
    }

    private long mineAndCorruptMarker(long behind) {
        for (int i = 0; i < MAIN_BLOCKS; i++) {
            mineMain(List.of());
        }
        long nmain = blockchain.getXdagStats().nmain;
        assertTrue("need a chain longer than the corruption: nmain=" + nmain, nmain > behind);
        // The shape a setMain that never finished leaves behind: the completion marker stuck below
        // the persisted tip, which is exactly what the boot check refuses to start on.
        kernel.getBlockStore().saveLastCompletedMain(nmain - behind);
        releaseStores();
        return nmain;
    }

    /**
     * The ordinary two-step an operator runs: plan the repair, then perform it. The dry run must
     * leave the store byte for byte as it found it; the repair must unwind the one incomplete height
     * and leave a store the next boot starts on.
     */
    @Test
    public void dryRunPlansAndTheRepairUnwindsToTheLastCompleteHeight() {
        long nmain = mineAndCorruptMarker(1);

        assertEquals("a plannable repair exits 0", 0, cli().repairChain("dry-run"));
        assertEquals("a dry run writes no marker", nmain - 1, marker());
        assertEquals("a dry run unwinds nothing", nmain, persistedNmain());

        assertEquals("the repair exits 0", 0, cli().repairChain(null));
        assertEquals(nmain - 1, marker());
        assertEquals("the tip was unwound to the last complete height", nmain - 1, persistedNmain());
        // The proof that the store is startable again: the check the boot runs now comes out clean,
        // which for this store means the marker and the persisted tip finally agree.
        assertEquals("marker and tip agree, i.e. the next boot starts", marker(), persistedNmain());
    }

    /**
     * The downgrade trap: a marker frozen far behind a tip that is in fact sound. {@code
     * reinit-marker} adopts the tip on the operator's word instead of unwinding everything above it.
     */
    @Test
    public void reinitMarkerAdoptsTheTipWithoutUnwinding() {
        long nmain = mineAndCorruptMarker(3);

        assertEquals(0, cli().repairChain("reinit-marker"));
        assertEquals("the marker was re-initialized to the persisted tip", nmain, marker());
        assertEquals("nothing was unwound", nmain, persistedNmain());
    }

    /** A typo must not become the most destructive of the four modes. */
    @Test
    public void anUnknownModeIsRefusedWithoutTouchingTheStore() {
        long nmain = mineAndCorruptMarker(1);

        assertEquals(3, cli().repairChain("dryrun"));
        assertEquals(nmain - 1, marker());
        assertEquals(nmain, persistedNmain());
    }
}
