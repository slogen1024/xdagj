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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class EvmStateJournalTest {

    private EvmStateJournal newJournal() {
        return new EvmStateJournal(new InMemoryKVSource());
    }

    @Test
    public void roundTripsValueAndTombstoneEntries() {
        EvmStateJournal journal = newJournal();
        journal.putHeightJournal(7, List.of(
                new EvmStateJournal.Entry(new byte[]{1, 2}, new byte[]{9}),
                new EvmStateJournal.Entry(new byte[]{3, 4}, null)));

        List<EvmStateJournal.Entry> read = journal.getHeightJournal(7).orElseThrow();
        assertEquals(2, read.size());
        assertArrayEquals(new byte[]{1, 2}, read.get(0).key());
        assertArrayEquals(new byte[]{9}, read.get(0).priorValue());
        assertArrayEquals(new byte[]{3, 4}, read.get(1).key());
        assertNull(read.get(1).priorValue());
    }

    @Test
    public void missingHeightIsEmpty() {
        assertTrue(newJournal().getHeightJournal(42).isEmpty());
    }

    @Test
    public void pruneBelowDropsOlderHeightsKeepsBoundary() {
        EvmStateJournal journal = newJournal();
        for (long h = 1; h <= 5; h++) {
            journal.putHeightJournal(h, List.of(new EvmStateJournal.Entry(new byte[]{(byte) h}, null)));
        }
        journal.pruneBelow(3); // keep >= 3
        assertTrue(journal.getHeightJournal(2).isEmpty());
        assertTrue(journal.getHeightJournal(3).isPresent());
        assertTrue(journal.getHeightJournal(5).isPresent());
    }

    @Test
    public void truncateAboveDropsNewerHeights() {
        EvmStateJournal journal = newJournal();
        for (long h = 1; h <= 5; h++) {
            journal.putHeightJournal(h, List.of(new EvmStateJournal.Entry(new byte[]{(byte) h}, null)));
        }
        journal.truncateAbove(3); // keep <= 3
        assertTrue(journal.getHeightJournal(3).isPresent());
        assertTrue(journal.getHeightJournal(4).isEmpty());
    }

    @Test
    public void clearRemovesEverything() {
        EvmStateJournal journal = newJournal();
        journal.putHeightJournal(1, List.of(new EvmStateJournal.Entry(new byte[]{1}, null)));
        journal.clear();
        assertTrue(journal.getHeightJournal(1).isEmpty());
        assertEquals(Optional.empty(), journal.getHeightJournal(1));
    }

    @Test
    public void emptyJournalRoundTrips() {
        EvmStateJournal journal = newJournal();
        journal.putHeightJournal(9, List.of());
        assertEquals(0, journal.getHeightJournal(9).orElseThrow().size());
    }

    @Test
    public void zeroLengthPresentValueIsNotTombstone() {
        // A present entry with an empty value must round-trip to a non-null zero-length array,
        // NOT collapse into a tombstone (priorValue == null). This is the key semantic distinction.
        EvmStateJournal journal = newJournal();
        journal.putHeightJournal(5, List.of(new EvmStateJournal.Entry(new byte[]{7}, new byte[0])));

        EvmStateJournal.Entry read = journal.getHeightJournal(5).orElseThrow().get(0);
        assertNotNull(read.priorValue());
        assertEquals(0, read.priorValue().length);
    }

    @Test
    public void corruptBufferThrowsIllegalState() {
        // count = 1 but no entry bytes follow -> a truncated buffer must be rejected, not read blindly.
        InMemoryKVSource store = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(store);
        store.put(new byte[]{0, 0, 0, 0, 0, 0, 0, 3}, new byte[]{0, 0, 0, 1}); // height 3 (BE), count = 1
        assertThrows(IllegalStateException.class, () -> journal.getHeightJournal(3));
    }
}
