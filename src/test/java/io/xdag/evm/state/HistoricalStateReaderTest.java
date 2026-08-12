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
package io.xdag.evm.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.Test;

public class HistoricalStateReaderTest {

    private static final Address ADDR =
            Address.fromHexString("0x00000000000000000000000000000000000000aa");

    /**
     * Commits one height's balance change against the store, journaling the reverse delta.
     * Uses getAccount() if the account already exists, createAccount() otherwise — RocksDbWorldUpdater
     * has no getOrCreate(); callers must choose the right path.
     */
    private void commitBalance(InMemoryKVSource store, EvmStateJournal journal, long height, long wei) {
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(store);
        MutableAccount acct = w.getAccount(ADDR);
        if (acct == null) {
            w.createAccount(ADDR, 0L, Wei.of(wei));
        } else {
            acct.setBalance(Wei.of(wei));
        }
        List<EvmStateJournal.Entry> entries = new ArrayList<>();
        w.commitAndDigest(entries);
        journal.putHeightJournal(height, entries);
    }

    @Test
    public void readsBalanceAtPastHeights() {
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        commitBalance(store, journal, 1, 100);
        commitBalance(store, journal, 2, 200);
        commitBalance(store, journal, 3, 300);
        HistoricalStateReader reader = new HistoricalStateReader(store, journal, 128);

        assertEquals(Wei.of(100), reader.worldAt(1, 3).getAccount(ADDR).getBalance());
        assertEquals(Wei.of(200), reader.worldAt(2, 3).getAccount(ADDR).getBalance());
        assertEquals(Wei.of(300), reader.worldAt(3, 3).getAccount(ADDR).getBalance()); // head -> latest
    }

    @Test
    public void accountAbsentAtGenesisHeight() {
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        commitBalance(store, journal, 1, 100); // prior at height 0 = absent
        HistoricalStateReader reader = new HistoricalStateReader(store, journal, 128);

        WorldUpdater atGenesis = reader.worldAt(0, 1);
        assertNull(atGenesis.getAccount(ADDR));
    }

    @Test
    public void outOfWindowAndFutureHeightThrow() {
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        HistoricalStateReader reader = new HistoricalStateReader(store, journal, 2); // window 2

        // head = 10: retained heights are 9,10; height 8 is below the window.
        assertThrows(StateUnavailableException.class, () -> reader.worldAt(8, 10));
        assertThrows(StateUnavailableException.class, () -> reader.worldAt(11, 10));
    }

    @Test
    public void readsStorageSlotAtPastHeight() {
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        UInt256 slot = UInt256.valueOf(7);

        RocksDbWorldUpdater h1 = new RocksDbWorldUpdater(store);
        h1.createAccount(ADDR, 0L, Wei.ZERO).setStorageValue(slot, UInt256.valueOf(11));
        List<EvmStateJournal.Entry> j1 = new ArrayList<>();
        h1.commitAndDigest(j1);
        journal.putHeightJournal(1, j1);

        RocksDbWorldUpdater h2 = new RocksDbWorldUpdater(store);
        h2.getAccount(ADDR).setStorageValue(slot, UInt256.valueOf(22));
        List<EvmStateJournal.Entry> j2 = new ArrayList<>();
        h2.commitAndDigest(j2);
        journal.putHeightJournal(2, j2);

        HistoricalStateReader reader = new HistoricalStateReader(store, journal, 128);
        Account at1 = reader.worldAt(1, 2).getAccount(ADDR);
        assertEquals(UInt256.valueOf(11), at1.getStorageValue(slot));
    }

    @Test
    public void keepsLowestTouchingHeightPreImage() {
        // A slot rewritten at BOTH heights 2 and 3, read at target height 1: the overlay must keep the
        // height-2 pre-image (the value as of height 1 = 10), NOT the height-3 one. This directly pins
        // the "lowest touching height wins" tie-break that a plain overwrite would get wrong (it would
        // yield 20). readsStorageSlotAtPastHeight only touches the slot at a single height in-window.
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        UInt256 slot = UInt256.valueOf(1);

        RocksDbWorldUpdater h1 = new RocksDbWorldUpdater(store);
        h1.createAccount(ADDR, 0L, Wei.ZERO).setStorageValue(slot, UInt256.valueOf(10));
        List<EvmStateJournal.Entry> j1 = new ArrayList<>();
        h1.commitAndDigest(j1);
        journal.putHeightJournal(1, j1);

        RocksDbWorldUpdater h2 = new RocksDbWorldUpdater(store);
        h2.getAccount(ADDR).setStorageValue(slot, UInt256.valueOf(20));
        List<EvmStateJournal.Entry> j2 = new ArrayList<>();
        h2.commitAndDigest(j2);
        journal.putHeightJournal(2, j2);

        RocksDbWorldUpdater h3 = new RocksDbWorldUpdater(store);
        h3.getAccount(ADDR).setStorageValue(slot, UInt256.valueOf(30));
        List<EvmStateJournal.Entry> j3 = new ArrayList<>();
        h3.commitAndDigest(j3);
        journal.putHeightJournal(3, j3);

        HistoricalStateReader reader = new HistoricalStateReader(store, journal, 128);
        assertEquals(UInt256.valueOf(10), reader.worldAt(1, 3).getAccount(ADDR).getStorageValue(slot));
    }
}
