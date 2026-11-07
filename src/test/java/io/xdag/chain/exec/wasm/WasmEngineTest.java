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

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import io.xdag.chain.l1.ChainL1Processor;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;

public class WasmEngineTest {

    private static final long GAS = 5_000_000L;

    /** codeHash of {@link #pinned()} -> instrumented module. Category costs live in this hash. */
    private static final String PINNED_CODE = "0x64210ba2fdcab809814defacb84df948f083ac03794469729f286843c5fd0f36";
    private static final String PINNED_INSTRUMENTED =
            "0xa0454d2e174785eb78ad085a6df0d0e8c19c1f52a404c33e8499f151f164ee84";

    @Test
    public void instrumentedHashIsPinned() throws Exception {
        byte[] wasm = pinned();
        byte[] out = GasInstrumenter.instrument(wasm);
        String code = HashUtils.sha256(Bytes.wrap(wasm)).toHexString();
        String instrumented = HashUtils.sha256(Bytes.wrap(out)).toHexString();
        assertEquals(code + " -> " + instrumented, PINNED_CODE, code);
        assertEquals(instrumented, PINNED_INSTRUMENTED, instrumented);
        WasmModule module = Parser.parse(out);
        assertEquals(2, exportIndex(module, "call"));
        assertEquals(2, module.globalSection().globalCount());
    }

    @Test
    public void interpreterAndCompilerAgree() throws Exception {
        PreparedModule left = ChicoryEngine.interpreter().prepare(hash(pinned()), Bytes.wrap(pinned()));
        PreparedModule right = ChicoryEngine.compiler().prepare(hash(pinned()), Bytes.wrap(pinned()));
        ExecResult a = ChicoryEngine.interpreter().execute(left, "call", new MemoryHostContext(), GAS);
        ExecResult b = ChicoryEngine.compiler().execute(right, "call", new MemoryHostContext(), GAS);
        assertFalse(a.trapMessage(), a.trapped());
        assertFalse(b.trapMessage(), b.trapped());
        assertEquals(a.gasUsed(), b.gasUsed());
        assertEquals(2, a.memoryPages());
        assertEquals(a.memoryPages(), b.memoryPages());
        assertArrayEquals(a.memory(), b.memory());
    }

    @Test
    public void memoryGrowChargesPerPage() throws Exception {
        long zero = run(grow(0)).gasUsed();
        long one = run(grow(1)).gasUsed();
        assertEquals(GasSchedule.MEMORY_GROW_GAS_PER_PAGE, one - zero);
        assertEquals(zero, run(ChicoryEngine.compiler(), grow(0)).gasUsed());
        assertEquals(one, run(ChicoryEngine.compiler(), grow(1)).gasUsed());
    }

    @Test
    public void stackLeaveResetsAndBothModesTrapTogether() throws Exception {
        GasSchedule schedule = new GasSchedule(40);
        ChicoryEngine interpreter = new ChicoryEngine(false, schedule, GasSchedule.MAX_WASM_BYTES);
        ChicoryEngine compiler = new ChicoryEngine(true, schedule, GasSchedule.MAX_WASM_BYTES);
        byte[] ok = recurse(20);
        ExecResult a = run(interpreter, ok);
        ExecResult b = run(compiler, ok);
        assertFalse(a.trapMessage(), a.trapped());
        assertFalse(b.trapMessage(), b.trapped());
        assertEquals(a.gasUsed(), b.gasUsed());

        byte[] deep = recurse(50);
        ExecResult c = run(interpreter, deep);
        ExecResult d = run(compiler, deep);
        assertTrue(c.trapMessage(), c.trapped());
        assertTrue(d.trapped());
        assertEquals(c.gasUsed(), d.gasUsed());
    }

    @Test
    public void trapPointDoesNotDependOnThreadStack() throws Exception {
        ExecResult small = onStack(1 << 20, recurse(50));
        ExecResult large = onStack(8 << 20, recurse(50));
        assertTrue(small.trapped());
        assertTrue(large.trapped());
        assertEquals(small.gasUsed(), large.gasUsed());
    }

