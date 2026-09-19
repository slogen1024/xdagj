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

import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.utils.BytesUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;

@Slf4j
public class AddressStoreImpl implements AddressStore {
    private static final int ADDRESS_SIZE = 20; // Corrected constant name to uppercase

    /** Stripe count for both lock tiers below. A power of two, so the index is a mask. */
    private static final int LOCK_STRIPES = 1024;

    private final KVSource<byte[], byte[]> addressSource; // Renamed for clarity

    /**
     * Tier 1 - the nonce RESERVATION locks. A submit path holds one of these across its whole
     * "read the counter, issue counter + 1, import, write the counter back" sequence, i.e. across
     * a full chain import, which is what stops two submits from one address being issued the same
     * nonce. Because the import inside takes the SyncManager and blockchain monitors, this tier is
     * ordered BEFORE those monitors and is never taken by a thread already holding them - see
     * {@link AddressStore#withNonceReservation(Collection, Supplier)}.
     */
    private final ReentrantLock[] reservationLocks = new ReentrantLock[LOCK_STRIPES];

    /**
     * Tier 2 - the per-address VALUE locks, which make one read-modify-write of an address' two
     * nonce counters atomic. A leaf: the only work done under one is a get/put on the ADDRESS
     * column family, which is a plain RocksDB handle (the write-behind layer wraps INDEX, BLOCK,
     * TIME and ORPHANIND, never ADDRESS), so nothing can be acquired beneath it. That is what lets
     * the consensus threads take it while holding the blockchain monitor without inverting tier 1.
     */
    private final Object[] valueLocks = new Object[LOCK_STRIPES];

