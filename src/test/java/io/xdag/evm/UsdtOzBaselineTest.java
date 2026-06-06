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

import java.io.IOException;
import java.math.BigInteger;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Before;
import org.junit.Test;

/**
 * Baseline proof on a clean, standard ERC-20 ("USDT", 6 decimals, returns bool) — the BEP-20-style
 * path. Complements {@link UsdtIssuanceTest}, which exercises the non-standard real Tether contract.
 */
public class UsdtOzBaselineTest extends EvmTestBase {

    private static final BigInteger ONE_USDT = BigInteger.valueOf(1_000_000L); // 6 decimals

    private Address usdt;

    @Before
    public void before() throws IOException {
        setUp();
        XdagExecutionResult deployed = deploy(owner, readBin("usdt_oz.bin")); // no constructor args
        assertTrue("deploy OZ USDT", deployed.success());
        usdt = deployed.createdContract().orElseThrow();
    }

    private BigInteger usdt(long whole) {
        return ONE_USDT.multiply(BigInteger.valueOf(whole));
    }

    @Test
    public void decimals_is_six() {
        assertEquals(BigInteger.valueOf(6), callUint(owner, usdt, Abi.selector("decimals()")));
    }

    @Test
    public void mint_then_transfer_returns_bool_and_moves_balance() {
        XdagExecutionResult mint = call(owner, usdt, Abi.concat(
                Abi.selector("mint(address,uint256)"),
                Abi.encodeAddress(owner),
                Abi.encodeUint256(usdt(1000))));
        assertTrue(mint.success());
        assertEquals("standard ERC-20 returns bool true", BigInteger.ONE, Abi.decodeUint256(mint.returnData()));

        XdagExecutionResult transfer = call(owner, usdt, Abi.concat(
                Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(alice),
                Abi.encodeUint256(usdt(250))));
        assertTrue(transfer.success());
        assertEquals(BigInteger.ONE, Abi.decodeUint256(transfer.returnData()));
        assertFalse("Transfer event emitted", transfer.logs().isEmpty());

        BigInteger balance = callUint(alice, usdt, Abi.concat(
                Abi.selector("balanceOf(address)"), Abi.encodeAddress(alice)));
        assertEquals(usdt(250), balance);
    }

    @Test
    public void approve_allowance_transferFrom() {
        call(owner, usdt, Abi.concat(Abi.selector("mint(address,uint256)"),
                Abi.encodeAddress(owner), Abi.encodeUint256(usdt(100))));

        XdagExecutionResult approve = call(owner, usdt, Abi.concat(Abi.selector("approve(address,uint256)"),
                Abi.encodeAddress(bob), Abi.encodeUint256(usdt(40))));
        assertTrue(approve.success());
        assertEquals(BigInteger.ONE, Abi.decodeUint256(approve.returnData()));

        XdagExecutionResult transferFrom = call(bob, usdt, Abi.concat(Abi.selector("transferFrom(address,address,uint256)"),
                Abi.encodeAddress(owner), Abi.encodeAddress(alice), Abi.encodeUint256(usdt(30))));
        assertTrue(transferFrom.success());

        BigInteger allowance = callUint(owner, usdt, Abi.concat(Abi.selector("allowance(address,address)"),
                Abi.encodeAddress(owner), Abi.encodeAddress(bob)));
        assertEquals(usdt(10), allowance);

        BigInteger aliceBalance = callUint(alice, usdt, Abi.concat(
                Abi.selector("balanceOf(address)"), Abi.encodeAddress(alice)));
        assertEquals(usdt(30), aliceBalance);
    }
}
