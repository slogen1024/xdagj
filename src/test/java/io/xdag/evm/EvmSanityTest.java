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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Before;
import org.junit.Test;

/** Sanity checks that the embedded Besu EVM is wired up at the Shanghai fork. */
public class EvmSanityTest extends EvmTestBase {

    @Before
    public void before() {
        setUp();
    }

    /**
     * Runtime code: PUSH0 PUSH0 MSTORE PUSH1 0x20 PUSH0 RETURN -> returns 32 zero bytes.
     * PUSH0 (0x5f) is only valid on Shanghai+, so a successful run proves the fork is active.
     */
    @Test
    public void push0_runs_on_shanghai() {
        Bytes runtime = Bytes.fromHexString("0x5f5f5260205ff3");
        evm.setCode(world, bob, runtime); // root, not a child: SimpleAccount.commit() drops code

        XdagExecutionResult r = call(owner, bob, Bytes.EMPTY);

        assertTrue("PUSH0 program should succeed on Shanghai", r.success());
        assertEquals(BigInteger.ZERO, Abi.decodeUint256(r.returnData()));
    }

    /** Runtime code: PUSH1 0x00 PUSH1 0x00 REVERT -> reverts with empty reason. */
    @Test
    public void revert_marks_failure() {
        Bytes runtime = Bytes.fromHexString("0x60006000fd");
        evm.setCode(world, bob, runtime); // root, not a child: SimpleAccount.commit() drops code

        XdagExecutionResult r = call(owner, bob, Bytes.EMPTY);

        assertFalse("REVERT should yield an unsuccessful result", r.success());
    }
}
