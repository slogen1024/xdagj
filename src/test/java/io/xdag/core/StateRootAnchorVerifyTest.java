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
package io.xdag.core;

import static org.junit.Assert.assertEquals;

import io.xdag.core.BlockchainImpl.AnchorVerdict;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class StateRootAnchorVerifyTest {

    private static final Bytes32 ROOT = Bytes32.fromHexString("0x" + "cd".repeat(32));
    private static final Bytes GOOD_LOW = EvmStateAnchor.rootLowOf(ROOT);

    // rootAt returns ROOT for the expected height (H-lag), and a different root elsewhere so a
    // wrong-height anchor cannot accidentally match.
    private static Bytes32 rootAt(long h) {
        return h == 9L ? ROOT : Bytes32.fromHexString("0x" + "ee".repeat(32));
    }

    @Test
    public void match_when_height_and_root_agree() {
        EvmStateAnchor a = new EvmStateAnchor(9L, GOOD_LOW, false); // H=10, lag=1 -> expected 9
        assertEquals(AnchorVerdict.MATCH,
                BlockchainImpl.verifyStateRootAnchor(a, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void mismatch_on_wrong_root() {
        Bytes wrong = Bytes.repeat((byte) 0xFF, EvmStateAnchor.ROOT_LOW_LENGTH);
        EvmStateAnchor a = new EvmStateAnchor(9L, wrong, false);
        assertEquals(AnchorVerdict.MISMATCH,
                BlockchainImpl.verifyStateRootAnchor(a, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void mismatch_on_wrong_height() {
        EvmStateAnchor a = new EvmStateAnchor(8L, GOOD_LOW, false); // declares 8, expected 9
        assertEquals(AnchorVerdict.MISMATCH,
                BlockchainImpl.verifyStateRootAnchor(a, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void mismatch_when_active_but_anchor_missing() {
        assertEquals(AnchorVerdict.MISMATCH,
                BlockchainImpl.verifyStateRootAnchor(null, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void absent_before_activation_ignores_any_anchor() {
        EvmStateAnchor a = new EvmStateAnchor(9L, GOOD_LOW, false);
        assertEquals(AnchorVerdict.ABSENT,
                BlockchainImpl.verifyStateRootAnchor(a, 10L, 100L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
        assertEquals(AnchorVerdict.ABSENT,
                BlockchainImpl.verifyStateRootAnchor(null, 10L, 100L, 1L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void absent_when_lagged_height_is_negative() {
        // H=1, lag=2 -> expected -1: too early to anchor, matches computeStateRootAnchor's null.
        assertEquals(AnchorVerdict.ABSENT,
                BlockchainImpl.verifyStateRootAnchor(null, 1L, 0L, 2L, StateRootAnchorVerifyTest::rootAt,
                        deferredHeight -> false));
    }

    @Test
    public void behind_when_an_earlier_height_is_deferred_at_or_below_the_anchored_height() {
        // Anchor is well-formed (height 9, correct root), but this node has a blob-deferred height <= 9,
        // so it has not executed height 9's root yet and cannot verify -> BEHIND (defer, do not reject).
        EvmStateAnchor a = new EvmStateAnchor(9L, GOOD_LOW, false);
        assertEquals(AnchorVerdict.BEHIND, BlockchainImpl.verifyStateRootAnchor(
                a, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt, deferredHeight -> true));
    }

    @Test
    public void not_behind_then_the_root_decides_match_or_mismatch() {
        EvmStateAnchor good = new EvmStateAnchor(9L, GOOD_LOW, false);
        assertEquals(AnchorVerdict.MATCH, BlockchainImpl.verifyStateRootAnchor(
                good, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt, deferredHeight -> false));

        Bytes wrong = Bytes.repeat((byte) 0xFF, EvmStateAnchor.ROOT_LOW_LENGTH);
        EvmStateAnchor bad = new EvmStateAnchor(9L, wrong, false);
        assertEquals(AnchorVerdict.MISMATCH, BlockchainImpl.verifyStateRootAnchor(
                bad, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt, deferredHeight -> false));
    }

    @Test
    public void a_structurally_wrong_anchor_is_mismatch_even_when_behind() {
        // Wrong anchor height is a genuine MISMATCH regardless of behind-ness (structural check first).
        EvmStateAnchor wrongHeight = new EvmStateAnchor(8L, GOOD_LOW, false); // declares 8, expected 9
        assertEquals(AnchorVerdict.MISMATCH, BlockchainImpl.verifyStateRootAnchor(
                wrongHeight, 10L, 0L, 1L, StateRootAnchorVerifyTest::rootAt, deferredHeight -> true));
    }
}
