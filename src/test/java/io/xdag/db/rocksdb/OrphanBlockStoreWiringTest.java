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

package io.xdag.db.rocksdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.orphan.OrphanAdmission;
import io.xdag.chain.orphan.OrphanCategory;
import io.xdag.chain.orphan.OrphanEntry;
import io.xdag.chain.orphan.OrphanLimits;
import io.xdag.chain.orphan.OrphanMeta;
import io.xdag.config.DevnetConfig;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagStats;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * The two properties of the orphan pool's wiring that no test of the pool itself can see, because
 * both of them are about what the store hands the pool rather than about what the pool then does
 * with it: the caps it is built with, and the clock its expiry tick is driven by.
 */
public class OrphanBlockStoreWiringTest {

    private Blockchain blockchain;
    private XdagStats stats;
    private OrphanBlockStoreImpl store;

    @Before
    public void setUp() {
        InMemoryKVSource source = new InMemoryKVSource();
        source.init();
        source.put(OrphanBlockStore.ORPHAN_SIZE, BytesUtils.longToBytes(0, false));

        stats = new XdagStats();
        blockchain = mock(Blockchain.class);
        when(blockchain.getXdagStats()).thenReturn(stats);

        AddressStore addressStore = mock(AddressStore.class);
        when(addressStore.getExecutedNonceNum(org.mockito.ArgumentMatchers.any())).thenReturn(UInt64.ZERO);

        Kernel kernel = mock(Kernel.class);
        when(kernel.getBlockchain()).thenReturn(blockchain);
        when(kernel.getAddressStore()).thenReturn(addressStore);
        when(kernel.getBlockStore()).thenReturn(mock(BlockStore.class));
        when(kernel.getConfig()).thenReturn(new DevnetConfig());

        // start() is deliberately not called: it would schedule the cleaner, and these tests drive
        // exactly one tick by hand so the result is not a matter of timing.
        store = new OrphanBlockStoreImpl(source, kernel);
    }

    /**
     * Every cap the pool enforces has to be named by the wiring.
     *
     * <p>{@link OrphanLimits} leaves an unnamed cap at {@link OrphanLimits#UNLIMITED} — not at a
     * conservative default. So a category the wiring forgets is not capped low, it is not capped at
     * all: the protection SP0b-3 exists to add would be silently absent for it, with nothing in a
     * log or a verdict to say so. This is the test that makes that impossible to do by accident,
     * and it will fail the day a fifth category is added without a key behind it.
     */
    @Test
    public void everyCapIsNamedByTheProductionWiring() {
        OrphanLimits limits = OrphanBlockStoreImpl.limitsFrom(new DevnetConfig().getChainSpec());

        for (OrphanCategory category : OrphanCategory.values()) {
            assertNotEquals("category " + category + " was left unbounded by the wiring",
                    OrphanLimits.UNLIMITED, limits.limit(category));
        }
        assertNotEquals("the pool as a whole was left unbounded by the wiring",
                OrphanLimits.UNLIMITED, limits.poolLimit());
        assertNotEquals("the per-peer chunk quota was left unbounded by the wiring",
                OrphanLimits.UNLIMITED, limits.chunkPerPeer());
        assertNotEquals("the per-chain chunk quota was left unbounded by the wiring",
                OrphanLimits.UNLIMITED, limits.chunkPerChain());
        assertNotEquals("the chunk TTL was left unbounded by the wiring",
                OrphanLimits.UNLIMITED, limits.chunkTtlEpochs());
    }

    /** And each cap is the configured key's value, not some other key's. */
    @Test
    public void everyCapCarriesItsOwnConfiguredValue() {
        ChainSpec spec = new DevnetConfig().getChainSpec();
        OrphanLimits limits = OrphanBlockStoreImpl.limitsFrom(spec);

        assertEquals(spec.getChainOrphanPoolLimit(), limits.poolLimit());
        assertEquals(spec.getChainOrphanAccountTxLimit(), limits.limit(OrphanCategory.ACCOUNT_TX));
        assertEquals(spec.getChainOrphanMtxLimit(), limits.limit(OrphanCategory.MTX));
        assertEquals(spec.getChainOrphanChunkLimit(), limits.limit(OrphanCategory.CHUNK));
        assertEquals(spec.getChainOrphanLinkLimit(), limits.limit(OrphanCategory.LINK));
        assertEquals(spec.getChainOrphanChunkPerPeer(), limits.chunkPerPeer());
        assertEquals(spec.getChainOrphanChunkPerChain(), limits.chunkPerChain());
        assertEquals(spec.getChainOrphanChunkTtlEpochs(), limits.chunkTtlEpochs());
    }

