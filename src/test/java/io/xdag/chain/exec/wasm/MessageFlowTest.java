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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.exec.InputStreamBuilder;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.p2p.ChainSubscriptions;
import io.xdag.crypto.hash.HashUtils;

/** Delivered outbox entries become {@code recv} inputs. A voided source rolls that height back. */
public class MessageFlowTest {

    @Test
    public void aDeliveredEntryCreditsTheSenderAndRunsRecv() {
        World w = new World();
        w.plantMessage(50L);
        List<ChainInput> inputs = new InputStreamBuilder(w.l1, w.exec).inputs(w.dst, 2L);
        assertEquals(1, inputs.size());
        assertEquals(ChainInput.Kind.MESSAGE, inputs.get(0).kind());
        assertEquals(w.leaf, inputs.get(0).inputHash());
        ChainExecutor.Outcome out = w.executor.step(w.dst, 2L, 9L).get(0);
        assertEquals(ExecStatus.OK, out.status());
        assertEquals(50L, w.executor.balance(w.dst, w.sender));
        assertArrayEquals(new byte[] {8}, w.executor.storage(w.dst, w.contract, new byte[] {7}));
    }

    @Test
    public void aMissingSourceOutboxIsNotReadyAndAVoidedAnchorIsSkipped() {
        World w = new World();
        w.plantMessage(50L);
        w.exec.deleteHead(ChainExecutor.chain32(w.src));
        assertFalse(new InputStreamBuilder(w.l1, w.exec).ready(w.dst, 2L));
        w.exec.putHead(ChainExecutor.chain32(w.src), 2L, Bytes32.ZERO);
        AnchorBody voided = new AnchorBody(AnchorRecord.Status.VOIDED, w.anchor, Bytes32.ZERO, Bytes32.ZERO, 1L, 0L,
                0L, 2L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putAnchor(w.src, 2L, voided.encode());
        w.l1.commit(batch);
        assertTrue(new InputStreamBuilder(w.l1, w.exec).deliveredMessages(w.dst, 2L).isEmpty());
        assertTrue(new InputStreamBuilder(w.l1, w.exec).ready(w.dst, 2L));
    }

    @Test
    public void onSourceVoidedRestoresTheSeqBeforeDelivery() {
        World w = new World();
        w.executor.step(w.dst, 1L, 9L);
        Bytes32 before = w.executor.stateRoot(w.dst);
        w.plantMessage(50L);
        w.executor.step(w.dst, 2L, 9L);
        assertEquals(50L, w.executor.balance(w.dst, w.sender));
        ChainExecDriver driver = new ChainExecDriver(w.executor, ChainSubscriptions.of(List.of(ChainExecutor.chain32(w.dst))),
                w.l1, 4L);
        driver.onSourceVoided(w.src.toArray(), 2L, 2L);
        assertEquals(1L, w.executor.appliedSeq(w.dst));
        assertEquals(before, w.executor.stateRoot(w.dst));
        assertEquals(0L, w.executor.balance(w.dst, w.sender));
        assertNull(w.executor.storage(w.dst, w.contract, new byte[] {7}));
        ChainExecutor reloaded = new ChainExecutor(ChicoryEngine.interpreter(), w.l1, w.exec, null, 16);
        assertEquals(1L, reloaded.appliedSeq(w.dst));
        assertEquals(before, reloaded.stateRoot(w.dst));
    }

    @Test
    public void deliveryWaitsUntilTheSourceChainHasExecuted() {
        World w = new World();
        AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, w.anchor, Bytes32.ZERO, Bytes32.ZERO, 0L, 0L,
                0L, 1L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putAnchor(w.src, 1L, body.encode());
        TriggerOps.write(batch, 1L, List.of(new TriggerList.Item(TriggerList.DELIVER, w.dst.toArray(), 1L,
                w.src.toArray(), 1L, 0L, new byte[20], 1L, w.anchor, 1L, true)));
        w.l1.commit(batch);
        assertFalse(w.executor.ready(w.dst, 1L));
        ChainExecDriver driver = new ChainExecDriver(w.executor,
                ChainSubscriptions.of(List.of(ChainExecutor.chain32(w.dst), ChainExecutor.chain32(w.src))), w.l1, 4L);
        driver.onSetMainEnd(1L, h -> 9L);
        assertEquals(1L, w.executor.appliedSeq(w.src));
        assertEquals(1L, w.executor.appliedSeq(w.dst));
        assertTrue(new InputStreamBuilder(w.l1, w.exec).deliveredMessages(w.dst, 1L).isEmpty());
    }

    @Test
    public void finalityDelayDoesNotRewind() {
        World w = new World();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(w.dst, new ChainRecord(1L, Bytes32.ZERO, 0L, 4L, 1_000_000L, 0L));
        w.l1.commit(batch);
        ChainExecutor exec = new ChainExecutor(ChicoryEngine.interpreter(), w.l1, w.exec, null, 16);
        for (long seq = 1L; seq <= 5L; seq++) {
            exec.step(w.dst, seq, 9L);
        }
        ChainExecDriver driver = new ChainExecDriver(exec, ChainSubscriptions.of(List.of(ChainExecutor.chain32(w.dst))),
                w.l1, 4L);
        driver.onSourceVoided(w.src.toArray(), 1L, 1L);
        assertEquals(5L, exec.appliedSeq(w.dst));
        assertFalse(driver.isBlocked());
    }

    private static final class World {
        final Bytes src = address(2);
        final Bytes dst = address(1);
        final Bytes contract = address(3);
        final byte[] sender = address(4).toArray();
        final byte[] code = recvModule();
        final Bytes32 anchor = Bytes32.repeat((byte) 5);
        final ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        final ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        final ChainExecutor executor;
        Bytes32 leaf;

        World() {
            l1.start();
            exec.start();
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(src, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            batch.putChain(dst, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, 1_000_000L, 0L));
            Bytes32 codeHash = HashUtils.sha256(Bytes.wrap(code));
            batch.putContract(contract, new ContractRecord(dst, codeHash, 1L, Bytes32.ZERO));
            batch.putCode(codeHash, 1L, Bytes.wrap(code));
            l1.commit(batch);
            executor = new ChainExecutor(ChicoryEngine.interpreter(), l1, exec, null, 16);
        }

        void plantMessage(long value) {
            byte[] raw = new OutboxEntry(src.toArray(), dst.toArray(), 0L, sender, contract.toArray(), 1, value, 1L,
                    new byte[0]).encode();
            leaf = HashUtils.sha256(Bytes.wrap(raw));
            Bytes32 srcId = ChainExecutor.chain32(src);
            Bytes32 dstId = ChainExecutor.chain32(dst);
            exec.putMmr(srcId, dstId, ChainExecutor.encodeMmr(List.of(raw)));
            exec.putOutboxSize(srcId, dstId, 2L, 1);
            exec.putHead(srcId, 2L, Bytes32.ZERO);
            AnchorBody body = new AnchorBody(AnchorRecord.Status.CANONICAL, anchor, Bytes32.ZERO, Bytes32.ZERO, 1L, 0L,
                    0L, 2L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putAnchor(src, 2L, body.encode());
            TriggerOps.write(batch, 2L, List.of(new TriggerList.Item(TriggerList.DELIVER, dst.toArray(), 2L,
                    src.toArray(), 2L, 0L, new byte[20], 2L, anchor, 2L, true)));
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
}
