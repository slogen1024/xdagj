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
package io.xdag.rpc.ws;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.xdag.config.spec.RPCSpec;
import io.xdag.rpc.server.handler.AuthHandler;
import io.xdag.rpc.server.handler.CorsHandler;
import io.xdag.rpc.server.handler.JsonRpcRequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.List;

/**
 * WebSocket JSON-RPC server (C6), on {@code rpc.ws.port}. Mirrors the HTTP {@link
 * io.xdag.rpc.server.core.JsonRpcServer} bootstrap; the pipeline runs the HTTP codec + CORS/auth for the
 * upgrade handshake, then {@link WebSocketServerProtocolHandler} + {@link RpcWebSocketFrameHandler} for
 * the persistent frame stream. Started only when {@code rpc.ws.enabled}. Node-local.
 */
@Slf4j
public class RpcWebSocketServer {

    private final RPCSpec rpcSpec;
    private final SubscriptionManager manager;
    private final List<JsonRpcRequestHandler> handlers;
    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public RpcWebSocketServer(RPCSpec rpcSpec, SubscriptionManager manager,
                              List<JsonRpcRequestHandler> handlers) {
        this.rpcSpec = rpcSpec;
        this.manager = manager;
        this.handlers = handlers;
    }

    public void start() {
        try {
            // WS reuses the HTTP thread-pool sizing (and CORS/auth/aggregator config below); only the
            // bind host/port are ws-specific. There is intentionally no separate rpc.ws.*.threads config.
            bossGroup = new MultiThreadIoEventLoopGroup(rpcSpec.getRpcHttpBossThreads(),
                    NioIoHandler.newFactory());
            workerGroup = new MultiThreadIoEventLoopGroup(rpcSpec.getRpcHttpWorkerThreads(),
                    NioIoHandler.newFactory());

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            // HTTP codec + aggregator: needed for the WebSocket upgrade handshake.
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(rpcSpec.getRpcHttpMaxContentLength()));
                            // CORS + auth gate the handshake GET; they pass WebSocket frames through
                            // unchanged (both ignore non-HttpRequest messages).
                            p.addLast(new CorsHandler(rpcSpec.getRpcHttpCorsOrigins()));
                            p.addLast(new AuthHandler(rpcSpec.getRpcHttpApiToken()));
                            // WebSocket upgrade, then the JSON-RPC frame handler for the persistent stream.
                            p.addLast(new WebSocketServerProtocolHandler("/"));
                            p.addLast(new RpcWebSocketFrameHandler(manager, handlers));
                        }
                    });
            log.info("---------WS Host:{}, WS Port:{}", rpcSpec.getRpcWsHost(), rpcSpec.getRpcWsPort());
            channel = b.bind(InetAddress.getByName(rpcSpec.getRpcWsHost()), rpcSpec.getRpcWsPort())
                    .sync().channel();
        } catch (Exception e) {
            stop();
            throw new RuntimeException("Failed to start WebSocket RPC server", e);
        }
    }

    public void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    /** The actual bound TCP port (useful when binding port 0); -1 before start()/after stop(). */
    public int boundPort() {
        return channel == null ? -1 : ((java.net.InetSocketAddress) channel.localAddress()).getPort();
    }
}
