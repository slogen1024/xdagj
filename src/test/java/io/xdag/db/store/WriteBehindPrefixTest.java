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

package io.xdag.db.store;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.chain.repair.ChainConsistencyCheck;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagStats;
import io.xdag.core.XdagTopStatus;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * P2 (SP0b-2): whatever point the write stream is cut at, the databases hold a prefix of it. The
 * queue is manual (no writer thread), so the test drains groups by hand, then "crashes" by
 * abandoning the rest.
 */
public class WriteBehindPrefixTest extends ChainL1TestBase {

    private static final int BLOCKS = 120;
    private WriteBehindQueue queue;

    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        // maxPending far above anything this test enqueues: a manual queue that fills up drains a
        // group on the caller, and nothing may reach the databases except through drainOnce() here.
        queue = new WriteBehindQueue(1_000_000, 16, 1_000_000L, false); // 16 entries per drainOnce()
        return new WriteBehindFactory(raw, queue, 1024);
    }

    private List<Block> plainTransfers(int n) {
        ECKeyPair senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        addressStore.updateBalance(sender.toArray(), XAmount.of(10_000, XUnit.XDAG));
        Address from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Address to = new Address(BytesUtils.arrayToByte32(ECKeyPair.generate().toAddress().toArray()),
                    XDAG_FIELD_OUTPUT, true);
            out.add(new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                    config, senderKey, txTime() + i, from, to, ONE_XDAG, UInt64.valueOf(i + 1)).toBytes())));
        }
        return out;
    }

    @Test
    public void theDatabasesAlwaysHoldAPrefixOfTheWriteStream() throws Exception {
        queue.flushSync(); // the fixture's own setup (address block, stats) is on disk
        List<Block> blocks = plainTransfers(BLOCKS);
        long[] stampAfter = new long[BLOCKS];
        long[] nblocksAfter = new long[BLOCKS];
        for (int i = 0; i < BLOCKS; i++) {
            assertImported(blocks.get(i));
            stampAfter[i] = queue.enqueuedCount();
            nblocksAfter[i] = blockchain.getXdagStats().nblocks;
        }
        assertTrue("the stream grew", stampAfter[BLOCKS - 1] > 0);

        // Cut the stream in the middle of an import, deterministically: drain group by group until
        // the first import that is only half written, and crash there. A random cut could land past
        // the end of the stream and quietly test nothing.
        long target = (stampAfter[0] + stampAfter[BLOCKS - 1]) / 2;
        while (queue.writtenCount() < target) {
            assertTrue("the queue still had a group to drain", queue.drainOnce());
        }
        long written = queue.writtenCount();
        queue.abandon();
        releaseStores();

        RocksdbFactory raw = new RocksdbFactory(config);
        BlockStore store = BlockStoreImpl.forNode(raw);
        store.start();
        try {
            int full = 0;
            while (full < BLOCKS && stampAfter[full] <= written) {
                full++;
            }
            // Guard against a vacuous run: the crash has to land inside the stream, not after it.
            assertTrue("the crash cut the stream mid-import: " + full + " of " + BLOCKS + " imports survived ("
                    + written + " of " + stampAfter[BLOCKS - 1] + " writes)", full > 0 && full < BLOCKS);
            XdagStats stats = store.getXdagStatus();
            assertEquals("stats on disk equal the last fully written import (" + written + " writes, " + full
                            + " imports)",
                    nblocksAfter[full - 1], stats.nblocks);
            for (int i = 0; i < full; i++) {
                assertNotNull("import " + i + " is fully on disk",
                        store.getBlockByHash(blocks.get(i).getHashLow(), false));
                assertNotNull("import " + i + " has its raw bytes too (saveBlock writes them first)",
                        store.getBlockByHash(blocks.get(i).getHashLow(), true));
            }
            // Later imports may be partial: a raw block without its info is invisible and harmless.
            for (int i = full + 1; i < BLOCKS; i++) {
                assertNull("import " + i + " is not on disk", store.getBlockByHash(blocks.get(i).getHashLow(), false));
            }
            // As built, SETTING_TOP_STATUS is written only by a pretop candidate (main-chain work),
            // by a fork's updateNewChain and by the snapshot/repair paths -- never by a plain
            // transfer import. A run like this one leaves it absent, which is a legitimate prefix
            // too; what must hold is that a top that IS on disk points at a block that is on disk.
            XdagTopStatus top = store.getXdagTopStatus();
            if (top != null && top.getTop() != null) {
                assertNotNull("the persisted top points at a persisted block",
                        store.getBlockByHash(Bytes32.wrap(top.getTop()), false));
            }
            ChainConsistencyCheck.Report report = ChainConsistencyCheck.run(store, stats, config.getChainSpec(),
                    config.getChainSpec().getChainConsistencyWindow());
            assertTrue(report.describe(), report.clean());
        } finally {
            raw.close();
        }
    }

    @Test
    public void aConsensusTransitionDrainsThenWritesDirectly() {
        List<Block> blocks = plainTransfers(3);
        for (Block b : blocks) {
            assertImported(b);
        }
        assertTrue("imports are queued", queue.pending() > 0);
        long queuedBeforeTransition = queue.enqueuedCount();
        mineMain(List.of(hashLow(blocks.get(0)), hashLow(blocks.get(1)), hashLow(blocks.get(2))));
        confirm(blocks.get(2)); // setMain runs inside: drains first, then writes directly
        // The drain half: everything queued before the transition is in the databases.
        assertTrue("the transition drained the stream ahead of it (" + queue.writtenCount() + " written, "
                        + queuedBeforeTransition + " queued before it)",
                queue.writtenCount() >= queuedBeforeTransition);
        // The direct half: setMain makes dozens of writes and not one of them entered the stream,
        // so what is still queued is only checkMain's own stats save, which runs after it returns.
        assertTrue("setMain's writes bypassed the queue; still queued: " + queue.pending(), queue.pending() <= 2);
        assertTrue(store().getLastCompletedMain() >= 1);
    }

    private BlockStore store() {
        return kernel.getBlockStore();
    }
}
