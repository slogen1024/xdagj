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

    /** EIP-3529 refund cap denominator from the configured fork's gas calculator (5 on Shanghai). */
    public long maxRefundQuotient() {
        return evm.getGasCalculator().getMaxRefundQuotient();
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
        return executeDeploy(parent, sender, initCode, value, gasLimit, blockValues, coinbase, true);
    }

    /**
     * Read-only contract creation for {@code eth_call}/{@code eth_estimateGas}: runs exactly like
     * {@link #deploy} but NEVER commits, so neither the sender nonce bump nor the deployed code is
     * persisted to {@code parent} (and therefore never to the backing store). The caller MUST discard
     * {@code parent} afterwards. This is the only safe way to drive the executor from a JSON-RPC
     * simulation — passing a child updater does not help, because the executor commits its {@code
     * parent}, which would flush the child into the root store.
     */
    public XdagExecutionResult simulateDeploy(WorldUpdater parent, Address sender, Bytes initCode, Wei value,
                                              long gasLimit) {
        return executeDeploy(parent, sender, initCode, value, gasLimit, new SimpleBlockValues(), Address.ZERO, false);
    }

    private XdagExecutionResult executeDeploy(WorldUpdater parent, Address sender, Bytes initCode, Wei value,
                                              long gasLimit, BlockValues blockValues, Address coinbase,
                                              boolean commit) {
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
        // Simulation (commit == false, eth_call/eth_estimateGas) skips this entirely — nothing persists.
        if (commit) {
            parent.commit();
        }
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
        return executeCall(parent, sender, to, callData, value, gasLimit, blockValues, coinbase, true);
    }

    /**
     * Read-only invocation for {@code eth_call}/{@code eth_estimateGas}: runs exactly like {@link #call}
     * but NEVER commits, so no state change (SSTORE, balance transfer, SELFDESTRUCT, ...) reaches
     * {@code parent} or the backing store. The caller MUST discard {@code parent} afterwards.
     */
    public XdagExecutionResult simulateCall(WorldUpdater parent, Address sender, Address to, Bytes callData,
                                            Wei value, long gasLimit) {
        return executeCall(parent, sender, to, callData, value, gasLimit, new SimpleBlockValues(), Address.ZERO,
                false);
    }

    private XdagExecutionResult executeCall(WorldUpdater parent, Address sender, Address to, Bytes callData, Wei value,
                                            long gasLimit, BlockValues blockValues, Address coinbase, boolean commit) {
        requireGasWithinLimit(gasLimit);
        MutableAccount toAccount = parent.getAccount(to);
        Bytes code = (toAccount == null) ? Bytes.EMPTY : toAccount.getCode();

        MessageFrame frame = buildFrame(MessageFrame.Type.MESSAGE_CALL, parent.updater(),
                sender, to, to, code, callData, value, gasLimit, blockValues, coinbase);
        runToHalt(frame);

        boolean success = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS;
        // Simulation (commit == false) never persists, so eth_call/eth_estimateGas stay side-effect-free.
        if (commit && success) {
            parent.commit();
        }
        return collect(frame, gasLimit, Optional.empty());
    }

    /**
     * Test/bootstrap helper: place runtime code directly at an address and persist it.
     *
     * <p>Pass the ROOT updater (e.g. the {@code SimpleWorld} itself), not a child: Besu's
     * {@code SimpleAccount.commit()} merges nonce/balance/storage but NOT code, so code written
     * through a child updater is silently dropped when the child commits into its parent.
     * ({@code RocksDbAccount.commit()} does merge code, so RocksDB-backed children are safe.)
     */
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
        // getOutputData() is what THIS frame produced via RETURN/REVERT; getReturnData() is the
        // buffer a child call returned into this frame (RETURNDATACOPY's source) — always empty
        // for a top-level frame that made no sub-calls.
        return new XdagExecutionResult(
                success,
                frame.getOutputData(),
                gasUsed,
                success ? frame.getGasRefund() : 0L, // raw EIP-3529 refund (0 on failure/halt); EvmBlockProcessor caps + applies it
                frame.getLogs(),
                frame.getRevertReason(),
                success ? createdContract : Optional.empty());
    }
}
