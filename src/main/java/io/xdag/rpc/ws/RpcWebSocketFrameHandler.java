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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.xdag.rpc.error.JsonRpcError;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.handler.JsonRpcHandler;
import io.xdag.rpc.server.handler.JsonRpcRequestHandler;
import io.xdag.rpc.server.protocol.JsonRpcErrorResponse;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import io.xdag.rpc.server.protocol.JsonRpcResponse;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket JSON-RPC handler (C6). Parses each inbound {@link TextWebSocketFrame} as a
 * {@link JsonRpcRequest}, routes {@code eth_subscribe}/{@code eth_unsubscribe} to the
 * {@link SubscriptionManager}, dispatches every other method through the shared handler list (the same
 * one the HTTP server uses), and writes the JSON-RPC response back as a frame. Unlike HTTP, the
 * connection stays open (subscriptions push asynchronously). Drops the channel's subscriptions on close.
 */
@Slf4j
public class RpcWebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final SubscriptionManager manager;
    private final List<JsonRpcRequestHandler> handlers;

    public RpcWebSocketFrameHandler(SubscriptionManager manager, List<JsonRpcRequestHandler> handlers) {
        this.manager = manager;
        this.handlers = handlers;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        JsonRpcRequest request;
        try {
            request = JsonRpcHandler.MAPPER.readValue(frame.text(), JsonRpcRequest.class);
        } catch (Exception e) {
            log.debug("Failed to parse WS JSON-RPC request", e);
            send(ctx, new JsonRpcErrorResponse(null, new JsonRpcError(JsonRpcError.ERR_PARSE,
                    "Invalid JSON request")));
            return;
        }
        try {
            Object result = handle(ctx, request);
            send(ctx, new JsonRpcResponse(request.getId(), result));
        } catch (JsonRpcException e) {
            log.debug("WS RPC error: {}", e.getMessage());
            send(ctx, new JsonRpcErrorResponse(request.getId(), new JsonRpcError(e.getCode(), e.getMessage())));
        } catch (Exception e) {
            log.error("Error processing WS request", e);
            // Keep the detail server-side only; return a generic message to the client.
            send(ctx, new JsonRpcErrorResponse(request.getId(),
                    new JsonRpcError(JsonRpcError.ERR_INTERNAL, "Internal error")));
        }
    }

    private Object handle(ChannelHandlerContext ctx, JsonRpcRequest request) throws JsonRpcException {
        String method = request.getMethod();
        if (method == null) {
            throw JsonRpcException.invalidRequest("Method cannot be null");
        }
        switch (method) {
            case "eth_subscribe" -> {
                Object[] params = request.getParams();
                if (params == null || params.length < 1 || !(params[0] instanceof String type)) {
                    throw JsonRpcException.invalidParams("eth_subscribe requires a subscription type");
                }
                Map<?, ?> filter = params.length > 1 && params[1] instanceof Map<?, ?> f ? f : null;
                try {
                    return manager.subscribe(ctx.channel(), type, filter);
                } catch (IllegalArgumentException e) {
                    throw JsonRpcException.invalidParams(e.getMessage());
                }
            }
            case "eth_unsubscribe" -> {
                Object[] params = request.getParams();
                if (params == null || params.length < 1 || !(params[0] instanceof String id)) {
                    throw JsonRpcException.invalidParams("eth_unsubscribe requires a subscription id");
                }
                return manager.unsubscribe(ctx.channel(), id);
            }
            default -> {
                // Non-subscription methods: the same scan as JsonRpcHandler.dispatch, over the shared
                // handler list, so a plain eth_* call behaves identically over WS as over HTTP.
                for (JsonRpcRequestHandler handler : handlers) {
                    if (handler.supportsMethod(method)) {
                        return handler.handle(request);
                    }
                }
                throw JsonRpcException.methodNotFound(method);
            }
        }
    }

    private void send(ChannelHandlerContext ctx, Object response) {
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(JsonRpcHandler.MAPPER.writeValueAsString(response)));
        } catch (Exception e) {
            log.error("Error sending WS response", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        manager.remove(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WS channel exception", cause);
        ctx.close();
    }
}
