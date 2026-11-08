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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.xdag.chain.rpc.ChainFeed;

/**
 * Text frames on {@code /chain}. A frame is {@code receipts}, {@code events}, {@code anchors}, or a
 * one-field JSON {@code {"method":"chain_subscribe","params":["receipts"]}}.
 */
@ChannelHandler.Sharable
public final class ChainSocket extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final AttributeKey<String> SUB = AttributeKey.valueOf("chainSub");

    private final ChainFeed feed;

    public ChainSocket(ChainFeed feed) {
        this.feed = feed;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String kind = kind(frame.text());
        if (kind == null) {
            ctx.writeAndFlush(new TextWebSocketFrame("{\"error\":\"kind\"}"));
            return;
        }
        String previous = ctx.channel().attr(SUB).get();
        if (previous != null) {
            feed.unsubscribe(previous);
        }
        String id = feed.subscribe(kind, line -> ctx.executor().execute(() -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new TextWebSocketFrame(line));
            }
        }));
        ctx.channel().attr(SUB).set(id);
        ctx.writeAndFlush(new TextWebSocketFrame("{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\"}"));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String id = ctx.channel().attr(SUB).get();
        if (id != null) {
            feed.unsubscribe(id);
        }
        ctx.fireChannelInactive();
    }

    static String kind(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.startsWith("{")) {
            int params = raw.indexOf("params");
            if (params < 0) {
                return null;
            }
            int quote = raw.indexOf('"', raw.indexOf('[', params) + 1);
            int end = quote < 0 ? -1 : raw.indexOf('"', quote + 1);
            if (quote < 0 || end < 0) {
                return null;
            }
            raw = raw.substring(quote + 1, end);
        }
        try {
            ChainFeed.check(raw);
            return raw;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
