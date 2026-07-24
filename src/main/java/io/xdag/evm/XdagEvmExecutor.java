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

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleBlockValues;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * Embeds the Hyperledger Besu EVM (Shanghai) and runs contract deployments and calls over a
 * {@link WorldUpdater}-backed world state.
 *
 * <p>The execution follows Besu's own canonical embedding pattern (see {@code EVMExecutor}): build a
 * {@link MessageFrame} whose world updater is a child of the caller's, drive every frame on the stack
 * through the matching processor until the stack empties, then commit the caller's updater on success.
 *
 * <p>State is whatever {@link WorldUpdater} the caller supplies. Sub-project A passes Besu's in-memory
 * {@code SimpleWorld}; Sub-project B will supply a RocksDB-backed {@link WorldUpdater} with no change
 * to this class.
 */
public final class XdagEvmExecutor {

    private final EVM evm;
    private final MessageCallProcessor callProcessor;
    private final ContractCreationProcessor creationProcessor;
    /** Upper bound on the gas a single message may request (S-25); see {@link EvmConfig#maxGasLimit()}. */
    private final long maxGasLimit;

    public XdagEvmExecutor(EvmConfig config) {
        this.maxGasLimit = config.maxGasLimit();
        // Shanghai interpreter for the configured chain id.
        this.evm = MainnetEVMs.shanghai(config.chainId(), EvmConfiguration.DEFAULT);
        // Shanghai adds no precompiles beyond Istanbul; the Istanbul registry is the correct set.
        PrecompileContractRegistry precompiles =
                MainnetPrecompiledContracts.istanbul(evm.getGasCalculator());
        this.callProcessor = new MessageCallProcessor(evm, precompiles);
        // requireCodeDepositToSucceed=true, no extra validation rules, initial contract nonce = 1.
        this.creationProcessor = new ContractCreationProcessor(evm, true, List.of(), 1L);
    }

    /**
     * Deploy a contract with a default (empty) block context. {@code initCode} is the creation
     * bytecode with any ABI-encoded constructor arguments appended (standard CREATE semantics). On
     * success the runtime code is deposited at the derived address and that address is returned.
     *
     * <p>The block context here is all-zero (number/timestamp/gasLimit = 0, coinbase = {@link
     * Address#ZERO}), which is fine for tests and context-free deployments. PRODUCTION on-chain
     * execution must call {@link #deploy(WorldUpdater, Address, Bytes, Wei, long, BlockValues, Address)}
     * so the NUMBER/TIMESTAMP/GASLIMIT/COINBASE/PREVRANDAO opcodes observe the real block.
     */
    public XdagExecutionResult deploy(WorldUpdater parent, Address sender, Bytes initCode, Wei value, long gasLimit) {
        return deploy(parent, sender, initCode, value, gasLimit, new SimpleBlockValues(), Address.ZERO);
    }

    /**
     * Deploy a contract against an explicit block context (real block number/timestamp/coinbase/...),
     * supplied by the on-chain caller so block-introspection opcodes read true values.
     */
    public XdagExecutionResult deploy(WorldUpdater parent, Address sender, Bytes initCode, Wei value, long gasLimit,
                                      BlockValues blockValues, Address coinbase) {
        requireGasWithinLimit(gasLimit);
        MutableAccount senderAccount = parent.getOrCreate(sender);
        long nonce = senderAccount.getNonce();
        Address contract = Address.contractAddress(sender, nonce);
        senderAccount.setNonce(nonce + 1);

        MessageFrame frame = buildFrame(MessageFrame.Type.CONTRACT_CREATION, parent.updater(),
                sender, contract, contract, initCode, Bytes.EMPTY, value, gasLimit, blockValues, coinbase);
        runToHalt(frame);

        boolean success = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS;
        // S-24: persist the sender nonce bump whether or not execution succeeded, matching Ethereum
        // (a failed transaction still consumes its nonce, preventing replay / contract-address reuse).
        // On success the EVM has already pushed the execution's state changes up to `parent`, so this
        // commit persists nonce + state. On failure the reverted child updater was never committed, so
        // only the nonce bump lives on `parent`; committing therefore persists the nonce alone.
        parent.commit();
        return collect(frame, gasLimit, success ? Optional.of(contract) : Optional.empty());
    }

