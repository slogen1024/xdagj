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
import java.util.LinkedHashMap;
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
import org.hyperledger.besu.evm.fluent.SimpleBlockValues;
import org.hyperledger.besu.evm.frame.BlockValues;
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
            "eth_getBlockByHash", "eth_getLogs", "eth_maxPriorityFeePerGas", "eth_feeHistory");

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
                case "eth_blockNumber" -> EthHex.quantity(executedHead()); // R1: the EXECUTED EVM head
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
                    long nonce = a == null ? 0L : a.getNonce();
                    if ("pending".equals(tagParam(request, 1)) && evmTxPool != null) {
                        nonce = evmTxPool.nextNonce(addr, nonce); // R3: next free nonce incl. the pool queue
                    }
                    yield EthHex.quantity(nonce);
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
                    CallArgs args = callObject(request, 0);
                    XdagExecutionResult r = simulate(args, resolveTarget(request, 1), args.gas());
                    if (!r.success()) {
                        throw reverted(r);
                    }
                    yield EthHex.data(r.returnData());
                }
                case "eth_estimateGas" -> EthHex.quantity(estimateGas(callObject(request, 0), resolveTarget(request, 1)));
                case "eth_maxPriorityFeePerGas" -> EthHex.quantity(minGasPriceWei);
                case "eth_feeHistory" -> feeHistory(request);
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
        EvmTransaction tx;
        try {
            tx = EvmTransaction.decode(rlp);
        } catch (RuntimeException e) {
            throw JsonRpcException.invalidParams("invalid transaction RLP");
        }
        // Node-local UX gate; consensus is enforced independently in EvmBlockProcessor (spec §4).
        if (tx.getType() == EvmTransaction.TYPE_EIP1559
                && blockchain.getLatestMainBlockNumber() + 1 < evmConfig.type2ActivationHeight()) {
            throw JsonRpcException.invalidParams("type-2 transactions are not activated yet on this network");
        }
        String hash = EthHex.data(tx.getHash().getBytes());
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
            case TOO_LARGE -> throw JsonRpcException.invalidParams("transaction too large");
        }
        throw JsonRpcException.internalError("unreachable add result");
    }

    /**
     * eth_feeHistory with honest static content for a chain with no base-fee market (spec §6):
     * base fees are genuinely 0, devnet gas usage is ≈0 of the 1e12 budget, and every effective
     * priority fee equals the node's floor price.
     */
    private Map<String, Object> feeHistory(JsonRpcRequest request) {
        Object[] params = request.getParams();
        if (params == null || params.length < 2) {
            throw new IllegalArgumentException("eth_feeHistory needs [blockCount, newestBlock(, percentiles)]");
        }
        long requested = params[0] instanceof Number n ? n.longValue()
                : EthHex.decodeQuantity((String) params[0]).longValueExact();
        long blockCount = Math.max(1L, Math.min(requested, 1024L));
        long newest = resolveHeight((String) params[1], executedHead());
        if (newest < 0) {
            throw new IllegalArgumentException("invalid newestBlock");
        }
        long oldest = Math.max(0L, newest - blockCount + 1);
        long count = newest - oldest + 1;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oldestBlock", EthHex.quantity(oldest));
        List<String> baseFees = new ArrayList<>();
        // blockCount + 1 entries: includes the next block's base fee (EIP-1559 feeHistory shape).
        for (long i = 0; i <= count; i++) {
            baseFees.add("0x0");
        }
        m.put("baseFeePerGas", baseFees);
        List<Double> ratios = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            ratios.add(0.0d);
        }
        m.put("gasUsedRatio", ratios);
        if (params.length > 2 && params[2] instanceof List<?> percentiles && !percentiles.isEmpty()) {
            List<List<String>> rewards = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                List<String> row = new ArrayList<>();
                for (int p = 0; p < percentiles.size(); p++) {
                    row.add(EthHex.quantity(minGasPriceWei));
                }
                rewards.add(row);
            }
            m.put("reward", rewards);
        }
        return m;
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
        // R4: block-wide coordinates — logIndex continues across the block's earlier txs (matching
        // eth_getLogs / WS) and cumulativeGasUsed sums the block's receipts up to this one.
        long cumulativeGas = 0L;
        int firstLogIndex = 0;
        List<Hash> blockTxs = evmMetaStore.getTxList(loc.get().height());
        for (int i = 0; i < Math.min(loc.get().index(), blockTxs.size()); i++) {
            Optional<EvmReceipt> earlier = evmMetaStore.getReceipt(blockTxs.get(i));
            cumulativeGas += earlier.map(EvmReceipt::gasUsed).orElse(0L);
            firstLogIndex += earlier.map(er -> er.logs().size()).orElse(0);
        }
        cumulativeGas += receipt.get().gasUsed();
        List<Object> logs = buildLogs(receipt.get(), tx.get(), loc.get(), blockHash, firstLogIndex);
        return EthObjects.receipt(tx.get(), receipt.get(), loc.get().height(), blockHash,
                loc.get().index(), logs, cumulativeGas);
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
        long head = executedHead();
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
        if (height <= 0 || height > executedHead()) {
            return null; // not a confirmed main block, or not yet executed by the EVM (R1)
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
        String logsBloom = evmMetaStore == null ? EthObjects.ZERO_BLOOM
                : evmMetaStore.getHeightBloom(height).map(EthHex::data).orElse(EthObjects.ZERO_BLOOM); // R6

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
                evmConfig.maxGasLimit(), gasUsed, stateRoot, txs, logsBloom);
    }

    private Object getLogs(JsonRpcRequest request) {
        Object[] params = request.getParams();
        if (params == null || params.length < 1 || !(params[0] instanceof Map<?, ?> filter)) {
            throw JsonRpcException.invalidParams("missing filter object");
        }
        long head = executedHead(); // R1: never scan heights whose receipts do not exist yet
        long from;
        long to;
        if (filter.get("blockHash") != null) {
            // R2 (EIP-234): blockHash selects exactly one block and excludes fromBlock/toBlock.
            if (filter.get("fromBlock") != null || filter.get("toBlock") != null) {
                throw JsonRpcException.invalidParams("blockHash cannot be combined with fromBlock/toBlock");
            }
            Block byHash = blockchain.getBlockByHash(
                    Bytes32.wrap(EthHex.decodeData((String) filter.get("blockHash"))), false);
            long height = byHash == null ? -1L : byHash.getInfo().getHeight();
            if (height <= 0 || height > head) {
                throw JsonRpcException.invalidParams("unknown block hash");
            }
            from = height;
            to = height;
        } else {
            from = filter.get("fromBlock") == null ? head : resolveHeight((String) filter.get("fromBlock"), head);
            to = filter.get("toBlock") == null ? head : resolveHeight((String) filter.get("toBlock"), head);
        }
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

    /** The highest EVM checkpoint height (state anchor); 0 when no EVM tx has executed (genesis only). */
    private long checkpointHead() {
        return evmMetaStore == null ? 0L : evmMetaStore.highestHeight().orElse(0L);
    }

    /**
     * R1: the height every eth_* block tag resolves against — the highest EVM height this node has fully
     * executed (never below the highest checkpoint), NOT the native main-chain head, which runs ahead by
     * up to stateRootLag-1 heights (plus any blob-deferred height). Receipts, logs, tx lists and state
     * are therefore all consistent at and below this number.
     */
    private long executedHead() {
        return Math.max(blockchain.getEvmExecutedHeight(), checkpointHead());
    }

    /** The block tag at {@code index}, defaulting to "latest" when omitted/blank. */
    private String tagParam(JsonRpcRequest request, int index) {
        Object[] params = request.getParams();
        return params != null && params.length > index && params[index] instanceof String s && !s.isBlank()
                ? s : "latest";
    }

    /** The height the block tag at {@code index} names, resolved against the executed head (R1). */
    private long resolveTarget(JsonRpcRequest request, int index) {
        long target = resolveHeight(tagParam(request, index), executedHead());
        if (target < 0) {
            throw new IllegalArgumentException("invalid block tag");
        }
        return target;
    }

    /**
     * A read-only world at {@code target} (C4 + R1): a height above the executed head is not available
     * yet; between the highest checkpoint and the executed head nothing changed the state, so those
     * heights read the live store; below the checkpoint the journals reconstruct the past state.
     */
    private WorldUpdater worldAt(long target) {
        if (target > executedHead()) {
            throw new StateUnavailableException(target);
        }
        long checkpoint = checkpointHead();
        return historical.worldAt(Math.min(target, checkpoint), checkpoint);
    }

    /** A read-only world at the height named by the block tag at {@code index}. */
    private WorldUpdater historicalWorld(JsonRpcRequest request, int index) {
        return worldAt(resolveTarget(request, index));
    }

    /** Loads an account at the block tag's height; null when absent. Read-only (never committed). */
    private Account account(Address address, JsonRpcRequest request, int tagIndex) {
        return historicalWorld(request, tagIndex).getAccount(address);
    }

    /** E3: the block context a simulation at {@code height} observes (NUMBER / TIMESTAMP / GASLIMIT / BASEFEE). */
    private BlockValues blockValuesAt(long height) {
        SimpleBlockValues values = new SimpleBlockValues();
        values.setNumber(height);
        Block block = blockchain.getBlockByHeight(height);
        values.setTimestamp(block == null ? 0L
                : io.xdag.utils.XdagTime.xdagTimestampToMs(block.getTimestamp()) / 1000);
        values.setGasLimit(evmConfig.maxGasLimit());
        if (height >= evmConfig.semanticsV2ActivationHeight()) {
            values.setBaseFee(Optional.of(Wei.ZERO));
        }
        return values;
    }

    /** The fork options in force at {@code height}, mirroring EvmBlockProcessor's consensus choice. */
    private XdagEvmExecutor.ExecutionOptions optionsAt(long height, Wei gasPrice) {
        if (height < evmConfig.semanticsV2ActivationHeight()) {
            return XdagEvmExecutor.ExecutionOptions.LEGACY;
        }
        return new XdagEvmExecutor.ExecutionOptions(true, gasPrice, h -> {
            Block b = blockchain.getBlockByHeight(h);
            return b == null ? null : b.getHash();
        });
    }

    /** Parsed eth_call / eth_estimateGas arguments. {@code to == null} means contract creation. */
    private record CallArgs(Address from, Address to, Bytes data, Wei value, long gas, Wei gasPrice) {
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
        Object price = map.get("gasPrice") != null ? map.get("gasPrice") : map.get("maxFeePerGas");
        Wei gasPrice = price == null ? Wei.ZERO : Wei.of(EthHex.decodeQuantity((String) price));
        return new CallArgs(from, to, data, value, gas, gasPrice);
    }

    /**
     * Runs the call/creation through the executor's simulation path, which NEVER commits, so
     * eth_call/eth_estimateGas cannot mutate the EVM_STATE (ADR-005). {@code gasLimit} is the
     * TRANSACTION gas limit: the intrinsic cost is charged first exactly as in execution, so a result
     * (and eth_estimateGas, which bisects over this) is a real tx gas limit. Runs in the tagged block's
     * context (E3) on a read-only historical world (C4), discarded when this method returns.
     */
    private XdagExecutionResult simulate(CallArgs args, long target, long gasLimit) {
        long intrinsic = IntrinsicGas.compute(args.data(), args.to() == null, List.of()); // no access list
        long messageGas = gasLimit - intrinsic;
        WorldUpdater world = worldAt(target);
        if (messageGas <= 0L) {
            if (args.to() != null && messageGas == 0L) {
                Account to = world.getAccount(args.to());
                boolean hasCode = to != null && to.getCode() != null && !to.getCode().isEmpty();
                if (!hasCode) {
                    // A 21000-gas value transfer: no code to run, succeeds with nothing to execute.
                    return new XdagExecutionResult(true, Bytes.EMPTY, 0L, 0L, List.of(), Optional.empty(),
                            Optional.empty());
                }
            }
            throw new IllegalArgumentException("intrinsic gas exceeds gas limit");
        }
        BlockValues blockValues = blockValuesAt(target);
        XdagEvmExecutor.ExecutionOptions options = optionsAt(target, args.gasPrice());
        return args.to() == null
                ? executor.simulateDeploy(world, args.from(), args.data(), args.value(), messageGas, blockValues,
                        options)
                : executor.simulateCall(world, args.from(), args.to(), args.data(), args.value(), messageGas,
                        blockValues, options);
    }

    /**
     * R5: eth_estimateGas as a binary search for the smallest tx gas limit that executes successfully,
     * geth-style. A single run at the cap under-estimates whenever a sub-call is involved: with exactly
     * "intrinsic + gasUsed" the caller can forward only 63/64 of its remaining gas (EIP-150) and the
     * callee runs out. Fails with the revert reason when even the cap does not execute.
     */
    private long estimateGas(CallArgs args, long target) {
        long intrinsic = IntrinsicGas.compute(args.data(), args.to() == null, List.of());
        long hi = args.gas();
        XdagExecutionResult atCap = simulate(args, target, hi);
        if (!atCap.success()) {
            throw reverted(atCap);
        }
        long lo = Math.max(intrinsic, 0L) - 1L; // < intrinsic never executes
        // Tighten the upper bound to what the cap run actually consumed plus a 64/63 head-room
        // (sub-call forwarding), then bisect: monotone in gas, so O(log) simulations.
        long used = intrinsic + atCap.gasUsed();
        long tightened = used + used / 63L + 1L;
        if (tightened < hi && simulate(args, target, tightened).success()) {
            hi = tightened;
        }
        while (lo + 1L < hi) {
            long mid = lo + (hi - lo) / 2L;
            boolean ok;
            try {
                ok = simulate(args, target, mid).success();
            } catch (IllegalArgumentException below) {
                ok = false; // mid below the intrinsic cost (or otherwise unexecutable)
            }
            if (ok) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    /** Error(string) selector: keccak("Error(string)")[0..4]. */
    private static final Bytes ERROR_STRING_SELECTOR = Bytes.fromHexString("0x08c379a0");

    /**
     * R7: a failed simulation as wallets expect it — code 3, "execution reverted" (+ the decoded
     * Error(string) reason when the payload is one), and the raw payload hex in {@code data}.
     */
    private static JsonRpcException reverted(XdagExecutionResult result) {
        Bytes payload = result.revertReason().orElse(result.returnData() == null ? Bytes.EMPTY : result.returnData());
        String message = "execution reverted";
        if (payload.size() >= 4 + 64 && payload.slice(0, 4).equals(ERROR_STRING_SELECTOR)) {
            try {
                int offset = payload.slice(4, 32).toUnsignedBigInteger().intValueExact();
                int length = payload.slice(4 + offset, 32).toUnsignedBigInteger().intValueExact();
                if (length >= 0 && 4 + offset + 32 + length <= payload.size()) {
                    message += ": " + new String(payload.slice(4 + offset + 32, length).toArrayUnsafe(),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (RuntimeException ignored) {
                // not a well-formed Error(string): keep the bare message, the raw data still travels
            }
        }
        return JsonRpcException.executionReverted(message, payload.isEmpty() ? null : EthHex.data(payload));
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
