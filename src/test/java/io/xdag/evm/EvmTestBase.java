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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * Base for EVM tests: an in-memory {@link SimpleWorld} plus a Shanghai {@link XdagEvmExecutor},
 * with funding and deploy/call conveniences.
 */
public abstract class EvmTestBase {

    protected SimpleWorld world;
    protected XdagEvmExecutor evm;

    // Deterministic actors. `owner` matches the historical XDAG EVM fixture owner.
    protected final Address owner = Address.fromHexString("0x23a6049381fd2cfb0661d9de206613b83d53d7df");
    protected final Address alice = Address.fromHexString("0x1111111111111111111111111111111111111111");
    protected final Address bob = Address.fromHexString("0x2222222222222222222222222222222222222222");
    protected final long gas = 8_000_000L;

    protected void setUp() {
        world = new SimpleWorld();
        evm = new XdagEvmExecutor(EvmConfig.devnet());
        fund(owner, Wei.fromEth(1000));
        fund(alice, Wei.fromEth(1000));
        fund(bob, Wei.fromEth(1000));
    }

    protected void fund(Address address, Wei amount) {
        WorldUpdater updater = world.updater();
        MutableAccount account = updater.getOrCreate(address);
        account.setBalance(amount);
        updater.commit();
    }

    protected XdagExecutionResult deploy(Address sender, Bytes initCodeWithArgs) {
        return evm.deploy(world.updater(), sender, initCodeWithArgs, Wei.ZERO, gas);
    }

    protected XdagExecutionResult call(Address sender, Address to, Bytes callData) {
        return evm.call(world.updater(), sender, to, callData, Wei.ZERO, gas);
    }

    /** Convenience for view calls returning a single uint256. */
    protected BigInteger callUint(Address sender, Address to, Bytes callData) {
        return Abi.decodeUint256(call(sender, to, callData).returnData());
    }

    /** Read a single-line hex bytecode fixture from {@code src/test/resources/solidity/}. */
    protected Bytes readBin(String fileName) throws IOException {
        String hex = new String(Files.readAllBytes(
                Paths.get("src/test/resources/solidity/" + fileName)), StandardCharsets.US_ASCII).trim();
        return Bytes.fromHexString(hex.startsWith("0x") ? hex : "0x" + hex);
    }
}