    /**
     * Invoke a function on an existing contract (or send value to an account) with a default (empty)
     * block context. PRODUCTION on-chain execution must call the
     * {@link #call(WorldUpdater, Address, Address, Bytes, Wei, long, BlockValues, Address)} overload.
     */
    public XdagExecutionResult call(WorldUpdater parent, Address sender, Address to, Bytes callData, Wei value,
                                    long gasLimit) {
        return call(parent, sender, to, callData, value, gasLimit, new SimpleBlockValues(), Address.ZERO);
    }

    /** Invoke a contract against an explicit block context (real block number/timestamp/coinbase/...). */
    public XdagExecutionResult call(WorldUpdater parent, Address sender, Address to, Bytes callData, Wei value,
                                    long gasLimit, BlockValues blockValues, Address coinbase) {
        requireGasWithinLimit(gasLimit);
        MutableAccount toAccount = parent.getAccount(to);
        Bytes code = (toAccount == null) ? Bytes.EMPTY : toAccount.getCode();

        MessageFrame frame = buildFrame(MessageFrame.Type.MESSAGE_CALL, parent.updater(),
                sender, to, to, code, callData, value, gasLimit, blockValues, coinbase);
        runToHalt(frame);

        boolean success = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS;
        if (success) {
            parent.commit();
        }
        return collect(frame, gasLimit, Optional.empty());
    }

    /** Test/bootstrap helper: place runtime code directly at an address and persist it. */
    public void setCode(WorldUpdater parent, Address address, Bytes runtimeCode) {
        MutableAccount account = parent.getOrCreate(address);
        account.setCode(runtimeCode);
        parent.commit();
    }

    private MessageFrame buildFrame(MessageFrame.Type type, WorldUpdater updater, Address sender,
                                    Address address, Address contract, Bytes code, Bytes input,
                                    Wei value, long gas, BlockValues blockValues, Address coinbase) {
        return MessageFrame.builder()
                .type(type)
                .worldUpdater(updater)
                .initialGas(gas)
                .address(address)
                .contract(contract)
                .sender(sender)
                .originator(sender)
                .gasPrice(Wei.ZERO)
                .blobGasPrice(Wei.ZERO)
                .value(value)
                .apparentValue(value)
                .inputData(input)
                .code(new Code(code))
                .blockValues(blockValues)
                .miningBeneficiary(coinbase)
                // S-26: BLOCKHASH of an unknown/out-of-range block must yield zero, never null — a null
                // would NPE inside Besu's BlockHashOperation. Sub-project B/C supplies a real lookup.
                .blockHashLookup((frame, blockNumber) -> Hash.ZERO)
                .completer(f -> {
                })
                .build();
    }

    private void runToHalt(MessageFrame initialFrame) {
        Deque<MessageFrame> stack = initialFrame.getMessageFrameStack();
        try {
            while (!stack.isEmpty()) {
                MessageFrame frame = stack.peek();
                switch (frame.getType()) {
                    case CONTRACT_CREATION -> creationProcessor.process(frame, OperationTracer.NO_TRACING);
                    case MESSAGE_CALL -> callProcessor.process(frame, OperationTracer.NO_TRACING);
                }
            }
        } catch (RuntimeException e) {
            // S-26: an unexpected processor/interpreter error must never crash the node. Degrade it to
            // an exceptional halt (revert-like) that consumes all remaining gas. The caller's child
            // world updater is not committed, so the failed execution leaks no state, and collect()
            // then reports success == false.
            long remaining = initialFrame.getRemainingGas();
            if (remaining > 0L) {
                initialFrame.decrementRemainingGas(remaining);
            }
            initialFrame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
        }
    }

    /** Reject a gas limit that is non-positive or above the configured ceiling (S-25). */
    private void requireGasWithinLimit(long gasLimit) {
        if (gasLimit <= 0L || gasLimit > maxGasLimit) {
            throw new IllegalArgumentException("gasLimit " + gasLimit + " out of range (1.." + maxGasLimit + ")");
        }
        // NOTE: transaction-style intrinsic gas (21000 base + per-calldata-byte + access list) is NOT
        // charged here; this executor runs a single message. A future tx layer must deduct intrinsic
        // gas from gasLimit before invoking deploy/call.
    }

    private XdagExecutionResult collect(MessageFrame frame, long gasLimit, Optional<Address> createdContract) {
        boolean success = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS;
        long gasUsed = gasLimit - frame.getRemainingGas();
        return new XdagExecutionResult(
                success,
                frame.getReturnData(),
                gasUsed,
                frame.getLogs(),
                frame.getRevertReason(),
                success ? createdContract : Optional.empty());
    }
}
