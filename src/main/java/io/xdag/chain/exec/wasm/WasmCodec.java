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
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.WasmEncoding;

/**
 * Canonical LEB128 and the opcode shapes Chicory's parser produces. {@code DROP} and {@code SELECT}
 * carry a phantom operand that is not in the binary; encoding follows {@link OpCode#signature}.
 */
final class WasmCodec {

    private WasmCodec() {
    }

    static void writeUleb(ByteArrayOutputStream out, int value) {
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

    static void writeSleb(ByteArrayOutputStream out, long value) {
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

    static void writeName(ByteArrayOutputStream out, String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        writeUleb(out, raw.length);
        out.writeBytes(raw);
    }

    static void writeOpcode(ByteArrayOutputStream out, OpCode op) {
        int c = op.opcode();
        if (c > 0xFF) {
            out.write((c >> 8) & 0xFF);
        }
        out.write(c & 0xFF);
    }

    /**
     * Block types Chicory stores as a raw {@code readVarUInt32}. {@code 0x40} (empty) collides with
     * the unsigned value 64, so it is special-cased. A type index whose shortest signed LEB is a
     * single value-type byte (index 127 is {@code i32}) cannot be recovered; modules that large
     * are outside what this encoder claims.
     */
    static void writeBlockType(ByteArrayOutputStream out, long operand) {
        if (operand == 0x40L) {
            out.write(0x40);
            return;
        }
        int low = (int) (operand & 0xFF);
        if ((operand >>> 8) == 0 && isValueTypeByte(low)) {
            out.write(low);
            return;
        }
        writeSleb(out, operand);
    }

    static void writeInstruction(ByteArrayOutputStream out, Instruction ins, int importCount) {
        OpCode op = ins.opcode();
        if (op == OpCode.BR_TABLE) {
            writeOpcode(out, op);
            long[] labels = ins.operands();
            int n = labels.length - 1;
            writeUleb(out, n);
            for (int i = 0; i < labels.length; i++) {
                writeUleb(out, (int) labels[i]);
            }
            return;
        }
        writeOpcode(out, op);
        long[] operands = ins.operands();
        int cursor = 0;
        for (WasmEncoding enc : OpCode.signature(op)) {
            switch (enc) {
                case VARUINT -> {
                    long v = operands[cursor++];
                    if (op == OpCode.CALL && v >= importCount) {
                        v++;
                    }
                    writeUleb(out, (int) v);
                }
                case VARSINT32, VARSINT64 -> writeSleb(out, operands[cursor++]);
                case BLOCK_TYPE -> writeBlockType(out, operands[cursor++]);
                case BYTE -> out.write((int) operands[cursor++]);
                case FLOAT32 -> writeFixed(out, operands[cursor++], 4);
                case FLOAT64 -> writeFixed(out, operands[cursor++], 8);
                case MEMARG -> {
                    long align = operands[cursor++];
                    long offset = operands[cursor++];
                    long mem = operands[cursor++];
                    if (mem == 0) {
                        writeUleb(out, (int) align);
                    } else {
                        writeUleb(out, (int) (align | 0x40));
                        writeUleb(out, (int) mem);
                    }
                    writeUleb(out, (int) offset);
                }
                case VEC_VALUE_TYPE -> {
                    int n = operands.length - cursor;
                    writeUleb(out, n);
                    for (int i = 0; i < n; i++) {
                        writeUleb(out, (int) (operands[cursor++] & 0xFF));
                    }
                }
                case VEC_VARUINT, VEC_CATCH, V128 -> throw new IllegalStateException("cannot encode " + op);
                default -> throw new IllegalStateException("cannot encode " + op + " " + enc);
            }
        }
    }

    static byte[] bumpStart(byte[] contents, int importCount) throws InvalidModuleException {
        int[] pos = {0};
        int idx = readUleb(contents, pos);
        if (pos[0] != contents.length) {
            throw new InvalidModuleException("malformed", "trailing bytes in the start section");
        }
        if (idx >= importCount) {
            idx++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUleb(out, idx);
        return out.toByteArray();
    }

    /**
     * Function indices in element segments live in the same index space as {@code call}. Expression
     * initializers are the reference-types proposal and are rejected.
     */
    static byte[] bumpElements(byte[] contents, int importCount) throws InvalidModuleException {
        Cursor in = new Cursor(contents);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = in.uleb();
        writeUleb(out, count);
        for (int i = 0; i < count; i++) {
            int flags = in.uleb();
            if ((flags & 0b100) != 0) {
                throw new InvalidModuleException("reference-types", "element expression initializer");
            }
            writeUleb(out, flags);
            boolean active = (flags & 0b001) == 0;
            boolean hasTable = active && (flags & 0b010) != 0;
            boolean alwaysFuncRef = active && !hasTable;
            boolean hasElemKind = !alwaysFuncRef;
            if (hasTable) {
                writeUleb(out, in.uleb());
            }
            if (active) {
                in.copyExpr(out);
            }
            if (hasElemKind) {
                writeUleb(out, in.uleb());
            }
            int n = in.uleb();
            writeUleb(out, n);
            for (int j = 0; j < n; j++) {
                int idx = in.uleb();
                if (idx >= importCount) {
                    idx++;
                }
                writeUleb(out, idx);
            }
        }
        if (in.remaining() != 0) {
            throw new InvalidModuleException("malformed", "trailing bytes in the element section");
        }
        return out.toByteArray();
    }

    static int readUleb(byte[] data, int[] pos) throws InvalidModuleException {
        long v = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= data.length) {
                throw new InvalidModuleException("malformed", "truncated leb128");
            }
            int b = data[pos[0]++] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (int) v;
            }
            shift += 7;
            if (shift > 28) {
                throw new InvalidModuleException("malformed", "leb128 too long");
            }
        }
    }

