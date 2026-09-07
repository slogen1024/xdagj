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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Enforces the optional RPC bearer token. When a non-empty token is configured every non-preflight
 * request must carry {@code Authorization: Bearer <token>}; the supplied token is compared to the
 * configured one in constant time. When the configured token is empty, authentication is disabled
 * and all requests pass through. Sits after {@link CorsHandler} so CORS preflight (OPTIONS) is
 * already answered upstream and is, in any case, never gated here.
 */
@Slf4j
@ChannelHandler.Sharable
public class AuthHandler extends ChannelInboundHandlerAdapter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String apiToken;

    public AuthHandler(String apiToken) {
        this.apiToken = apiToken;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // No token configured -> authentication disabled, pass everything through.
        if (apiToken == null || apiToken.isEmpty()) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Never gate CORS preflight: it carries no credentials and is normally handled by CorsHandler.
        if (request.method() == HttpMethod.OPTIONS) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (isAuthorized(request)) {
            ctx.fireChannelRead(msg);
        } else {
            log.warn("Rejected RPC request with missing or invalid Authorization token");
            sendUnauthorized(ctx);
            if (msg instanceof FullHttpRequest) {
                ((FullHttpRequest) msg).release();
            }
        }
    }

    private boolean isAuthorized(HttpRequest request) {
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header != null && header.length() > BEARER_PREFIX.length()
                && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return tokenMatches(header.substring(BEARER_PREFIX.length()).trim());
        }
        // R7: a browser WebSocket cannot set Authorization on the upgrade GET; accept the bearer token
        // as a `token` (or `access_token`) query parameter, compared exactly like the header.
        String queryToken = queryToken(request.uri());
        return queryToken != null && tokenMatches(queryToken);
    }

    private static String queryToken(String uri) {
        if (uri == null || uri.indexOf('?') < 0) {
            return null;
        }
        try {
            var params = new QueryStringDecoder(uri).parameters();
            List<String> values = params.getOrDefault("token", params.get("access_token"));
            return values == null || values.isEmpty() ? null : values.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean tokenMatches(String provided) {
        byte[] expectedBytes = apiToken.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison to avoid leaking the token via timing side channels.
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private void sendUnauthorized(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED);
        response.headers()
                .set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                .set(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in Auth handler", cause);
        ctx.close();
    }
}
