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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import io.xdag.crypto.hash.HashUtils;

/**
 * Chicory 1.7.5. {@link #interpreter()} is the consensus reference. {@link #compiler()} compiles
 * the instrumented module once and builds a fresh {@link Instance} per call, so memory starts at
 * zero every time. A start section is not run; contracts enter through {@code init}/{@code call}/
 * {@code recv}/{@code query}.
 */
public final class ChicoryEngine implements WasmEngine {

    private final boolean compiler;
    private final GasSchedule schedule;
    private final int maxWasmBytes;
    private final ConcurrentHashMap<Bytes32, PreparedModule> cache = new ConcurrentHashMap<>();

    public ChicoryEngine(boolean compiler, GasSchedule schedule, int maxWasmBytes) {
        if (maxWasmBytes <= 0) {
            throw new IllegalArgumentException("maxWasmBytes");
        }
        this.compiler = compiler;
        this.schedule = Objects.requireNonNull(schedule);
        this.maxWasmBytes = maxWasmBytes;
    }

    public static ChicoryEngine interpreter() {
        return new ChicoryEngine(false, GasSchedule.V1, GasSchedule.MAX_WASM_BYTES);
    }

    public static ChicoryEngine compiler() {
        return new ChicoryEngine(true, GasSchedule.V1, GasSchedule.MAX_WASM_BYTES);
    }

    @Override
    public boolean isInterpreterMode() {
        return !compiler;
    }

    @Override
    public PreparedModule prepare(Bytes32 codeHash, Bytes wasm) throws InvalidModuleException {
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(wasm, "wasm");
        byte[] bytes = wasm.toArray();
        if (bytes.length > maxWasmBytes) {
            throw new InvalidModuleException("size", bytes.length + " > " + maxWasmBytes);
        }
        Bytes32 actual = HashUtils.sha256(Bytes.wrap(bytes));
        if (!actual.equals(codeHash)) {
            throw new InvalidModuleException("code-hash", actual.toHexString());
        }
        PreparedModule hit = cache.get(codeHash);
        if (hit != null) {
            return hit;
        }
        byte[] instrumented = GasInstrumenter.instrument(bytes, schedule);
        WasmModule module;
        try {
            module = Parser.parse(instrumented);
        } catch (ChicoryException e) {
            throw new InvalidModuleException("instrument", e.getMessage());
        }
        java.util.function.Function<Instance, Machine> factory = null;
        if (compiler) {
            try {
                factory = MachineFactoryCompiler.compile(module);
            } catch (ChicoryException e) {
                throw new InvalidModuleException("instrument", e.getMessage());
            }
        }
        PreparedModule prepared = new PreparedModule(module, factory, codeHash,
                HashUtils.sha256(Bytes.wrap(instrumented)), instrumented);
        cache.put(codeHash, prepared);
        return prepared;
    }

    @Override
    public ExecResult execute(PreparedModule module, String export, HostContext ctx, long gasLimit) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(ctx, "ctx");
        GasMeter meter = new GasMeter(gasLimit);
        boolean writes = !"query".equals(export) && ctx.writesAllowed();
        Instance.Builder builder = Instance.builder(module.module)
                .withImportValues(HostAbi.bind(module.module, meter, ctx, writes))
                .withStart(false);
        if (module.machineFactory != null) {
            builder.withMachineFactory(module.machineFactory);
        }
        Instance instance = null;
        try {
            instance = builder.build();
            ExportFunction fn = instance.export(export);
            fn.apply();
            return finish(false, null, meter, ctx, instance);
        } catch (ChicoryException e) {
            return finish(true, e.getMessage(), meter, ctx, instance);
        } catch (StackOverflowError e) {
            return finish(true, "call stack exhausted", meter, ctx, instance);
        }
    }

    private static ExecResult finish(boolean trapped, String message, GasMeter meter, HostContext ctx,
            Instance instance) {
        byte[] memory = new byte[0];
        int pages = 0;
        if (instance != null) {
            try {
                Memory mem = instance.memory();
                pages = mem.pages();
                memory = mem.readBytes(0, pages * Memory.PAGE_SIZE);
            } catch (RuntimeException ignored) {
                memory = new byte[0];
                pages = 0;
            }
        }
        return new ExecResult(trapped, message, meter.used(), ctx.returnData(), memory, pages);
    }
}
