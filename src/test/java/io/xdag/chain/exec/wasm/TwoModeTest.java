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
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.crypto.hash.HashUtils;

public class TwoModeTest {

    private static final Bytes CHAIN = address(1);
    private static final Bytes SENDER = address(2);
    private static final Bytes CONTRACT = address(3);
    private static final long GAS = 2_000_000L;

    @Test
    public void compilerMatchesTheInterpreterPin() throws InvalidModuleException {
        byte[] code = storeModule();
        ChainExecutor.Outcome interpreter = run(ChicoryEngine.interpreter(), code);
        ChainExecutor.Outcome compiled = run(ChicoryEngine.compiler(), code);
        assertEquals(interpreter.stateRoot(), compiled.stateRoot());
        assertEquals(interpreter.commitment(), compiled.commitment());
        assertEquals(3754L, interpreter.gasUsed());
        assertEquals("0x781f0ce1be1bfdcab5daedfe859c44b2e8e0050b95aaa113803c467e07736ac0",
                interpreter.stateRoot().toHexString());
        assertEquals("0xbbfc95f2f3ae2d2f175b21232c9c92b8b3a712cc830b269905a9ee44a2254221",
                interpreter.commitment().toHexString());
        PreparedModule module = ChicoryEngine.compiler().prepare(HashUtils.sha256(Bytes.wrap(code)), Bytes.wrap(code));
        assertTrue(module.compiled());
        assertFalse(module.fellBack());
    }

    @Test
    public void aRejectedCompilerFallsBackWithoutMovingTheRoot() throws InvalidModuleException {
        byte[] code = storeModule();
        ChicoryEngine fell = new ChicoryEngine(true, GasSchedule.V1, GasSchedule.MAX_WASM_BYTES, module -> {
            throw new IllegalStateException("method");
        });
        PreparedModule prepared = fell.prepare(HashUtils.sha256(Bytes.wrap(code)), Bytes.wrap(code));
        assertFalse(prepared.compiled());
        assertTrue(prepared.fellBack());
        ChainExecutor.Outcome interpreter = run(ChicoryEngine.interpreter(), code);
        ChainExecutor.Outcome fallback = run(fell, code);
        assertEquals(interpreter.stateRoot(), fallback.stateRoot());
        assertEquals(interpreter.commitment(), fallback.commitment());
    }

    private static ChainExecutor.Outcome run(WasmEngine engine, byte[] code) {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        ChainExecutor exec = new ChainExecutor(engine, l1, store, null, 16);
        byte[] hash = new byte[32];
        hash[31] = 4;
        return exec.apply(CHAIN, 1L, 1L, GasSchedule.MAX_CALL_GAS, 9L, java.util.List.of(new ChainExecutor.Tx(
                Bytes32.wrap(hash), ChainInput.Kind.CALL, null, SENDER, CONTRACT, GAS, GAS, 1, new byte[0], code)))
                .get(0);
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

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
