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
 * Immutable EVM execution configuration: target fork, chain id, gas ceiling, price floor, and the type-2/bridge activation heights.
 *
 * <p>The {@code CHAINID} opcode reads {@link #chainId()}. The mainnet/testnet/devnet chain-id
 * triple below is the reserved (defect-4) value set — DISTINCT per network so signed-tx replay
 * across networks is prevented. At runtime the per-network HOCON {@code evm.chainId} key is
 * authoritative; these constants are the test/tooling defaults. Confirm no collision on
 * ethereum-lists/chains before launch. The fork is fixed at Shanghai: it enables {@code PUSH0}
 * (required by modern Solidity) with legacy gas semantics and no blob/KZG machinery.
 */
public final class EvmConfig {

    /** Reserved mainnet chain id (confirm no collision on ethereum-lists/chains before launch). */
    public static final BigInteger MAINNET_CHAIN_ID = BigInteger.valueOf(0xCAFC); // 51964

    /** Reserved testnet chain id (confirm no collision on ethereum-lists/chains before launch). */
    public static final BigInteger TESTNET_CHAIN_ID = BigInteger.valueOf(0xCAFD); // 51965

    /** Reserved devnet chain id. */
    public static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE); // 51966

    /** Default ceiling on the gas a single message may request (Ethereum-style block gas limit). */
    public static final long DEFAULT_MAX_GAS_LIMIT = 30_000_000L;

    /** Default minimum gas price in wei; execution rejects any tx priced below it (anti-spam floor). */
    public static final BigInteger DEFAULT_MIN_GAS_PRICE = BigInteger.ONE;

    /** Default type-2 activation: always active (tests/devnet factory); production passes the spec value. */
    public static final long DEFAULT_TYPE2_ACTIVATION_HEIGHT = 0L;

    /**
     * Default bridge activation: NOT scheduled. Unlike type2ActivationHeight (default active for
     * tests), bridge activation gates contract-code SEEDING into EVM_STATE — an always-active
     * default would silently plant the contract in every unrelated test's world state.
     */
    public static final long DEFAULT_BRIDGE_ACTIVATION_HEIGHT = Long.MAX_VALUE;

    private final EvmSpecVersion fork;
    private final BigInteger chainId;
    private final long maxGasLimit;
    private final BigInteger minGasPrice;
    private final long type2ActivationHeight;
    private final long bridgeActivationHeight;

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId) {
        this(fork, chainId, DEFAULT_MAX_GAS_LIMIT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit) {
        this(fork, chainId, maxGasLimit, DEFAULT_MIN_GAS_PRICE);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice) {
        this(fork, chainId, maxGasLimit, minGasPrice, DEFAULT_TYPE2_ACTIVATION_HEIGHT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice,
                     long type2ActivationHeight) {
        this(fork, chainId, maxGasLimit, minGasPrice, type2ActivationHeight, DEFAULT_BRIDGE_ACTIVATION_HEIGHT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice,
                     long type2ActivationHeight, long bridgeActivationHeight) {
        this.fork = fork;
        this.chainId = chainId;
        this.maxGasLimit = maxGasLimit;
        this.minGasPrice = minGasPrice;
        this.type2ActivationHeight = type2ActivationHeight;
        this.bridgeActivationHeight = bridgeActivationHeight;
    }

    /**
     * Mainnet configuration: Shanghai fork + reserved mainnet chain id. Type-2 is always active
     * here (test/tooling default); production networks pass evm.type2ActivationHeight explicitly.
     */
    public static EvmConfig mainnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, MAINNET_CHAIN_ID);
    }

    /**
     * Testnet configuration: Shanghai fork + reserved testnet chain id. Type-2 is always active
     * here (test/tooling default); production networks pass evm.type2ActivationHeight explicitly.
     */
    public static EvmConfig testnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, TESTNET_CHAIN_ID);
    }

    /**
     * Default development configuration: Shanghai fork + reserved devnet chain id. Type-2 is
     * always active here (test/tooling default); production networks pass evm.type2ActivationHeight
     * explicitly.
     */
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

    /** Minimum gas price in wei; execution rejects any tx priced below it (anti-spam floor). */
    public BigInteger minGasPrice() {
        return minGasPrice;
    }

    /** Height at which type-2 (EIP-1559) txs activate; consensus-gated in EvmBlockProcessor. */
    public long type2ActivationHeight() {
        return type2ActivationHeight;
    }

    /** Height at which the XDAG<->EVM bridge activates (gates contract seeding); MAX_VALUE = not scheduled. */
    public long bridgeActivationHeight() {
        return bridgeActivationHeight;
    }
}
