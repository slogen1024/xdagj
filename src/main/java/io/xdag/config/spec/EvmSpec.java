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
package io.xdag.config.spec;

import io.xdag.evm.GenesisAllocEntry;
import java.math.BigInteger;
import java.util.List;

/**
 * The Embedded-EVM specification section in the configuration (spec §9). All values come from the
 * per-network HOCON {@code evm} block; the EVM is fully disabled unless {@code evm.enabled = true}.
 */
public interface EvmSpec {

    boolean isEvmEnabled();

    /** Main-block height at which EVM_TX_REF fields become valid (hard-fork gate). */
    long getEvmActivationHeight();

    /**
     * Main-block height at which the miner switches the 0x0F ref from a single tx hash to a
     * batch commitment (batch D2 fork). Defaults to {@code Long.MAX_VALUE} (not activated).
     */
    long getEvmBatchActivationHeight();

    /** Height at which type-2 (EIP-1559) transactions activate; Long.MAX_VALUE = not scheduled. */
    long getEvmType2ActivationHeight();

    /** Height at which the XDAG<->EVM bridge activates; Long.MAX_VALUE = not scheduled. */
    long getEvmBridgeActivationHeight();

    /**
     * EVM address minted to when a deposit's remark cannot be decoded (spec §2.2). Hex string
     * (0x + 40); null only when the bridge is not scheduled — networks that activate the bridge
     * MUST set it (fail-fast at config load).
     */
    String getEvmBridgeRecoveryAddress();

    /** EIP-155 chain id for this network (devnet provisional: 51966 = 0xCAFE). */
    long getEvmChainId();

    /** Per-message gas ceiling and miner block gas budget. */
    long getEvmBlockGasLimit();

    long getEvmTxPoolTtlSeconds();

    /** Upper bound for EVM tx gossip payloads (P2P DoS guard). */
    int getEvmMaxP2pTxBytes();

    /** Minimum gas price in wei a pool/miner accepts. */
    BigInteger getEvmMinGasPrice();

    /** Maximum block span an eth_getLogs query may scan (DoS guard). */
    long getEvmMaxLogScanRange();

    /** Recent heights whose historical world state is retrievable via a block tag (C4 window). */
    int getEvmStateHistoryWindow();

    /** Genesis pre-funding (the funding on-ramp): addresses credited with wei at chain genesis. */
    List<GenesisAllocEntry> getEvmGenesisAlloc();
}
