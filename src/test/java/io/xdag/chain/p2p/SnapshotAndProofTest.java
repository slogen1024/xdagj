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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.SparseMerkleTree;
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

public class SnapshotAndProofTest {

    private static final MessageFactory FACTORY = new MessageFactory();

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }

    @Test
    public void smtRootIgnoresInsertionOrderAndAbsenceVerifies() {
        Kv a = new Kv(id(1), new byte[] {1});
        Kv b = new Kv(id(2), new byte[] {2, 3});
        Bytes32 root = SparseMerkleTree.root(List.of(a, b));
        assertEquals(root, SparseMerkleTree.root(List.of(b, a)));
        assertEquals(SparseMerkleTree.emptyRoot(), SparseMerkleTree.root(List.of()));
        assertEquals(0, SparseMerkleTree.nonEmptySiblings(id(1), List.of(a)));
        assertTrue(SparseMerkleTree.nonEmptySiblings(id(1), List.of(a, b)) >= 1);

        assertTrue(SparseMerkleTree.proof(id(2), List.of(a, b)).verifies(id(2), root));
        assertTrue(SparseMerkleTree.proof(id(9), List.of(a, b)).verifies(id(9), root));
        assertFalse(SparseMerkleTree.proof(id(9), List.of(a, b)).present());
        SparseMerkleTree.Proof forged = SparseMerkleTree.proof(id(2), List.of(a, b));
        assertFalse(new SparseMerkleTree.Proof(true, new byte[] {9}, forged.siblings()).verifies(id(2), root));
    }

    @Test
    public void mmrProofsCoverEveryLeaf() {
        Mmr mmr = new Mmr();
        assertEquals(Mmr.emptyRoot(), mmr.root());
        for (int i = 0; i < 5; i++) {
            mmr.append(id(i + 1));
        }
        for (int i = 0; i < 5; i++) {
            Mmr.Proof proof = Mmr.Proof.decode(java.nio.ByteBuffer.wrap(mmr.proof(i).encode()));
            assertEquals(mmr.root(), proof.root(id(i + 1)));
        }
        assertFalse(mmr.proof(0).root(id(9)).equals(mmr.root()));
    }

    @Test
    public void pagesRebuildTheRootOrTheyAreRefused() throws Exception {
        List<Kv> kvs = List.of(new Kv(id(1), new byte[] {1}), new Kv(id(4), new byte[] {4}),
                new Kv(id(2), new byte[] {2}));
        ChainRecords records = new ChainRecords();
        records.putSnapshot(id(7), 64, kvs);
        Bytes32 root = records.snapshotRoot(id(7), 64);
        ChainExchange host = new ChainExchange(ChainSubscriptions.all(), records);

        List<Kv> pulled = SnapshotSync.pull(root, page -> reply(host, id(7), 64, page));
        assertEquals(List.of(id(1), id(2), id(4)), pulled.stream().map(Kv::key).toList());
        assertArrayEquals(new byte[] {2}, pulled.get(1).value());

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> SnapshotSync.pull(Bytes32.ZERO, page -> reply(host, id(7), 64, page)));
        assertTrue(mismatch.getMessage().contains("state root"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> SnapshotSync.pull(root, page -> reply(host, id(8), 64, page)));
        assertTrue(missing.getMessage().contains("no snapshot"));
    }

    @Test
    public void anEmptySnapshotIsOneBlankPage() {
        ChainRecords records = new ChainRecords();
        records.putSnapshot(id(1), 0, List.of());
        assertEquals(SparseMerkleTree.emptyRoot(), records.snapshotRoot(id(1), 0));
        List<Kv> pulled = SnapshotSync.pull(SparseMerkleTree.emptyRoot(),
                page -> reply(new ChainExchange(ChainSubscriptions.all(), records), id(1), 0, page));
        assertTrue(pulled.isEmpty());
    }

    @Test
    public void pullStopsAtTheByteCapBeforeBuildingTheRoot() {
        ChainRecords records = new ChainRecords();
        records.putSnapshot(id(7), 64, List.of(new Kv(id(1), new byte[] {1, 2, 3, 4})));
        ChainExchange host = new ChainExchange(ChainSubscriptions.all(), records);
        IllegalArgumentException cap = assertThrows(IllegalArgumentException.class,
                () -> SnapshotSync.pull(records.snapshotRoot(id(7), 64),
                        page -> reply(host, id(7), 64, page), 8));
        assertTrue(cap.getMessage().contains("snapshot byte cap"));
    }

    @Test
    public void oneEntryLargerThanAPageIsRefused() {
        byte[] huge = new byte[ChainCodec.MAX_PAYLOAD];
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotPages.split(List.of(new Kv(id(1), huge)), ChainCodec.MAX_PAYLOAD));
    }

    @Test
    public void theFourProofBodiesVerifyAgainstTheCallersRoot() throws Exception {
        Bytes32 dst = id(3);
        byte[] entry = new byte[] {9, 8, 7};
        Mmr outbox = new Mmr();
        outbox.append(HashUtils.sha256(Bytes.wrap(entry)));
        byte[] segment = ProofBodies.encodeSegment(dst, 0, 1, outbox, List.of(entry));
        ProofBodies.Segment decoded = ProofBodies.decodeSegment(segment);
        assertTrue(decoded.verifies());
        assertEquals(outbox.root(), decoded.mmrRoot());

        byte[] value = new byte[40];
        value[0] = 5;
        Kv leaf = new Kv(dst, value);
        Bytes32 mapRoot = SparseMerkleTree.root(List.of(leaf));
        byte[] outboxProof = ProofBodies.encodeOutbox(SparseMerkleTree.proof(dst, List.of(leaf)));
        assertTrue(ProofBodies.decodeOutbox(outboxProof).verifies(dst, mapRoot));
        assertFalse(ProofBodies.decodeOutbox(outboxProof).verifies(id(1), mapRoot));

        ProofBodies.Opened opened = new ProofBodies.Opened(id(1), id(2), mapRoot, id(4), 0, 100);
        byte[] opening = ProofBodies.encodeCommitment(opened);
        assertEquals(opened.digest(), ProofBodies.decodeCommitment(opening).digest());
        assertEquals(ProofBodies.commitment(id(1), id(2), mapRoot, id(4), 0, 100), opened.digest());

        byte[] event = new byte[] {1, 2};
        Mmr events = new Mmr();
        events.append(eventLeaf(event));
        byte[] encoded = ProofBodies.encodeEvent(0, events, event);
        assertTrue(ProofBodies.decodeEvent(encoded).verifies(events.root()));

        ChainRecords records = new ChainRecords();
        records.putProof(id(7), 64, ProofKind.SEGMENT, ProofBodies.segmentKey(dst, 0, 1), segment);
        ChainExchange host = new ChainExchange(ChainSubscriptions.of(List.of(id(7))), records);
        ChainProofReplyMessage reply = proof(host, id(7), ProofBodies.segmentKey(dst, 0, 1));
        assertEquals(Status.READY, Delivery.segment(reply, outbox.root()));
        assertEquals(Status.REJECT, Delivery.segment(reply, Bytes32.ZERO));
        ChainProofReplyMessage absent = proof(host, id(7), ProofBodies.eventKey(0));
        assertTrue(absent.absent());
        assertEquals(Status.HOLD, Delivery.segment(absent, outbox.root()));
    }

    private static Bytes32 eventLeaf(byte[] event) {
        byte[] raw = new byte[1 + event.length];
        raw[0] = 0x04;
        System.arraycopy(event, 0, raw, 1, event.length);
        return HashUtils.sha256(Bytes.wrap(raw));
    }

    private static ChainSnapshotReplyMessage reply(ChainExchange host, Bytes32 chain, long seq, int page) {
        Message raw = host.reply(new ChainSnapshotRequestMessage(page, chain, seq, page));
        try {
            return (ChainSnapshotReplyMessage) FACTORY.create(raw.getCode().toByte(), raw.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChainProofReplyMessage proof(ChainExchange host, Bytes32 chain, byte[] key) throws Exception {
        Message raw = host.reply(new ChainProofRequestMessage(1, chain, 64, ProofKind.SEGMENT, key));
        return (ChainProofReplyMessage) FACTORY.create(raw.getCode().toByte(), raw.getBody());
    }
}
