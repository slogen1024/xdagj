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

import static com.dylibso.chicory.wasm.types.OpCode.BR;
import static com.dylibso.chicory.wasm.types.OpCode.BR_IF;
import static com.dylibso.chicory.wasm.types.OpCode.BR_TABLE;
import static com.dylibso.chicory.wasm.types.OpCode.CALL;
import static com.dylibso.chicory.wasm.types.OpCode.ELSE;
import static com.dylibso.chicory.wasm.types.OpCode.END;
import static com.dylibso.chicory.wasm.types.OpCode.GLOBAL_GET;
import static com.dylibso.chicory.wasm.types.OpCode.GLOBAL_SET;
import static com.dylibso.chicory.wasm.types.OpCode.I32_ADD;
import static com.dylibso.chicory.wasm.types.OpCode.I32_CONST;
import static com.dylibso.chicory.wasm.types.OpCode.I32_GT_U;
import static com.dylibso.chicory.wasm.types.OpCode.I32_MUL;
import static com.dylibso.chicory.wasm.types.OpCode.I32_SUB;
import static com.dylibso.chicory.wasm.types.OpCode.IF;
import static com.dylibso.chicory.wasm.types.OpCode.LOCAL_GET;
import static com.dylibso.chicory.wasm.types.OpCode.LOCAL_TEE;
import static com.dylibso.chicory.wasm.types.OpCode.LOOP;
import static com.dylibso.chicory.wasm.types.OpCode.MEMORY_GROW;
import static com.dylibso.chicory.wasm.types.OpCode.RETURN;
import static com.dylibso.chicory.wasm.types.OpCode.UNREACHABLE;
import static com.dylibso.chicory.wasm.types.SectionId.CODE;
import static com.dylibso.chicory.wasm.types.SectionId.DATA;
import static com.dylibso.chicory.wasm.types.SectionId.DATA_COUNT;
import static com.dylibso.chicory.wasm.types.SectionId.ELEMENT;
import static com.dylibso.chicory.wasm.types.SectionId.EXPORT;
import static com.dylibso.chicory.wasm.types.SectionId.FUNCTION;
import static com.dylibso.chicory.wasm.types.SectionId.GLOBAL;
import static com.dylibso.chicory.wasm.types.SectionId.IMPORT;
import static com.dylibso.chicory.wasm.types.SectionId.MEMORY;
import static com.dylibso.chicory.wasm.types.SectionId.START;
import static com.dylibso.chicory.wasm.types.SectionId.TABLE;
import static com.dylibso.chicory.wasm.types.SectionId.TYPE;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.WasmWriter;
import com.dylibso.chicory.wasm.types.AnnotatedInstruction;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Global;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.RawSection;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * Deployment-time instrumentation. The output is a pure function of the original bytes and the
 * {@link GasSchedule}: custom sections are dropped, {@code xdag.gas} is appended as the last
 * function import (every local function index and every function export/start/element index at or
 * above the old import count moves up by one), and a mutable {@code i32} stack counter is appended
 * as the last global.
 *
 * <p>Each function body is wrapped in a block of the function's result type. {@code return}, and
 * any {@code br} that used to leave the function, land on that block, so the stack decrement after
 * the block runs on every normal exit. Traps skip it; the instance is discarded. The counter traps
 * above {@link GasSchedule#maxStackHeight()} via {@code unreachable}, which does not depend on the
 * JVM stack.
 *
 * <p>A basic block starts at the function body, at a {@code loop}, at an {@code else}, and at the
 * instruction after a block/loop/if/else or a terminator. Its {@code xdag.gas} argument is the sum
 * of {@link GasSchedule#opcodeCost} over the original instructions in the block. The function-end
 * {@code end} is its own block and is only charged on fallthrough. {@code memory.grow} is preceded
 * by {@code local.tee; i32.const 8192; i32.mul; call $gas} using one extra {@code i32} local.
 */
public final class GasInstrumenter {

    private GasInstrumenter() {
    }

    public static byte[] instrument(byte[] wasm) throws InvalidModuleException {
        return instrument(wasm, GasSchedule.V1);
    }

    public static byte[] instrument(byte[] wasm, GasSchedule schedule) throws InvalidModuleException {
        WasmModule module;
        try {
            module = Parser.parse(wasm);
        } catch (ChicoryException e) {
            throw new InvalidModuleException("malformed", e.getMessage());
        }
        ModuleValidator.validate(module);
        Map<Integer, byte[]> raw = rawSections(wasm);
        int importCount = module.importSection().count(ExternalType.FUNCTION);
        int gasType = gasTypeIndex(module);
        int gasFunc = importCount;
        int stackGlobal = module.globalSection().globalCount();

        byte[] type = encodeTypes(module, gasType);
        byte[] imports = encodeImports(module, gasType);
        byte[] globals = encodeGlobals(module);
        byte[] exports = encodeExports(module, importCount);
        byte[] code = encodeCode(module, schedule, importCount, gasFunc, stackGlobal);
        if (module.codeSection().functionBodyCount() > 0 && raw.get(FUNCTION) == null) {
            throw new InvalidModuleException("instrument", "code without a function section");
        }
        byte[] element = raw.containsKey(ELEMENT) ? WasmCodec.bumpElements(raw.get(ELEMENT), importCount) : null;
        byte[] start = raw.containsKey(START) ? WasmCodec.bumpStart(raw.get(START), importCount) : null;

        WasmWriter writer = new WasmWriter();
        write(writer, TYPE, type);
        write(writer, IMPORT, imports);
        write(writer, FUNCTION, raw.get(FUNCTION));
        write(writer, TABLE, raw.get(TABLE));
        write(writer, MEMORY, raw.get(MEMORY));
        write(writer, GLOBAL, globals);
        write(writer, EXPORT, exports);
        write(writer, START, start);
        write(writer, ELEMENT, element);
        write(writer, DATA_COUNT, raw.get(DATA_COUNT));
        write(writer, CODE, code);
        write(writer, DATA, raw.get(DATA));
        byte[] out = writer.bytes();
        try {
            Parser.parse(out);
        } catch (ChicoryException e) {
            throw new InvalidModuleException("instrument", e.getMessage());
        }
        return out;
    }

    private static Map<Integer, byte[]> rawSections(byte[] wasm) {
        Map<Integer, byte[]> raw = new HashMap<>();
        Parser.parseWithoutDecoding(wasm, section -> {
            if (section instanceof RawSection r) {
                raw.put(r.sectionId(), r.contents());
            }
        });
        return raw;
    }

    private static int gasTypeIndex(WasmModule module) {
        FunctionType want = HostAbi.gasType();
        for (int i = 0; i < module.typeSection().typeCount(); i++) {
            if (module.typeSection().getType(i).typesMatch(want)) {
                return i;
            }
        }
        return module.typeSection().typeCount();
    }

    private static byte[] encodeTypes(WasmModule module, int gasType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int extra = gasType == module.typeSection().typeCount() ? 1 : 0;
        WasmCodec.writeUleb(out, module.typeSection().typeCount() + extra);
        for (int i = 0; i < module.typeSection().typeCount(); i++) {
            writeType(out, module.typeSection().getType(i));
        }
        if (extra == 1) {
            writeType(out, HostAbi.gasType());
        }
        return out.toByteArray();
    }

    private static void writeType(ByteArrayOutputStream out, FunctionType type) {
        out.write(0x60);
        writeValTypes(out, type.params());
        writeValTypes(out, type.returns());
    }

    private static void writeValTypes(ByteArrayOutputStream out, List<ValType> types) {
        WasmCodec.writeUleb(out, types.size());
        for (ValType t : types) {
            out.write(t.opcode());
        }
    }

    private static byte[] encodeImports(WasmModule module, int gasType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmCodec.writeUleb(out, module.importSection().importCount() + 1);
        for (int i = 0; i < module.importSection().importCount(); i++) {
            Import im = module.importSection().getImport(i);
            FunctionImport fi = (FunctionImport) im;
            WasmCodec.writeName(out, fi.module());
            WasmCodec.writeName(out, fi.name());
            out.write(ExternalType.FUNCTION.id());
            WasmCodec.writeUleb(out, fi.typeIndex());
        }
        WasmCodec.writeName(out, "xdag");
        WasmCodec.writeName(out, "gas");
        out.write(ExternalType.FUNCTION.id());
        WasmCodec.writeUleb(out, gasType);
        return out.toByteArray();
    }

    private static byte[] encodeGlobals(WasmModule module) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmCodec.writeUleb(out, module.globalSection().globalCount() + 1);
        for (int i = 0; i < module.globalSection().globalCount(); i++) {
            Global g = module.globalSection().getGlobal(i);
            out.write(g.valueType().opcode());
            out.write(g.mutabilityType().id());
            for (var ins : g.initInstructions()) {
                WasmCodec.writeInstruction(out, ins, 0);
            }
            out.write(END.opcode());
        }
        out.write(ValType.I32.opcode());
        out.write(0x01);
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, 0);
        out.write(END.opcode());
        return out.toByteArray();
    }

    private static byte[] encodeExports(WasmModule module, int importCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmCodec.writeUleb(out, module.exportSection().exportCount());
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            Export exp = module.exportSection().getExport(i);
            WasmCodec.writeName(out, exp.name());
            out.write(exp.exportType().id());
            int index = exp.index();
            if (exp.exportType() == ExternalType.FUNCTION && index >= importCount) {
                index++;
            }
            WasmCodec.writeUleb(out, index);
        }
        return out.toByteArray();
    }

    private static byte[] encodeCode(WasmModule module, GasSchedule schedule, int importCount, int gasFunc,
            int stackGlobal) throws InvalidModuleException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = module.codeSection().functionBodyCount();
        if (n == 0) {
            return null;
        }
        WasmCodec.writeUleb(out, n);
        for (int i = 0; i < n; i++) {
            FunctionType type = module.functionSection().getFunctionType(i, module.typeSection());
            byte[] body = encodeBody(module.codeSection().getFunctionBody(i), type, schedule, importCount, gasFunc,
                    stackGlobal);
            WasmCodec.writeUleb(out, body.length);
            out.writeBytes(body);
        }
        return out.toByteArray();
    }

    private static byte[] encodeBody(FunctionBody body, FunctionType type, GasSchedule schedule, int importCount,
            int gasFunc, int stackGlobal) throws InvalidModuleException {
        List<AnnotatedInstruction> ins = body.instructions();
        if (ins.isEmpty() || ins.get(ins.size() - 1).opcode() != END) {
            throw new InvalidModuleException("instrument", "function body does not end with end");
        }
        int expr = ins.size() - 1;
        boolean grows = false;
        for (int i = 0; i < expr; i++) {
            if (ins.get(i).opcode() == MEMORY_GROW) {
                grows = true;
                break;
            }
        }
        int tmp = type.params().size() + body.localTypes().size();
        ByteArrayOutputStream locals = new ByteArrayOutputStream();
        writeLocals(locals, body.localTypes(), grows);
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        emitEnter(code, stackGlobal, schedule.maxStackHeight());
        emitBlock(code, type.returns());
        boolean[] leader = leaders(ins, expr);
        for (int i = 0; i < expr; i++) {
            if (leader[i]) {
                int cost = 0;
                for (int k = i; k < expr && (k == i || !leader[k]); k++) {
                    cost += GasSchedule.opcodeCost(ins.get(k).opcode());
                }
                emitGas(code, cost, gasFunc);
            }
            AnnotatedInstruction in = ins.get(i);
            if (in.opcode() == RETURN) {
                WasmCodec.writeOpcode(code, BR);
                WasmCodec.writeUleb(code, in.depth());
            } else if (in.opcode() == MEMORY_GROW) {
                emitGrow(code, tmp, gasFunc, (int) in.operand(0));
            } else {
                WasmCodec.writeInstruction(code, in, importCount);
            }
        }
        if (expr == 0 || fallsThrough(ins.get(expr - 1).opcode())) {
            emitGas(code, GasSchedule.opcodeCost(END), gasFunc);
        }
        code.write(END.opcode());
        emitLeave(code, stackGlobal);
        code.write(END.opcode());
        byte[] exprBytes = code.toByteArray();
        byte[] localBytes = locals.toByteArray();
        byte[] merged = new byte[localBytes.length + exprBytes.length];
        System.arraycopy(localBytes, 0, merged, 0, localBytes.length);
        System.arraycopy(exprBytes, 0, merged, localBytes.length, exprBytes.length);
        return merged;
    }

    private static boolean[] leaders(List<AnnotatedInstruction> ins, int expr) {
        boolean[] leader = new boolean[expr];
        if (expr > 0) {
            leader[0] = true;
        }
        for (int i = 0; i < expr; i++) {
            OpCode op = ins.get(i).opcode();
            switch (op) {
                case BLOCK, IF -> mark(leader, i + 1);
                case LOOP -> {
                    leader[i] = true;
                    mark(leader, i + 1);
                }
                case ELSE -> {
                    leader[i] = true;
                    mark(leader, i + 1);
                }
                case END, BR, BR_IF, BR_TABLE, RETURN, UNREACHABLE -> mark(leader, i + 1);
                default -> {
                }
            }
        }
        return leader;
    }

    private static void mark(boolean[] leader, int i) {
        if (i < leader.length) {
            leader[i] = true;
        }
    }

    private static boolean fallsThrough(OpCode op) {
        return op != BR && op != BR_TABLE && op != RETURN && op != UNREACHABLE;
    }

    private static void writeLocals(ByteArrayOutputStream out, List<ValType> declared, boolean extra) {
        List<Integer> opcodes = new ArrayList<>();
        for (ValType t : declared) {
            opcodes.add(t.opcode());
        }
        if (extra) {
            opcodes.add(ValType.I32.opcode());
        }
        int groups = 0;
        for (int i = 0; i < opcodes.size();) {
            groups++;
            int op = opcodes.get(i);
            while (i < opcodes.size() && opcodes.get(i) == op) {
                i++;
            }
        }
        WasmCodec.writeUleb(out, groups);
        for (int i = 0; i < opcodes.size();) {
            int op = opcodes.get(i);
            int n = 0;
            while (i < opcodes.size() && opcodes.get(i) == op) {
                n++;
                i++;
            }
            WasmCodec.writeUleb(out, n);
            out.write(op);
        }
    }

    private static void emitEnter(ByteArrayOutputStream out, int global, int limit) {
        globalOp(out, GLOBAL_GET, global);
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, 1);
        out.write(I32_ADD.opcode());
        globalOp(out, GLOBAL_SET, global);
        globalOp(out, GLOBAL_GET, global);
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, limit);
        out.write(I32_GT_U.opcode());
        out.write(IF.opcode());
        out.write(0x40);
        out.write(UNREACHABLE.opcode());
        out.write(END.opcode());
    }

    private static void emitLeave(ByteArrayOutputStream out, int global) {
        globalOp(out, GLOBAL_GET, global);
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, 1);
        out.write(I32_SUB.opcode());
        globalOp(out, GLOBAL_SET, global);
    }

    private static void emitGas(ByteArrayOutputStream out, int cost, int gasFunc) {
        if (cost <= 0) {
            return;
        }
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, cost);
        WasmCodec.writeOpcode(out, CALL);
        WasmCodec.writeUleb(out, gasFunc);
    }

    private static void emitGrow(ByteArrayOutputStream out, int tmp, int gasFunc, int memIdx) {
        WasmCodec.writeOpcode(out, LOCAL_TEE);
        WasmCodec.writeUleb(out, tmp);
        out.write(I32_CONST.opcode());
        WasmCodec.writeSleb(out, GasSchedule.MEMORY_GROW_GAS_PER_PAGE);
        out.write(I32_MUL.opcode());
        WasmCodec.writeOpcode(out, CALL);
        WasmCodec.writeUleb(out, gasFunc);
        WasmCodec.writeOpcode(out, LOCAL_GET);
        WasmCodec.writeUleb(out, tmp);
        out.write(MEMORY_GROW.opcode());
        WasmCodec.writeUleb(out, memIdx);
    }

    private static void emitBlock(ByteArrayOutputStream out, List<ValType> results) throws InvalidModuleException {
        out.write(OpCode.BLOCK.opcode());
        if (results.isEmpty()) {
            out.write(0x40);
        } else if (results.size() == 1 && (results.get(0).opcode() == ValType.I32.opcode()
                || results.get(0).opcode() == ValType.I64.opcode())) {
            out.write(results.get(0).opcode());
        } else {
            throw new InvalidModuleException("instrument", "multi-value function results");
        }
    }

    private static void globalOp(ByteArrayOutputStream out, OpCode op, int global) {
        WasmCodec.writeOpcode(out, op);
        WasmCodec.writeUleb(out, global);
    }

    private static void write(WasmWriter writer, int id, byte[] payload) {
        if (payload != null) {
            writer.writeSection(id, payload);
        }
    }
}