    @Test
    public void hostSha256AndSecpRoundTrip() throws Exception {
        byte[] abc = "abc".getBytes();
        ExecResult hashed = run(sha(abc));
        assertFalse(hashed.trapMessage(), hashed.trapped());
        byte[] digest = new byte[32];
        System.arraycopy(hashed.memory(), 16, digest, 0, 32);
        assertArrayEquals(HashUtils.sha256(Bytes.wrap(abc)).toArray(), digest);

        ECKeyPair key = ECKeyPair.generate();
        Bytes32 msg = HashUtils.sha256(Bytes.wrap(new byte[] {7}));
        Signature sig = Signer.sign(msg, key);
        byte[] encoded = sig.encodedBytes().toArray();
        byte[] compressed = key.getPublicKey().toCompressedBytes().toArray();
        ExecResult verified = run(verifyModule(msg.toArray(), encoded, compressed));
        assertFalse(verified.trapMessage(), verified.trapped());
        assertEquals(1, verified.memory()[96]);

        ExecResult recovered = run(recoverModule(msg.toArray(), encoded));
        assertFalse(recovered.trapMessage(), recovered.trapped());
        byte[] pub = new byte[33];
        System.arraycopy(recovered.memory(), 100, pub, 0, 33);
        assertArrayEquals(compressed, pub);
    }

    @Test
    public void repeatedStorageReadChargesWitnessOnce() throws Exception {
        MemoryHostContext ctx = new MemoryHostContext().setSiblings(new byte[] {1}, 2);
        ctx.storagePut(new byte[] {1}, new byte[] {9, 9, 9, 9});
        long once = run(ChicoryEngine.interpreter(), storageGets(1), ctx).gasUsed();
        long twice = run(ChicoryEngine.interpreter(), storageGets(2), ctx).gasUsed();
        long witness = GasSchedule.witnessGas(4, 2);
        // second get: four i32.const, call, drop, and STORAGE_GET with the witness already paid
        long second = 4L * GasSchedule.OP_CONST + GasSchedule.OP_CALL + GasSchedule.OP_CONST + GasSchedule.STORAGE_GET;
        assertEquals(second, twice - once);
        assertTrue(once > witness);
        ExecResult compiled = run(ChicoryEngine.compiler(), storageGets(2), ctx);
        assertEquals(twice, compiled.gasUsed());
        assertEquals(4, compiled.memory()[0]);
    }

    @Test
    public void queryWriteTrapsAndCallDoesNot() throws Exception {
        byte[] wasm = storageSet();
        MemoryHostContext query = new MemoryHostContext();
        ExecResult rejected = run(ChicoryEngine.interpreter(), wasm, query, "query");
        assertTrue(rejected.trapped());
        assertTrue(rejected.trapMessage(), rejected.trapMessage().contains("query write"));
        assertNull(query.storageGet(new byte[] {7}));

        MemoryHostContext call = new MemoryHostContext();
        ExecResult ok = run(ChicoryEngine.interpreter(), wasm, call, "call");
        assertFalse(ok.trapMessage(), ok.trapped());
        assertArrayEquals(new byte[] {8}, call.storageGet(new byte[] {7}));
    }

    @Test
    public void inputLenAndAbort() throws Exception {
        MemoryHostContext ctx = new MemoryHostContext().setInput(new byte[] {1, 2, 3, 4});
        ExecResult len = run(ChicoryEngine.interpreter(), inputLen(), ctx);
        assertFalse(len.trapMessage(), len.trapped());
        assertEquals(4, len.memory()[0] | (len.memory()[1] << 8) | (len.memory()[2] << 16) | (len.memory()[3] << 24));

        ExecResult aborted = run(ChicoryEngine.interpreter(), abortModule(), new MemoryHostContext());
        assertTrue(aborted.trapped());
        assertTrue(aborted.trapMessage(), aborted.trapMessage().contains("nope"));
        ExecResult same = run(ChicoryEngine.compiler(), abortModule(), new MemoryHostContext());
        assertEquals(aborted.gasUsed(), same.gasUsed());
    }

    @Test
    public void sizeAndCodeHash() {
        byte[] wasm = pinned();
        ChicoryEngine engine = new ChicoryEngine(false, GasSchedule.V1, 32);
        try {
            engine.prepare(hash(wasm), Bytes.wrap(wasm));
            org.junit.Assert.fail("size");
        } catch (InvalidModuleException e) {
            assertEquals("size", e.rule());
        }
        try {
            ChicoryEngine.interpreter().prepare(Bytes32.ZERO, Bytes.wrap(wasm));
            org.junit.Assert.fail("code-hash");
        } catch (InvalidModuleException e) {
            assertEquals("code-hash", e.rule());
        }
    }

