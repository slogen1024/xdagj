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

import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.AddressStore;
import io.xdag.utils.BytesUtils;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Nonce reservation on the real {@link AddressStoreImpl} over a temporary RocksDB.
 *
 * <p>Two counters per address drive transaction acceptance: {@code CURRENT_TRANSACTION_QUANTITY}
 * (the highest nonce issued) and {@code EXECUTED_NONCE_NUM} (how many of the address' transactions
 * have executed). Every submit path is a read-modify-write of the first one that spans a whole
 * chain import, and it used to run with no lock at all on an arbitrary RPC or telnet thread. These
 * tests pin the two races that came out of that, plus the rollback that must keep working after
 * the raise was made monotonic.
 */
public class AddressStoreNonceTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private DatabaseFactory dbFactory;
    private GatedSource addressSource;
    private AddressStore store;

    private final byte[] addr = BytesUtils.hexStringToBytes("fb3fb15072826ffa5f5b6c123029798a27cd0c64");

    @Before
    public void setUp() throws Exception {
        AbstractConfig paths = (AbstractConfig) config;
        paths.setRootDir(root.newFolder("node").getAbsolutePath());
        paths.setDir();
        dbFactory = new RocksdbFactory(config);
        addressSource = new GatedSource(dbFactory.getDB(DatabaseName.ADDRESS));
        store = new AddressStoreImpl(addressSource);
        store.reset();
    }

    @After
    public void tearDown() {
        if (dbFactory != null) {
            dbFactory.close();
        }
    }

    /**
     * Race A. Two submits from one address, both inside their reservation, both doing what every
     * submit path does: read the counter, issue {@code counter + 1}, import, write the counter
     * back. They must not be handed the same nonce - only one of two blocks carrying the same
     * nonce can ever execute, and the other dies at main time long after its caller was given a
     * hash and the block was gossiped to the network.
     *
     * <p>Unlocked, the second thread reads the counter while the first is still "importing" and
     * both issue 1.
     */
    @Test(timeout = 30_000)
    public void twoConcurrentSubmitsAreNeverIssuedTheSameNonce() throws Exception {
        UInt64[] issued = new UInt64[2];
        CountDownLatch firstHasRead = new CountDownLatch(1);
        CountDownLatch secondAtTheDoor = new CountDownLatch(1);

        Thread first = new Thread(() -> issued[0] = store.withNonceReservation(addr, () -> {
            UInt64 nonce = store.getTxQuantity(addr).add(UInt64.ONE);
            firstHasRead.countDown();
            // The other submit has reached the reservation, and then some: it has had every
            // chance to read the counter before this one writes it back. That window is the
            // chain import in production.
            awaitQuietly(secondAtTheDoor);
            sleepQuietly(300);
            store.updateTxQuantity(addr, nonce);
            return nonce;
        }), "submit-1");

        Thread second = new Thread(() -> {
            secondAtTheDoor.countDown();
            issued[1] = store.withNonceReservation(addr, () -> {
                UInt64 nonce = store.getTxQuantity(addr).add(UInt64.ONE);
                store.updateTxQuantity(addr, nonce);
                return nonce;
            });
        }, "submit-2");

        first.start();
        // Which submit gets there first is not the point; that the second one cannot read the
        // counter until the first has written it back is. So let the first one in, deterministically.
        awaitQuietly(firstHasRead);
        second.start();
        first.join();
        second.join();

        assertNotEquals("both submits from one address were issued the same nonce",
                issued[0], issued[1]);
        assertEquals(UInt64.ONE, issued[0]);
        assertEquals(UInt64.valueOf(2), issued[1]);
        assertEquals(UInt64.valueOf(2), store.getTxQuantity(addr));
    }

    /**
     * Race B, as a deterministic interleaving rather than a thread schedule. The commit path's
     * {@code processNonceAfterTransactionExecution} is a get-get-put: it reads the issued counter,
     * reads the executed counter and stores the larger. A submit's write-back landing between
     * those reads and that store used to be clobbered, dropping the counter back below a nonce
     * that was already in flight and out on the wire - and the next submit then reissues it, which
     * is race A again.
     */
    @Test
    public void aStaleCommitPathWriteCannotLowerTheCounter() {
        // Commit path, verbatim from processNonceAfterTransactionExecution.
        store.updateExcutedNonceNum(addr, true);
        UInt64 currentTxNonce = store.getTxQuantity(addr);
        UInt64 currentExeNonce = store.getExecutedNonceNum(addr);
        assertEquals(UInt64.ZERO, currentTxNonce);
        assertEquals(UInt64.ONE, currentExeNonce);

        // A submit on another thread reserves nonce 7 right here, between the reads and the store.
        store.updateTxQuantity(addr, UInt64.valueOf(7));
        assertEquals(UInt64.valueOf(7), store.getTxQuantity(addr));

        // Back on the commit thread, which is now holding two stale reads.
        store.updateTxQuantity(addr, currentTxNonce, currentExeNonce);

        assertEquals("a stale commit-path write lowered the issued-nonce counter",
                UInt64.valueOf(7), store.getTxQuantity(addr));
    }

    /** The plain raise is monotonic on its own, whoever calls it and in whatever order. */
    @Test
    public void updateTxQuantityOnlyEverRaises() {
        store.updateTxQuantity(addr, UInt64.valueOf(5));
        assertEquals(UInt64.valueOf(5), store.getTxQuantity(addr));

        store.updateTxQuantity(addr, UInt64.valueOf(3));
        assertEquals(UInt64.valueOf(5), store.getTxQuantity(addr));

        store.updateTxQuantity(addr, UInt64.valueOf(5));
        assertEquals(UInt64.valueOf(5), store.getTxQuantity(addr));

        store.updateTxQuantity(addr, UInt64.valueOf(6));
        assertEquals(UInt64.valueOf(6), store.getTxQuantity(addr));
    }

    /**
     * The counterpart, and the reason the two intents had to stop sharing a method name: the
     * consensus rollback has to keep being able to LOWER the counter, or an address whose counter
     * ran ahead of what executed would never heal.
     */
    @Test
    public void resetTxQuantityStillLowersTheCounter() {
        store.updateTxQuantity(addr, UInt64.valueOf(9));
        assertEquals(UInt64.valueOf(9), store.getTxQuantity(addr));

        store.resetTxQuantity(addr, UInt64.valueOf(4));
        assertEquals("the consensus rollback must still be able to lower the counter",
                UInt64.valueOf(4), store.getTxQuantity(addr));

        store.resetTxQuantity(addr, UInt64.ZERO);
        assertEquals(UInt64.ZERO, store.getTxQuantity(addr));
    }

    /**
     * {@code getExecutedNonceNum} materialises a missing row as zero, so it is a writer as well as
     * a reader, and it is called from query-shaped sites on arbitrary threads. Two of those racing
     * each other is harmless (they both write zero); one of them racing the commit path's
     * {@code updateExcutedNonceNum} is not - a zero from a reader landing after the increment
     * silently un-executes a transaction.
     *
     * <p>Driven here by gating the reader inside its own {@code get}: with the write behind the
     * same per-address lock the incrementer cannot get in while the reader is there, the gate
     * times out, and the increment lands last.
     */
    @Test(timeout = 30_000)
    public void aReadersZeroFillCannotClobberTheExecutedCounter() throws Exception {
        byte[] exeKey = BytesUtils.merge(AddressStore.EXECUTED_NONCE_NUM, addr);
        CountDownLatch incrementDone = new CountDownLatch(1);
        addressSource.gateOn(exeKey, incrementDone, 500);

        Thread reader = new Thread(() -> store.getExecutedNonceNum(addr), "nonce-reader");
        reader.start();
        addressSource.awaitGateEntered();

        store.updateExcutedNonceNum(addr, true);
        incrementDone.countDown();
        reader.join();

        assertEquals("a reader's zero-fill overwrote the executed-nonce increment",
                UInt64.ONE, store.getExecutedNonceNum(addr));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Passes everything through to the real database, but lets one {@code get} of one key park
     * inside the store method that issued it, so an interleaving that otherwise needs a lucky
     * schedule becomes deterministic. The park is bounded: under the fix the thread the test is
     * waiting for is itself blocked on the lock, so the gate has to time out rather than deadlock.
     */
    private static final class GatedSource implements KVSource<byte[], byte[]> {

        private final KVSource<byte[], byte[]> delegate;
        private volatile byte[] gateKey;
        private volatile CountDownLatch gate;
        private volatile long gateMillis;
        private final CountDownLatch entered = new CountDownLatch(1);

        private GatedSource(KVSource<byte[], byte[]> delegate) {
            this.delegate = delegate;
        }

        void gateOn(byte[] key, CountDownLatch release, long millis) {
            this.gateKey = key;
            this.gateMillis = millis;
            this.gate = release;
        }

        void awaitGateEntered() throws InterruptedException {
            entered.await(10, TimeUnit.SECONDS);
        }

        @Override
        public byte[] get(byte[] key) {
            byte[] value = delegate.get(key);
            CountDownLatch g = gate;
            if (g != null && Arrays.equals(key, gateKey)) {
                gate = null; // one shot
                entered.countDown();
                try {
                    g.await(gateMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return value;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public void setName(String name) {
            delegate.setName(name);
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public void init() {
            delegate.init();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public void put(byte[] key, byte[] val) {
            delegate.put(key, val);
        }

        @Override
        public void delete(byte[] key) {
            delegate.delete(key);
        }

        @Override
        public java.util.Set<byte[]> keys() {
            return delegate.keys();
        }

        @Override
        public List<byte[]> prefixKeyLookup(byte[] key) {
            return delegate.prefixKeyLookup(key);
        }

        @Override
        public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
            delegate.fetchPrefix(key, func);
        }

        @Override
        public List<byte[]> prefixValueLookup(byte[] key) {
            return delegate.prefixValueLookup(key);
        }

        @Override
        public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
            return delegate.prefixKeyAndValueLookup(key);
        }
    }
}
