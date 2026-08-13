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
package io.xdag.rpc.server.handler;

import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.XdagEvmExecutor;
import io.xdag.evm.XdagExecutionResult;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.evm.state.StateUnavailableException;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.evm.tx.IntrinsicGas;
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.eth.EthObjects;
import io.xdag.rpc.eth.LogFilter;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/** Serves the read-only Ethereum JSON-RPC surface (sub-project C1) over the EVM_STATE world state. */
@Slf4j
public class EthRequestHandler implements JsonRpcRequestHandler {

    private static final String CLIENT_VERSION = "xdagj/0.8.3";

    private static final Set<String> SUPPORTED = Set.of(
            "eth_chainId", "net_version", "web3_clientVersion", "eth_gasPrice", "eth_blockNumber",
            "eth_getBalance", "eth_getTransactionCount", "eth_getCode", "eth_getStorageAt",
            "eth_call", "eth_estimateGas", "eth_accounts", "net_listening", "eth_sendRawTransaction",
            "eth_getTransactionByHash", "eth_getTransactionReceipt", "eth_getBlockByNumber",
            "eth_getBlockByHash", "eth_getLogs");

    private final EvmConfig evmConfig;
    private final BigInteger minGasPriceWei;
    private final Blockchain blockchain;
    private final XdagEvmExecutor executor;
    /** Null when EVM writes are not enabled; then eth_sendRawTransaction errors. */
    private final EvmTxPool evmTxPool;
    /** Publishes an accepted blob to peers; null-safe no-op when absent. */
    private final Consumer<Bytes> broadcaster;
    /** Null when EVM disabled; query methods return null without it. */
    private final EvmTxStore evmTxStore;
    private final EvmMetaStore evmMetaStore;
    /** eth_getLogs range cap (used in getLogs). */
    private final long maxLogScanRange;
    /** Reconstructs past world state within the retained window for block-tag reads (C4). */
    private final HistoricalStateReader historical;

    public EthRequestHandler(EvmConfig evmConfig,
                             BigInteger minGasPriceWei, Blockchain blockchain, EvmTxPool evmTxPool,
                             Consumer<Bytes> broadcaster, EvmTxStore evmTxStore, EvmMetaStore evmMetaStore,
                             long maxLogScanRange, HistoricalStateReader historical) {
        this.evmConfig = evmConfig;
        this.minGasPriceWei = minGasPriceWei;
        this.blockchain = blockchain;
        this.executor = new XdagEvmExecutor(evmConfig);
        this.evmTxPool = evmTxPool;
        this.broadcaster = broadcaster;
        this.evmTxStore = evmTxStore;
        this.evmMetaStore = evmMetaStore;
        this.maxLogScanRange = maxLogScanRange;
        this.historical = historical;
    }

    @Override
    public boolean supportsMethod(String methodName) {
        return methodName != null && SUPPORTED.contains(methodName);
    }

