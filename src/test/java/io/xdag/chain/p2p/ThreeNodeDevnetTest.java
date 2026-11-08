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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.SparseMerkleTree.Kv;
import io.xdag.chain.p2p.Delivery.Status;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageFactory;
import io.xdag.net.message.chain.ChainProofReplyMessage;
import io.xdag.net.message.chain.ChainProofRequestMessage;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;
import io.xdag.net.message.chain.ChainSnapshotRequestMessage;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * Three subscribers, two chains. A runs S, B runs X, C runs both. This drill is the announcement,
 * a snapshot whose root is the caller's, a segment proof, and the stall when the segment is not
 * served. Installing that segment into a destination executor is {@code DeliveryFetchTest}. The
 * slash of a bad anchor is {@code AnchorFlowTest}. Docker is not this test.
 */
public class ThreeNodeDevnetTest {

    private static final MessageFactory FACTORY = new MessageFactory();

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }

    @Test
    public void subscribersServeOnlyTheirChains() throws Exception {
        Bytes32 chainS = id(1);
        Bytes32 chainX = id(2);
        Kv state = new Kv(id(9), new byte[] {4});
        byte[] entry = new byte[] {6};
        Mmr outbox = new Mmr();
        outbox.append(HashUtils.sha256(Bytes.wrap(entry)));

        ChainRecords stored = new ChainRecords();
        stored.putSnapshot(chainS, 64, List.of(state));
        stored.putProof(chainS, 64, ProofKind.SEGMENT, ProofBodies.segmentKey(chainX, 0, 1),
                ProofBodies.encodeSegment(chainX, 0, 1, outbox, List.of(entry)));

        ChainExchange nodeA = new ChainExchange(ChainSubscriptions.of(List.of(chainS)), stored);
        ChainExchange nodeB = new ChainExchange(ChainSubscriptions.of(List.of(chainX)), new ChainRecords());
        ChainExchange nodeC = new ChainExchange(ChainSubscriptions.all(), new ChainRecords());

        nodeA.onRemote(nodeB.announce());
        nodeB.onRemote(nodeA.announce());
        nodeC.onRemote(nodeA.announce());
        nodeC.onRemote(nodeB.announce());
        assertTrue(nodeA.remote().covers(chainX));
        assertFalse(nodeA.remote().covers(chainS));
        assertTrue(nodeC.remote().covers(chainX));

        List<Kv> pulled = SnapshotSync.pull(stored.snapshotRoot(chainS, 64),
                page -> snapshot(nodeA, chainS, page));
        assertEquals(state.key(), pulled.get(0).key());

        assertTrue(snapshot(nodeA, chainX, 0).absent());
        assertTrue(snapshot(nodeB, chainS, 0).absent());
        assertTrue(snapshot(nodeC, chainS, 0).absent());

        ChainProofReplyMessage segment = proof(nodeA, chainS, ProofBodies.segmentKey(chainX, 0, 1));
        assertEquals(Status.READY, Delivery.segment(segment, outbox.root()));
        assertEquals(Status.HOLD, Delivery.segment(proof(nodeB, chainS, ProofBodies.segmentKey(chainX, 0, 1)),
                outbox.root()));
        assertEquals(Status.HOLD, Delivery.segment(proof(nodeA, chainS, ProofBodies.segmentKey(chainX, 5, 6)),
                outbox.root()));
    }

    private static ChainSnapshotReplyMessage snapshot(ChainExchange host, Bytes32 chain, int page) {
        return (ChainSnapshotReplyMessage) decode(
                host.reply(new ChainSnapshotRequestMessage(page, chain, 64, page)));
    }

    private static ChainProofReplyMessage proof(ChainExchange host, Bytes32 chain, byte[] key) {
        return (ChainProofReplyMessage) decode(
                host.reply(new ChainProofRequestMessage(1, chain, 64, ProofKind.SEGMENT, key)));
    }

    private static Message decode(Message raw) {
        try {
            return FACTORY.create(raw.getCode().toByte(), raw.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