    @Test
    public void witnessBoundMatchesCallGasCap() {
        assertEquals(ChainL1Processor.MAX_CALL_GAS_CAP, GasSchedule.MAX_CALL_GAS);
        long bound = GasSchedule.MAX_CALL_GAS / GasSchedule.GAS_PER_WITNESS_BYTE + 32 * 1024;
        assertEquals(bound, GasSchedule.MAX_WITNESS_BYTES);
    }

    @Test
    public void startFunctionIndexMovesPastTheGasImport() throws Exception {
        WasmAsm asm = WasmAsm.base();
        asm.start(0);
        byte[] out = GasInstrumenter.instrument(asm.bytes());
        assertEquals(1, Parser.parse(out).startSection().orElseThrow().startIndex());
    }

    private static ExecResult onStack(long stack, byte[] wasm) throws Exception {
        AtomicReference<ExecResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                GasSchedule schedule = new GasSchedule(40);
                result.set(run(new ChicoryEngine(false, schedule, GasSchedule.MAX_WASM_BYTES), wasm));
            } catch (Throwable t) {
                error.set(t);
            }
        }, "wasm-stack", stack);
        thread.start();
        thread.join();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private static ExecResult run(byte[] wasm) throws Exception {
        return run(ChicoryEngine.interpreter(), wasm, new MemoryHostContext(), "call");
    }

    private static ExecResult run(ChicoryEngine engine, byte[] wasm) throws Exception {
        return run(engine, wasm, new MemoryHostContext(), "call");
    }

    private static ExecResult run(ChicoryEngine engine, byte[] wasm, MemoryHostContext ctx) throws Exception {
        return run(engine, wasm, ctx, "call");
    }

    private static ExecResult run(ChicoryEngine engine, byte[] wasm, MemoryHostContext ctx, String export)
            throws Exception {
        PreparedModule module = engine.prepare(hash(wasm), Bytes.wrap(wasm));
        return engine.execute(module, export, ctx, GAS);
    }

    private static Bytes32 hash(byte[] wasm) {
        return HashUtils.sha256(Bytes.wrap(wasm));
    }

    private static int exportIndex(WasmModule module, String name) {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (name.equals(module.exportSection().getExport(i).name())) {
                return module.exportSection().getExport(i).index();
            }
        }
        throw new AssertionError(name);
    }

    /** Sign-ext, a block, br_table, if/else, memory.fill, memory.grow 1, a mutable global. */
    private static byte[] pinned() {
        WasmAsm asm = WasmAsm.base();
        asm.mutableI32();
        asm.memory(1, 4);
        asm.setBody(1, new byte[] {
                0x02, 0x40, 0x01, 0x0b,
                0x02, 0x40, 0x41, 0x00, 0x0e, 0x01, 0x00, 0x00, 0x0b,
                0x41, 0x01, 0x04, 0x40, 0x01, 0x05, 0x01, 0x0b,
                0x41, (byte) 0x80, 0x00, (byte) 0xc0, 0x1a,
                0x23, 0x00, 0x1a,
                0x41, 0x00, 0x41, 0x00, 0x41, 0x04, (byte) 0xfc, 0x0b, 0x00,
                0x41, 0x01, 0x40, 0x00, 0x1a,
                0x0b});
        return asm.bytes();
    }

    private static byte[] grow(int pages) {
        WasmAsm asm = WasmAsm.base();
        asm.memory(1, 4);
        asm.setBody(1, new byte[] {0x41, (byte) pages, 0x40, 0x00, 0x1a, 0x0b});
        return asm.bytes();
    }

    /** call invokes burst twice. burst recurses {@code depth} frames, counting in global 0. */
    private static byte[] recurse(int depth) {
        WasmAsm asm = WasmAsm.base();
        asm.mutableI32();
        int t = 0;
        int burst = asm.addFunc(t, burst(depth));
        asm.setBody(1, new byte[] {0x10, (byte) burst, 0x10, (byte) burst, 0x0b});
        return asm.bytes();
    }

    private static byte[] burst(int depth) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // global++
        o.write(0x23);
        o.write(0x00);
        o.write(0x41);
        o.write(0x01);
        o.write(0x6a);
        o.write(0x24);
        o.write(0x00);
        // if global < depth: call self
        o.write(0x23);
        o.write(0x00);
        o.write(0x41);
        WasmAsm.sleb(o, depth);
        o.write(0x49); // i32.lt_u
        o.write(0x04);
        o.write(0x40);
        o.write(0x10);
        o.write(0x04); // local function 4, the burst itself in a module whose locals are 0..4
        o.write(0x0b);
        // global--
        o.write(0x23);
        o.write(0x00);
        o.write(0x41);
        o.write(0x01);
        o.write(0x6b);
        o.write(0x24);
        o.write(0x00);
        o.write(0x0b);
        return o.toByteArray();
    }

    private static byte[] sha(byte[] payload) {
        byte[] mem = new byte[16 + payload.length];
        System.arraycopy(payload, 0, mem, 0, payload.length);
        WasmAsm asm = new WasmAsm();
        int hash = asm.addType(new int[] {0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "sha256", hash);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {
                0x41, 0x00, 0x41, (byte) payload.length, 0x41, 0x10, 0x10, 0x00, 0x0b});
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

    private static byte[] verifyModule(byte[] hash, byte[] sig, byte[] pub) {
        byte[] mem = new byte[160];
        System.arraycopy(hash, 0, mem, 0, 32);
        System.arraycopy(sig, 0, mem, 32, Math.min(65, sig.length));
        System.arraycopy(pub, 0, mem, 97, Math.min(33, pub.length));
        // dest 96, hash 0, sig 32, pub 97, call, i32.store8. Immediates >= 64 go through sleb.
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        i32const(body, 96);
        i32const(body, 0);
        i32const(body, 32);
        i32const(body, 97);
        body.write(0x10);
        body.write(0x00);
        body.write(0x3a);
        body.write(0x00);
        body.write(0x00);
        body.write(0x0b);
        return hostCall("secp256k1_verify", new int[] {0x7f, 0x7f, 0x7f}, new int[] {0x7f}, body.toByteArray(), mem);
    }

    private static byte[] recoverModule(byte[] hash, byte[] sig) {
        byte[] mem = new byte[160];
        System.arraycopy(hash, 0, mem, 0, 32);
        System.arraycopy(sig, 0, mem, 32, 64);
        int v = sig.length > 64 ? sig[64] & 0xff : 0;
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        i32const(body, 99);
        i32const(body, 0);
        i32const(body, 32);
        i32const(body, v);
        i32const(body, 100);
        body.write(0x10);
        body.write(0x00);
        body.write(0x3a);
        body.write(0x00);
        body.write(0x00);
        body.write(0x0b);
        return hostCall("secp256k1_recover", new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[] {0x7f}, body.toByteArray(),
                mem);
    }

    private static byte[] storageGets(int times) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < times; i++) {
            boolean last = i == times - 1;
            if (last) {
                i32const(body, 0);
            }
            i32const(body, 8);
            i32const(body, 1);
            i32const(body, 16);
            i32const(body, 4);
            body.write(0x10);
            body.write(0x00);
            if (last) {
                body.write(0x3a);
                body.write(0x00);
                body.write(0x00);
            } else {
                body.write(0x1a);
            }
        }
        body.write(0x0b);
        byte[] mem = new byte[32];
        mem[8] = 1;
        return hostCall("storage_get", new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[] {0x7f}, body.toByteArray(), mem);
    }

    private static byte[] storageSet() {
        byte[] mem = new byte[] {7, 8};
        byte[] body = new byte[] {0x41, 0x00, 0x41, 0x01, 0x41, 0x01, 0x41, 0x01, 0x10, 0x00, 0x0b};
        WasmAsm asm = new WasmAsm();
        int host = asm.addType(new int[] {0x7f, 0x7f, 0x7f, 0x7f}, new int[0]);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "storage_set", host);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, body);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, body);
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        asm.data(0, mem);
        return asm.bytes();
    }

    private static byte[] inputLen() {
        return hostCall("input_len", new int[0], new int[] {0x7f},
                new byte[] {0x41, 0x00, 0x10, 0x00, 0x36, 0x02, 0x00, 0x0b}, new byte[0]);
    }

    private static byte[] abortModule() {
        byte[] mem = "nope".getBytes();
        return hostCall("abort", new int[] {0x7f, 0x7f}, new int[0],
                new byte[] {0x41, 0x00, 0x41, 0x04, 0x10, 0x00, 0x0b}, mem);
    }

    private static void i32const(java.io.ByteArrayOutputStream out, int value) {
        out.write(0x41);
        WasmAsm.sleb(out, value);
    }

    private static byte[] hostCall(String name, int[] params, int[] results, byte[] callBody, byte[] data) {
        WasmAsm asm = new WasmAsm();
        int host = asm.addType(params, results);
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", name, host);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, callBody);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        if (data.length > 0) {
            asm.data(0, data);
        }
        return asm.bytes();
    }
}
