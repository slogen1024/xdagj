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
package io.xdag.net.message.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.net.message.MessageFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/** Wire round-trips for the EVM tx gossip messages (0x1B-0x1D, spec §6.1). */
public class EvmTxMessageTest {

    private final MessageFactory factory = new MessageFactory();

    private final Bytes rlp = Bytes.fromHexString("0xf86c0985046052260a");
    private final Bytes32 txHash = Bytes32.fromHexString("0x" + "ab".repeat(32));

    @Test
    public void broadcast_round_trip() throws Exception {
        EvmTxBroadcastMessage sent = new EvmTxBroadcastMessage(rlp);
        Message decoded = factory.create(MessageCode.EVM_TX_BROADCAST.toByte(), sent.getBody());
        assertTrue(decoded instanceof EvmTxBroadcastMessage);
        assertEquals(rlp, ((EvmTxBroadcastMessage) decoded).getRawRlp());
    }

    @Test
    public void request_round_trip() throws Exception {
        EvmTxRequestMessage sent = new EvmTxRequestMessage(txHash);
        Message decoded = factory.create(MessageCode.EVM_TX_REQUEST.toByte(), sent.getBody());
        assertTrue(decoded instanceof EvmTxRequestMessage);
        assertEquals(txHash, ((EvmTxRequestMessage) decoded).getTxHash());
    }

    @Test
    public void reply_round_trip() throws Exception {
        EvmTxReplyMessage sent = new EvmTxReplyMessage(rlp);
        Message decoded = factory.create(MessageCode.EVM_TX_REPLY.toByte(), sent.getBody());
        assertTrue(decoded instanceof EvmTxReplyMessage);
        assertEquals(rlp, ((EvmTxReplyMessage) decoded).getRawRlp());
    }
}