    /**
     * The expiry tick ages chunks against <b>the chain's top, not the wall clock</b>.
     *
     * <p>The scenario is a node that is behind: its main chain has reached epoch N+1 while the wall
     * clock has already moved on to N+3. A chunk from epoch N is still perfectly referenceable by
     * the epoch N+1 paying blocks this node has not finished importing — {@code ChunkChain}'s age
     * rule allows N and N+1 — so dropping it now would make this node reject a chain every node
     * that kept it accepts. Two honest nodes, one chain, two answers.
     *
     * <p>Driven by the wall clock the same chunk is three epochs old and goes. That is the mutation
     * this test exists to catch, and it is the only difference between the two: the chunk, the
     * pool, the TTL and the tick are identical.
     */
    @Test
    public void aChunkIsAgedAgainstTheChainTopNotTheWallClock() {
        long wallClockEpoch = XdagTime.getCurrentEpoch();
        long chunkEpoch = wallClockEpoch - 3;
        chainTopAtEpoch(chunkEpoch + 1);

        assertEquals(OrphanAdmission.ADMITTED, store.getPool().add(chunkAtEpoch(1, chunkEpoch)));

        store.cleanExpiredOrphans();

        assertEquals("a chunk one epoch behind the chain top is still inside the age rule's reach;"
                        + " the wall clock is three epochs past it and must not be what decides",
                1, store.getPool().size(OrphanCategory.CHUNK));
    }

    /**
     * And the other half of the same rule: once the <em>chain</em> moves past the last epoch that
     * could reference the chunk, it does go. Without this the test above would also pass on a
     * wiring that never evicted anything at all.
     */
    @Test
    public void aChunkGoesOnceTheChainItselfHasMovedPastIt() {
        long chunkEpoch = XdagTime.getCurrentEpoch() - 3;
        chainTopAtEpoch(chunkEpoch + 1);
        store.getPool().add(chunkAtEpoch(1, chunkEpoch));

        store.cleanExpiredOrphans();
        assertEquals(1, store.getPool().size(OrphanCategory.CHUNK));

        chainTopAtEpoch(chunkEpoch + 2);
        store.cleanExpiredOrphans();

        assertEquals("the chain is now past every epoch that could reference it", 0,
                store.getPool().size(OrphanCategory.CHUNK));
    }

    /**
     * An expired chunk gives {@code nnoref} back even though it never had an ORPHANIND row.
     *
     * <p>{@code nnoref} is incremented for every non-extra block imported, chunks included, and the
     * tick used to decrement it only where it found a row to delete. Chunks are memory-only now, so
     * a row-gated decrement would leave the count one too high for every chunk that ever expires —
     * and {@code nnoref / 11} is what decides how many link blocks this node mines, so the drift
     * would be a node mining link blocks for orphans it is not holding.
     */
    @Test
    public void anExpiredChunkStillReleasesNnorefEvenWithNoRowToDelete() {
        long chunkEpoch = XdagTime.getCurrentEpoch() - 3;
        chainTopAtEpoch(chunkEpoch + 2);
        store.getPool().add(chunkAtEpoch(1, chunkEpoch));
        stats.nnoref = 1;

        store.cleanExpiredOrphans();

        assertEquals("the chunk left the pool", 0, store.getPool().size(OrphanCategory.CHUNK));
        assertEquals("and nnoref went with it, row or no row", 0, stats.nnoref);
    }

    /** Nothing ages out before this node has a main chain to measure chain progress against. */
    @Test
    public void nothingIsAgedOutBeforeTheFirstMainBlock() {
        stats.nmain = 0;
        store.getPool().add(chunkAtEpoch(1, XdagTime.getCurrentEpoch() - 100));

        store.cleanExpiredOrphans();

        assertEquals("with no main chain there is no chain progress to age a chunk against",
                1, store.getPool().size(OrphanCategory.CHUNK));
    }

    private void chainTopAtEpoch(long epoch) {
        Block top = mock(Block.class);
        when(top.getTimestamp()).thenReturn(epoch << 16);
        stats.nmain = 1;
        when(blockchain.getBlockByHeight(1)).thenReturn(top);
    }

    /**
     * A chunk entry placed straight into the pool. The ingest pipeline that would classify one on
     * the way in is a later task; what this test is about is the clock the tick drives eviction
     * with, which does not depend on how the chunk got there.
     */
    private static OrphanEntry chunkAtEpoch(long id, long epoch) {
        OrphanMeta meta = new OrphanMeta()
                .setHashlow(hashLow(id))
                .setNonce(0)
                .setTx(false)
                .setTime(epoch << 16)
                .setFee(0)
                .setAddress(new byte[20]);
        return OrphanEntry.chunk(meta, "203.0.113.7", hashLow(1000 + id), null);
    }

    private static Bytes32 hashLow(long id) {
        MutableBytes32 hash = MutableBytes32.create();
        hash.set(24, org.apache.tuweni.bytes.Bytes.wrap(BytesUtils.longToBytes(id, false)));
        return hash;
    }
}
