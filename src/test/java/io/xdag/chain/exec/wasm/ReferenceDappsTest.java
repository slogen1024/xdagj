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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.l1.ChainL1Store;

/**
 * The three reference contracts as wasm the compiler path actually runs. Selector 0 on the token
 * mints 50 to the caller. {@code transfer(address,u64)} moves a little-endian amount and leaves
 * storage alone when the balance is short. Selector 0 on the pool seeds 100/100; {@code swap}
 * takes 25 of asset 0. The inbox {@code send}s one row.
 */
public class ReferenceDappsTest {

    private static final int TRANSFER = 1433710159;
    private static final int SWAP = -488097224;
    private static final int PING = -150857028;
    private static final long GAS = 500_000L;
    private static final Bytes CHAIN = address(1);
    private static final Bytes SENDER = address(2);
    private static final Bytes TOKEN = address(3);
    private static final Bytes BOB = address(4);
    private static final Bytes POOL = address(5);
    private static final Bytes INBOX = address(6);

    @Test
    public void executorRunsTransferSwapAndSend() {
        byte[] token = tokenModule();
        byte[] pool = poolModule();
        byte[] inbox = inboxModule();
        List<ChainExecutor.Tx> txs = new ArrayList<>();
        txs.add(tx(1, TOKEN, 0, new byte[0], token));
        txs.add(tx(2, TOKEN, TRANSFER, transferArgs(BOB, 20), token));
        txs.add(tx(3, TOKEN, TRANSFER, transferArgs(BOB, 100), token));
        txs.add(tx(4, POOL, 0, new byte[0], pool));
        txs.add(tx(5, POOL, SWAP, new byte[0], pool));
        txs.add(tx(6, INBOX, PING, new byte[0], inbox));
        ChainExecutor exec = executor();
        List<ChainExecutor.Outcome> out = exec.apply(CHAIN, 0L, 1L, GAS, 0L, txs);
        for (int i = 0; i < out.size(); i++) {
            assertEquals("tx " + i + " gas " + out.get(i).gasUsed(), ExecStatus.OK, out.get(i).status());
        }
        assertEquals(30L, le(exec.storage(CHAIN, TOKEN, balanceKey(SENDER))));
        assertEquals(20L, le(exec.storage(CHAIN, TOKEN, balanceKey(BOB))));
        assertEquals(125L, le(exec.storage(CHAIN, POOL, new byte[] {'r', '0'})));
        assertEquals(80L, le(exec.storage(CHAIN, POOL, new byte[] {'r', '1'})));
        assertFalse(out.get(4).outboxRoot().equals(out.get(5).outboxRoot()));
    }

    private static ChainExecutor.Tx tx(int n, Bytes contract, int selector, byte[] args, byte[] code) {
        byte[] hash = new byte[32];
        hash[31] = (byte) n;
        return new ChainExecutor.Tx(Bytes32.wrap(hash), ChainInput.Kind.CALL, null, SENDER, contract, GAS, GAS,
                selector, args, code);
    }

    private static byte[] transferArgs(Bytes to, long amount) {
        byte[] raw = new byte[28];
        System.arraycopy(to.toArray(), 0, raw, 0, 20);
        ByteBuffer.wrap(raw, 20, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount);
        return raw;
    }

    private static byte[] balanceKey(Bytes who) {
        byte[] key = new byte[21];
        key[0] = 1;
        System.arraycopy(who.toArray(), 0, key, 1, 20);
        return key;
    }

