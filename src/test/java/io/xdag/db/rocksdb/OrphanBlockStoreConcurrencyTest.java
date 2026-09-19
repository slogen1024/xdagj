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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.XAmount;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.AddressStore;
import io.xdag.utils.BytesUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * D1: {@code getOrphan}/{@code selectBlocks} used to run on the PoW main thread and the check-main
 * thread with no lock at all, while every mutator of the same in-memory queues
 * ({@code addOrphan}, {@code deleteFromQueue}, {@code cleanExpiredOrphans}) held the blockchain
 * monitor. These tests pin the lock rather than the symptom: the symptom (a {@code peek()} that
 * returns null right after {@code isEmpty()} said otherwise, or a {@code poll()} that drops an
 * entry nobody selected) only shows up on a lost race.
 */
public class OrphanBlockStoreConcurrencyTest {

    private InMemoryKVSource source;
    private Blockchain blockchain;
    private OrphanBlockStoreImpl store;

    @Before
    public void setUp() {
        source = new InMemoryKVSource();
        source.init();
        source.put(OrphanBlockStore.ORPHAN_SIZE, BytesUtils.longToBytes(0, false));

        blockchain = mock(Blockchain.class);
        AddressStore addressStore = mock(AddressStore.class);
        when(addressStore.getExecutedNonceNum(org.mockito.ArgumentMatchers.any())).thenReturn(UInt64.ZERO);
        Kernel kernel = mock(Kernel.class);
        when(kernel.getBlockchain()).thenReturn(blockchain);
        when(kernel.getAddressStore()).thenReturn(addressStore);

        // start() is deliberately not called: it would also schedule the expiry cleaner, whose
        // ticks take the very monitor these tests hold on purpose.
        store = new OrphanBlockStoreImpl(source, kernel);
    }

    /**
     * The deterministic pin. While the blockchain monitor is held, {@code getOrphan} must not be
     * able to run at all — with no lock it completes in microseconds.
     */
    @Test(timeout = 30_000)
    public void getOrphanRunsUnderTheBlockchainMonitor() throws Exception {
        store.addOrphan(linkBlock(1), false, UInt64.ZERO, XAmount.ZERO, null);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            started.countDown();
            store.getOrphan(16, new long[]{Long.MAX_VALUE, 0}, false);
            finished.countDown();
        }, "orphan-reader");

        synchronized (blockchain) {
            reader.start();
            assertTrue("the reader thread never started", started.await(10, TimeUnit.SECONDS));
            assertFalse("getOrphan ran while the blockchain monitor was held — it takes no lock",
                    finished.await(500, TimeUnit.MILLISECONDS));
        }

        assertTrue("getOrphan never completed after the blockchain monitor was released",
                finished.await(10, TimeUnit.SECONDS));
        reader.join();
    }

    /**
     * The stress pin: one thread mutates the per-account transaction queues exactly as the import
     * path does (under the monitor), another selects blocks out of them. Nothing may throw, and
     * the store must be left empty — the mutator deletes every block it adds, and the selection
     * path removes exactly what it selected.
     *
     * <p>Without the lock this trips the site that has no null check at all: {@code selectBlocks}
     * walks {@code accountTxMap} and offers {@code entry.getValue().peek()} as a candidate, while
     * {@code deleteFromQueue} empties an account's queue several statements before it drops the map
     * entry. A candidate built on that null reaches {@code getKeyFromMeta} and throws.
     */
    @Test(timeout = 120_000)
    public void getOrphanSurvivesConcurrentAddAndRemove() throws Exception {
        final int rounds = 4000;
        final int accounts = 16;
        List<Throwable> failures = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Thread mutator = new Thread(() -> {
            try {
                for (int round = 0; round < rounds; round++) {
                    Block[] blocks = new Block[accounts];
                    synchronized (blockchain) {
                        for (int i = 0; i < accounts; i++) {
                            blocks[i] = linkBlock(round * (long) accounts + i + 1L);
                            store.addOrphan(blocks[i], true, UInt64.ONE, XAmount.ZERO, address(i));
                        }
                    }
                    synchronized (blockchain) {
                        for (int i = 0; i < accounts; i++) {
                            store.deleteFromQueue(blocks[i], true, UInt64.ONE, XAmount.ZERO, address(i));
                            store.deleteByKey(blocks[i].getHashLow().toArray(), true, UInt64.ONE,
                                    XAmount.ZERO, address(i));
                        }
                    }
                }
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            } finally {
                done.set(true);
            }
        }, "orphan-mutator");

        Thread reader = new Thread(() -> {
            try {
                while (!done.get()) {
                    store.getOrphan(16, new long[]{Long.MAX_VALUE, 0}, false);
                }
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            }
        }, "orphan-reader");

        mutator.start();
        reader.start();
        mutator.join();
        reader.join();

        synchronized (failures) {
            if (!failures.isEmpty()) {
                Throwable first = failures.getFirst();
                StringWriter trace = new StringWriter();
                first.printStackTrace(new PrintWriter(trace));
                fail("selectBlocks raced the import path: " + trace);
            }
        }
        // Whatever the interleaving was, the store must be left consistent.
        assertEquals("the orphan store leaked entries", 0, store.getOrphanSize());
    }

    /**
     * Under the lock, selection is unchanged: the link queue is drained oldest-first and every
     * selected block is removed from the pool exactly once.
     */
    @Test
    public void selectionPolicyIsUnchanged() {
        for (long i = 1; i <= 5; i++) {
            store.addOrphan(linkBlock(i), false, UInt64.ZERO, XAmount.ZERO, null);
        }
        assertEquals(5, store.getOrphanSize());

        long[] sendtime = new long[]{Long.MAX_VALUE, 0};
        List<io.xdag.core.Address> selected = store.getOrphan(3, sendtime, false);

        assertEquals(3, selected.size());
        assertEquals(2, store.getOrphanSize());
        // oldest first: linkQueue orders on time, and linkBlock(i) uses time == i
        assertEquals(hashLow(1).toHexString(), selected.get(0).getAddress().toHexString());
        assertEquals(hashLow(2).toHexString(), selected.get(1).getAddress().toHexString());
        assertEquals(hashLow(3).toHexString(), selected.get(2).getAddress().toHexString());
    }

    private static Block linkBlock(long id) {
        Block block = mock(Block.class);
        when(block.getHashLow()).thenReturn(hashLow(id));
        when(block.getTimestamp()).thenReturn(id);
        return block;
    }

    private static byte[] address(int index) {
        byte[] address = new byte[20];
        address[0] = (byte) (index + 1);
        return address;
    }

    private static MutableBytes32 hashLow(long id) {
        MutableBytes32 hash = MutableBytes32.create();
        hash.set(24, org.apache.tuweni.bytes.Bytes.wrap(BytesUtils.longToBytes(id, false)));
        return hash;
    }
}
