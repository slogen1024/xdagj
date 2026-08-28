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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class StateRootAnchorSelectionTest {

    private static final Bytes32 ROOT = Bytes32.fromHexString("0x" + "cd".repeat(32));

    @Test
    public void returns_null_before_activation() {
        assertNull(BlockchainImpl.computeStateRootAnchor(5L, 10L, 1L, h -> ROOT, false));
    }

    @Test
    public void returns_null_when_the_lagged_height_is_negative() {
        assertNull(BlockchainImpl.computeStateRootAnchor(0L, 0L, 2L, h -> ROOT, false));
    }

    @Test
    public void anchors_the_lagged_root_when_active() {
        EvmStateAnchor anchor = BlockchainImpl.computeStateRootAnchor(10L, 0L, 1L, h -> {
            assertEquals("must read the root as of H-lag", 9L, h);
            return ROOT;
        }, false);
        assertEquals(9L, anchor.height());
        assertEquals(EvmStateAnchor.rootLowOf(ROOT), anchor.rootLow());
        assertFalse("daSkip defaults to false (include)", anchor.daSkip());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejects_a_lag_below_one() {
        BlockchainImpl.computeStateRootAnchor(10L, 0L, 0L, h -> ROOT, false);
    }

    @Test
    public void computeStateRootAnchor_carries_the_daskip_bit() {
        EvmStateAnchor included = BlockchainImpl.computeStateRootAnchor(10L, 0L, 1L, h -> ROOT, false);
        assertNotNull(included);
        assertFalse("daSkip=false threads through as include", included.daSkip());

        EvmStateAnchor skipped = BlockchainImpl.computeStateRootAnchor(10L, 0L, 1L, h -> ROOT, true);
        assertNotNull(skipped);
        assertTrue("daSkip=true threads through as skip", skipped.daSkip());
        // The root/height are unaffected by the daSkip choice.
        assertEquals(included.height(), skipped.height());
        assertEquals(included.rootLow(), skipped.rootLow());
    }
}