    @Override
    public Object handle(JsonRpcRequest request) throws JsonRpcException {
        if (request == null) {
            throw JsonRpcException.invalidParams("Request cannot be null");
        }
        String method = request.getMethod();
        try {
            return switch (method) {
                case "eth_chainId" -> EthHex.quantity(evmConfig.chainId());
                case "net_version" -> evmConfig.chainId().toString();
                case "web3_clientVersion" -> CLIENT_VERSION;
                case "eth_gasPrice" -> EthHex.quantity(minGasPriceWei);
                case "eth_blockNumber" -> EthHex.quantity(blockchain.getLatestMainBlockNumber());
                case "eth_accounts" -> List.of();
                case "net_listening" -> Boolean.TRUE;
                case "eth_getBalance" -> {
                    Address addr = addressParam(request, 0);
                    Account a = account(addr, request, 1);
                    yield EthHex.quantity(a == null ? BigInteger.ZERO : a.getBalance().getAsBigInteger());
                }
                case "eth_getTransactionCount" -> {
                    Address addr = addressParam(request, 0);
                    Account a = account(addr, request, 1);
                    yield EthHex.quantity(a == null ? 0L : a.getNonce());
                }
                case "eth_getCode" -> {
                    Address addr = addressParam(request, 0);
                    Account a = account(addr, request, 1);
                    yield EthHex.data(a == null ? Bytes.EMPTY : a.getCode());
                }
                case "eth_getStorageAt" -> {
                    Address addr = addressParam(request, 0);
                    UInt256 slot = UInt256.valueOf(EthHex.decodeQuantity(stringParam(request, 1)));
                    Account a = account(addr, request, 2);
                    UInt256 value = a == null ? UInt256.ZERO : a.getStorageValue(slot);
                    yield EthHex.data(value.toBytes());
                }
                case "eth_call" -> {
                    XdagExecutionResult r = simulate(callObject(request, 0), historicalWorld(request, 1));
                    if (!r.success()) {
                        throw JsonRpcException.internalError(revertMessage(r));
                    }
                    yield EthHex.data(r.returnData());
                }
                case "eth_estimateGas" -> {
                    CallArgs args = callObject(request, 0);
                    XdagExecutionResult r = simulate(args, historicalWorld(request, 1));
                    if (!r.success()) {
                        throw JsonRpcException.internalError(revertMessage(r));
                    }
                    long intrinsic = IntrinsicGas.compute(args.data(), args.to() == null);
                    yield EthHex.quantity(intrinsic + r.gasUsed());
                }
                case "eth_sendRawTransaction" -> sendRawTransaction(request);
                case "eth_getTransactionByHash" -> getTransactionByHash(request);
                case "eth_getTransactionReceipt" -> getTransactionReceipt(request);
                case "eth_getBlockByNumber" -> getBlockByNumber(request);
                case "eth_getBlockByHash" -> getBlockByHash(request);
                case "eth_getLogs" -> getLogs(request);
                default -> throw JsonRpcException.methodNotFound(method);
            };
        } catch (JsonRpcException e) {
            throw e;
        } catch (IllegalArgumentException | ClassCastException e) {
            // Malformed params (bad hex/address, wrong JSON type in a filter) are the client's fault.
            throw JsonRpcException.invalidParams(e.getMessage() == null ? "invalid params" : e.getMessage());
        } catch (StateUnavailableException e) {
            // The requested block tag resolves outside the retained history window (matches geth -32000).
            throw JsonRpcException.serverError("state at block " + EthHex.quantity(e.getHeight())
                    + " is not available (node retains a bounded history window)");
        } catch (RuntimeException e) {
            log.error("eth RPC error handling {}", method, e);
            throw JsonRpcException.internalError("internal error");
        }
    }

    /**
     * eth_sendRawTransaction (C2): validate + enqueue the signed EIP-155 blob, gossip it, and return
     * the Ethereum tx hash. A duplicate returns its hash without re-broadcast (Ethereum idempotency);
     * any pool rejection maps to a specific invalid-params error.
     */
    private String sendRawTransaction(JsonRpcRequest request) {
        if (evmTxPool == null) {
            throw JsonRpcException.internalError("EVM transactions are not enabled");
        }
        Bytes rlp = EthHex.decodeData(stringParam(request, 0));
        String hash;
        try {
            hash = EthHex.data(EvmTransaction.decode(rlp).getHash().getBytes());
        } catch (RuntimeException e) {
            throw JsonRpcException.invalidParams("invalid transaction RLP");
        }
        EvmTxPool.AddResult result = evmTxPool.add(rlp);
        switch (result) {
            case ADDED, REPLACED -> {
                if (broadcaster != null) {
                    broadcaster.accept(rlp);
                }
                return hash;
            }
            case DUPLICATE -> {
                return hash; // Ethereum idempotency: known tx returns its hash, no re-broadcast
            }
            case INVALID_ENCODING -> throw JsonRpcException.invalidParams("invalid transaction RLP");
            case WRONG_CHAIN_ID -> throw JsonRpcException.invalidParams("wrong chain id");
            case INVALID_SIGNATURE -> throw JsonRpcException.invalidParams("invalid signature");
            case GAS_LIMIT_TOO_HIGH -> throw JsonRpcException.invalidParams("gas limit exceeds block gas limit");
            case INTRINSIC_GAS_TOO_LOW -> throw JsonRpcException.invalidParams("intrinsic gas exceeds gas limit");
            case UNDERPRICED -> throw JsonRpcException.invalidParams("transaction underpriced");
            case NONCE_MISMATCH -> throw JsonRpcException.invalidParams("nonce mismatch");
            case INSUFFICIENT_BALANCE -> throw JsonRpcException.invalidParams("insufficient balance for value");
            case POOL_FULL -> throw JsonRpcException.invalidParams("transaction pool is full");
        }
        throw JsonRpcException.internalError("unreachable add result");
    }

