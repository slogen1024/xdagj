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
package io.xdag.config;

import io.xdag.config.spec.EvmSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The EVM parameters that are CONSENSUS on a network (audit round 2, E6): they decide receipt status,
 * the per-block gas budget, the GASLIMIT opcode, the bridge release schedule and every fork height —
 * hence the chained state root. Two nodes that disagree on any of them fork. On a shared network they
 * are therefore pinned here in code (like an Ethereum chain config); the loaded HOCON must match or the
 * node refuses to start ({@link AbstractConfig#pinnedEvmConsensusParams()}). Devnet is not pinned.
 * Changing a pinned value is a coordinated hard fork = a code release, never a config edit.
 */
public record EvmConsensusParams(long chainId, long blockGasLimit, BigInteger minGasPrice, long stateRootLag,
                                 long bridgeWithdrawalDelay, String bridgeRecoveryAddress,
                                 boolean genesisAllocEmpty, long activationHeight, long batchActivationHeight,
                                 long type2ActivationHeight, long bridgeActivationHeight,
                                 long eip3529ActivationHeight, long feeRewardActivationHeight,
                                 long invalidTxSkipActivationHeight, long semanticsV2ActivationHeight,
                                 long stateRootActivationHeight) {

    private static final long UNSCHEDULED = Long.MAX_VALUE;

    /** Testnet (chain id 51965 / 0xCAFD): every fork unscheduled, framework economic defaults. */
    public static final EvmConsensusParams TESTNET = new EvmConsensusParams(51965L, 30_000_000L,
            BigInteger.valueOf(1_000_000_000L), 16L, 16L, null, true, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED,
            UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED);

    /** Mainnet (chain id 51964 / 0xCAFC): every fork unscheduled, framework economic defaults. */
    public static final EvmConsensusParams MAINNET = new EvmConsensusParams(51964L, 30_000_000L,
            BigInteger.valueOf(1_000_000_000L), 16L, 16L, null, true, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED,
            UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED, UNSCHEDULED);

    public EvmConsensusParams withBlockGasLimit(long value) {
        return new EvmConsensusParams(chainId, value, minGasPrice, stateRootLag, bridgeWithdrawalDelay,
                bridgeRecoveryAddress, genesisAllocEmpty, activationHeight, batchActivationHeight,
                type2ActivationHeight, bridgeActivationHeight, eip3529ActivationHeight, feeRewardActivationHeight,
                invalidTxSkipActivationHeight, semanticsV2ActivationHeight, stateRootActivationHeight);
    }

    public EvmConsensusParams withMinGasPrice(BigInteger value) {
        return new EvmConsensusParams(chainId, blockGasLimit, value, stateRootLag, bridgeWithdrawalDelay,
                bridgeRecoveryAddress, genesisAllocEmpty, activationHeight, batchActivationHeight,
                type2ActivationHeight, bridgeActivationHeight, eip3529ActivationHeight, feeRewardActivationHeight,
                invalidTxSkipActivationHeight, semanticsV2ActivationHeight, stateRootActivationHeight);
    }

    /**
     * Every pinned key whose EFFECTIVE value in {@code spec} differs, as "key: expected X, got Y" lines.
     * Empty = the loaded configuration is consensus-identical to this network's constants.
     */
    public List<String> mismatches(EvmSpec spec) {
        List<String> out = new ArrayList<>();
        check(out, "evm.chainId", chainId, spec.getEvmChainId());
        check(out, "evm.blockGasLimit", blockGasLimit, spec.getEvmBlockGasLimit());
        check(out, "evm.minGasPrice", minGasPrice, spec.getEvmMinGasPrice());
        check(out, "evm.stateRootLag", stateRootLag, spec.getEvmStateRootLag());
        check(out, "evm.bridgeWithdrawalDelay", bridgeWithdrawalDelay, spec.getEvmBridgeWithdrawalDelay());
        check(out, "evm.bridgeRecoveryAddress", normalize(bridgeRecoveryAddress),
                normalize(spec.getEvmBridgeRecoveryAddress()));
        check(out, "evm.alloc (empty)", genesisAllocEmpty,
                spec.getEvmGenesisAlloc() == null || spec.getEvmGenesisAlloc().isEmpty());
        check(out, "evm.activationHeight", activationHeight, spec.getEvmActivationHeight());
        check(out, "evm.batchActivationHeight", batchActivationHeight, spec.getEvmBatchActivationHeight());
        check(out, "evm.type2ActivationHeight", type2ActivationHeight, spec.getEvmType2ActivationHeight());
        check(out, "evm.bridgeActivationHeight", bridgeActivationHeight, spec.getEvmBridgeActivationHeight());
        check(out, "evm.eip3529ActivationHeight", eip3529ActivationHeight, spec.getEvmEip3529ActivationHeight());
        check(out, "evm.feeRewardActivationHeight", feeRewardActivationHeight, spec.getEvmFeeRewardActivationHeight());
        check(out, "evm.invalidTxSkipActivationHeight", invalidTxSkipActivationHeight,
                spec.getEvmInvalidTxSkipActivationHeight());
        check(out, "evm.semanticsV2ActivationHeight", semanticsV2ActivationHeight,
                spec.getEvmSemanticsV2ActivationHeight());
        check(out, "evm.stateRootActivationHeight", stateRootActivationHeight, spec.getEvmStateRootActivationHeight());
        return out;
    }

    private static String normalize(String address) {
        return address == null || address.isBlank() ? "" : address.trim().toLowerCase();
    }

    private static void check(List<String> out, String key, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            out.add(key + ": pinned " + expected + ", configured " + actual);
        }
    }
}
