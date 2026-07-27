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
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

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
}
