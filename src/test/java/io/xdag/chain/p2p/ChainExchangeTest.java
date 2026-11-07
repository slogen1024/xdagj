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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageException;
import io.xdag.net.message.MessageFactory;
import io.xdag.net.message.chain.ChainProofReplyMessage;
import io.xdag.net.message.chain.ChainProofRequestMessage;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;
import io.xdag.net.message.chain.ChainSnapshotRequestMessage;
import io.xdag.net.message.chain.ChainSubscriptionsMessage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainExchangeTest {

    private static final Bytes32 CHAIN = Bytes32.wrap(new byte[32]);
    private static final MessageFactory FACTORY = new MessageFactory();

    @Test
    public void absentSourceAnswersAbsentAndTheReplyRoundTrips() throws Exception {
        ChainExchange ex = new ChainExchange(ChainSubscriptions.all(), ChainDataSource.NONE);
        Message reply = ex.reply(new ChainSnapshotRequestMessage(7, CHAIN, 3, 0));
        ChainSnapshotReplyMessage back = (ChainSnapshotReplyMessage) FACTORY.create(
                reply.getCode().toByte(), reply.getBody());
        assertTrue(back.absent());
        assertEquals(7, back.requestId());
        assertEquals(0, back.page().length);
    }

    @Test
    public void aServedPageRoundTripsWithItsHash() throws Exception {
        byte[] page = new byte[] {1, 2, 3};
        ChainExchange ex = new ChainExchange(ChainSubscriptions.all(), new ChainDataSource() {
            @Override
            public byte[] snapshotPage(Bytes32 chainId, long seq, int p) {
                return page;
            }
        });
        Message reply = ex.reply(new ChainSnapshotRequestMessage(9, CHAIN, 1, 4));
        ChainSnapshotReplyMessage back = (ChainSnapshotReplyMessage) FACTORY.create(
                reply.getCode().toByte(), reply.getBody());
        assertTrue(back.ok());
        assertArrayEquals(page, back.page());
    }

    @Test
    public void anUnsubscribedChainIsAbsentWithoutCallingTheSource() {
        AtomicBoolean called = new AtomicBoolean();
        ChainDataSource source = new ChainDataSource() {
            @Override
            public byte[] snapshotPage(Bytes32 chainId, long seq, int page) {
                called.set(true);
                return new byte[] {1};
            }

            @Override
            public byte[] proof(Bytes32 chainId, long seq, ProofKind kind, byte[] key) {
                called.set(true);
                return new byte[] {1};
            }
        };
        byte[] other = new byte[32];
        other[0] = 1;
        ChainExchange ex = new ChainExchange(ChainSubscriptions.of(java.util.List.of(Bytes32.wrap(other))), source);
        assertTrue(((ChainSnapshotReplyMessage) ex.reply(new ChainSnapshotRequestMessage(1, CHAIN, 0, 0))).absent());
        assertTrue(((ChainProofReplyMessage) ex.reply(
                new ChainProofRequestMessage(2, CHAIN, 0, ProofKind.EVENT, new byte[] {9}))).absent());
        assertTrue(!called.get());
    }

    @Test
    public void aBlobPastTheFrameCapIsOversize() {
        byte[] huge = new byte[ChainCodec.MAX_PAYLOAD + 1];
        ChainExchange ex = new ChainExchange(ChainSubscriptions.all(), new ChainDataSource() {
            @Override
            public byte[] snapshotPage(Bytes32 chainId, long seq, int page) {
                return huge;
            }

            @Override
            public byte[] proof(Bytes32 chainId, long seq, ProofKind kind, byte[] key) {
                return huge;
            }
        });
        assertTrue(((ChainSnapshotReplyMessage) ex.reply(
                new ChainSnapshotRequestMessage(1, CHAIN, 0, 0))).oversize());
        assertTrue(((ChainProofReplyMessage) ex.reply(
                new ChainProofRequestMessage(2, CHAIN, 0, ProofKind.SEGMENT, new byte[0]))).oversize());
    }

    @Test
    public void aMismatchedPageHashIsRejected() {
        ChainSnapshotReplyMessage ok = ChainSnapshotReplyMessage.ok(1, new byte[] {1});
        byte[] body = ok.getBody().clone();
        body[body.length - 1] ^= 1;
        assertThrows(MessageException.class, () -> FACTORY.create(ok.getCode().toByte(), body));
    }

    @Test
    public void proofRequestRoundTripsAndUnknownKindIsMalformed() throws Exception {
        byte[] key = new byte[] {4, 5};
        ChainProofRequestMessage req = new ChainProofRequestMessage(11, CHAIN, 8, ProofKind.COMMITMENT, key);
        ChainProofRequestMessage back = (ChainProofRequestMessage) FACTORY.create(req.getCode().toByte(), req.getBody());
        assertEquals(ProofKind.COMMITMENT, back.kind());
        assertArrayEquals(key, back.key());
        assertEquals(8, back.seq());

        byte[] body = req.getBody().clone();
        int kindAt = Long.BYTES + 32 + Long.BYTES;
        body[kindAt] = 9;
        assertThrows(MessageException.class, () -> FACTORY.create(req.getCode().toByte(), body));
    }

    @Test
    public void announcementsAndRepliesAreRememberedPerConnection() throws Exception {
        ChainExchange ex = new ChainExchange(ChainSubscriptions.none(), ChainDataSource.NONE);
        ChainSubscriptions remote = ChainSubscriptions.of(java.util.List.of(CHAIN));
        ChainSubscriptionsMessage announced = new ChainSubscriptionsMessage(remote);
        ex.onRemote((ChainSubscriptionsMessage) FACTORY.create(announced.getCode().toByte(), announced.getBody()));
        assertEquals(remote.chainIds(), ex.remote().chainIds());

        ChainProofReplyMessage proof = ChainProofReplyMessage.ok(42, new byte[] {7});
        ex.onProofReply((ChainProofReplyMessage) FACTORY.create(proof.getCode().toByte(), proof.getBody()));
        assertArrayEquals(new byte[] {7}, ex.proofReply(42).proof());
    }

    @Test
    public void negativePageAndOversizedKeyAreMalformed() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainSnapshotRequestMessage(1, CHAIN, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainProofRequestMessage(1, CHAIN, 0, ProofKind.OUTBOX_MAP,
                        new byte[ChainCodec.MAX_KEY + 1]));
    }

    @Test
    public void subscriptionsRoundTripThroughTheFactory() throws Exception {
        ChainSubscriptionsMessage msg = new ChainSubscriptionsMessage(ChainSubscriptions.all());
        Message back = FACTORY.create(msg.getCode().toByte(), msg.getBody());
        assertTrue(back instanceof ChainSubscriptionsMessage);
        assertTrue(((ChainSubscriptionsMessage) back).subscriptions().subscribeAll());
        assertTrue(Arrays.equals(msg.getBody(), back.getBody()));
    }
}
