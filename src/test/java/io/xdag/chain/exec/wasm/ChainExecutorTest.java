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

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.exec.ChainExecDriver;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.exec.InputStreamBuilder;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.chain.p2p.ChainSubscriptions;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.chain.ext.ExtKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainExecutorTest {

    private static final long GAS = 2_000_000L;
    private static final Bytes CHAIN = addr(1);
    private static final Bytes SENDER = addr(2);
    private static final Bytes CONTRACT = addr(3);

    @Test
    public void confirmedInputsKeepTheirPlace() {
        ChainL1Store l1 = l1();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putInput(CHAIN, 4L, 0L, new InputRecord(id(8), ExtKind.CALL, InputStatus.OK, CONTRACT));
        batch.putInput(CHAIN, 4L, 1L, new InputRecord(id(9), ExtKind.CALL, InputStatus.INVALID_FEE, CONTRACT));
        batch.putCallCount(CHAIN, 4L, 2L);
        l1.commit(batch);
        List<io.xdag.chain.exec.ChainInput> inputs = new InputStreamBuilder(l1).inputs(CHAIN, 4L);
        assertEquals(2, inputs.size());
        assertEquals(0, inputs.get(0).index());
        assertEquals(ChainInput.Kind.CALL, inputs.get(1).kind());
        assertEquals(InputStatus.INVALID_FEE, inputs.get(1).l1Status());
        assertTrue(new InputStreamBuilder(l1).systemInputs(CHAIN, 4L).isEmpty());
        assertTrue(new InputStreamBuilder(l1).deliveredMessages(CHAIN, 4L).isEmpty());
    }

    @Test
    public void rewardPrecedesTheCallAndSweepsTheFeePool() {
        ChainL1Store l1 = l1();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putAnchor(CHAIN, 2L, new AnchorBody(AnchorRecord.Status.CANONICAL, id(3), Bytes32.ZERO, Bytes32.ZERO, 1L,
                0L, 0L, 4L, Bytes32.ZERO, SENDER.toArray(), Bytes32.ZERO).encode());
        batch.putInput(CHAIN, 4L, 0L, new InputRecord(id(8), ExtKind.CALL, InputStatus.OK, CONTRACT));
        batch.putCallCount(CHAIN, 4L, 1L);
        l1.commit(batch);
        List<ChainInput> inputs = new InputStreamBuilder(l1).inputs(CHAIN, 4L);
        assertEquals(2, inputs.size());
        assertEquals(ChainInput.Kind.SYSTEM, inputs.get(0).kind());
        assertEquals(RewardInputs.hash(id(3)), inputs.get(0).inputHash());
        assertEquals(ChainInput.Kind.CALL, inputs.get(1).kind());
        assertEquals(1, inputs.get(1).index());

        ChainExecutor exec = executor();
        exec.apply(CHAIN, 1L, 1L, GasSchedule.MAX_CALL_GAS, 9L, List.of(new ChainExecutor.Tx(id(4), ChainInput.Kind.CALL,
                null, SENDER, CONTRACT, 1_000L, 1_000L, 1, new byte[0], store()))).get(0);
        long fees = exec.feePool(CHAIN);
        assertTrue(fees > 0L);
        ChainExecutor.Outcome reward = exec.apply(CHAIN, 2L, 1L, GasSchedule.MAX_CALL_GAS, 9L,
                List.of(new ChainExecutor.Tx(RewardInputs.hash(id(3)), ChainInput.Kind.SYSTEM, null, SENDER, SENDER, 0L,
                        0L, 0, new byte[0], null))).get(0);
        assertEquals(ExecStatus.OK, reward.status());
        assertEquals(0L, exec.feePool(CHAIN));
        assertEquals(fees, exec.balance(CHAIN, SENDER.toArray()));
    }

    @Test
    public void aRejectedInputCreditsTheSenderAndDoesNotRun() {
        ChainExecutor exec = executor();
        ChainExecutor.Outcome out = exec.apply(CHAIN, 1L, 1L, GasSchedule.MAX_CALL_GAS, 9L, List.of(
                new ChainExecutor.Tx(id(1), ChainInput.Kind.CALL, ExecStatus.INVALID_FEE, SENDER, CONTRACT, 50L, 1000L,
                        1, new byte[0], null))).get(0);
        assertEquals(ExecStatus.INVALID_FEE, out.status());
        assertEquals(0L, out.gasUsed());
        assertEquals(50L, exec.balance(CHAIN, SENDER.toArray()));
        assertEquals(0L, exec.feePool(CHAIN));
        assertEquals(ProofBodies.commitment(id(1), out.stateRoot(), out.outboxRoot(), out.eventsRoot(),
                ExecStatus.INVALID_FEE.code(), 0L), out.commitment());
    }

    @Test
    public void insufficientGasLeavesTheValue() {
        ChainExecutor exec = executor();
        ChainExecutor.Outcome out = exec.apply(CHAIN, 1L, 1L, GasSchedule.MAX_CALL_GAS, 9L, List.of(
                new ChainExecutor.Tx(id(1), ChainInput.Kind.CALL, null, SENDER, CONTRACT, 10L, 1000L, 1, new byte[0],
                        store()))).get(0);
        assertEquals(ExecStatus.INSUFFICIENT_GAS, out.status());
        assertEquals(10L, exec.balance(CHAIN, SENDER.toArray()));
        assertNull(exec.storage(CHAIN, CONTRACT, new byte[] {7}));
    }

    @Test
    public void twoRunsPinTheSameRootAndCommitment() {
        byte[] code = storeModule();
        ChainExecutor.Outcome a = runStore(executor(), code);
        ChainExecutor.Outcome b = runStore(executor(), code);
        assertEquals(a.stateRoot(), b.stateRoot());
        assertEquals(a.commitment(), b.commitment());
        assertEquals(ExecStatus.OK, a.status());
        assertArrayEquals(new byte[] {8}, executorStorage(code));
        assertEquals(ProofBodies.commitment(id(4), a.stateRoot(), a.outboxRoot(), a.eventsRoot(), ExecStatus.OK.code(),
                a.gasUsed()), a.commitment());
        assertEquals(3754L, a.gasUsed());
        assertEquals("0x781f0ce1be1bfdcab5daedfe859c44b2e8e0050b95aaa113803c467e07736ac0", a.stateRoot().toHexString());
        assertEquals("0xbbfc95f2f3ae2d2f175b21232c9c92b8b3a712cc830b269905a9ee44a2254221",
                a.commitment().toHexString());
    }

    @Test
    public void reentrancyRollsTheFrameBack() {
        byte[] code = reenter(false);
        ChainExecutor exec = executor();
        ChainExecutor.Outcome out = apply(exec, code, 7);
        assertEquals(ExecStatus.FAILED, out.status());
        assertNull(exec.storage(CHAIN, CONTRACT, new byte[] {1}));
        assertEquals(GAS, exec.balance(CHAIN, SENDER.toArray()) + exec.feePool(CHAIN));
    }

    @Test
    public void aMarkedSelectorMayReenterUpToTheDepthCap() {
        byte[] code = reenter(true);
        ChainExecutor exec = executor();
        ChainExecutor.Outcome out = apply(exec, code, 7);
        assertEquals(out.status() + " gas " + out.gasUsed(), ExecStatus.OK, out.status());
        assertArrayEquals(new byte[] {2}, exec.storage(CHAIN, CONTRACT, new byte[] {1}));
    }

    @Test
    public void theDriverRewindsAnUnsetHeightAndReplaysIt() {
        ChainL1Store l1 = l1();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(1L, Bytes32.ZERO, 1L, 0L, GasSchedule.MAX_CALL_GAS, 0L));
        l1.commit(batch);
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        ChainExecutor exec = new ChainExecutor(ChicoryEngine.interpreter(), l1, store, null, 16);
        ChainExecDriver driver = new ChainExecDriver(exec,
                ChainSubscriptions.of(List.of(ChainExecutor.chain32(CHAIN))), l1);
        driver.onSetMainEnd(1L, h -> 100L + h);
        assertEquals(1L, exec.appliedSeq(CHAIN));
        driver.onSetMainEnd(3L, h -> 100L + h);
        assertEquals(3L, exec.appliedSeq(CHAIN));
        driver.onUnsetMain(3L);
        assertFalse(driver.isBlocked());
        assertEquals(2L, exec.appliedSeq(CHAIN));
        driver.onSetMainEnd(4L, h -> 100L + h);
        assertEquals(4L, exec.appliedSeq(CHAIN));
    }

    private static ChainExecutor.Outcome runStore(ChainExecutor exec, byte[] code) {
        return apply(exec, code, 1);
    }

    private static byte[] executorStorage(byte[] code) {
        ChainExecutor exec = executor();
        apply(exec, code, 1);
        return exec.storage(CHAIN, CONTRACT, new byte[] {7});
    }

    private static ChainExecutor.Outcome apply(ChainExecutor exec, byte[] code, int selector) {
        return exec.apply(CHAIN, 1L, 1L, GasSchedule.MAX_CALL_GAS, 9L, List.of(new ChainExecutor.Tx(id(4),
                ChainInput.Kind.CALL, null, SENDER, CONTRACT, GAS, GAS, selector, new byte[0], code))).get(0);
    }

    private static ChainExecutor executor() {
        ChainL1Store l1 = l1();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        return new ChainExecutor(ChicoryEngine.interpreter(), l1, store, null, 16);
    }

    private static ChainL1Store l1() {
        ChainL1Store store = new ChainL1Store(new InMemoryKVSource());
        store.start();
        return store;
    }

    private static byte[] storeModule() {
        byte[] mem = new byte[] {7, 8};
        byte[] body = new byte[] {0x41, 0x00, 0x41, 0x01, 0x41, 0x01, 0x41, 0x01, 0x10, 0x00, 0x0b};
        WasmAsm asm = new WasmAsm();
        int host = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "storage_set", host);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, body);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        asm.data(0, mem);
        return asm.bytes();
    }

    private static byte[] reenter(boolean marked) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        i32(body, 0);
        i32(body, 7);
        i32(body, 0);
        i32(body, 0);
        body.write(0x42);
        body.write(0x00);
        i32(body, 0);
        i32(body, 0);
        body.write(0x10);
        body.write(0x00);
        body.write(0x1a);
        i32(body, 40);
        i32(body, 1);
        i32(body, 41);
        i32(body, 1);
        body.write(0x10);
        body.write(0x01);
        body.write(0x0b);
        byte[] mem = new byte[42];
        System.arraycopy(CONTRACT.toArray(), 0, mem, 0, 20);
        mem[40] = 1;
        mem[41] = 2;
        WasmAsm asm = new WasmAsm();
        int call = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f, 0x7e, 0x7f, 0x7f}, new int[] {0x7f});
        int set = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "call", call);
        asm.addImport("xdag", "storage_set", set);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, body.toByteArray());
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        asm.data(0, mem);
        byte[] wasm = asm.bytes();
        return marked ? mark(wasm, 7) : wasm;
    }

    private static byte[] mark(byte[] wasm, int selector) {
        byte[] name = "xdag.reentrant".getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[wasm.length + 2 + 1 + name.length + 4];
        System.arraycopy(wasm, 0, out, 0, wasm.length);
        int p = wasm.length;
        out[p++] = 0;
        out[p++] = (byte) (1 + name.length + 4);
        out[p++] = (byte) name.length;
        System.arraycopy(name, 0, out, p, name.length);
        p += name.length;
        out[p++] = (byte) selector;
        out[p++] = 0;
        out[p++] = 0;
        out[p] = 0;
        return out;
    }

    private static void i32(ByteArrayOutputStream out, int value) {
        out.write(0x41);
        WasmAsm.sleb(out, value);
    }

    private static Bytes addr(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }

    private static byte[] store() {
        return storeModule();
    }
}