    /**
     * O(1) reverse-index lookup of the tx's (height, index); empty if not on-chain (C3 §2.1). This
     * replaces a full scan of every height's tx list, which let a bogus hash force an unbounded
     * per-call walk of the whole chain (RPC DoS).
     */
    private Optional<EvmMetaStore.TxLocation> locate(Hash txHash) {
        return evmMetaStore == null ? Optional.empty() : evmMetaStore.findTxLocation(txHash);
    }

    /**
     * The one true eth block-hash for a height: the XDAG main block's full hash (C3 §2.0). Falls
     * back to the zero hash if the block is momentarily unresolvable, so a query never 500s over
     * missing block metadata.
     */
    private String blockHashAt(long height) {
        Block block = blockchain.getBlockByHeight(height);
        return block == null ? "0x" + "0".repeat(64) : EthHex.data(block.getHash());
    }

    private Object getTransactionByHash(JsonRpcRequest request) {
        Hash txHash = Hash.wrap(Bytes32.wrap(EthHex.decodeData(stringParam(request, 0))));
        Optional<EvmMetaStore.TxLocation> loc = locate(txHash);
        if (loc.isEmpty() || evmTxStore == null) {
            return null;
        }
        Optional<EvmTransaction> tx = evmTxStore.getDecoded(txHash);
        return tx.<Object>map(t -> EthObjects.transaction(t, loc.get().height(),
                blockHashAt(loc.get().height()), loc.get().index())).orElse(null);
    }

    private Object getTransactionReceipt(JsonRpcRequest request) {
        Hash txHash = Hash.wrap(Bytes32.wrap(EthHex.decodeData(stringParam(request, 0))));
        Optional<EvmMetaStore.TxLocation> loc = locate(txHash);
        if (loc.isEmpty() || evmTxStore == null || evmMetaStore == null) {
            return null;
        }
        Optional<EvmTransaction> tx = evmTxStore.getDecoded(txHash);
        Optional<EvmReceipt> receipt = evmMetaStore.getReceipt(txHash);
        if (tx.isEmpty() || receipt.isEmpty()) {
            return null;
        }
        String blockHash = blockHashAt(loc.get().height());
        List<Object> logs = buildLogs(receipt.get(), tx.get(), loc.get(), blockHash, 0);
        return EthObjects.receipt(tx.get(), receipt.get(), loc.get().height(), blockHash,
                loc.get().index(), logs);
    }

    /** Builds the log objects for one tx's receipt, numbering logIndex from {@code startLogIndex}. */
    private List<Object> buildLogs(EvmReceipt receipt, EvmTransaction tx, EvmMetaStore.TxLocation loc,
                                   String blockHash, int startLogIndex) {
        List<Object> out = new ArrayList<>();
        int logIndex = startLogIndex;
        for (Log log : receipt.logs()) {
            out.add(EthObjects.log(log, loc.height(), blockHash, tx.getHash(), loc.index(), logIndex++));
        }
        return out;
    }

    private Object getBlockByNumber(JsonRpcRequest request) {
        long head = blockchain.getLatestMainBlockNumber();
        long height = resolveHeight(stringParam(request, 0), head);
        if (height < 0 || height > head) {
            return null;
        }
        boolean fullTx = request.getParams().length > 1 && Boolean.TRUE.equals(request.getParams()[1]);
        return buildBlock(height, fullTx);
    }

