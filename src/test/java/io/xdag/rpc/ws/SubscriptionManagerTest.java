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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.xdag.evm.EvmSubscriptionSink;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.junit.Test;

public class SubscriptionManagerTest {

    private static final Address EMITTER = Address.fromHexString("0x00000000000000000000000000000000000000aa");
    private static final Bytes32 SIG = Bytes32.fromHexString("0x" + "11".repeat(32));
    private static final Bytes32 HASH = Bytes32.fromHexString("0x" + "cd".repeat(32));
    private static final Hash TX = Hash.wrap(Bytes32.fromHexString("0x" + "ab".repeat(32)));

    private static Log log(Address a, Bytes32 topic0) {
        return new Log(a, Bytes.EMPTY, List.of(LogTopic.wrap(topic0)));
    }

    private static String drain(EmbeddedChannel ch) {
        ch.runPendingTasks();
        TextWebSocketFrame f = ch.readOutbound();
        return f == null ? null : f.text();
    }

    @Test
    public void subscribe_returns_unique_ids_and_unsubscribe_removes() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        String a = m.subscribe(ch, "newHeads", null);
        String b = m.subscribe(ch, "newHeads", null);
        assertFalse(a.equals(b));
        assertTrue(m.unsubscribe(ch, a));
        assertFalse("second unsubscribe of the same id is false", m.unsubscribe(ch, a));
    }

    @Test
    public void new_head_pushes_a_newHeads_envelope() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        String id = m.subscribe(ch, "newHeads", null);
        Bytes32 parent = Bytes32.fromHexString("0x" + "44".repeat(32));
        Bytes32 root = Bytes32.fromHexString("0x" + "55".repeat(32));
        m.onNewMainHead(new EvmSubscriptionSink.HeadInfo(5, HASH, parent, 1000L, 30_000_000L, 21_000L, root, null));
        String json = drain(ch);
        assertTrue(json.contains("\"method\":\"eth_subscription\""));
        assertTrue(json.contains("\"subscription\":\"" + id + "\""));
        assertTrue(json.contains("\"number\":\"0x5\""));
        // R7: a real header, not a placeholder.
        assertTrue(json.contains("\"parentHash\":\"" + parent.toHexString() + "\""));
        assertTrue(json.contains("\"stateRoot\":\"" + root.toHexString() + "\""));
        assertTrue(json.contains("\"gasUsed\":\"0x5208\""));
        assertTrue("header only", !json.contains("\"transactions\""));
    }

    @Test
    public void logs_subscription_filters_and_marks_removed() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        String id = m.subscribe(ch, "logs", Map.of("address", EMITTER.toHexString()));

        EvmSubscriptionSink.LogRecord rec = new EvmSubscriptionSink.LogRecord(log(EMITTER, SIG), TX, 0, 0);
        m.onLogs(5, HASH, List.of(rec), false);
        String fwd = drain(ch);
        assertTrue(fwd.contains("\"subscription\":\"" + id + "\""));
        assertTrue(fwd.contains("\"removed\":false"));

        m.onLogs(5, HASH, List.of(rec), true);
        assertTrue(drain(ch).contains("\"removed\":true"));

        EmbeddedChannel ch2 = new EmbeddedChannel();
        m.subscribe(ch2, "logs", Map.of("address", "0x000000000000000000000000000000000000beef"));
        m.onLogs(6, HASH, List.of(rec), false);
        assertNull(drain(ch2));
    }

    @Test
    public void remove_drops_all_channel_subscriptions() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        m.subscribe(ch, "newHeads", null);
        m.remove(ch);
        m.onNewMainHead(new EvmSubscriptionSink.HeadInfo(1, HASH, HASH, 1L, 0L, 0L, HASH, null));
        assertNull("removed channel gets no push", drain(ch));
    }

    @Test
    public void unknown_subscription_type_is_rejected() {
        SubscriptionManager m = new SubscriptionManager();
        assertThrows(IllegalArgumentException.class,
                () -> m.subscribe(new EmbeddedChannel(), "newPendingTransactions", null));
    }

    @Test
    public void per_channel_subscription_cap_is_enforced() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        for (int i = 0; i < 1024; i++) { // MAX_SUBS_PER_CHANNEL
            m.subscribe(ch, "newHeads", null);
        }
        assertThrows(IllegalArgumentException.class, () -> m.subscribe(ch, "newHeads", null));
    }

    @Test
    public void unsubscribe_and_remove_on_an_unknown_channel_are_safe() {
        SubscriptionManager m = new SubscriptionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        assertFalse("unsubscribe on an unregistered channel is false", m.unsubscribe(ch, "0xdead"));
        m.remove(ch); // must not throw on an unregistered channel
    }
}
