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
package io.xdag.chain.p2p;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.net.message.chain.ChainProofReplyMessage;
import io.xdag.net.message.chain.ChainProofRequestMessage;

/** A wired peer sends {@code 0x1E} and reads {@code 0x1F} on the same request id. */
public class ChainPullTest {

    @Test
    public void aWiredPeerSendsTheProofRequestAndTakesTheReply() {
        Bytes32 chain = Bytes32.repeat((byte) 4);
        byte[] key = new byte[] {1, 2, 3};
        byte[] body = new byte[] {9, 8};
        ChainRecords records = new ChainRecords();
        records.putProof(chain, 3L, ProofKind.SEGMENT, key, body);
        ChainSubscriptions subs = ChainSubscriptions.of(List.of(chain));
        ChainExchange exchange = new ChainExchange(subs, records);
        exchange.onRemote(new ChainExchange(subs, ChainDataSource.NONE).announce());
        List<Message> sent = new ArrayList<>();
        ChainPull pull = new ChainPull();
        pull.wire(exchange, sent::add);

        assertNull(pull.proof(chain, 3L, ProofKind.SEGMENT, key));
        assertEquals(1, sent.size());
        assertEquals(MessageCode.CHAIN_PROOF_REQUEST, sent.get(0).getCode());
        ChainProofRequestMessage req = new ChainProofRequestMessage(sent.get(0).getBody());
        exchange.onProofReply(new ChainProofReplyMessage(exchange.reply(req).getBody()));

        assertArrayEquals(body, pull.proof(chain, 3L, ProofKind.SEGMENT, key));
        assertEquals(1, sent.size());
    }
}