    private Object getBlockByHash(JsonRpcRequest request) {
        Block block = blockchain.getBlockByHash(Bytes32.wrap(EthHex.decodeData(stringParam(request, 0))), false);
        if (block == null) {
            return null;
        }
        long height = block.getInfo().getHeight();
        if (height <= 0) {
            return null; // not a confirmed main block
        }
        boolean fullTx = request.getParams().length > 1 && Boolean.TRUE.equals(request.getParams()[1]);
        return buildBlock(height, fullTx);
    }

    /** Resolves a block tag ("latest"/.../hex) to a height; -1 for an unparseable/negative value. */
    private long resolveHeight(String tag, long head) {
        if (tag == null || tag.isBlank()) {
            return head;
        }
        return switch (tag) {
            case "latest", "pending", "safe", "finalized" -> head;
            case "earliest" -> 0L;
            default -> EthHex.decodeQuantity(tag).longValueExact();
        };
    }

    private Map<String, Object> buildBlock(long height, boolean fullTx) {
        Block block = blockchain.getBlockByHeight(height);
        if (block == null) {
            return null; // e.g. "earliest"/height 0 on a pruned chain: no such block to report
        }
        String zeroHash = "0x" + "0".repeat(64);
        String hash = EthHex.data(block.getHash());
        Block parent = height > 0 ? blockchain.getBlockByHeight(height - 1) : null;
        String parentHash = parent == null ? zeroHash : EthHex.data(parent.getHash());
        long timestampSeconds = io.xdag.utils.XdagTime.xdagTimestampToMs(block.getTimestamp()) / 1000;
        Optional<EvmMetaStore.HeightRecord> record =
                evmMetaStore == null ? Optional.empty() : evmMetaStore.getHeightRecord(height);
        String stateRoot = record.map(r -> EthHex.data(r.stateRoot())).orElse("0x" + "0".repeat(64));
        List<Hash> txHashes = evmMetaStore == null ? List.of() : evmMetaStore.getTxList(height);

        List<Object> txs = new ArrayList<>();
        long gasUsed = 0L;
        for (int i = 0; i < txHashes.size(); i++) {
            Hash txHash = txHashes.get(i);
            Optional<EvmReceipt> receipt = evmMetaStore.getReceipt(txHash);
            gasUsed += receipt.map(EvmReceipt::gasUsed).orElse(0L);
            if (fullTx) {
                evmTxStore.getDecoded(txHash)
                        .ifPresent(tx -> txs.add(EthObjects.transaction(tx, height, hash, txs.size())));
            } else {
                txs.add(EthHex.data(txHash.getBytes()));
            }
        }
        return EthObjects.block(height, hash, parentHash, timestampSeconds,
                evmConfig.maxGasLimit(), gasUsed, stateRoot, txs);
    }

    private Object getLogs(JsonRpcRequest request) {
        Object[] params = request.getParams();
        if (params == null || params.length < 1 || !(params[0] instanceof Map<?, ?> filter)) {
            throw JsonRpcException.invalidParams("missing filter object");
        }
        long head = blockchain.getLatestMainBlockNumber();
        long from = filter.get("fromBlock") == null ? head
                : resolveHeight((String) filter.get("fromBlock"), head);
        long to = filter.get("toBlock") == null ? head : resolveHeight((String) filter.get("toBlock"), head);
        if (from < 0 || to < 0 || to < from) {
            throw JsonRpcException.invalidParams("invalid block range");
        }
        if (to - from + 1 > maxLogScanRange) {
            throw JsonRpcException.invalidParams("log query range exceeds " + maxLogScanRange + " blocks");
        }
        LogFilter logFilter = LogFilter.parse(filter);

        List<Object> out = new ArrayList<>();
        for (long height = from; height <= Math.min(to, head) && evmMetaStore != null; height++) {
            Optional<Bytes> bloom = evmMetaStore.getHeightBloom(height);
            if (bloom.isPresent() && !logFilter.couldMatch(new LogsBloomFilter(bloom.get()))) {
                continue; // bloom proves no log at this height can match — skip the receipt reads
            }
            List<Hash> txHashes = evmMetaStore.getTxList(height);
            String blockHash = txHashes.isEmpty() ? null : blockHashAt(height);
            int logIndex = 0;
            for (int i = 0; i < txHashes.size(); i++) {
                Hash txHash = txHashes.get(i);
                Optional<EvmReceipt> receipt = evmMetaStore.getReceipt(txHash);
                if (receipt.isEmpty()) {
                    continue;
                }
                for (Log log : receipt.get().logs()) {
                    if (logFilter.matches(log)) {
                        out.add(EthObjects.log(log, height, blockHash, txHash, i, logIndex));
                    }
                    logIndex++;
                }
            }
        }
        return out;
    }

