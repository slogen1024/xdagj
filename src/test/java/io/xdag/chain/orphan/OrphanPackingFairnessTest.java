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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Blockchain;
import io.xdag.db.AddressStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * The packing half of the starvation SP0b-3 exists to kill.
 *
 * <p>Task 8 closed the admission half: a flood of one category no longer refuses another at the
 * door. That is not enough on its own. An account transaction that is admitted but never packed is
 * starved just the same, and the packing walk used to guarantee exactly that: the link branch
 * returned unconditionally once it had drained what it could, so the merged account-transaction and
 * mtx walk below it was only ever reached when {@code linkQueue} was empty. A steady trickle of
 * link traffic — which is what a chunk flood looks like to this walk — kept it empty never.
 *
 * <h2>Why the flood here is built as link entries</h2>
 *
 * <p>The starvation this fix is about is a link-queue starvation, and the flood is built out of
 * link entries because that is the queue it has to stop giving a main block's first pick to. When
 * these tests were written chunk blocks sat in that queue too — nothing classified them, so
 * {@code OrphanCategory.of} was handed a null kind on every path into it. Blocks arriving from the
 * network are classified now and chunks have a queue of their own, which changes who the flooder
 * is and not what the fix does; a chunk arriving with no classification (locally produced, or
 * re-imported on a node with the ingest pipeline off) still lands here.
 *
 * <h2>The over-correction these tests also guard</h2>
 *
 * <p>The roadmap's wording was "a main block packs only from the account transaction queue". Taken
 * literally that leaves {@code linkQueue} to {@code createLinkBlock} alone, which {@code
 * checkOrphan} drives at {@code nnoref / 11} — so on a quiet node a main block would carry almost
 * no references at all. {@link #aMainBlockStillFillsRemainingSlotsFromTheLinkQueue} and
 * {@link #aLinkBlockStillDrainsTheLinkQueue} are what fail if the fix goes that far.
 */
public class OrphanPackingFairnessTest {

    /** A main block's reference budget, the number {@code createMainBlock} asks for. */
    private static final int BUDGET = 16;

    private OrphanBlockStoreImpl store;
    private ChainOrphanPool pool;

    /**
     * The store with a mocked kernel, as {@code OrphanBlockStoreConcurrencyTest} builds it, and
     * deliberately without {@code start()}: these tests drive {@code selectBlocks} directly and
     * want neither the ORPHANIND rebuild nor the expiry cleaner's ticks in the middle of a walk.
     */
    @Before
    public void setUp() {
        InMemoryKVSource source = new InMemoryKVSource();
        source.init();
        source.put(OrphanBlockStore.ORPHAN_SIZE, BytesUtils.longToBytes(0, false));

        AddressStore addressStore = mock(AddressStore.class);
        when(addressStore.getExecutedNonceNum(org.mockito.ArgumentMatchers.any())).thenReturn(UInt64.ZERO);
        Kernel kernel = mock(Kernel.class);
        when(kernel.getBlockchain()).thenReturn(mock(Blockchain.class));
        when(kernel.getAddressStore()).thenReturn(addressStore);
        when(kernel.getConfig()).thenReturn(new DevnetConfig());

        store = new OrphanBlockStoreImpl(source, kernel);
        pool = store.getPool();
    }

    /**
     * The starvation shape that was live: with chunks queued, an account transaction never got in.
     *
     * <p>"Before" here is about the budget, not about position in the reference list — the account
     * transaction takes its slot ahead of the chunks and is then written behind them. See
     * {@link #linkBlocksStayAheadOfAccountTransactionsInTheReferenceList} for why that split exists.
     */
    @Test
    public void aMainBlockTakesAccountTransactionsBeforeChunks() {
        for (int i = 0; i < 50; i++) {
            pool.add(chunkAsPooledToday(hash(100 + i), i));
        }
        pool.add(accountTx(hash(1), addr(1), 1L));

        List<OrphanEntry> picked = store.selectBlocks(BUDGET, Long.MAX_VALUE, true);

        assertTrue("an account transaction must reach a main block even with chunks queued",
                picked.stream().anyMatch(e -> e.hashlow().equals(hash(1))));
    }

    /** But {@code linkQueue} must still be consumed, or a quiet node's nnoref never catches up. */
    @Test
    public void aMainBlockStillFillsRemainingSlotsFromTheLinkQueue() {
        pool.add(accountTx(hash(1), addr(1), 1L));
        for (int i = 0; i < 50; i++) {
            pool.add(link(hash(100 + i), i));
        }

        List<OrphanEntry> picked = store.selectBlocks(BUDGET, Long.MAX_VALUE, true);

        assertEquals("the main block must still be filled to its budget", BUDGET, picked.size());
        assertTrue("and the account transaction must be one of them",
                picked.stream().anyMatch(e -> e.hashlow().equals(hash(1))));
    }

    /** A main block with nothing but link traffic still fills to its budget from it. */
    @Test
    public void aMainBlockWithOnlyLinkTrafficIsStillFilled() {
        for (int i = 0; i < 50; i++) {
            pool.add(link(hash(100 + i), i));
        }

        assertEquals(BUDGET, store.selectBlocks(BUDGET, Long.MAX_VALUE, true).size());
    }

    /**
     * The field order a main block actually gets, which is <em>not</em> the allocation order.
     *
     * <p>{@code applyBlock} descends into a block's references in field order, and a transaction
     * whose nonce is more than one past its sender's executed nonce is rejected for good — the
     * nonce check calls {@code resetTxQuantity} and the {@code BI_MAIN_REF} it leaves behind means
     * the block is never reconsidered. A link block from a peer routinely carries the nonce-n
     * predecessor of a nonce-n+1 transaction still pooled here (importing that link is what took
     * the predecessor out of the pool). Put the transaction in an earlier field than the link and
     * the predecessor has not executed when the nonce is checked, so a transaction that would have
     * confirmed is killed instead: {@code BlockchainTest.testLinkAndNonceImpactOnSorting} loses five
     * confirmations to exactly that. Slots for the account transactions, layout for the link blocks.
     */
    @Test
    public void linkBlocksStayAheadOfAccountTransactionsInTheReferenceList() {
        pool.add(accountTx(hash(1), addr(1), 1L));
        for (int i = 0; i < 3; i++) {
            pool.add(link(hash(100 + i), i));
        }

        List<OrphanEntry> picked = store.selectBlocks(BUDGET, Long.MAX_VALUE, true);

        assertEquals("everything pooled fits in the budget", 4, picked.size());
        assertEquals("the account transaction must be last, behind every link block",
                hash(1), picked.get(picked.size() - 1).hashlow());
    }

    @Test
    public void aLinkBlockStillDrainsTheLinkQueue() {
        for (int i = 0; i < 50; i++) {
            pool.add(link(hash(100 + i), i));
        }

        assertFalse(store.selectBlocks(BUDGET, Long.MAX_VALUE, false).isEmpty());
    }

    /**
     * The link block's own gate, unchanged by this task and pinned so that it stays that way. With
     * account transactions waiting and link traffic too light to fill the budget on its own, a link
     * block leads with the account transactions exactly as it does today — the inversion is a main
     * block's business, and widening it to the link path would be a change nothing here asked for.
     */
    @Test
    public void aLinkBlockStillLeadsWithAccountTransactionsWhenLinkTrafficIsLight() {
        pool.add(accountTx(hash(1), addr(1), 1L));
        pool.add(accountTx(hash(2), addr(2), 1L));
        for (int i = 0; i < 3; i++) {
            pool.add(link(hash(100 + i), i));
        }

        List<OrphanEntry> picked = store.selectBlocks(BUDGET, Long.MAX_VALUE, false);

        assertEquals("both account transactions must be in a light-link-traffic link block",
                2, picked.stream().filter(e -> e.category() == OrphanCategory.ACCOUNT_TX).count());
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * A chunk block as the pool holds an unclassified one: a link entry. See the class comment —
     * the kind that would make it a {@link OrphanCategory#CHUNK} reaches the store only for blocks
     * that arrived through the ingest pipeline.
     */
    private static OrphanEntry chunkAsPooledToday(Bytes32 hashlow, long time) {
        return link(hashlow, time);
    }

    private static OrphanEntry link(Bytes32 hashlow, long time) {
        return OrphanEntry.link(meta(hashlow, false, 0L, time, 0L, new byte[20]), null);
    }

    private static OrphanEntry accountTx(Bytes32 hashlow, byte[] address, long nonce) {
        return OrphanEntry.accountTx(meta(hashlow, true, nonce, 0L, 0L, address), null);
    }

    private static OrphanMeta meta(Bytes32 hashlow, boolean isTx, long nonce, long time, long fee,
            byte[] address) {
        return new OrphanMeta()
                .setHashlow(hashlow)
                .setTx(isTx)
                .setNonce(nonce)
                .setTime(time)
                .setFee(fee)
                .setAddress(address);
    }

    private static Bytes32 hash(int seed) {
        byte[] raw = new byte[32];
        raw[28] = (byte) (seed >>> 24);
        raw[29] = (byte) (seed >>> 16);
        raw[30] = (byte) (seed >>> 8);
        raw[31] = (byte) seed;
        return Bytes32.wrap(raw);
    }

    private static byte[] addr(int seed) {
        byte[] raw = new byte[20];
        raw[18] = (byte) (seed >>> 8);
        raw[19] = (byte) seed;
        return raw;
    }
}
