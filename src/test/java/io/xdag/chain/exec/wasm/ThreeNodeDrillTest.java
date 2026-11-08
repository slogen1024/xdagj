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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.AnchorFlowTest;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.OutboxEntry;
import io.xdag.chain.exec.ChainExecDriver;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.p2p.ChainPull;
import io.xdag.chain.p2p.ChainRecords;
import io.xdag.chain.p2p.ChainSubscriptions;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/**
 * Three subscribers. A serves the source. B's delay is 0 and it runs {@code recv} from a
 * {@code 0x1E} it sent. C's delay equals the finality window, so the same anchor pays nothing
 * until it is FINAL. The bad-anchor slash is {@link AnchorFlowTest#slashBadCall()}.
 */
public class ThreeNodeDrillTest {

    private static final long WINDOW = 4L;

    @Test
    public void delayZeroDeliversDelayWindowWaitsAndABadAnchorIsSlashed() {
        World w = new World();
        w.serve();
        AtomicInteger proofs = new AtomicInteger();
        ChainPull pull = w.pull(proofs);

        ChainExecDriver early = w.driver(w.d0, pull);
        early.onSetMainEnd(1L, h -> 9L);
        assertTrue(proofs.get() > 0);
        assertEquals(-1L, w.earlyExec.appliedSeq(w.src));
        assertEquals(1L, w.earlyExec.appliedSeq(w.d0));
        assertEquals(50L, w.earlyExec.balance(w.d0, w.sender));
        assertArrayEquals(new byte[] {8}, w.earlyExec.storage(w.d0, w.contract, new byte[] {7}));

        ChainExecDriver late = w.driver(w.dw, pull);
        late.onSetMainEnd(WINDOW, h -> 9L);
        assertEquals(WINDOW, w.lateExec.appliedSeq(w.dw));
        assertEquals(0L, w.lateExec.balance(w.dw, w.sender));

        w.finalizeAt(WINDOW + 1L);
        late.onSetMainEnd(WINDOW + 1L, h -> 9L);
        assertEquals(WINDOW + 1L, w.lateExec.appliedSeq(w.dw));
        assertEquals(50L, w.lateExec.balance(w.dw, w.sender));
        assertArrayEquals(new byte[] {8}, w.lateExec.storage(w.dw, w.contract, new byte[] {7}));

        AnchorFlowTest.slashBadCall();
    }