    private static void writeFixed(ByteArrayOutputStream out, long value, int n) {
        for (int i = 0; i < n; i++) {
            out.write((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    private static boolean isValueTypeByte(int b) {
        return b == 0x7F || b == 0x7E || b == 0x7D || b == 0x7C || b == 0x7B || b == 0x70 || b == 0x6F;
    }

    private static final class Cursor {
        private final byte[] b;
        private int p;

        Cursor(byte[] b) {
            this.b = b;
        }

        int remaining() {
            return b.length - p;
        }

        int uleb() throws InvalidModuleException {
            int[] pos = {p};
            int v = readUleb(b, pos);
            p = pos[0];
            return v;
        }

        void copyExpr(ByteArrayOutputStream out) throws InvalidModuleException {
            while (true) {
                if (p >= b.length) {
                    throw new InvalidModuleException("malformed", "element offset did not end");
                }
                int start = p;
                if ((b[p] & 0xFF) == OpCode.END.opcode()) {
                    p++;
                    out.write(b, start, 1);
                    return;
                }
                skipInstruction();
                out.write(b, start, p - start);
            }
        }

        private void skipInstruction() throws InvalidModuleException {
            if (p >= b.length) {
                throw new InvalidModuleException("malformed", "truncated instruction");
            }
            int raw = b[p++] & 0xFF;
            int code = raw;
            if (raw == 0xFC || raw == 0xFD || raw == 0xFE) {
                if (p >= b.length) {
                    throw new InvalidModuleException("malformed", "truncated prefix opcode");
                }
                code = (raw << 8) | (b[p++] & 0xFF);
            }
            OpCode op = OpCode.byOpCode(code);
            if (op == null) {
                throw new InvalidModuleException("malformed", "unknown opcode " + code);
            }
            for (WasmEncoding enc : OpCode.signature(op)) {
                switch (enc) {
                    case VARUINT, VARSINT32, VARSINT64, BLOCK_TYPE -> uleb();
                    case BYTE -> skip(1);
                    case FLOAT32 -> skip(4);
                    case FLOAT64 -> skip(8);
                    case V128 -> skip(16);
                    case MEMARG -> {
                        int flags = uleb();
                        if ((flags >> 6) != 0) {
                            uleb();
                        }
                        uleb();
                    }
                    case VEC_VARUINT, VEC_VALUE_TYPE -> {
                        int n = uleb();
                        for (int i = 0; i < n; i++) {
                            uleb();
                        }
                    }
                    case VEC_CATCH -> throw new InvalidModuleException("exceptions", "try_table");
                    default -> throw new InvalidModuleException("malformed", "opcode " + op);
                }
            }
        }

        private void skip(int n) throws InvalidModuleException {
            if (p + n > b.length) {
                throw new InvalidModuleException("malformed", "truncated operand");
            }
            p += n;
        }
    }
}
