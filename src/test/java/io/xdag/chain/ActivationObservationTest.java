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
package io.xdag.chain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.ActivationObservation.Record;
import io.xdag.chain.ActivationObservation.Shot;

public class ActivationObservationTest {

    private static final long H = 1000L;
    private static final long NOW = ActivationObservation.MIN_WINDOW_MS;
    private static final Bytes32 BEFORE = hash((byte) 1);
    private static final Bytes32 AFTER = hash((byte) 2);

    @Test
    public void unscheduledHeightStaysOpen() {
        String open = ActivationObservation.describe(Long.MAX_VALUE, ready(Long.MAX_VALUE), NOW);
        assertTrue(open, open.startsWith("open "));
        assertTrue(open, open.contains("height"));
    }

    @Test
    public void readyAfterBothSnapshotsACleanScanADrillAndFourWeeks() {
        assertEquals("ready", ActivationObservation.describe(H, ready(H), NOW));
        assertEquals("open window", ActivationObservation.describe(H, ready(H), NOW - 1));
        assertEquals("open snapshot-before",
                ActivationObservation.describe(H, new Record(H, 0, new Shot(H, BEFORE), new Shot(H, AFTER), true, 0, true), NOW));
        assertEquals("open snapshot-after",
                ActivationObservation.describe(H, new Record(H, 0, new Shot(H - 1, BEFORE), new Shot(H, BEFORE), true, 0, true), NOW));
        assertEquals("open consistency",
                ActivationObservation.describe(H, new Record(H, 0, new Shot(H - 1, BEFORE), new Shot(H, AFTER), false, 0, true), NOW));
        assertEquals("open consistency-hits",
                ActivationObservation.describe(H, new Record(H, 0, new Shot(H - 1, BEFORE), new Shot(H, AFTER), true, 1, true), NOW));
        assertEquals("open challenge",
                ActivationObservation.describe(H, new Record(H, 0, new Shot(H - 1, BEFORE), new Shot(H, AFTER), true, 0, false), NOW));
    }

    @Test
    public void mainnetHeightIsADifferentFutureHeightAndIsNotWrittenHere() {
        assertEquals("allowed 5000", ActivationObservation.mainnet(H, ready(H), NOW, 5000L));
        assertEquals("refused mainnet-height", ActivationObservation.mainnet(H, ready(H), NOW, H));
        assertEquals("refused mainnet-height", ActivationObservation.mainnet(H, ready(H), NOW, Long.MAX_VALUE));
        assertTrue(ActivationObservation.mainnet(Long.MAX_VALUE, ready(Long.MAX_VALUE), NOW, 5000L).startsWith("refused "));
    }

    private static Record ready(long height) {
        return new Record(height, 0L, new Shot(height - 1, BEFORE), new Shot(height, AFTER), true, 0, true);
    }

    private static Bytes32 hash(byte mark) {
        byte[] raw = new byte[32];
        raw[31] = mark;
        return Bytes32.wrap(raw);
    }
}
