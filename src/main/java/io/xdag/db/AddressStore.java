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
package io.xdag.db;

import io.xdag.core.XdagLifecycle;
import java.util.Collection;
import java.util.function.Supplier;
import org.apache.tuweni.units.bigints.UInt64;

import io.xdag.core.XAmount;

public interface AddressStore extends XdagLifecycle {

    byte ADDRESS_SIZE = (byte) 0x10;
    byte AMOUNT_SUM = (byte) 0x20;
    byte ADDRESS = (byte) 0x30;
    byte CURRENT_TRANSACTION_QUANTITY = (byte) 0x40;
    byte EXECUTED_NONCE_NUM = (byte) 0x50;

    void reset();

    XAmount getBalanceByAddress(byte[] Address);

    boolean addressIsExist(byte[] Address);

    void addAddress(byte[] Address);

    XAmount getAllBalance();

    void saveAddressSize(byte[] addressSize);

    void saveAmountSum(XAmount balanceSum);

    void updateAllBalance(XAmount balance);

    UInt64 getAddressSize();

    void updateBalance(byte[] address, XAmount balance);

    void snapshotAddress(byte[] address, XAmount balance);

    void snapshotTxQuantity(byte[] address, UInt64 txQuantity);

    void snapshotExeTxNonceNum(byte[] address, UInt64 exeTxNonceNum);

    UInt64 getTxQuantity(byte[] address);

    /**
     * Raises the "highest nonce issued" counter to {@code newTxQuantity}, atomically and
     * <b>monotonically</b>: a value at or below the stored one is a no-op, so a stale writer can
     * never drag the counter backwards over a nonce that is already in flight. Every caller that
     * hands out a nonce uses this; the only path allowed to lower the counter is
     * {@link #resetTxQuantity(byte[], UInt64)}.
     */
    void updateTxQuantity(byte[] address, UInt64 newTxQuantity);

    /**
     * The same monotonic raise, to the larger of the two candidates. Kept for the commit-path
     * caller that has just read both counters: the read-modify-write happens here, under the
     * address' value lock, so a submit's raise landing between that caller's reads and this call
     * survives instead of being clobbered.
     */
    void updateTxQuantity(byte[] address, UInt64 currentTxNonce, UInt64 currentExeNonce);

    /**
     * Forces the "highest nonce issued" counter to {@code newTxQuantity}, <b>lowering it if
     * needed</b>. This is the deliberate rollback the consensus paths perform when a block's nonce
     * has run ahead of what actually executed: it is what lets an address that was issued a nonce
     * it can no longer use heal itself, at the cost of that one transaction.
     *
     * <p>Consensus paths only, and only while the blockchain monitor is held. A submit path must
     * never call it: outside the monitor it is exactly the lost update
     * {@link #updateTxQuantity(byte[], UInt64)} exists to prevent.
     */
    void resetTxQuantity(byte[] address, UInt64 newTxQuantity);

    /**
     * Runs {@code action} holding the nonce-reservation lock for {@code address}.
     *
     * @see #withNonceReservation(Collection, Supplier)
     */
    <T> T withNonceReservation(byte[] address, Supplier<T> action);

    /**
     * Runs {@code action} holding the nonce-reservation locks for every address in
     * {@code addresses}, so that the whole "read the counter, issue {@code counter + 1}, import,
     * write the counter back" sequence of a submit is atomic per sender. Without it two submits
     * from one address both read the same counter and are both issued the same nonce; both import
     * and both gossip, but only one can ever execute, and the other is a silently dead transaction
     * the caller was already handed a hash for.
     *
     * <p><b>Lock order.</b> This lock is taken <i>before</i> the SyncManager and blockchain
     * monitors, because the import the action performs takes them. It must therefore never be
     * acquired by a thread that already holds either — in practice only the RPC and CLI submit
     * paths use it. The consensus threads run the other way round (monitor first, then this store),
     * and none of the store's own methods acquires this lock: they take the much finer per-address
     * <i>value</i> lock instead, which is a leaf. The resulting global order
     * {@code reservation -> SyncManager -> blockchain -> value} has no cycle.
     *
     * <p>Locks are acquired in a canonical order, so two callers with overlapping address sets
     * cannot deadlock against each other either. An empty collection simply runs the action.
     */
    <T> T withNonceReservation(Collection<byte[]> addresses, Supplier<T> action);

    UInt64 getExecutedNonceNum(byte[] address);

    void updateExcutedNonceNum(byte[] address,boolean addOrSubstract);
}
