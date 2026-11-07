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

import static com.dylibso.chicory.wasm.types.ExternalType.MEMORY;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Global;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * §8.1 whitelist. MVP tables and {@code call_indirect} stay; the reference-types proposal
 * ({@code ref.*}, {@code externref}, {@code table.get}) does not. {@code xdag.gas} is not a
 * contract import — the instrumenter adds it.
 */
public final class ModuleValidator {

    private static final Set<String> EXPORTS = Set.of("init", "call", "recv", "query");

    private ModuleValidator() {
    }

    public static void validate(byte[] wasm) throws InvalidModuleException {
        WasmModule module;
        try {
            module = Parser.parse(wasm);
        } catch (ChicoryException e) {
            throw new InvalidModuleException("malformed", e.getMessage());
        }
        validate(module);
    }

    public static void validate(WasmModule module) throws InvalidModuleException {
        if (module.tagSection().isPresent()) {
            throw new InvalidModuleException("exceptions", "tag section");
        }
        for (ValType t : types(module)) {
            rejectValue(t, "type");
        }
        validateMemory(module);
        validateTables(module);
        validateImports(module);
        validateGlobals(module);
        validateCode(module);
        validateExports(module);
    }

    private static void validateMemory(WasmModule module) throws InvalidModuleException {
        int defined = module.memorySection().map(s -> s.memoryCount()).orElse(0);
        if (defined != 1) {
            throw new InvalidModuleException(defined > 1 ? "multi-memory" : "memory",
                    "defined memories = " + defined);
        }
        MemoryLimits limits = module.memorySection().orElseThrow().getMemory(0).limits();
        if (limits.shared()) {
            throw new InvalidModuleException("atomics", "shared memory");
        }
        // Chicory's one-arg constructor stores "no maximum" as MAX_PAGES. A declared max of
        // 65536 is also above the protocol cap, so both land on memory-pages / memory-max.
        if (limits.maximumPages() == MemoryLimits.MAX_PAGES) {
            throw new InvalidModuleException("memory-max", "memory maximum is required");
        }
        if (limits.initialPages() > GasSchedule.MAX_MEMORY_PAGES
                || limits.maximumPages() > GasSchedule.MAX_MEMORY_PAGES) {
            throw new InvalidModuleException("memory-pages",
                    "initial " + limits.initialPages() + " max " + limits.maximumPages());
        }
    }

    private static void validateTables(WasmModule module) throws InvalidModuleException {
        for (int i = 0; i < module.tableSection().tableCount(); i++) {
            Table table = module.tableSection().getTable(i);
            if (table.elementType().opcode() != ValType.ID.FuncRef) {
                throw new InvalidModuleException("reference-types", "table element " + table.elementType());
            }
        }
    }

    private static void validateImports(WasmModule module) throws InvalidModuleException {
        Set<String> seen = new HashSet<>();
        int n = module.importSection().importCount();
        for (int i = 0; i < n; i++) {
            Import im = module.importSection().getImport(i);
            if (!(im instanceof FunctionImport fi) || !"xdag".equals(fi.module())) {
                throw new InvalidModuleException("import", "only xdag function imports are allowed, got " + im);
            }
            FunctionType expected = HostAbi.signature(fi.name());
            if (expected == null) {
                throw new InvalidModuleException("import", "unknown xdag." + fi.name());
            }
            FunctionType actual = module.typeSection().getType(fi.typeIndex());
            if (!actual.typesMatch(expected)) {
                throw new InvalidModuleException("import", "xdag." + fi.name() + " has the wrong type");
            }
            if (!seen.add(fi.name())) {
                throw new InvalidModuleException("import", "duplicate xdag." + fi.name());
            }
        }
    }

    private static void validateGlobals(WasmModule module) throws InvalidModuleException {
        for (int i = 0; i < module.globalSection().globalCount(); i++) {
            Global g = module.globalSection().getGlobal(i);
            rejectValue(g.valueType(), "global");
            for (Instruction ins : g.initInstructions()) {
                scan(ins);
            }
        }
    }

    private static void validateCode(WasmModule module) throws InvalidModuleException {
        int bodies = module.codeSection().functionBodyCount();
        for (int i = 0; i < bodies; i++) {
            for (Instruction ins : module.codeSection().getFunctionBody(i).instructions()) {
                scan(ins);
            }
        }
    }

