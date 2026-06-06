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
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Before;
import org.junit.Test;

/**
 * The headline proof that "USDT can be issued on XDAG": deploys the verbatim Ethereum-mainnet Tether
 * contract on the embedded Besu EVM and exercises the full owner lifecycle — issue (mint), transfer
 * (including the non-standard no-return-value quirk), approve/transferFrom, the fee mechanism, the
 * blacklist, pause, and redeem (burn). All amounts are at 6-decimal scale (1 USDT == 1_000_000 raw).
 */
public class UsdtIssuanceTest extends EvmTestBase {

    private static final BigInteger ONE_USDT = BigInteger.valueOf(1_000_000L); // 6 decimals

    private Address usdt;

    @Before
    public void before() throws IOException {
        setUp();
        deployUsdt();
    }

    // TetherToken(uint _initialSupply, string _name, string _symbol, uint _decimals)
    // ABI head = 4 words (128 bytes = 0x80): [initialSupply][offset name=0x80][offset symbol=0xC0][decimals]
    private void deployUsdt() throws IOException {
        Bytes args = Abi.concat(
                Abi.encodeUint256(BigInteger.ZERO),            // _initialSupply
                Abi.encodeUint256(BigInteger.valueOf(0x80)),   // offset of _name
                Abi.encodeUint256(BigInteger.valueOf(0xC0)),   // offset of _symbol
                Abi.encodeUint256(BigInteger.valueOf(6)),      // _decimals
                encodeString("Tether USD"),                    // at 0x80 (length + 32-byte body)
                encodeString("USDT"));                         // at 0xC0 (length + 32-byte body)

        XdagExecutionResult deployed = deploy(owner, Abi.concat(readBin("usdt.bin"), args));
        assertTrue("deploy real Tether USDT", deployed.success());
        usdt = deployed.createdContract().orElseThrow();
        assertEquals("decimals() == 6", BigInteger.valueOf(6),
                callUint(owner, usdt, Abi.selector("decimals()")));
    }

    /** ABI dynamic string = 32-byte length word + right-padded data (multiple of 32). */
    private static Bytes encodeString(String s) {
        byte[] data = s.getBytes(StandardCharsets.US_ASCII);
        int paddedLen = ((data.length + 31) / 32) * 32;
        byte[] body = new byte[paddedLen];
        System.arraycopy(data, 0, body, 0, data.length);
        return Abi.concat(Abi.encodeUint256(BigInteger.valueOf(data.length)), Bytes.wrap(body));
    }

    private BigInteger usdt(long whole) {
        return ONE_USDT.multiply(BigInteger.valueOf(whole));
    }

    private BigInteger balanceOf(Address a) {
        return callUint(owner, usdt, Abi.concat(Abi.selector("balanceOf(address)"), Abi.encodeAddress(a)));
    }

    private BigInteger totalSupply() {
        return callUint(owner, usdt, Abi.selector("totalSupply()"));
    }

    private XdagExecutionResult issue(BigInteger amount) {
        return call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"), Abi.encodeUint256(amount)));
    }

    @Test
    public void issue_mints_to_owner() {
        assertTrue("USDT can be issued on XDAG", issue(usdt(1_000_000)).success());
        assertEquals(usdt(1_000_000), totalSupply());
        assertEquals(usdt(1_000_000), balanceOf(owner));
    }

    @Test
    public void transfer_has_no_return_value_but_moves_balance() {
        issue(usdt(1000));
        XdagExecutionResult t = call(owner, usdt, Abi.concat(
                Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(alice), Abi.encodeUint256(usdt(250))));
        assertTrue(t.success());
        assertEquals("real USDT transfer() returns NO data", 0, t.returnData().size());
        assertEquals(usdt(250), balanceOf(alice));
        assertFalse("Transfer event emitted", t.logs().isEmpty());
    }

    @Test
    public void approve_allowance_transferFrom() {
        issue(usdt(100));
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("approve(address,uint256)"),
                Abi.encodeAddress(bob), Abi.encodeUint256(usdt(40)))).success());

        assertTrue(call(bob, usdt, Abi.concat(Abi.selector("transferFrom(address,address,uint256)"),
                Abi.encodeAddress(owner), Abi.encodeAddress(alice), Abi.encodeUint256(usdt(30)))).success());

        BigInteger allowance = callUint(owner, usdt, Abi.concat(Abi.selector("allowance(address,address)"),
                Abi.encodeAddress(owner), Abi.encodeAddress(bob)));
        assertEquals(usdt(10), allowance);
        assertEquals(usdt(30), balanceOf(alice));
    }

    @Test
    public void fee_mechanism_credits_owner() {
        issue(usdt(1000));
        // setParams(basisPointsRate=10 i.e. 0.1%, maxFee=5 USDT). Requires bp<20 and maxFee<50.
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("setParams(uint256,uint256)"),
                Abi.encodeUint256(BigInteger.valueOf(10)), Abi.encodeUint256(BigInteger.valueOf(5)))).success());

        BigInteger ownerBefore = balanceOf(owner);
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(alice), Abi.encodeUint256(usdt(100)))).success());

        // fee = min(100 USDT * 10/10000 = 0.1 USDT, maxFee 5 USDT) = 0.1 USDT
        assertEquals("recipient receives amount - fee", usdt(100).subtract(BigInteger.valueOf(100_000L)), balanceOf(alice));
        assertEquals("owner keeps net + fee", ownerBefore.subtract(usdt(100)).add(BigInteger.valueOf(100_000L)), balanceOf(owner));
    }

    @Test
    public void blacklist_blocks_and_destroys_funds() {
        issue(usdt(10));
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(alice), Abi.encodeUint256(usdt(10)))).success());

        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("addBlackList(address)"),
                Abi.encodeAddress(alice))).success());
        XdagExecutionResult blocked = call(alice, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(bob), Abi.encodeUint256(usdt(1))));
        assertFalse("blacklisted sender cannot transfer", blocked.success());

        BigInteger supplyBefore = totalSupply();
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("destroyBlackFunds(address)"),
                Abi.encodeAddress(alice))).success());
        assertEquals("blacklisted balance zeroed", BigInteger.ZERO, balanceOf(alice));
        assertEquals("totalSupply reduced by destroyed funds", supplyBefore.subtract(usdt(10)), totalSupply());
    }

    @Test
    public void pause_blocks_transfers() {
        issue(usdt(1));
        assertTrue(call(owner, usdt, Abi.selector("pause()")).success());
        XdagExecutionResult t = call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
                Abi.encodeAddress(alice), Abi.encodeUint256(usdt(1))));
        assertFalse("paused contract blocks transfer", t.success());
        assertTrue(call(owner, usdt, Abi.selector("unpause()")).success());
    }

    @Test
    public void redeem_burns_supply() {
        issue(usdt(500));
        BigInteger before = totalSupply();
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("redeem(uint256)"),
                Abi.encodeUint256(usdt(200)))).success());
        assertEquals(before.subtract(usdt(200)), totalSupply());
    }
}
