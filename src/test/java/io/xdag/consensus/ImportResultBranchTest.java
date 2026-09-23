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
package io.xdag.consensus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.BlockBuilder;
import io.xdag.chain.ingest.PreValidated;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.Blockchain;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagStats;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.net.ChannelManager;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * What {@link ImportResult#CHAIN_FEE_POLICY} means to every branch that already had an opinion
 * about {@link ImportResult#INVALID_BLOCK}.
 *
 * <h2>The verdict the variant exists for</h2>
 *
 * <p>The design's §5.5 asks for the new code to say "this node does not want this block", never
 * "this block is broken", and for no path to turn it into a punitive action. The punitive half is
 * easy to state and easy to check: there is no scoring, banning or blacklisting machinery in this
 * codebase at all — {@code releaseWaiters}' {@code INVALID_BLOCK} arm is an empty block with its
 * one log line commented out — so the only collaborator that could reach a peer at all is the
 * {@link ChannelManager}, and the assertion below is written against that.
 *
 * <p>The half that actually costs something is the other one. {@code INVALID_BLOCK} does not call
 * {@code syncPopBlock}, so every child waiting in {@code syncMap} on a block refused that way stays
 * there until it is evicted: one "I am full" answer strands a whole subtree. That is what the new
 * code is for, and it is why {@code CHAIN_FEE_POLICY} releases the waiters instead of landing in
 * the {@code default -> {}} arm a new enum constant would otherwise fall into silently.
 *
 * <h2>Why releasing them is right when the block really is not in the DAG</h2>
 *
 * <p>A released child is re-imported, finds its parent missing, answers {@code NO_PARENT} and is
 * pushed back under that parent's hash — which re-sends the request for it. That is the loop §5.2
 * completes: a block this node asked for is exempt from the policy, so the parent comes back and is
 * taken the second time. Leaving the children parked instead re-requests nothing and progresses
 * nothing; they simply wait out their eviction.
 *
 * <p>{@code INVALID_BLOCK}'s stranding is pinned here too. It is not endorsed — it is the measured
 * cost the design argues from — and a change that makes it pop as well should replace that test
 * deliberately rather than discover it.
 */
public class ImportResultBranchTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    /**
     * A refused block still releases the children waiting on it, and their children after them —
     * the inner switch in {@code syncPopBlock} has to agree with the outer one in
     * {@code releaseWaiters}, or a grandchild is stranded by the child's own refusal.
     */
    @Test
    public void aPolicyRefusalReleasesEveryWaiterBehindIt() {
        Fixture f = new Fixture(ImportResult.CHAIN_FEE_POLICY);
        Block parent = f.block(1);
        Block child = f.block(2);
        Block grandchild = f.block(3);
        f.waitingOn(parent, child);
        f.waitingOn(child, grandchild);
        f.stats.nwaitsync = 2;

        f.syncManager.validateAndAddNewBlock(f.wrap(parent));

        assertFalse("the children waiting on a refused block must be released",
                f.syncMap.containsKey(hashLow(parent)));
        assertFalse("and so must the ones waiting on them",
                f.syncMap.containsKey(hashLow(child)));
        assertEquals("nwaitsync must follow the queues that were really drained",
                0, f.stats.nwaitsync);
        verify(f.blockchain, times(3)).tryToConnect(any(PreValidated.class));
    }

    /**
     * The contrast, and the measurement the design argues from: the same shape answered with
     * {@code INVALID_BLOCK} leaves the whole subtree parked. A node that said "I am full" with that
     * code would strand every child of every block it refused.
     */
    @Test
    public void anInvalidBlockStrandsTheSameWaiters() {
        Fixture f = new Fixture(ImportResult.INVALID_BLOCK);
        Block parent = f.block(4);
        Block child = f.block(5);
        f.waitingOn(parent, child);
        f.stats.nwaitsync = 1;

        f.syncManager.validateAndAddNewBlock(f.wrap(parent));

        assertTrue("INVALID_BLOCK pops nothing; this is the cost the honest code exists to avoid",
                f.syncMap.containsKey(hashLow(parent)));
        assertEquals(1, f.stats.nwaitsync);
        verify(f.blockchain, times(1)).tryToConnect(any(PreValidated.class));
    }

    /**
     * Nothing punitive, and nothing relayed. Every path that could reach a peer — the gossip in
     * {@code relayImported} and the re-request in the {@code NO_PARENT} arm — goes through the
     * channel manager, so leaving it untouched is the whole statement: the sender is not
     * disconnected, not scored, not asked for anything, and the block this node chose not to want
     * is not passed on to anybody else.
     */
    @Test
    public void aPolicyRefusalTouchesNoPeer() {
        Fixture f = new Fixture(ImportResult.CHAIN_FEE_POLICY);
        Block parent = f.block(6);
        f.waitingOn(parent, f.block(7));
        f.stats.nwaitsync = 1;

        f.syncManager.validateAndAddNewBlock(f.wrap(parent));

        verifyNoInteractions(f.channelMgr);
    }

    /** A refusal with nobody waiting on it is simply a no-op; the pop must not throw. */
    @Test
    public void aPolicyRefusalWithNoWaitersChangesNothing() {
        Fixture f = new Fixture(ImportResult.CHAIN_FEE_POLICY);
        Block lonely = f.block(8);

        assertEquals(ImportResult.CHAIN_FEE_POLICY,
                f.syncManager.validateAndAddNewBlock(f.wrap(lonely)));

        assertTrue(f.syncMap.isEmpty());
        assertEquals(0, f.stats.nwaitsync);
    }

    // ---- fixture -------------------------------------------------------------------------

    private static Bytes32 hashLow(Block b) {
        return Bytes32.wrap(b.getHashLow().toArray());
    }

    /**
     * A {@link SyncManager} with nothing running — the constructor starts no threads and nothing
     * here calls {@code start()} — over a chain that answers every import with one fixed verdict.
     * Only {@code getXdagStats()} has to be real, because the pop's bookkeeping writes through it.
     */
    private final class Fixture {

        final Blockchain blockchain = mock(Blockchain.class);
        final ChannelManager channelMgr = mock(ChannelManager.class);
        final XdagStats stats = new XdagStats();
        final ConcurrentHashMap<Bytes32, Queue<BlockWrapper>> syncMap = new ConcurrentHashMap<>();
        final SyncManager syncManager;

        Fixture(ImportResult verdict) {
            when(blockchain.getXdagStats()).thenReturn(stats);
            when(blockchain.tryToConnect(any(PreValidated.class))).thenReturn(verdict);
            Kernel kernel = mock(Kernel.class);
            when(kernel.getBlockchain()).thenReturn(blockchain);
            when(kernel.getChannelMgr()).thenReturn(channelMgr);
            when(kernel.getTxHistoryStore()).thenReturn(mock(TransactionHistoryStore.class));
            syncManager = new SyncManager(kernel);
            syncManager.setSyncMap(syncMap);
        }

        /** A real, parseable block; the import path re-serialises and re-parses what it is given. */
        Block block(int seed) {
            Block built = BlockBuilder.generateAddressBlock(config, key, 1600616700000L + seed);
            return new Block(new XdagBlock(built.toBytes()));
        }

        BlockWrapper wrap(Block block) {
            return new BlockWrapper(block, 0);
        }

        /** Parks {@code waiter} in {@code syncMap} under the block it is missing. */
        void waitingOn(Block missing, Block waiter) {
            Queue<BlockWrapper> queue = new ConcurrentLinkedQueue<>();
            queue.add(wrap(waiter));
            syncMap.put(hashLow(missing), queue);
        }
    }
}