    @Test
    public void aNewSubscriberStartsAtTheFinalAnchorSnapshot() {
        World w = new World();
        w.serve();
        ChainExecDriver dest = w.driver(w.d0, w.pull(new AtomicInteger()));
        dest.onSetMainEnd(1L, h -> 9L);
        Bytes32 root = w.dest(w.d0).stateRoot(w.d0);
        assertEquals(1L, w.dest(w.d0).appliedSeq(w.d0));

        ChainL1Store lateL1 = new ChainL1Store(new InMemoryKVSource());
        lateL1.start();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(w.d0, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
        AnchorBody fin = new AnchorBody(AnchorRecord.Status.FINAL, w.anchor, w.outbox, root, 0L, 0L, 0L, 1L,
                Bytes32.ZERO, new byte[20], Bytes32.ZERO);
        batch.putAnchor(w.d0, 1L, fin.encode());
        lateL1.commit(batch);
        ChainExecStore lateStore = new ChainExecStore(new InMemoryKVSource());
        lateStore.start();
        ChainExecutor lateExec = new ChainExecutor(ChicoryEngine.interpreter(), lateL1, lateStore, null, 16);
        ChainExecDriver late = new ChainExecDriver(lateExec,
                ChainSubscriptions.of(List.of(ChainExecutor.chain32(w.d0))), lateL1, WINDOW);
        late.setNetwork(w.pull(new AtomicInteger()), null);
        late.onSetMainEnd(1L, h -> 9L);
        assertEquals(1L, lateExec.appliedSeq(w.d0));
        assertEquals(root, lateExec.stateRoot(w.d0));
        assertEquals(50L, lateExec.balance(w.d0, w.sender));
        assertArrayEquals(new byte[] {8}, lateExec.storage(w.d0, w.contract, new byte[] {7}));
    }

    private static final class World {
        final Bytes src = address(2);
        final Bytes d0 = address(1);
        final Bytes dw = address(3);
        final Bytes contract = address(4);
        final byte[] sender = address(5).toArray();
        final Bytes32 anchor = Bytes32.repeat((byte) 6);
        final ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        final ChainExecStore sourceStore = new ChainExecStore(new InMemoryKVSource());
        final ChainExecStore earlyStore = new ChainExecStore(new InMemoryKVSource());
        final ChainExecStore lateStore = new ChainExecStore(new InMemoryKVSource());
        final ChainRecords records = new ChainRecords();
        final ChainExecutor sourceExec;
        final ChainExecutor earlyExec;
        final ChainExecutor lateExec;
        Bytes32 outbox;

        World() {
            l1.start();
            sourceStore.start();
            earlyStore.start();
            lateStore.start();
            byte[] code = recvModule();
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(src, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            batch.putChain(d0, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            batch.putChain(dw, new ChainRecord(1L, Bytes32.ZERO, 0L, WINDOW, 1_000_000L, 0L));
            Bytes32 codeHash = HashUtils.sha256(Bytes.wrap(code));
            batch.putContract(contract, new ContractRecord(d0, codeHash, 1L, Bytes32.ZERO));
            batch.putCode(codeHash, 1L, Bytes.wrap(code));
            l1.commit(batch);
            sourceExec = new ChainExecutor(ChicoryEngine.interpreter(), l1, sourceStore, null, 16);
            earlyExec = new ChainExecutor(ChicoryEngine.interpreter(), l1, earlyStore, null, 16);
            lateExec = new ChainExecutor(ChicoryEngine.interpreter(), l1, lateStore, null, 16);
        }

        void serve() {
            Bytes32 srcId = ChainExecutor.chain32(src);
            sourceStore.putMmr(srcId, ChainExecutor.chain32(d0), ChainExecutor.encodeMmr(List.of(entry(d0))));
            sourceStore.putMmr(srcId, ChainExecutor.chain32(dw), ChainExecutor.encodeMmr(List.of(entry(dw))));
            sourceStore.putHead(srcId, 1L, SparseMerkleTree.emptyRoot());
            outbox = sourceExec.outboxRoot(src);
            AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, anchor, outbox, Bytes32.ZERO, 0L, 0L, 0L,
                    1L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(src, 1L, body.encode());
            TriggerOps.write(batch, 1L, List.of(mark(d0, 1L)));
            l1.commit(batch);
            sourceExec.publish(src, records, List.of(ChainExecutor.chain32(d0), ChainExecutor.chain32(dw)));
        }

        void finalizeAt(long height) {
            AnchorBody body = new AnchorBody(AnchorRecord.Status.FINAL, anchor, outbox, Bytes32.ZERO, 0L, 0L, 0L, 1L,
                    Bytes32.ZERO, new byte[20], Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(src, 1L, body.encode());
            TriggerOps.write(batch, height, List.of(mark(dw, height)));
            l1.commit(batch);
        }

        TriggerList.Item mark(Bytes dest, long height) {
            return new TriggerList.Item(TriggerList.DELIVER, dest.toArray(), 1L, src.toArray(), 1L, 0L, new byte[20],
                    1L, anchor, height, true);
        }

        ChainPull pull(AtomicInteger proofs) {
            ChainSubscriptions subs = ChainSubscriptions.all();
            ChainPull.Peer local = ChainPull.local(subs, records);
            ChainPull pull = new ChainPull();
            pull.add((ChainPull.Peer) new ChainPull.Peer() {
                @Override
                public ChainSubscriptions remote() {
                    return subs;
                }

                @Override
                public Message exchange(Message request) {
                    if (request.getCode() == MessageCode.CHAIN_PROOF_REQUEST) {
                        proofs.incrementAndGet();
                    }
                    return local.exchange(request);
                }
            });
            return pull;
        }

        ChainExecDriver driver(Bytes chain, ChainPull pull) {
            ChainExecDriver driver = new ChainExecDriver(dest(chain) == earlyExec ? earlyExec : lateExec,
                    ChainSubscriptions.of(List.of(ChainExecutor.chain32(chain))), l1, WINDOW);
            driver.setNetwork(pull, records);
            return driver;
        }

        ChainExecutor dest(Bytes chain) {
            return chain.equals(d0) ? earlyExec : lateExec;
        }

        byte[] entry(Bytes dest) {
            return new OutboxEntry(src.toArray(), dest.toArray(), 0L, sender, contract.toArray(), 1, 50L, 1L,
                    new byte[0]).encode();
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
}