    private static long le(byte[] raw) {
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static byte[] tokenModule() {
        Op op = new Op();
        op.call(0);
        op.i32(TRANSFER);
        op.eq32();
        op.ifVoid();
        op.i32(0);
        op.call(1);
        op.i32(96);
        op.i32(1);
        op.store8();
        for (int i = 0; i < 20; i++) {
            op.i32(97 + i);
            op.i32(i);
            op.load8u();
            op.store8();
        }
        op.i32(64);
        op.i32(1);
        op.store8();
        op.i32(65);
        op.call(2);
        op.i32(128);
        op.i32(20);
        op.load64();
        op.store64();
        op.i32(64);
        op.i32(21);
        op.i32(160);
        op.i32(8);
        op.call(3);
        op.drop();
        op.i32(160);
        op.load64();
        op.i32(128);
        op.load64();
        op.ltU64();
        op.ifVoid();
        op.otherwise();
        op.i32(176);
        op.i32(160);
        op.load64();
        op.i32(128);
        op.load64();
        op.sub64();
        op.store64();
        op.i32(64);
        op.i32(21);
        op.i32(176);
        op.i32(8);
        op.call(4);
        op.i32(96);
        op.i32(21);
        op.i32(192);
        op.i32(8);
        op.call(3);
        op.drop();
        op.i32(200);
        op.i32(192);
        op.load64();
        op.i32(128);
        op.load64();
        op.add64();
        op.store64();
        op.i32(96);
        op.i32(21);
        op.i32(200);
        op.i32(8);
        op.call(4);
        op.end();
        op.otherwise();
        op.i32(64);
        op.i32(1);
        op.store8();
        op.i32(65);
        op.call(2);
        op.i32(128);
        op.i64(50);
        op.store64();
        op.i32(64);
        op.i32(21);
        op.i32(128);
        op.i32(8);
        op.call(4);
        op.end();
        op.end();
        return tokenBytes(op.body());
    }

    private static byte[] poolModule() {
        Op op = new Op();
        writeKey(op, 0, 'r', '0');
        writeKey(op, 4, 'r', '1');
        op.call(0);
        op.i32(SWAP);
        op.eq32();
        op.ifVoid();
        op.i32(0);
        op.i32(2);
        op.i32(16);
        op.i32(8);
        op.call(1);
        op.drop();
        op.i32(4);
        op.i32(2);
        op.i32(24);
        op.i32(8);
        op.call(1);
        op.drop();
        op.i32(32);
        op.i32(16);
        op.load64();
        op.i32(24);
        op.load64();
        op.mul64();
        op.i32(16);
        op.load64();
        op.i64(25);
        op.add64();
        op.divU64();
        op.store64();
        op.i32(40);
        op.i32(16);
        op.load64();
        op.i64(25);
        op.add64();
        op.store64();
        op.i32(0);
        op.i32(2);
        op.i32(40);
        op.i32(8);
        op.call(2);
        op.i32(4);
        op.i32(2);
        op.i32(32);
        op.i32(8);
        op.call(2);
        op.otherwise();
        op.i32(16);
        op.i64(100);
        op.store64();
        op.i32(24);
        op.i64(100);
        op.store64();
        op.i32(0);
        op.i32(2);
        op.i32(16);
        op.i32(8);
        op.call(2);
        op.i32(4);
        op.i32(2);
        op.i32(24);
        op.i32(8);
        op.call(2);
        op.end();
        op.end();
        WasmAsm asm = new WasmAsm();
        int sel = asm.addType(new int[0], new int[] {0x7f});
        int get = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[] {0x7f});
        int set = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "selector", sel);
        asm.addImport("xdag", "storage_get", get);
        asm.addImport("xdag", "storage_set", set);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, op.body());
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] inboxModule() {
        Op op = new Op();
        fill(op, 0, 20, 3);
        fill(op, 20, 20, 4);
        op.i32(40);
        op.i32(9);
        op.store8();
        op.i32(41);
        op.i32(8);
        op.store8();
        op.i32(0);
        op.i32(20);
        op.i32(PING);
        op.i32(40);
        op.i32(2);
        op.i64(0);
        op.call(0);
        op.drop();
        op.end();
        WasmAsm asm = new WasmAsm();
        int send = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7e}, new int[] {0x7f});
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "send", send);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, op.body());
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] tokenBytes(byte[] callBody) {
        WasmAsm asm = new WasmAsm();
        int sel = asm.addType(new int[0], new int[] {0x7f});
        int read = asm.addType(new int[] {0x7f}, new int[0]);
        int get = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[] {0x7f});
        int set = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "selector", sel);
        asm.addImport("xdag", "input_read", read);
        asm.addImport("xdag", "caller", read);
        asm.addImport("xdag", "storage_get", get);
        asm.addImport("xdag", "storage_set", set);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, callBody);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static void writeKey(Op op, int at, char a, char b) {
        op.i32(at);
        op.i32(a);
        op.store8();
        op.i32(at + 1);
        op.i32(b);
        op.store8();
    }

    private static void fill(Op op, int at, int n, int value) {
        for (int i = 0; i < n; i++) {
            op.i32(at + i);
            op.i32(value);
            op.store8();
        }
    }

    private static ChainExecutor executor() {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        return new ChainExecutor(ChicoryEngine.compiler(), l1, store, null, 16);
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }

    private static final class Op {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        void i32(int v) {
            o.write(0x41);
            WasmAsm.sleb(o, v);
        }

        void i64(long v) {
            o.write(0x42);
            WasmAsm.sleb(o, v);
        }

        void store8() {
            raw(0x3a, 0x00, 0x00);
        }

        void load8u() {
            raw(0x2d, 0x00, 0x00);
        }

        void store64() {
            raw(0x37, 0x00, 0x00);
        }

        void load64() {
            raw(0x29, 0x00, 0x00);
        }

        void call(int f) {
            o.write(0x10);
            WasmAsm.uleb(o, f);
        }

        void drop() {
            o.write(0x1a);
        }

        void eq32() {
            o.write(0x46);
        }

        void ltU64() {
            o.write(0x58);
        }

        void add64() {
            o.write(0x7c);
        }

        void sub64() {
            o.write(0x7d);
        }

        void mul64() {
            o.write(0x7e);
        }

        void divU64() {
            o.write(0x80);
        }

        void ifVoid() {
            o.write(0x04);
            o.write(0x40);
        }

        void otherwise() {
            o.write(0x05);
        }

        void end() {
            o.write(0x0b);
        }

        byte[] body() {
            return o.toByteArray();
        }

        private void raw(int... bytes) {
            for (int b : bytes) {
                o.write(b);
            }
        }
    }
}