    // Constructor to initialize address source
    public AddressStoreImpl(KVSource<byte[], byte[]> addressSource) {
        this.addressSource = addressSource;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            reservationLocks[i] = new ReentrantLock();
            valueLocks[i] = new Object();
        }
    }

    /** Stripe index for an address; masking keeps it in range and non-negative for any hash. */
    private static int stripeOf(byte[] address) {
        int h = Arrays.hashCode(address);
        return (h ^ (h >>> 16)) & (LOCK_STRIPES - 1);
    }

    private Object valueLock(byte[] address) {
        return valueLocks[stripeOf(address)];
    }

    public void start() {
        this.addressSource.init();
        if (addressSource.get(new byte[]{ADDRESS_SIZE}) == null) {
            addressSource.put(new byte[]{ADDRESS_SIZE}, BytesUtils.longToBytes(0, false));
        }
        if (addressSource.get(new byte[]{AMOUNT_SUM}) == null) {
            addressSource.put(new byte[]{AMOUNT_SUM}, BytesUtils.longToBytes(0, false));
        }
    }

    @Override
    public void stop() {
        addressSource.close();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void reset() {
        this.addressSource.reset();
        addressSource.put(new byte[]{ADDRESS_SIZE}, BytesUtils.longToBytes(0, false));
        addressSource.put(new byte[]{AMOUNT_SUM}, BytesUtils.longToBytes(0, false));
    }

    public XAmount getBalanceByAddress(byte[] address) {
        byte[] data = addressSource.get(BytesUtils.merge(ADDRESS, address));
        if (data == null) {
            log.debug("This public key doesn't exist");
            return XAmount.ZERO;
        } else {
            return XAmount.ofXAmount(UInt64.fromBytes(Bytes.wrap(data)).toLong());
        }
    }

    public boolean addressIsExist(byte[] address) {
        return addressSource.get(BytesUtils.merge(ADDRESS, address)) != null;
    }

    public void addAddress(byte[] address) {
        addressSource.put(BytesUtils.merge(ADDRESS, address), UInt64.ZERO.toBytes().toArray());
        long currentSize = BytesUtils.bytesToLong(addressSource.get(new byte[]{ADDRESS_SIZE}), 0, false);
        addressSource.put(new byte[]{ADDRESS_SIZE}, BytesUtils.longToBytes(currentSize + 1, false));
    }

    public XAmount getAllBalance() {
        UInt64 u64v = UInt64.fromBytes(Bytes.wrap(addressSource.get(new byte[]{AMOUNT_SUM})));
        return XAmount.ofXAmount(u64v.toLong());
    }

    @Override
    public void saveAddressSize(byte[] addressSize) {
        addressSource.put(new byte[]{ADDRESS_SIZE}, addressSize);
    }

    @Override
    public void saveAmountSum(XAmount balanceSum) { // Fixed typo in method name
        UInt64 u64v = balanceSum.toXAmount();
        addressSource.put(new byte[]{AMOUNT_SUM}, u64v.toBytes().toArray());
    }

    public UInt64 getAddressSize() {
        return UInt64.fromBytes(Bytes.wrap(addressSource.get(new byte[]{ADDRESS_SIZE})));
    }

    public void updateAllBalance(XAmount balance) {
        UInt64 u64V = balance.toXAmount();
        addressSource.put(new byte[]{AMOUNT_SUM}, u64V.toBytes().toArray());
    }

    // TODO: Move calculation to application layer
    public void updateBalance(byte[] address, XAmount balance) {
        if (address.length != ADDRESS_SIZE) {
            log.debug("The address type is wrong");
            return;
        }
        if (addressSource.get(BytesUtils.merge(ADDRESS, address)) == null) {
            log.debug("This address doesn't exist");
            addAddress(address);
        }
        UInt64 u64V = balance.toXAmount();
        addressSource.put(BytesUtils.merge(ADDRESS, address), u64V.toBytes().toArray());
    }

    @Override
    public void snapshotAddress(byte[] address, XAmount balance) {
        UInt64 u64V = balance.toXAmount();
        addressSource.put(address, u64V.toBytes().toArray());
    }

    @Override
    public void snapshotTxQuantity(byte[] address, UInt64 txQuantity) {
        addressSource.put(address, txQuantity.toBytes().toArray());
    }

    @Override
    public void snapshotExeTxNonceNum(byte[] address, UInt64 exeTxNonceNum) {
        addressSource.put(address, exeTxNonceNum.toBytes().toArray());
    }

    @Override
    public UInt64 getTxQuantity(byte[] address) {
        byte[] key = BytesUtils.merge(CURRENT_TRANSACTION_QUANTITY, address);
        byte[] txQuantity = addressSource.get(key);

        if (txQuantity == null) {
            return UInt64.ZERO;
        } else {
            return UInt64.fromBytes(Bytes.wrap(txQuantity));
        }
    }

    @Override
    public void updateTxQuantity(byte[] address, UInt64 newTxQuantity) {
        raiseTxQuantity(address, newTxQuantity);
    }

    @Override
    public void updateTxQuantity(byte[] address, UInt64 currentTxNonce, UInt64 currentExeNonce) {
        raiseTxQuantity(address,
                currentTxNonce.compareTo(currentExeNonce) >= 0 ? currentTxNonce : currentExeNonce);
    }

    /**
     * The monotonic half of the counter: compare against what is stored and write only if the
     * candidate is strictly higher, all under the address' value lock. Both the read and the write
     * have to be inside it - the commit path's "read the issued counter, read the executed counter,
     * store the larger" used to be three separate store calls, and a submit's raise landing between
     * its reads and its write was silently clobbered, dropping the counter below a nonce that was
     * already in flight and out on the wire.
     */
    private void raiseTxQuantity(byte[] address, UInt64 candidate) {
        byte[] key = BytesUtils.merge(CURRENT_TRANSACTION_QUANTITY, address);
        synchronized (valueLock(address)) {
            byte[] stored = addressSource.get(key);
            UInt64 current = stored == null ? UInt64.ZERO : UInt64.fromBytes(Bytes.wrap(stored));
            if (candidate.compareTo(current) <= 0) {
                return;
            }
            addressSource.put(key, candidate.toBytes().toArray());
        }
    }

    @Override
    public void resetTxQuantity(byte[] address, UInt64 newTxQuantity) {
        byte[] key = BytesUtils.merge(CURRENT_TRANSACTION_QUANTITY, address);
        synchronized (valueLock(address)) {
            addressSource.put(key, newTxQuantity.toBytes().toArray());
        }
    }

    @Override
    public <T> T withNonceReservation(byte[] address, Supplier<T> action) {
        return withNonceReservation(List.of(address), action);
    }

    @Override
    public <T> T withNonceReservation(Collection<byte[]> addresses, Supplier<T> action) {
        // Sorted STRIPE indices, not sorted addresses: the canonical order has to be a total order
        // on the locks themselves, or two callers whose address sets map onto the same pair of
        // stripes in opposite orders could still deadlock.
        int[] stripes = addresses.stream().mapToInt(AddressStoreImpl::stripeOf).distinct().sorted().toArray();
        int held = 0;
        try {
            while (held < stripes.length) {
                reservationLocks[stripes[held]].lock();
                held++;
            }
            return action.get();
        } finally {
            for (int i = held - 1; i >= 0; i--) {
                reservationLocks[stripes[i]].unlock();
            }
        }
    }

    /**
     * Note that a miss still materialises the row as zero, which is not just a read: the snapshot
     * writer finds an address' nonce state by scanning for {@code EXECUTED_NONCE_NUM} keys, so
     * dropping the write would quietly change which addresses a snapshot carries. It is done under
     * the value lock instead, which is what makes it safe - the racy pair was never two of these
     * (they both write zero) but this one against {@link #updateExcutedNonceNum}, whose own
     * read-modify-write could otherwise have its increment overwritten by a late zero from here.
     */
    @Override
    public UInt64 getExecutedNonceNum(byte[] address) {
        byte[] key = BytesUtils.merge(EXECUTED_NONCE_NUM, address);
        synchronized (valueLock(address)) {
            byte[] processedTxNonce = addressSource.get(key);
            if (processedTxNonce == null) {
                addressSource.put(key, UInt64.ZERO.toBytes().toArray());
                return UInt64.ZERO;
            } else {
                return UInt64.fromBytes(Bytes.wrap(processedTxNonce));
            }
        }
    }

    @Override
    public void updateExcutedNonceNum(byte[] address, boolean addOrSubstract) {
        byte[] key = BytesUtils.merge(EXECUTED_NONCE_NUM, address);
        synchronized (valueLock(address)) {
            UInt64 before = getExecutedNonceNum(address);
            UInt64 now;
            if (addOrSubstract) {
                now = before.add(UInt64.ONE);
            } else {
                if (before.compareTo(UInt64.ZERO) == 0) {
                    now = UInt64.ZERO;
                } else {
                    now = before.subtract(UInt64.ONE);
                }
            }
            addressSource.put(key, now.toBytes().toArray());
        }
    }
}
