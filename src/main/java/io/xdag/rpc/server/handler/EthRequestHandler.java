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

import io.xdag.core.Blockchain;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.XdagEvmExecutor;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;

/** Serves the read-only Ethereum JSON-RPC surface (sub-project C1) over the EVM_STATE world state. */
@Slf4j
public class EthRequestHandler implements JsonRpcRequestHandler {

    private static final String CLIENT_VERSION = "xdagj/0.8.3";

    private static final Set<String> SUPPORTED = Set.of(
            "eth_chainId", "net_version", "web3_clientVersion", "eth_gasPrice", "eth_blockNumber",
            "eth_getBalance", "eth_getTransactionCount", "eth_getCode", "eth_getStorageAt",
            "eth_call", "eth_estimateGas", "eth_accounts", "net_listening");

    private final KVSource<byte[], byte[]> evmStateStore;
    private final EvmConfig evmConfig;
    private final BigInteger minGasPriceWei;
    private final Blockchain blockchain;
    private final XdagEvmExecutor executor;

    public EthRequestHandler(KVSource<byte[], byte[]> evmStateStore, EvmConfig evmConfig,
                             BigInteger minGasPriceWei, Blockchain blockchain) {
        this.evmStateStore = evmStateStore;
        this.evmConfig = evmConfig;
        this.minGasPriceWei = minGasPriceWei;
        this.blockchain = blockchain;
        this.executor = new XdagEvmExecutor(evmConfig);
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
                    validateBlockTag(request, 1);
                    Account a = account(addr);
                    yield EthHex.quantity(a == null ? BigInteger.ZERO : a.getBalance().getAsBigInteger());
                }
                case "eth_getTransactionCount" -> {
                    Address addr = addressParam(request, 0);
                    validateBlockTag(request, 1);
                    Account a = account(addr);
                    yield EthHex.quantity(a == null ? 0L : a.getNonce());
                }
                case "eth_getCode" -> {
                    Address addr = addressParam(request, 0);
                    validateBlockTag(request, 1);
                    Account a = account(addr);
                    yield EthHex.data(a == null ? Bytes.EMPTY : a.getCode());
                }
                case "eth_getStorageAt" -> {
                    Address addr = addressParam(request, 0);
                    UInt256 slot = UInt256.valueOf(EthHex.decodeQuantity(stringParam(request, 1)));
                    validateBlockTag(request, 2);
                    Account a = account(addr);
                    UInt256 value = a == null ? UInt256.ZERO : a.getStorageValue(slot);
                    yield EthHex.data(value.toBytes());
                }
                default -> throw JsonRpcException.methodNotFound(method);
            };
        } catch (JsonRpcException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw JsonRpcException.invalidParams(e.getMessage());
        } catch (RuntimeException e) {
            log.error("eth RPC error handling {}", method, e);
            throw JsonRpcException.internalError("internal error");
        }
    }

    /** Loads an account from the latest EVM_STATE; null when absent. Read-only (never committed). */
    private Account account(Address address) {
        return new RocksDbWorldUpdater(evmStateStore).getAccount(address);
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

    /**
     * Validates the block tag (default "latest" when omitted/null): only the current committed state
     * is available. "latest"/"pending"/"earliest"/"safe"/"finalized" and the head height pass; any
     * other explicit height throws (no archival state in v1).
     */
    private void validateBlockTag(JsonRpcRequest request, int index) {
        Object[] params = request.getParams();
        if (params == null || params.length <= index || params[index] == null
                || !(params[index] instanceof String tag) || tag.isBlank()) {
            return; // default: latest
        }
        switch (tag) {
            case "latest", "pending", "earliest", "safe", "finalized" -> {
            }
            default -> {
                BigInteger requested = EthHex.decodeQuantity(tag);
                if (!requested.equals(BigInteger.valueOf(blockchain.getLatestMainBlockNumber()))) {
                    throw new IllegalArgumentException(
                            "historical state is not available (no archive node in v1)");
                }
            }
        }
    }
}
