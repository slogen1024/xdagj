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
import static org.junit.Assert.fail;

import org.junit.Test;

public class ModuleValidatorTest {

    @Test
    public void whitelistCounterexamples() {
        reject("float", WasmAsm.contract(new byte[] {
                0x43, 0x00, 0x00, 0x00, 0x00,
                0x43, 0x00, 0x00, 0x00, 0x00,
                (byte) 0x92, 0x1a, 0x0b}));
        reject("simd", WasmAsm.contract(prefix(new byte[] {(byte) 0xfd, 0x0c}, new byte[16], new byte[] {0x1a, 0x0b})));
        reject("atomics", shared());
        reject("exceptions", withTag());
        reject("multi-memory", twoMemories());
        reject("reference-types", WasmAsm.contract(new byte[] {(byte) 0xd0, 0x70, 0x1a, 0x0b}));
        reject("tail-call", WasmAsm.contract(new byte[] {0x12, 0x01, 0x0b}));
        reject("memory", noMemory());
        reject("memory-max", noMax());
        reject("memory-pages", tooManyPages());
        reject("import", foreignImport());
        reject("import", unknownXdag());
        reject("import", gasImport());
        reject("export", missingQuery());
        reject("export", initReturnsI32());
    }

    private static void reject(String rule, byte[] wasm) {
        try {
            ModuleValidator.validate(wasm);
            fail("accepted module, wanted " + rule);
        } catch (InvalidModuleException e) {
            assertEquals(e.getMessage(), rule, e.rule());
        }
    }

    private static byte[] withTag() {
        WasmAsm asm = WasmAsm.base();
        asm.tag();
        return asm.bytes();
    }

    private static byte[] shared() {
        WasmAsm asm = WasmAsm.base();
        asm.sharedMemory();
        return asm.bytes();
    }

    private static byte[] twoMemories() {
        WasmAsm asm = WasmAsm.base();
        asm.memories(2);
        return asm.bytes();
    }

    private static byte[] noMemory() {
        WasmAsm asm = WasmAsm.base();
        asm.memories(0);
        asm.clearExports();
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        return asm.bytes();
    }

    private static byte[] noMax() {
        WasmAsm asm = WasmAsm.base();
        asm.memoryNoMax(1);
        return asm.bytes();
    }

    private static byte[] tooManyPages() {
        WasmAsm asm = WasmAsm.base();
        asm.memory(1, 65);
        return asm.bytes();
    }

    private static byte[] foreignImport() {
        WasmAsm asm = new WasmAsm();
        int t = asm.addType(new int[0], new int[0]);
        asm.addImport("env", "random", t);
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] unknownXdag() {
        WasmAsm asm = new WasmAsm();
        int t = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "nope", t);
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] gasImport() {
        WasmAsm asm = new WasmAsm();
        int gas = asm.addType(new int[] {0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "gas", gas);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] missingQuery() {
        WasmAsm asm = new WasmAsm();
        int t = asm.addType(new int[0], new int[0]);
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] initReturnsI32() {
        WasmAsm asm = new WasmAsm();
        int ret = asm.addType(new int[0], new int[] {0x7f});
        int empty = asm.addType(new int[0], new int[0]);
        asm.addFunc(ret, new byte[] {0x41, 0x00, 0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static byte[] prefix(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }
}
