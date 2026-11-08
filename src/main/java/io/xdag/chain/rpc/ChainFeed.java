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
package io.xdag.chain.rpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.ChainEvents;
import io.xdag.chain.l1.ChainL1Keys;

/**
 * In-process bus for {@code chain_subscribe}. A websocket on {@code /chain} pushes. The HTTP method
 * only checks the kind and returns that path. Kinds are {@code receipts}, {@code events},
 * {@code anchors}.
 */
public final class ChainFeed implements ChainEvents {

    public static final String PATH = "/chain";

    private final AtomicLong ids = new AtomicLong();
    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> recent = new ConcurrentHashMap<>();

    public static void check(String kind) {
        if (!"receipts".equals(kind) && !"events".equals(kind) && !"anchors".equals(kind)) {
            throw new IllegalArgumentException("kind");
        }
    }

    public String subscribe(String kind, Consumer<String> push) {
        check(kind);
        String id = Long.toHexString(ids.incrementAndGet());
        subs.add(new Sub(id, kind, push));
        return id;
    }

    public void unsubscribe(String id) {
        subs.removeIf(sub -> sub.id.equals(id));
    }

    public List<String> recent(String kind) {
        return List.copyOf(recent.getOrDefault(kind, List.of()));
    }

    @Override
    public void onReceipt(Bytes chain, long seq, Bytes32 blockHash, List<byte[]> events) {
        publish("receipts", chain.toHexString() + " " + seq + " " + blockHash.toHexString());
        if (events == null) {
            return;
        }
        for (byte[] event : events) {
            publish("events", Bytes.wrap(event).toHexString());
        }
    }

    public void anchors(List<Pair<byte[], byte[]>> puts) {
        if (puts == null) {
            return;
        }
        for (Pair<byte[], byte[]> row : puts) {
            byte[] key = row.getKey();
            if (key != null && key.length == 29 && key[0] == ChainL1Keys.ANCHOR) {
                publish("anchors", Bytes.wrap(key).toHexString());
            }
        }
    }

    private void publish(String kind, String line) {
        recent.computeIfAbsent(kind, k -> new CopyOnWriteArrayList<>()).add(line);
        for (Sub sub : subs) {
            if (sub.kind.equals(kind)) {
                try {
                    sub.push.accept(line);
                } catch (RuntimeException ignored) {
                    subs.remove(sub);
                }
            }
        }
    }

    private record Sub(String id, String kind, Consumer<String> push) {
    }
}
