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
 * in Sub-project B/C. The fork is fixed at Shanghai: it enables {@code PUSH0} (required by
 * modern Solidity) with legacy gas semantics and no blob/KZG machinery.
 */
public final class EvmConfig {

    /** Provisional devnet chain id; replace with reserved values in Sub-project B/C. */
    public static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE); // 51966 (provisional)

    private final EvmSpecVersion fork;
    private final BigInteger chainId;

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId) {
        this.fork = fork;
        this.chainId = chainId;
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
}
