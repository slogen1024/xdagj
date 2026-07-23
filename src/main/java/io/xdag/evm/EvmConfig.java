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

import java.math.BigInteger;
import org.hyperledger.besu.evm.EvmSpecVersion;

/**
 * Immutable EVM execution configuration: the target hard fork and the chain id.
 *
 * <p>In Sub-project A only the {@code CHAINID} opcode reads {@link #chainId()}; the final
 * mainnet/testnet/devnet chain-id triple is decided (and reserved via ethereum-lists/chains)
 * in Sub-project B/C. The three ids below are DISTINCT and provisional so that signed-tx replay
 * across networks can be prevented once a tx layer exists; replace the values with the reserved
 * ones in B/C. The fork is fixed at Shanghai: it enables {@code PUSH0} (required by modern
 * Solidity) with legacy gas semantics and no blob/KZG machinery.
 */
public final class EvmConfig {

    /** Provisional mainnet chain id; replace with the reserved value in Sub-project B/C. */
    public static final BigInteger MAINNET_CHAIN_ID = BigInteger.valueOf(0xCAFC); // 51964 (provisional)

    /** Provisional testnet chain id; replace with the reserved value in Sub-project B/C. */
    public static final BigInteger TESTNET_CHAIN_ID = BigInteger.valueOf(0xCAFD); // 51965 (provisional)

    /** Provisional devnet chain id; replace with the reserved value in Sub-project B/C. */
    public static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE); // 51966 (provisional)

    /** Default ceiling on the gas a single message may request (Ethereum-style block gas limit). */
    public static final long DEFAULT_MAX_GAS_LIMIT = 30_000_000L;

    private final EvmSpecVersion fork;
    private final BigInteger chainId;
    private final long maxGasLimit;

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId) {
        this(fork, chainId, DEFAULT_MAX_GAS_LIMIT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit) {
        this.fork = fork;
        this.chainId = chainId;
        this.maxGasLimit = maxGasLimit;
    }

    /** Mainnet configuration: Shanghai fork + provisional mainnet chain id. */
    public static EvmConfig mainnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, MAINNET_CHAIN_ID);
    }

    /** Testnet configuration: Shanghai fork + provisional testnet chain id. */
    public static EvmConfig testnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, TESTNET_CHAIN_ID);
    }

    /** Default development configuration: Shanghai fork + provisional devnet chain id. */
    public static EvmConfig devnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, DEVNET_CHAIN_ID);
    }

    public EvmSpecVersion fork() {
        return fork;
    }

    public BigInteger chainId() {
        return chainId;
    }

    /** Upper bound on the gas a single deploy/call may request (S-25 ceiling). */
    public long maxGasLimit() {
        return maxGasLimit;
    }
}
