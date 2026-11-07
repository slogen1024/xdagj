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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Enough of a wasm emitter to build the §8.1 counterexamples and the instrumentation vector. */
final class WasmAsm {

    private final List<int[]> params = new ArrayList<>();
    private final List<int[]> results = new ArrayList<>();
    private final List<String> importModule = new ArrayList<>();
    private final List<String> importName = new ArrayList<>();
    private final List<Integer> importType = new ArrayList<>();
    private final List<Integer> funcType = new ArrayList<>();
    private final List<byte[]> funcBody = new ArrayList<>();
    private final List<String> exportName = new ArrayList<>();
    private final List<Integer> exportKind = new ArrayList<>();
    private final List<Integer> exportIndex = new ArrayList<>();
    private int memories = 1;
    private boolean memoryDeclaredMax = true;
    private boolean shared;
    private int memMin = 1;
    private int memMax = 1;
    private boolean mutableGlobal;
    private boolean tag;
    private int startLocal = -1;
    private int dataOffset = -1;
    private byte[] data = new byte[0];

    int addType(int[] paramTypes, int[] resultTypes) {
        params.add(paramTypes);
        results.add(resultTypes);
        return params.size() - 1;
    }

    void addImport(String module, String name, int type) {
        importModule.add(module);
        importName.add(name);
        importType.add(type);
    }

    void setBody(int localFunc, byte[] bodyWithEnd) {
        funcBody.set(localFunc, bodyWithEnd);
    }

    int addFunc(int type, byte[] bodyWithEnd) {
        funcType.add(type);
        funcBody.add(bodyWithEnd);
        return funcType.size() - 1;
    }

    void exportFunc(String name, int localFunc) {
        exportName.add(name);
        exportKind.add(0x00);
        exportIndex.add(importModule.size() + localFunc);
    }

    void clearExports() {
        exportName.clear();
        exportKind.clear();
        exportIndex.clear();
    }

    void exportMemory() {
        exportName.add("memory");
        exportKind.add(0x02);
        exportIndex.add(0);
    }

    void memories(int count) {
        this.memories = count;
    }

    void memory(int min, int max) {
        this.memMin = min;
        this.memMax = max;
        this.memoryDeclaredMax = true;
    }

    void memoryNoMax(int min) {
        this.memMin = min;
        this.memoryDeclaredMax = false;
    }

    void sharedMemory() {
        this.shared = true;
    }

    void mutableI32() {
        this.mutableGlobal = true;
    }

    void tag() {
        this.tag = true;
    }

    void start(int localFunc) {
        this.startLocal = localFunc;
    }

    void data(int offset, byte[] bytes) {
        this.dataOffset = offset;
        this.data = bytes;
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        section(out, 1, types());
        if (tag) {
            section(out, 13, new byte[] {0x01, 0x00, 0x00});
        }
        if (!importModule.isEmpty()) {
            section(out, 2, imports());
        }
        if (!funcType.isEmpty()) {
            section(out, 3, functions());
        }
        if (memories > 0) {
            section(out, 5, memory());
        }
        if (mutableGlobal) {
            section(out, 6, global());
        }
        if (!exportName.isEmpty()) {
            section(out, 7, exports());
        }
        if (startLocal >= 0) {
            section(out, 8, uleb(importModule.size() + startLocal));
        }
        if (!funcBody.isEmpty()) {
            section(out, 10, code());
        }
        if (dataOffset >= 0) {
            section(out, 11, dataSection());
        }
        return out.toByteArray();
    }

    /** Four ()->() functions, one memory page, the four required exports plus memory. */
    static byte[] contract(byte[] callBody) {
        WasmAsm asm = base();
        asm.funcBody.set(1, callBody);
        return asm.bytes();
    }

    static WasmAsm base() {
        WasmAsm asm = new WasmAsm();
        int t = asm.addType(new int[0], new int[0]);
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.addFunc(t, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm;
    }

    private byte[] types() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, params.size());
        for (int i = 0; i < params.size(); i++) {
            o.write(0x60);
            uleb(o, params.get(i).length);
            for (int t : params.get(i)) {
                o.write(t);
            }
            uleb(o, results.get(i).length);
            for (int t : results.get(i)) {
                o.write(t);
            }
        }
        return o.toByteArray();
    }

    private byte[] imports() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, importModule.size());
        for (int i = 0; i < importModule.size(); i++) {
            name(o, importModule.get(i));
            name(o, importName.get(i));
            o.write(0x00);
            uleb(o, importType.get(i));
        }
        return o.toByteArray();
    }

    private byte[] functions() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, funcType.size());
        for (int t : funcType) {
            uleb(o, t);
        }
        return o.toByteArray();
    }

    private byte[] memory() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, memories);
        for (int i = 0; i < memories; i++) {
            if (shared) {
                o.write(0x03);
                uleb(o, memMin);
                uleb(o, memMax);
            } else if (!memoryDeclaredMax) {
                o.write(0x00);
                uleb(o, memMin);
            } else {
                o.write(0x01);
                uleb(o, memMin);
                uleb(o, memMax);
            }
        }
        return o.toByteArray();
    }

    private static byte[] global() {
        return new byte[] {0x01, 0x7f, 0x01, 0x41, 0x00, 0x0b};
    }

    private byte[] exports() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, exportName.size());
        for (int i = 0; i < exportName.size(); i++) {
            name(o, exportName.get(i));
            o.write(exportKind.get(i));
            uleb(o, exportIndex.get(i));
        }
        return o.toByteArray();
    }

    private byte[] code() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, funcBody.size());
        for (byte[] body : funcBody) {
            byte[] func = new byte[body.length + 1];
            func[0] = 0x00;
            System.arraycopy(body, 0, func, 1, body.length);
            uleb(o, func.length);
            o.writeBytes(func);
        }
        return o.toByteArray();
    }

    private byte[] dataSection() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, 1);
        o.write(0x00);
        o.write(0x41);
        sleb(o, dataOffset);
        o.write(0x0b);
        uleb(o, data.length);
        o.writeBytes(data);
        return o.toByteArray();
    }

    private static void section(ByteArrayOutputStream out, int id, byte[] payload) {
        out.write(id);
        uleb(out, payload.length);
        out.writeBytes(payload);
    }

    private static byte[] uleb(int v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        uleb(o, v);
        return o.toByteArray();
    }

    static void uleb(ByteArrayOutputStream out, int value) {
        long x = Integer.toUnsignedLong(value);
        while (true) {
            if (x < 0x80) {
                out.write((int) x);
                return;
            }
            out.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
    }

    static void sleb(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) (value & 0x7F);
            value >>= 7;
            boolean end = (value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0);
            if (end) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    private static void name(ByteArrayOutputStream out, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        uleb(out, raw.length);
        out.writeBytes(raw);
    }
}
