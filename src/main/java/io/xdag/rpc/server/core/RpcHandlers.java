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
package io.xdag.rpc.server.core;

import io.xdag.Kernel;
import io.xdag.config.spec.EvmSpec;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.net.message.p2p.EvmTxBroadcastMessage;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.server.handler.EthRequestHandler;
import io.xdag.rpc.server.handler.JsonRequestHandler;
import io.xdag.rpc.server.handler.JsonRpcRequestHandler;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.EvmSpecVersion;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds the shared JSON-RPC handler list used by BOTH the HTTP server and the WebSocket server (C6). */
public final class RpcHandlers {

    private RpcHandlers() {
    }

    public static List<JsonRpcRequestHandler> build(XdagApi xdagApi, Kernel kernel) {
        // Create request handlers
        List<JsonRpcRequestHandler> handlers = new ArrayList<>();
        handlers.add(new JsonRequestHandler(xdagApi));

        // Register the eth_* handler when the embedded EVM is enabled (sub-projects C1/C2).
        EvmSpec evmSpec = kernel.getConfig().getEvmSpec();
        if (evmSpec.isEvmEnabled() && kernel.getEvmStateStore() != null) {
            EvmConfig evmConfig = new EvmConfig(EvmSpecVersion.SHANGHAI,
                    BigInteger.valueOf(evmSpec.getEvmChainId()), evmSpec.getEvmBlockGasLimit());
            // Broadcast an accepted eth tx to every connected peer (C2 write path).
            Consumer<Bytes> broadcaster = rlp -> {
                for (io.xdag.net.Channel ch : kernel.getChannelMgr().getActiveChannels()) {
                    ch.getMessageQueue().sendMessage(
                            new EvmTxBroadcastMessage(rlp));
                }
            };
            // Reconstruct past world state within the retained window so eth_* reads honor a
            // historical block tag (sub-project C4), instead of always reading the latest store.
            HistoricalStateReader historical =
                    new HistoricalStateReader(kernel.getEvmStateStore(),
                            kernel.getEvmStateJournal(), evmSpec.getEvmStateHistoryWindow());
            handlers.add(new EthRequestHandler(evmConfig,
                    evmSpec.getEvmMinGasPrice(), kernel.getBlockchain(),
                    kernel.getEvmTxPool(), broadcaster,
                    kernel.getEvmTxStore(), kernel.getEvmMetaStore(),
                    evmSpec.getEvmMaxLogScanRange(), historical));
        }
        return handlers;
    }
}
