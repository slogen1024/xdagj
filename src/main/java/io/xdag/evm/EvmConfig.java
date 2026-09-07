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
 * Immutable EVM execution configuration: target fork, chain id, gas ceiling, price floor, and consensus activation heights (type-2, bridge, EIP-3529).
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

    /** Default EIP-3529 activation: always active (tests/devnet factory); production passes the spec value. */
    public static final long DEFAULT_EIP3529_ACTIVATION_HEIGHT = 0L;

    /**
     * Default invalid-tx-skip activation (audit round 2, P3): always active (tests/devnet factory);
     * production passes the spec value.
     */
    public static final long DEFAULT_INVALID_TX_SKIP_ACTIVATION_HEIGHT = 0L;

    /**
     * Default execution-semantics-v2 activation (audit round 2 E1/E2/E4/E5/B2): always active
     * (tests/devnet factory); production passes the spec value.
     */
    public static final long DEFAULT_SEMANTICS_V2_ACTIVATION_HEIGHT = 0L;

    private final EvmSpecVersion fork;
    private final BigInteger chainId;
    private final long maxGasLimit;
    private final BigInteger minGasPrice;
    private final long type2ActivationHeight;
    private final long bridgeActivationHeight;
    private final long eip3529ActivationHeight;
    private final long invalidTxSkipActivationHeight;
    private final long semanticsV2ActivationHeight;

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
        this(fork, chainId, maxGasLimit, minGasPrice, type2ActivationHeight, bridgeActivationHeight,
                DEFAULT_EIP3529_ACTIVATION_HEIGHT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice,
                     long type2ActivationHeight, long bridgeActivationHeight, long eip3529ActivationHeight) {
        this(fork, chainId, maxGasLimit, minGasPrice, type2ActivationHeight, bridgeActivationHeight,
                eip3529ActivationHeight, DEFAULT_INVALID_TX_SKIP_ACTIVATION_HEIGHT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice,
                     long type2ActivationHeight, long bridgeActivationHeight, long eip3529ActivationHeight,
                     long invalidTxSkipActivationHeight) {
        this(fork, chainId, maxGasLimit, minGasPrice, type2ActivationHeight, bridgeActivationHeight,
                eip3529ActivationHeight, invalidTxSkipActivationHeight, DEFAULT_SEMANTICS_V2_ACTIVATION_HEIGHT);
    }

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId, long maxGasLimit, BigInteger minGasPrice,
                     long type2ActivationHeight, long bridgeActivationHeight, long eip3529ActivationHeight,
                     long invalidTxSkipActivationHeight, long semanticsV2ActivationHeight) {
        this.fork = fork;
        this.chainId = chainId;
        this.maxGasLimit = maxGasLimit;
        this.minGasPrice = minGasPrice;
        this.type2ActivationHeight = type2ActivationHeight;
        this.bridgeActivationHeight = bridgeActivationHeight;
        this.eip3529ActivationHeight = eip3529ActivationHeight;
        this.invalidTxSkipActivationHeight = invalidTxSkipActivationHeight;
        this.semanticsV2ActivationHeight = semanticsV2ActivationHeight;
    }

    /**
     * Mainnet configuration: Shanghai fork + reserved mainnet chain id. Type-2 and EIP-3529 are
     * always active here (test/tooling default); production networks pass evm.type2ActivationHeight
     * and evm.eip3529ActivationHeight explicitly.
     */
    public static EvmConfig mainnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, MAINNET_CHAIN_ID);
    }

    /**
     * Testnet configuration: Shanghai fork + reserved testnet chain id. Type-2 and EIP-3529 are
     * always active here (test/tooling default); production networks pass evm.type2ActivationHeight
     * and evm.eip3529ActivationHeight explicitly.
     */
    public static EvmConfig testnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, TESTNET_CHAIN_ID);
    }

    /**
     * Default development configuration: Shanghai fork + reserved devnet chain id. Type-2 and
     * EIP-3529 are always active here (test/tooling defaults); production networks pass
     * evm.type2ActivationHeight and evm.eip3529ActivationHeight explicitly.
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

    /** Height at which EIP-3529 precise gas refunds activate; consensus-gated in EvmBlockProcessor. */
    public long eip3529ActivationHeight() {
        return eip3529ActivationHeight;
    }

    /**
     * Height from which validation-failed tx refs are dropped instead of receipted (audit round 2, P3);
     * consensus-gated in EvmBlockProcessor.
     */
    public long invalidTxSkipActivationHeight() {
        return invalidTxSkipActivationHeight;
    }

    /**
     * Height from which the execution-semantics-v2 fork pack applies (audit round 2 E1/E2/E4/E5/B2);
     * consensus-gated in EvmBlockProcessor / XdagEvmExecutor.
     */
    public long semanticsV2ActivationHeight() {
        return semanticsV2ActivationHeight;
    }
}
