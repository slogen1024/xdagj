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

import static org.junit.Assert.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.List;
import org.junit.Test;

public class RpcWebSocketFrameHandlerTest {

    @Test
    public void subscribe_then_unsubscribe_over_a_frame() {
        SubscriptionManager mgr = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel(new RpcWebSocketFrameHandler(mgr, List.of()));

        ch.writeInbound(new TextWebSocketFrame(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}"));
        ch.runPendingTasks();
        String sub = ((TextWebSocketFrame) ch.readOutbound()).text();
        assertTrue("subscribe returns a 0x id in result", sub.contains("\"result\":\"0x"));

        String id = sub.replaceAll(".*\"result\":\"(0x[0-9a-f]+)\".*", "$1");
        ch.writeInbound(new TextWebSocketFrame(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"eth_unsubscribe\",\"params\":[\"" + id + "\"]}"));
        ch.runPendingTasks();
        assertTrue("unsubscribe returns true",
                ((TextWebSocketFrame) ch.readOutbound()).text().contains("\"result\":true"));
    }

    @Test
    public void unknown_subscription_type_returns_an_error() {
        SubscriptionManager mgr = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel(new RpcWebSocketFrameHandler(mgr, List.of()));
        ch.writeInbound(new TextWebSocketFrame(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"eth_subscribe\",\"params\":[\"badType\"]}"));
        ch.runPendingTasks();
        assertTrue(((TextWebSocketFrame) ch.readOutbound()).text().contains("\"error\""));
    }

    @Test
    public void channel_inactive_removes_the_channel_subscriptions() {
        SubscriptionManager mgr = new SubscriptionManager();
        RpcWebSocketFrameHandler h = new RpcWebSocketFrameHandler(mgr, List.of());
        EmbeddedChannel ch = new EmbeddedChannel(h);
        ch.writeInbound(new TextWebSocketFrame(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}"));
        ch.runPendingTasks();
        ch.readOutbound();
        ch.close(); // fires channelInactive -> mgr.remove
        ch.runPendingTasks();
        // After removal, a new head produces no frame on this (closed) channel.
        mgr.onNewMainHead(1, org.apache.tuweni.bytes.Bytes32.ZERO, 1L);
        ch.runPendingTasks();
        assertTrue("closed+removed channel gets no notification", ch.readOutbound() == null);
    }

    @Test
    public void unknown_method_with_no_matching_handler_returns_method_not_found() {
        SubscriptionManager mgr = new SubscriptionManager();
        // Empty handler list -> the default-dispatch arm finds no supportsMethod match.
        EmbeddedChannel ch = new EmbeddedChannel(new RpcWebSocketFrameHandler(mgr, List.of()));
        ch.writeInbound(new TextWebSocketFrame(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"eth_blockNumber\",\"params\":[]}"));
        ch.runPendingTasks();
        assertTrue(((TextWebSocketFrame) ch.readOutbound()).text().contains("\"error\""));
    }
}
