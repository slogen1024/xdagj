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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.utils.BytesUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * SP0b-3 moved every in-memory orphan collection into {@link ChainOrphanPool} and, on the way,
 * replaced five concurrent implementations with plain {@link java.util.TreeSet}s and
 * {@link java.util.HashMap}s. That is only correct while the lock {@code 811deec0} established is
 * still the one lock: <b>blockchain monitor first, pool second, and never the other way round.</b>
 *
 * <p>{@code OrphanBlockStoreConcurrencyTest} is the {@code 811deec0} regression test and pins the
 * first half — {@code getOrphan} runs under the blockchain monitor. Its assertions are left exactly
 * as they are; what is borrowed here is its latch-interleaving technique, applied to the half it
 * does not cover: that <em>while a writer holds the monitor and is inside the pool</em>, a reader
 * is held out, and that there is no path anywhere that takes the pool first and asks for the
 * blockchain afterwards.
 *
 * <h2>Why the reverse path is worth an assertion when it looks impossible</h2>
 *
 * <p>It is impossible today for a structural reason rather than a careful one: the pool has no lock
 * of its own, so there is no second lock to take in the wrong order. That is a decision — the pool's
 * class header says so in as many words, and says not to "fix" it back — and a decision that is
 * only recorded in a comment is one lock annotation away from being undone. So
 * {@link #thePoolTakesNoLockOfItsOwnSoThereIsNoOrderToInvert} pins the structure and
 * {@link #aReaderIsHeldOutWhileAWriterIsInsideThePoolUnderTheMonitor} pins the behaviour; either
 * one alone would be half the statement.
 */
public class OrphanLockOrderTest {

    private Blockchain blockchain;
    private OrphanBlockStoreImpl store;

    /**
     * The fixture {@code OrphanBlockStoreConcurrencyTest} uses, for the same reason: a real
     * {@link DevnetConfig} so the pool is built with the caps the node ships with, and no
     * {@code start()} — that would schedule the expiry cleaner, whose ticks take the very monitor
     * these tests hold on purpose.
     */
    @Before
    public void setUp() {
        InMemoryKVSource source = new InMemoryKVSource();
        source.init();
        source.put(OrphanBlockStore.ORPHAN_SIZE, BytesUtils.longToBytes(0, false));

        blockchain = mock(Blockchain.class);
        AddressStore addressStore = mock(AddressStore.class);
        when(addressStore.getExecutedNonceNum(org.mockito.ArgumentMatchers.any())).thenReturn(UInt64.ZERO);
        Kernel kernel = mock(Kernel.class);
        when(kernel.getBlockchain()).thenReturn(blockchain);
        when(kernel.getAddressStore()).thenReturn(addressStore);
        when(kernel.getConfig()).thenReturn(new DevnetConfig());

        store = new OrphanBlockStoreImpl(source, kernel);
    }

    /**
     * A writer holds the blockchain monitor and is in the middle of mutating the pool; a reader
     * asks the pool for blocks to pack. The reader must not get in — the collections it would walk
     * are plain ones, so a read that overlapped the write would be reading a {@code TreeSet}
     * mid-rebalance.
     *
     * <p>The interleaving is deterministic rather than raced: the writer parks inside the monitor
     * on a latch, the reader is started and given ten seconds to get anywhere, and the assertion is
     * that it does not.
     *
     * <p><b>This is also the deadlock probe.</b> The writer takes blockchain &rarr; pool; the reader
     * takes pool-entry-point &rarr; blockchain (that is what {@code getOrphan} does, it synchronizes
     * on the blockchain itself). If any pool path ever acquired a lock of its own before asking for
     * the monitor, these two orders would close a cycle and this test would hang rather than fail —
     * which is why it carries a timeout.
     */
    @Test(timeout = 30_000)
    public void aReaderIsHeldOutWhileAWriterIsInsideThePoolUnderTheMonitor() throws Exception {
        // Two, and the reader below asks for one: with a single entry "the reader saw a consistent
        // pool" could not tell a correct selection from any selection at all.
        store.addOrphan(linkBlock(1), false, UInt64.ZERO, XAmount.ZERO, null);
        store.addOrphan(linkBlock(3), false, UInt64.ZERO, XAmount.ZERO, null);

        CountDownLatch writerIsInside = new CountDownLatch(1);
        CountDownLatch writerMayFinish = new CountDownLatch(1);
        CountDownLatch readerFinished = new CountDownLatch(1);
        List<Address> selectedByReader = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        Thread writer = new Thread(() -> {
            try {
                synchronized (blockchain) {
                    // Inside the monitor and inside the pool: the entry is added before the reader
                    // is even started, so what the reader would be racing is a pool that has just
                    // been written to rather than one that is quiescent.
                    store.addOrphan(linkBlock(2), false, UInt64.ZERO, XAmount.ZERO, null);
                    writerIsInside.countDown();
                    if (!writerMayFinish.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the writer was never released");
                    }
                    store.deleteFromQueue(linkBlock(2), false, UInt64.ZERO, XAmount.ZERO, null);
                }
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            }
        }, "orphan-writer");

        Thread reader = new Thread(() -> {
            try {
                selectedByReader.addAll(store.getOrphan(1, new long[]{Long.MAX_VALUE, 0}, false));
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            } finally {
                readerFinished.countDown();
            }
        }, "orphan-reader");

        writer.start();
        assertTrue("the writer never reached the pool", writerIsInside.await(10, TimeUnit.SECONDS));
        reader.start();
        assertFalse("a reader got into the pool while a writer held the blockchain monitor and was"
                        + " inside it -- the pool's collections are not concurrent any more",
                readerFinished.await(500, TimeUnit.MILLISECONDS));

        writerMayFinish.countDown();
        assertTrue("the reader never completed after the monitor was released",
                readerFinished.await(10, TimeUnit.SECONDS));
        writer.join();
        reader.join();

        synchronized (failures) {
            assertTrue("a thread failed: " + failures, failures.isEmpty());
        }
        // The writer added one entry and removed it again, so what the reader was finally let in to
        // see is the entry that was there before either of them started -- never the half-written
        // pool it was held out of, and never the entry the writer had already taken back out.
        assertEquals("the reader must have seen the pool the writer left, not the one it was"
                        + " holding open: " + selectedByReader, 1, selectedByReader.size());
        assertEquals("and must have taken the oldest of it, which is the packing order",
                hashLow(1).toHexString(), selectedByReader.get(0).getAddress().toHexString());
        assertEquals("and selection takes what it selected", 1, store.getOrphanSize());
    }

    /**
     * The structural half: there is no second lock, so there is no order to get wrong.
     *
     * <p>Reflection reaches declared modifiers and declared fields, which is where a second lock
     * would have to show up — a {@code synchronized} method, or a {@link Lock} somebody added to
     * make the pool "thread-safe on its own". It cannot see a {@code synchronized} block inside a
     * method body; that gap is what the interleaving test above is for, since a block that locked
     * the pool and then waited for the monitor would deadlock there.
     */
    @Test
    public void thePoolTakesNoLockOfItsOwnSoThereIsNoOrderToInvert() {
        for (Method method : ChainOrphanPool.class.getDeclaredMethods()) {
            assertFalse("ChainOrphanPool." + method.getName() + " is synchronized: the pool is"
                            + " guarded by the blockchain monitor its callers already hold, and a"
                            + " lock of its own is a second lock to get into the wrong order with"
                            + " it (see the class header -- this is a decision, not an oversight)",
                    Modifier.isSynchronized(method.getModifiers()));
        }
        for (Field field : ChainOrphanPool.class.getDeclaredFields()) {
            assertFalse("ChainOrphanPool." + field.getName() + " is a lock: see above",
                    Lock.class.isAssignableFrom(field.getType())
                            || ReadWriteLock.class.isAssignableFrom(field.getType()));
        }
    }

    /**
     * And the store's own side of the order: {@code getOrphan} asks for the monitor and everything
     * it does to the pool happens inside it. Pinned here on the outcome a caller can see — a
     * selection made while the monitor is held by somebody else cannot have happened — rather than
     * on the lock, which is what the interleaving above already covers.
     *
     * <p>The entry selected is removed from the pool by the selection itself, which is what makes
     * this a mutation under the monitor and not merely a read.
     */
    @Test(timeout = 30_000)
    public void selectionMutatesThePoolAndDoesSoUnderTheMonitor() throws Exception {
        store.addOrphan(linkBlock(3), false, UInt64.ZERO, XAmount.ZERO, null);
        assertEquals(1, store.getOrphanSize());

        List<Address> selected;
        synchronized (blockchain) {
            // Re-entrant: a caller already holding the monitor is unaffected, which is the shape
            // every production caller has (the import path is inside tryToConnect).
            selected = store.getOrphan(16, new long[]{Long.MAX_VALUE, 0}, false);
        }
        assertEquals(1, selected.size());
        assertEquals("selection takes the entry out of the pool", 0, store.getOrphanSize());
        assertNull("and out of the index with it",
                store.getPool().get(selected.get(0).getAddress()));
    }

    private static Block linkBlock(long id) {
        Block block = mock(Block.class);
        when(block.getHashLow()).thenReturn(hashLow(id));
        when(block.getTimestamp()).thenReturn(id);
        return block;
    }

    private static MutableBytes32 hashLow(long id) {
        MutableBytes32 hash = MutableBytes32.create();
        hash.set(24, org.apache.tuweni.bytes.Bytes.wrap(BytesUtils.longToBytes(id, false)));
        return hash;
    }
}
