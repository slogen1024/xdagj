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

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * §11.3 gate. A testnet height may be copied toward mainnet only after a future activation
 * height, a snapshot on each side of it, a clean consistency report, one challenge drill, and
 * 28 days. This class does not write {@code chain.activation.height}.
 */
public final class ActivationObservation {

    /** Four weeks. The roadmap's observation floor. */
    public static final long MIN_WINDOW_MS = 28L * 24 * 60 * 60 * 1000;

    private ActivationObservation() {
    }

    /** One three-directory snapshot, identified by the main height it was taken at. */
    public record Shot(long height, Bytes32 hash) {
    }

    /**
     * What an operator has actually recorded. {@code consistencyRan} stays false until a report
     * exists; a default of zero hits is not a clean scan.
     */
    public record Record(long activationHeight, long startedAtMillis, Shot before, Shot after,
            boolean consistencyRan, int consistencyHits, boolean challengeDrilled) {
    }

    public static List<String> gaps(long networkHeight, Record record, long nowMillis) {
        List<String> gaps = new ArrayList<>();
        boolean heightOk = networkHeight > 0 && networkHeight != Long.MAX_VALUE
                && record.activationHeight() == networkHeight;
        if (!heightOk) {
            gaps.add("height");
        }
        if (record.before() == null || record.before().hash() == null || !heightOk
                || record.before().height() >= networkHeight) {
            gaps.add("snapshot-before");
        }
        boolean distinct = record.before() != null && record.before().hash() != null
                && record.after() != null && record.after().hash() != null
                && !record.after().hash().equals(record.before().hash());
        if (record.after() == null || record.after().hash() == null || !heightOk || !distinct
                || record.after().height() < networkHeight) {
            gaps.add("snapshot-after");
        }
        if (!record.consistencyRan()) {
            gaps.add("consistency");
        } else if (record.consistencyHits() != 0) {
            gaps.add("consistency-hits");
        }
        if (!record.challengeDrilled()) {
            gaps.add("challenge");
        }
        if (record.startedAtMillis() < 0 || nowMillis < record.startedAtMillis()
                || nowMillis - record.startedAtMillis() < MIN_WINDOW_MS) {
            gaps.add("window");
        }
        return gaps;
    }

    public static String describe(long networkHeight, Record record, long nowMillis) {
        List<String> gaps = gaps(networkHeight, record, nowMillis);
        return gaps.isEmpty() ? "ready" : "open " + String.join(",", gaps);
    }

    /**
     * A proposed mainnet height is a different future height. Ready testnet evidence does not
     * schedule it; the caller has to set that height outside this class, and not in a shipped conf.
     */
    public static String mainnet(long networkHeight, Record record, long nowMillis, long proposed) {
        List<String> gaps = gaps(networkHeight, record, nowMillis);
        if (proposed <= 0 || proposed == Long.MAX_VALUE || proposed == networkHeight) {
            gaps.add("mainnet-height");
        }
        return gaps.isEmpty() ? "allowed " + proposed : "refused " + String.join(",", gaps);
    }
}