    private static void validateExports(WasmModule module) throws InvalidModuleException {
        boolean memory = false;
        Set<String> funcs = new HashSet<>();
        int n = module.exportSection().exportCount();
        for (int i = 0; i < n; i++) {
            var exp = module.exportSection().getExport(i);
            if ("memory".equals(exp.name())) {
                if (exp.exportType() != MEMORY) {
                    throw new InvalidModuleException("export", "memory export is not a memory");
                }
                memory = true;
                continue;
            }
            if (!EXPORTS.contains(exp.name())) {
                continue;
            }
            if (exp.exportType() != ExternalType.FUNCTION) {
                throw new InvalidModuleException("export", exp.name() + " is not a function");
            }
            FunctionType type = functionType(module, exp.index());
            if (!type.params().isEmpty() || !type.returns().isEmpty()) {
                throw new InvalidModuleException("export", exp.name() + " must be () -> ()");
            }
            funcs.add(exp.name());
        }
        if (!memory) {
            throw new InvalidModuleException("export", "missing memory export");
        }
        for (String name : EXPORTS) {
            if (!funcs.contains(name)) {
                throw new InvalidModuleException("export", "missing " + name);
            }
        }
    }

    private static FunctionType functionType(WasmModule module, int funcIndex) throws InvalidModuleException {
        int seen = 0;
        int n = module.importSection().importCount();
        for (int i = 0; i < n; i++) {
            Import im = module.importSection().getImport(i);
            if (im instanceof FunctionImport fi) {
                if (seen == funcIndex) {
                    return module.typeSection().getType(fi.typeIndex());
                }
                seen++;
            }
        }
        int local = funcIndex - seen;
        if (local < 0 || local >= module.functionSection().functionCount()) {
            throw new InvalidModuleException("export", "function index " + funcIndex);
        }
        return module.functionSection().getFunctionType(local, module.typeSection());
    }

    private static void scan(Instruction ins) throws InvalidModuleException {
        String rule = forbidden(ins.opcode());
        if (rule != null) {
            throw new InvalidModuleException(rule, ins.opcode().toString());
        }
        if (ins.opcode() == OpCode.MEMORY_GROW || ins.opcode() == OpCode.MEMORY_SIZE
                || ins.opcode() == OpCode.MEMORY_FILL) {
            if (ins.operandCount() > 0 && ins.operand(0) != 0) {
                throw new InvalidModuleException("multi-memory", ins.opcode().toString());
            }
        } else if (ins.opcode() == OpCode.MEMORY_COPY) {
            if (ins.operand(0) != 0 || ins.operand(1) != 0) {
                throw new InvalidModuleException("multi-memory", "memory.copy");
            }
        } else if (ins.opcode() == OpCode.MEMORY_INIT && ins.operandCount() > 1 && ins.operand(1) != 0) {
            throw new InvalidModuleException("multi-memory", "memory.init");
        }
    }

    static String forbidden(OpCode op) {
        String name = op.name();
        if (name.contains("F32") || name.contains("F64")) {
            return "float";
        }
        int c = op.opcode();
        if ((c & 0xFF00) == 0xFD00) {
            return "simd";
        }
        if ((c & 0xFF00) == 0xFE00) {
            return "atomics";
        }
        if ((c & 0xFF00) == 0xFB00) {
            return "reference-types";
        }
        if ((c & 0xFF00) == 0xFC00) {
            if (op == OpCode.MEMORY_INIT || op == OpCode.DATA_DROP || op == OpCode.MEMORY_COPY
                    || op == OpCode.MEMORY_FILL) {
                return null;
            }
            return "reference-types";
        }
        return switch (op) {
            case RETURN_CALL, RETURN_CALL_INDIRECT, RETURN_CALL_REF -> "tail-call";
            case THROW, THROW_REF, TRY_TABLE -> "exceptions";
            case REF_NULL, REF_IS_NULL, REF_FUNC, REF_EQ, REF_AS_NON_NULL, REF_TEST, REF_TEST_NULL, REF_I31, CALL_REF,
                    TABLE_GET, TABLE_SET -> "reference-types";
            default -> null;
        };
    }

    private static void rejectValue(ValType t, String where) throws InvalidModuleException {
        if (t.isFloatingPoint()) {
            throw new InvalidModuleException("float", where);
        }
        if (t.isReference()) {
            throw new InvalidModuleException("reference-types", where);
        }
        if (t.opcode() == ValType.ID.V128) {
            throw new InvalidModuleException("simd", where);
        }
    }

    private static List<ValType> types(WasmModule module) {
        List<ValType> out = new java.util.ArrayList<>();
        for (int i = 0; i < module.typeSection().typeCount(); i++) {
            FunctionType type = module.typeSection().getType(i);
            out.addAll(type.params());
            out.addAll(type.returns());
        }
        return out;
    }
}
