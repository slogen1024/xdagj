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
package io.xdag.chain.exec.wasm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.OutboxEntry;
import io.xdag.chain.exec.ChainExecDriver;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.SegmentIntake;
import io.xdag.chain.exec.SegmentProofs;
import io.xdag.chain.exec.state.Mmr;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.p2p.ChainSubscriptions;
import io.xdag.chain.p2p.Delivery;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.crypto.hash.HashUtils;

/**
 * A destination subscriber that does not execute the source installs the segment proof and runs
 * {@code recv}. A proof that does not match the anchor's outbox map is not installed.
 */
public class DeliveryFetchTest {

    @Test
    public void aDestOnlyNodeInstallsTheSegmentAndDoesNotRunTheSource() {
        World w = new World();
        w.anchor(w.proofs(w.entry(50L), 0L, 1L));
        w.driver.setProofs((src, seq, dst, root) -> w.bodies);
        w.driver.onSetMainEnd(1L, h -> 9L);
        assertEquals(-1L, w.executor.appliedSeq(w.src));
        assertEquals(1L, w.executor.appliedSeq(w.dst));
        assertEquals(50L, w.executor.balance(w.dst, w.sender));
        assertArrayEquals(new byte[] {8}, w.executor.storage(w.dst, w.contract, new byte[] {7}));
    }

    @Test
    public void aMissingPullLeavesTheDestinationUnstepped() {
        World w = new World();
        w.anchor(w.proofs(w.entry(50L), 0L, 1L));
        w.driver.onSetMainEnd(1L, h -> 9L);
        assertEquals(-1L, w.executor.appliedSeq(w.dst));
        assertEquals(-1, w.exec.outboxSize(ChainExecutor.chain32(w.src), ChainExecutor.chain32(w.dst), 1L));
    }

    @Test
    public void aSegmentWhoseRootIsNotTheCommittedOutboxIsRejected() {
        World w = new World();
        byte[] real = w.entry(50L);
        Built honest = w.proofs(real, 0L, 1L);
        Built other = w.proofs(w.entry(7L), 0L, 1L);
        w.anchor(honest);
        w.bodies = new SegmentProofs.Bodies(honest.outbox, other.segment);
        assertEquals(Delivery.Status.REJECT, SegmentIntake.install(w.exec, w.src.toArray(), w.dst.toArray(), 1L, 0L,
                honest.root, honest.outbox, other.segment));
        assertEquals(-1, w.exec.outboxSize(ChainExecutor.chain32(w.src), ChainExecutor.chain32(w.dst), 1L));
        w.driver.setProofs((src, seq, dst, root) -> w.bodies);
        w.driver.onSetMainEnd(1L, h -> 9L);
        assertEquals(-1L, w.executor.appliedSeq(w.dst));
        assertNull(w.executor.storage(w.dst, w.contract, new byte[] {7}));
    }

    @Test
    public void aGapBeforeFromStaysOnHold() {
        World w = new World();
        byte[] first = w.entry(1L);
        byte[] second = new OutboxEntry(w.src.toArray(), w.dst.toArray(), 1L, w.sender, w.contract.toArray(), 1, 2L,
                1L, new byte[0]).encode();
        Built built = w.proofs(new byte[][] {first, second}, 1L, 2L);
        assertEquals(Delivery.Status.HOLD, SegmentIntake.install(w.exec, w.src.toArray(), w.dst.toArray(), 1L, 0L,
                built.root, built.outbox, built.segment));
        assertEquals(-1, w.exec.outboxSize(ChainExecutor.chain32(w.src), ChainExecutor.chain32(w.dst), 1L));
    }

    private static final class World {
        final Bytes src = address(2);
        final Bytes dst = address(1);
        final Bytes contract = address(3);
        final byte[] sender = address(4).toArray();
        final ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        final ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        final ChainExecutor executor;
        final ChainExecDriver driver;
        SegmentProofs.Bodies bodies;
        Bytes32 anchorHash = Bytes32.repeat((byte) 5);

        World() {
            l1.start();
            exec.start();
            byte[] code = recvModule();
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(src, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            batch.putChain(dst, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            Bytes32 codeHash = HashUtils.sha256(Bytes.wrap(code));
            batch.putContract(contract, new ContractRecord(dst, codeHash, 1L, Bytes32.ZERO));
            batch.putCode(codeHash, 1L, Bytes.wrap(code));
            l1.commit(batch);
            executor = new ChainExecutor(ChicoryEngine.interpreter(), l1, exec, null, 16);
            driver = new ChainExecDriver(executor, ChainSubscriptions.of(List.of(ChainExecutor.chain32(dst))), l1, 4L);
        }

        byte[] entry(long value) {
            return new OutboxEntry(src.toArray(), dst.toArray(), 0L, sender, contract.toArray(), 1, value, 1L,
                    new byte[0]).encode();
        }

        Built proofs(byte[] entry, long from, long to) {
            return proofs(new byte[][] {entry}, from, to);
        }

        Built proofs(byte[][] entries, long from, long to) {
            Mmr mmr = new Mmr();
            for (byte[] raw : entries) {
                mmr.append(HashUtils.sha256(Bytes.wrap(raw)));
            }
            Bytes32 dstId = ChainExecutor.chain32(dst);
            OutboxMap map = new OutboxMap();
            map.put(dstId, mmr.root(), to);
            List<byte[]> slice = List.of(entries).subList((int) from, (int) to);
            byte[] segment = ProofBodies.encodeSegment(dstId, from, to, mmr, slice);
            byte[] outbox = ProofBodies.encodeOutbox(map.proof(dstId));
            return new Built(map.root(), outbox, segment);
        }

        void anchor(Built built) {
            bodies = new SegmentProofs.Bodies(built.outbox, built.segment);
            AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, anchorHash, built.root, Bytes32.ZERO, 0L,
                    0L, 0L, 1L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(src, 1L, body.encode());
            TriggerOps.write(batch, 1L, List.of(new TriggerList.Item(TriggerList.DELIVER, dst.toArray(), 1L,
                    src.toArray(), 1L, 0L, new byte[20], 1L, anchorHash, 1L, true)));
            l1.commit(batch);
        }

        static byte[] recvModule() {
            byte[] body = new byte[] {0x41, 0x00, 0x41, 0x01, 0x41, 0x01, 0x41, 0x01, 0x10, 0x00, 0x0b};
            WasmAsm asm = new WasmAsm();
            int host = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
            int empty = asm.addType(new int[0], new int[0]);
            asm.addImport("xdag", "storage_set", host);
            asm.addFunc(empty, new byte[] {0x0b});
            asm.addFunc(empty, new byte[] {0x0b});
            asm.addFunc(empty, body);
            asm.addFunc(empty, new byte[] {0x0b});
            asm.exportFunc("init", 0);
            asm.exportFunc("call", 1);
            asm.exportFunc("recv", 2);
            asm.exportFunc("query", 3);
            asm.exportMemory();
            asm.data(0, new byte[] {7, 8});
            return asm.bytes();
        }

        static Bytes address(int n) {
            byte[] raw = new byte[20];
            raw[19] = (byte) n;
            return Bytes.wrap(raw);
        }
    }

    private record Built(Bytes32 root, byte[] outbox, byte[] segment) {
    }
}
