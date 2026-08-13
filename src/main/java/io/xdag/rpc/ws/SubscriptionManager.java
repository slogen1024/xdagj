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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.xdag.evm.EvmSubscriptionSink;
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.eth.EthObjects;
import io.xdag.rpc.eth.LogFilter;
import io.xdag.rpc.server.handler.JsonRpcHandler;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Per-channel {@code eth_subscribe} registry + notification fan-out (C6). Implements
 * {@link EvmSubscriptionSink}: consensus/EVM call {@code onNewMainHead}/{@code onLogs}; this manager
 * filters each event against every matching subscription and pushes an {@code eth_subscription} envelope
 * on the target channel's event loop (never blocking the consensus thread). Node-local; thread-safe
 * (events arrive on the consensus thread, subscribe/remove on Netty threads).
 */
@Slf4j
public final class SubscriptionManager implements EvmSubscriptionSink {

    private static final int MAX_SUBS_PER_CHANNEL = 1024;
    private static final String ZERO_HASH = "0x" + "0".repeat(64);

    private enum Type { NEW_HEADS, LOGS }

    private record Subscription(String id, Type type, LogFilter filter) {
    }

    /**
     * Per-channel registry. The WebSocket frame handler MUST call {@link #remove(Channel)} on channel
     * close, or a closed connection's entry leaks (it is never written to — the {@code isActive} guard in
     * {@link #push} protects that — but it is still walked on every event).
     */
    private final Map<Channel, Map<String, Subscription>> byChannel = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Registers a subscription; returns its id. Throws IllegalArgumentException on a bad type/filter. */
    public String subscribe(Channel channel, String type, Map<?, ?> logFilter) {
        Type t = switch (type) {
            case "newHeads" -> Type.NEW_HEADS;
            case "logs" -> Type.LOGS;
            default -> throw new IllegalArgumentException("unsupported subscription type: " + type);
        };
        LogFilter filter = t == Type.LOGS ? LogFilter.parse(logFilter == null ? Map.of() : logFilter) : null;
        Map<String, Subscription> subs = byChannel.computeIfAbsent(channel, c -> new ConcurrentHashMap<>());
        if (subs.size() >= MAX_SUBS_PER_CHANNEL) {
            throw new IllegalArgumentException("too many subscriptions on this connection");
        }
        String id = newId();
        subs.put(id, new Subscription(id, t, filter));
        return id;
    }

    /** Removes a subscription; true if it existed. */
    public boolean unsubscribe(Channel channel, String id) {
        Map<String, Subscription> subs = byChannel.get(channel);
        return subs != null && subs.remove(id) != null;
    }

    /** Drops every subscription for a (closed) channel. */
    public void remove(Channel channel) {
        byChannel.remove(channel);
    }

    @Override
    public void onNewMainHead(long height, Bytes32 blockHash, long timestampSeconds) {
        String hashHex = EthHex.data(blockHash);
        forEachSub(Type.NEW_HEADS, (channel, sub) -> {
            Map<String, Object> header = EthObjects.block(height, hashHex, ZERO_HASH, timestampSeconds,
                    0L, 0L, ZERO_HASH, List.of());
            header.remove("transactions"); // newHeads is a header only — drop the tx/uncle collections
            header.remove("uncles");
            push(channel, sub.id(), header);
        });
    }

    @Override
    public void onLogs(long height, Bytes32 blockHash, List<LogRecord> logs, boolean removed) {
        if (logs.isEmpty()) {
            return;
        }
        String hashHex = EthHex.data(blockHash);
        forEachSub(Type.LOGS, (channel, sub) -> {
            for (LogRecord r : logs) {
                if (sub.filter().matches(r.log())) {
                    Map<String, Object> obj = EthObjects.log(r.log(), height, hashHex, r.txHash(),
                            r.txIndex(), r.logIndex());
                    obj.put("removed", removed);
                    push(channel, sub.id(), obj);
                }
            }
        });
    }

    private interface SubAction {
        void apply(Channel channel, Subscription sub);
    }

    private void forEachSub(Type type, SubAction action) {
        byChannel.forEach((channel, subs) -> subs.values().forEach(sub -> {
            if (sub.type() == type) {
                action.apply(channel, sub);
            }
        }));
    }

    private void push(Channel channel, String subId, Object result) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subscription", subId);
        params.put("result", result);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", "eth_subscription");
        envelope.put("params", params);
        String json;
        try {
            json = JsonRpcHandler.MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            // Drop the notification on a serialize failure — it must never propagate onto the
            // consensus thread that fired the event. Logged so a real bug is at least observable.
            log.debug("failed to serialize eth_subscription notification for {}", subId, e);
            return;
        }
        channel.eventLoop().execute(() -> {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(json));
            }
        });
    }

    private String newId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return "0x" + Bytes.wrap(b).toUnprefixedHexString();
    }
}
