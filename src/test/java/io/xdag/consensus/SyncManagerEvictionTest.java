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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.core.BlockWrapper;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagStats;
import io.xdag.net.ChannelManager;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * Evicting from a full {@code syncMap} must not cost the import path anything like the map's size
 * per victim.
 *
 * <p>{@code syncPushBlock} used to rebuild the whole key set <em>inside</em> its eviction loop:
 * {@code DELETE_NUM} (5,000) fresh copies of a {@code MAX_SIZE} (500,000) key set, about 2.5 billion
 * reference copies and 5,000 multi-megabyte array allocations, to delete 5,000 entries. Since SP0b-2
 * that runs on the single ingest commit thread while holding the {@code SyncManager} monitor, so it
 * stalls every import on the node; and it is remotely reachable, because {@code syncMap} is exactly
 * where blocks whose parent is unknown pile up — a peer that sends enough of them drives the map to
 * {@code MAX_SIZE}.
 *
 * <p>The same five lines had two more problems, covered here too: {@code assert key != null} is a
 * no-op unless the JVM was started with {@code -ea}, and the per-iteration {@code keyList.size()}
 * fed {@code CryptoProvider.nextInt(0, 0)} — which throws {@code IllegalArgumentException("bound
 * must be greater than origin")} — whenever the concurrent map emptied under the loop.
 */
public class SyncManagerEvictionTest {

    /**
     * How many entries the map really holds in the cost test. Well above {@code DELETE_NUM} so the
     * map cannot empty mid-eviction (that is the other tests' subject), and small enough that the
     * pre-fix quadratic path still finishes in seconds rather than minutes — see {@link ProbeMap}
     * for why the real key count and the "am I full?" answer are allowed to disagree here.
     */
    private static final int HELD_KEYS = 20_000;

    /** The parent hash the pushed block is waiting on; never one of the {@link #key} fill keys. */
    private static final Bytes32 MISSING_PARENT = key(Integer.MAX_VALUE);

    /**
     * Loose backstop only. The real cost pin is {@link ProbeMap#keySetCalls()}: at this test's scale
     * the pre-fix path is merely slow (about 87 million reference copies), not slow enough for a
     * wall clock to tell it apart from the fixed one without a bound so tight it would flake on a
     * loaded CI box. The counter separates them 5000-to-1 and does not care how busy the machine is.
     */
    private static final long GENEROUS_BUDGET_MS = 10_000;

    /**
     * A {@code syncMap} that reports itself full and counts how many times its key set is
     * materialised.
     *
     * <p>The size is a floor, not the truth: the eviction branch is guarded by
     * {@code syncMap.size() >= MAX_SIZE}, and really filling 500,000 entries would cost this test
     * ~100MB and make the pre-fix run take minutes instead of failing fast. Nothing is distorted by
     * the lie — {@code size()} is read once, to decide whether to evict at all, and the eviction
     * itself works off {@code keySet()}, which reports only the entries actually present.
     */
    private static final class ProbeMap extends ConcurrentHashMap<Bytes32, Queue<BlockWrapper>> {

        private static final long serialVersionUID = 1L;

        private final AtomicInteger keySetCalls = new AtomicInteger();
        private final int fullnessFloor;

        ProbeMap(int fullnessFloor) {
            this.fullnessFloor = fullnessFloor;
        }

        @Override
        public ConcurrentHashMap.KeySetView<Bytes32, Queue<BlockWrapper>> keySet() {
            keySetCalls.incrementAndGet();
            return super.keySet();
        }

        @Override
        public int size() {
            return Math.max(super.size(), fullnessFloor);
        }

        int keySetCalls() {
            return keySetCalls.get();
        }

        /** The entry count {@link #size()} is masking. */
        int entriesHeld() {
            return super.size();
        }
    }

    /** A 32-byte key that is a pure function of {@code i}, so fills never collide. */
    private static Bytes32 key(int i) {
        byte[] raw = new byte[32];
        raw[28] = (byte) (i >>> 24);
        raw[29] = (byte) (i >>> 16);
        raw[30] = (byte) (i >>> 8);
        raw[31] = (byte) i;
        return Bytes32.wrap(raw);
    }

    /**
     * A {@code SyncManager} with nothing running: the constructor starts no threads, and nothing
     * below calls {@code start()}. Only {@code blockchain.getXdagStats()} has to be real, because
     * the eviction's bookkeeping writes through it.
     */
    private static SyncManager syncManager(XdagStats stats) {
        Kernel kernel = mock(Kernel.class);
        Blockchain blockchain = mock(Blockchain.class);
        when(blockchain.getXdagStats()).thenReturn(stats);
        when(kernel.getBlockchain()).thenReturn(blockchain);
        when(kernel.getChannelMgr()).thenReturn(mock(ChannelManager.class));
        return new SyncManager(kernel);
    }

    /**
     * The block being pushed. {@code syncPushBlock} only stamps a time on it; the merge function
     * that would read the block itself runs only when the parent hash is already a key, and
     * {@link #MISSING_PARENT} never is.
     */
    private static BlockWrapper pushedBlock() {
        return mock(BlockWrapper.class);
    }

    private static ProbeMap fullMapHolding(int entries) {
        ProbeMap map = new ProbeMap(SyncManager.MAX_SIZE);
        for (int i = 0; i < entries; i++) {
            map.put(key(i), new ConcurrentLinkedQueue<>());
        }
        return map;
    }

    /**
     * The defect itself, pinned by a deterministic counter rather than a stopwatch: one eviction
     * materialises the key set once, not once per victim.
     *
     * <p>Also pins the quota. Victims are now drawn without replacement, so all {@code DELETE_NUM}
     * removals land; the old draw-with-replacement kept re-drawing keys it had already removed and
     * under-deleted (about 4,412 of 5,000 at this test's 20,000 entries, ~4,975 of 5,000 at the
     * production {@code MAX_SIZE}). {@code nwaitsync} tracks the removals exactly either way,
     * because it is only decremented when {@code remove} really took an entry out.
     */
    @Test
    public void evictionMaterialisesTheKeySetOnceAndDeletesTheWholeQuota() {
        ProbeMap map = fullMapHolding(HELD_KEYS);
        XdagStats stats = new XdagStats();
        stats.nwaitsync = HELD_KEYS;
        SyncManager syncManager = syncManager(stats);
        syncManager.setSyncMap(map);

        long startedAt = System.nanoTime();
        syncManager.syncPushBlock(pushedBlock(), MISSING_PARENT);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("one eviction must snapshot the key set once, not once per victim",
                1, map.keySetCalls());

        // DELETE_NUM distinct entries gone, then the pushed block's own entry added.
        int expected = HELD_KEYS - SyncManager.DELETE_NUM + 1;
        assertEquals("eviction must remove exactly DELETE_NUM distinct entries",
                expected, map.entriesHeld());
        assertEquals("nwaitsync must follow the entries that were actually removed",
                expected, stats.nwaitsync);

        assertTrue("eviction took " + tookMs + "ms, budget " + GENEROUS_BUDGET_MS + "ms",
                tookMs < GENEROUS_BUDGET_MS);
    }

    /**
     * {@code syncMap} is concurrent and {@link SyncManager#syncPopBlock} empties it from elsewhere,
     * so the map can be gone by the time the eviction looks at it. That must not throw out of the
     * import path: the pre-fix loop ran {@code DELETE_NUM} times regardless and handed the zero-wide
     * range to {@code CryptoProvider.nextInt(0, 0)} on the first iteration.
     */
    @Test
    public void evictionOnAMapThatEmptiedUnderItDoesNotThrow() {
        ProbeMap map = fullMapHolding(0);
        XdagStats stats = new XdagStats();
        SyncManager syncManager = syncManager(stats);
        syncManager.setSyncMap(map);

        assertTrue("a first push for this parent must ask for it",
                syncManager.syncPushBlock(pushedBlock(), MISSING_PARENT));

        assertEquals("nothing to evict, then the pushed block", 1, map.entriesHeld());
        assertEquals(1, stats.nwaitsync);
    }

    /**
     * The same race one step later: the map still has entries, but fewer than the quota. The
     * eviction must take what is there and stop — the pre-fix loop drained those three and then hit
     * the empty range on its fourth iteration.
     */
    @Test
    public void evictionStopsWhenTheMapHoldsFewerEntriesThanTheQuota() {
        int held = 3;
        ProbeMap map = fullMapHolding(held);
        XdagStats stats = new XdagStats();
        stats.nwaitsync = held;
        SyncManager syncManager = syncManager(stats);
        syncManager.setSyncMap(map);

        assertTrue(syncManager.syncPushBlock(pushedBlock(), MISSING_PARENT));

        assertEquals("all three evicted, then the pushed block", 1, map.entriesHeld());
        assertEquals("nwaitsync must not be decremented past what was removed", 1, stats.nwaitsync);
        assertEquals(1, map.keySetCalls());
    }
}
