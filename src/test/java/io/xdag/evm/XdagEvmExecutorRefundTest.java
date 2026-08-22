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
package io.xdag.evm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Before;
import org.junit.Test;

/** Task 1: XdagExecutionResult must carry the frame's accumulated (raw, uncapped) gas refund. */
public class XdagEvmExecutorRefundTest extends EvmTestBase {

    // Constructor SSTOREs slot0 = 42, then deploys runtime `6000600055` (clear slot0).
    // Layout: [602a600055 set][6005601160003960056000f3 return 5B@0x11][6000600055 runtime].
    private static final Bytes INIT_SET_THEN_CLEAR =
            Bytes.fromHexString("0x602a6000556005601160003960056000f36000600055");
    // Constructor SSTOREs slot0 = 42, runtime `602a600055` sets slot0 = 42 again (no clear -> no refund).
    private static final Bytes INIT_SET_ONLY =
            Bytes.fromHexString("0x602a6000556005601160003960056000f3602a600055");

    @Before
    public void setUp() {
        super.setUp();
    }

    @Test
    public void clearing_a_nonzero_slot_yields_a_positive_refund() {
        Address c = evm.deploy(world.updater(), owner, INIT_SET_THEN_CLEAR, Wei.ZERO, gas)
                .createdContract().orElseThrow();
        XdagExecutionResult cleared = evm.call(world.updater(), owner, c, Bytes.EMPTY, Wei.ZERO, gas);
        assertTrue("clearing call must succeed", cleared.success());
        assertTrue("clearing a non-zero slot must accumulate a refund", cleared.gasRefund() > 0L);
    }

    @Test
    public void a_call_that_clears_nothing_yields_zero_refund() {
        Address c = evm.deploy(world.updater(), owner, INIT_SET_ONLY, Wei.ZERO, gas)
                .createdContract().orElseThrow();
        XdagExecutionResult noClear = evm.call(world.updater(), owner, c, Bytes.EMPTY, Wei.ZERO, gas);
        assertTrue("non-clearing call must succeed", noClear.success());
        assertEquals("re-setting a slot to its value earns no refund", 0L, noClear.gasRefund());
    }

    @Test
    public void shanghai_refund_quotient_is_five() {
        assertEquals(5L, evm.maxRefundQuotient());
    }
}
