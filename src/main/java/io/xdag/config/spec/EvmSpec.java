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

    /** Height at which EIP-3529 precise gas refunds activate; Long.MAX_VALUE = not scheduled. */
    long getEvmEip3529ActivationHeight();

    /**
     * Height from which a tx ref that FAILS VALIDATION (bad nonce, unaffordable, wrong chain, undecodable,
     * ...) is dropped from the block instead of being recorded as a status-0 receipt (audit round 2, P3:
     * the receipt consumed the tx hash forever, letting any miner burn a pending tx at zero cost).
     * Long.MAX_VALUE = not scheduled (legacy receipt behaviour, byte-identical chained roots).
     */
    long getEvmInvalidTxSkipActivationHeight();

    /**
     * Height from which the "execution semantics v2" fork pack applies (audit round 2 E1/E2/E4/E5/B2):
     * SELFDESTRUCT deletes the account + EIP-161 touched-empty cleanup, BASEFEE reads 0 (was an
     * exceptional halt), GASPRICE reads the effective price (was 0), BLOCKHASH resolves main-block
     * hashes (was 0), EIP-170 / EIP-3541 creation rules, transaction-start "original" storage values,
     * and failed executions surface no logs. Long.MAX_VALUE = not scheduled (legacy semantics).
     */
    long getEvmSemanticsV2ActivationHeight();

    /**
     * Height at which the net EVM fee is credited to the miner reward pool (ADR-016 / G3-T1) instead
     * of burned. MAX_VALUE = not scheduled (fee stays burned; native accounting byte-identical).
     */
    long getEvmFeeRewardActivationHeight();

    /** Height at which the miner starts embedding EVM state-root anchors; Long.MAX_VALUE = not scheduled. */
    long getEvmStateRootActivationHeight();

    /** Lag delta (in main heights): a block at height H anchors the chained root as of H-delta. Must be >= 1. */
    long getEvmStateRootLag();

    /** Whether a state-root anchor mismatch hard-rejects the block (true) or only warns (false). */
    boolean isEvmStateRootHardReject();

    /**
     * EVM address minted to when a deposit's remark cannot be decoded (spec §2.2). Hex string
     * (0x + 40); null only when the bridge is not scheduled — networks that activate the bridge
     * MUST set it (fail-fast at config load).
     */
    String getEvmBridgeRecoveryAddress();

    /** Main blocks between an EVM burn and its native release (spec §3.2 N); devnet 2, recommended shared-net 16. */
    long getEvmBridgeWithdrawalDelay();

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