    /** The latest executed EVM height (state anchor); 0 when no EVM tx has executed (genesis only). */
    private long evmHead() {
        return evmMetaStore == null ? 0L : evmMetaStore.highestHeight().orElse(0L);
    }

    /** The block tag at {@code index}, defaulting to "latest" when omitted/blank. */
    private String tagParam(JsonRpcRequest request, int index) {
        Object[] params = request.getParams();
        return params != null && params.length > index && params[index] instanceof String s && !s.isBlank()
                ? s : "latest";
    }

    /** A read-only world at the height named by the block tag at {@code index} (C4). */
    private WorldUpdater historicalWorld(JsonRpcRequest request, int index) {
        long head = evmHead();
        return historical.worldAt(resolveHeight(tagParam(request, index), head), head);
    }

    /** Loads an account at the block tag's height; null when absent. Read-only (never committed). */
    private Account account(Address address, JsonRpcRequest request, int tagIndex) {
        return historicalWorld(request, tagIndex).getAccount(address);
    }

    /** Parsed eth_call / eth_estimateGas arguments. {@code to == null} means contract creation. */
    private record CallArgs(Address from, Address to, Bytes data, Wei value, long gas) {
    }

    private CallArgs callObject(JsonRpcRequest request, int index) {
        Object[] params = request.getParams();
        if (params == null || params.length <= index || !(params[index] instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("missing call object at index " + index);
        }
        Address from = map.get("from") == null ? Address.ZERO : EthHex.decodeAddress((String) map.get("from"));
        Address to = map.get("to") == null ? null : EthHex.decodeAddress((String) map.get("to"));
        Object dataHex = map.get("data") != null ? map.get("data") : map.get("input");
        Bytes data = dataHex == null ? Bytes.EMPTY : EthHex.decodeData((String) dataHex);
        Wei value = map.get("value") == null ? Wei.ZERO : Wei.of(EthHex.decodeQuantity((String) map.get("value")));
        long gas = map.get("gas") == null ? evmConfig.maxGasLimit()
                : EthHex.decodeQuantity((String) map.get("gas")).longValueExact();
        return new CallArgs(from, to, data, value, gas);
    }

    /**
     * Runs the call/creation through the executor's simulation path, which NEVER commits, so
     * eth_call/eth_estimateGas cannot mutate the EVM_STATE (ADR-005). The updater is a read-only
     * historical world (C4); it is discarded when this method returns.
     */
    private XdagExecutionResult simulate(CallArgs args, WorldUpdater updater) {
        return args.to() == null
                ? executor.simulateDeploy(updater, args.from(), args.data(), args.value(), args.gas())
                : executor.simulateCall(updater, args.from(), args.to(), args.data(), args.value(), args.gas());
    }

    private static String revertMessage(XdagExecutionResult result) {
        return result.revertReason()
                .map(r -> "execution reverted: " + r.toHexString())
                .orElse("execution reverted");
    }

    private Address addressParam(JsonRpcRequest request, int index) {
        return EthHex.decodeAddress(stringParam(request, index));
    }

    private String stringParam(JsonRpcRequest request, int index) {
        Object[] params = request.getParams();
        if (params == null || params.length <= index || !(params[index] instanceof String s)) {
            throw new IllegalArgumentException("missing string parameter at index " + index);
        }
        return s;
    }
}
