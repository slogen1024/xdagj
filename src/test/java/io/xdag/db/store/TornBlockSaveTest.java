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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * The crash that used to stall sync for good: {@code saveBlock} writes the raw 512 bytes before the
 * {@code BlockInfo}, and since SP0b-2 both are queued, so a kill between them leaves a block that
 * {@code isExist} called EXIST and {@code getBlockByHash} could not return. {@code tryToConnect}
 * short-circuited on EXIST before it could re-save, and every child of that block stayed NO_PARENT
 * forever.
 *
 * <p>The cut is made the way {@link WriteBehindPrefixTest} makes it: a manual queue, drained by hand
 * for exactly the prefix that must survive, then abandoned. The write-behind sources answer reads
 * from their own pending maps, so the torn state is only observable after a restart -- hence the
 * reopen below, which is also what a real node does.
 */
public class TornBlockSaveTest extends ChainL1TestBase {

    private WriteBehindQueue queue;

    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        // One entry per drainOnce(): the crash has to land between two named writes, not somewhere
        // inside a group of sixteen.
        queue = new WriteBehindQueue(1_000_000, 1, 1_000_000L, false);
        return new WriteBehindFactory(raw, queue, 1024);
    }

    @Test
    public void aBlockWhoseInfoNeverLandedIsNotExistAndCanBeReImported() {
        queue.flushSync(); // the fixture's own setup (address block, stats) is on disk
        Block block = BlockBuilder.generateLinkBlock(config, poolKey, txTime(), null,
                List.of(new Address(topRef, XDAG_FIELD_OUT, false)));
        Bytes32 hashlow = hashLow(block);

        // saveBlock enqueues, in order: timeSource.put, blockSource.put (the raw 512 bytes),
        // saveBlockSums, saveBlockInfo. Drain exactly the first two and abandon the rest.
        long before = queue.writtenCount();
        kernel.getBlockStore().saveBlock(block);
        assertTrue("saveBlock made more writes than the two that are kept", queue.pending() > 2);
        while (queue.writtenCount() < before + 2) {
            assertTrue("the queue still had a group to drain", queue.drainOnce());
        }
        queue.abandon();
        releaseStores();

        // Restart on the databases as the crash left them. The fixture's fields are reassigned so
        // tearDownChain stops and closes what is opened here.
        dbFactory = new RocksdbFactory(config);
        BlockStore reopened = BlockStoreImpl.forNode(dbFactory);
        reopened.start();
        assertTrue("the raw bytes survived the cut", reopened.hasBlock(hashlow));
        assertFalse("its BlockInfo did not", reopened.hasBlockInfo(hashlow));
        assertNull("so nothing can read the block back", reopened.getBlockByHash(hashlow, false));

        OrphanBlockStore orphans = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphans.start();
        AddressStore addresses = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addresses.start();
        chainStore = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
        chainStore.start();
        kernel.setBlockStore(reopened);
        kernel.setOrphanBlockStore(orphans);
        kernel.setAddressStore(addresses);
        kernel.setChainL1Store(chainStore);
        kernel.setPersist(null); // the restarted node in this test writes synchronously
        blockchain = new MockBlockchain(kernel);

        assertFalse("a half-saved block must not answer EXIST, or nothing ever re-imports it",
                blockchain.isExist(hashlow));

        ImportResult result = blockchain.tryToConnect(block);
        assertTrue("the re-import repairs the block instead of short-circuiting on EXIST: " + result + " "
                        + result.getErrorInfo(),
                result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST);
        assertTrue("and now both halves are on disk", reopened.hasBlockInfo(hashlow));
        assertTrue(blockchain.isExist(hashlow));
    }
}
