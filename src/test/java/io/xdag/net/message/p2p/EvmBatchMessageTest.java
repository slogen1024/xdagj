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

/** Wire round-trips for the EVM batch fetch messages (0x1F-0x20, batch D2). */
public class EvmBatchMessageTest {

    private final MessageFactory factory = new MessageFactory();

    private final Bytes32 batchHash = Bytes32.fromHexString("0x" + "cd".repeat(32));
    private final Bytes batchBody = Bytes.fromHexString("0xc281aa");

    @Test
    public void request_round_trips_through_its_body() {
        EvmBatchRequestMessage msg = new EvmBatchRequestMessage(batchHash);
        EvmBatchRequestMessage decoded = new EvmBatchRequestMessage(msg.getBody());
        assertEquals(batchHash, decoded.getBatchHash());
    }

    @Test
    public void reply_round_trips_through_its_body() {
        EvmBatchReplyMessage msg = new EvmBatchReplyMessage(batchBody);
        EvmBatchReplyMessage decoded = new EvmBatchReplyMessage(msg.getBody());
        assertEquals(batchBody, decoded.getBatchBody());
    }

    @Test
    public void request_factory_round_trip() throws Exception {
        EvmBatchRequestMessage sent = new EvmBatchRequestMessage(batchHash);
        Message decoded = factory.create(MessageCode.EVM_BATCH_REQUEST.toByte(), sent.getBody());
        assertTrue(decoded instanceof EvmBatchRequestMessage);
        assertEquals(batchHash, ((EvmBatchRequestMessage) decoded).getBatchHash());
    }

    @Test
    public void reply_factory_round_trip() throws Exception {
        EvmBatchReplyMessage sent = new EvmBatchReplyMessage(batchBody);
        Message decoded = factory.create(MessageCode.EVM_BATCH_REPLY.toByte(), sent.getBody());
        assertTrue(decoded instanceof EvmBatchReplyMessage);
        assertEquals(batchBody, ((EvmBatchReplyMessage) decoded).getBatchBody());
    }
}
