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

import com.dylibso.chicory.wasm.types.OpCode;

/**
 * Protocol gas constants from the contracts spec §8.3 and §8.4. Frozen once the chain activates;
 * changing any number is a hard fork. Instruction categories are not numbered in the spec, so
 * this initial table is the protocol table and is pinned by the instrumented-module hash:
 *
 * <ul>
 * <li>const / local / global / nop / drop / select: {@value #OP_CONST}
 * <li>control: {@value #OP_CONTROL}
 * <li>integer arithmetic (everything else that §8.1 allows): {@value #OP_ARITH}
 * <li>load / store / memory: {@value #OP_MEM}
 * <li>call: {@value #OP_CALL}
 * </ul>
 *
 * Injected metering instructions are not in any block's cost, or the meter would charge itself.
 * {@code memory.grow} still pays {@link #OP_MEM} inside its block, plus {@link #MEMORY_GROW_GAS_PER_PAGE}
 * per page requested.
 */
public final class GasSchedule {

    public static final int OP_CONST = 1;
    public static final int OP_ARITH = 2;
    public static final int OP_CONTROL = 2;
    public static final int OP_MEM = 3;
    public static final int OP_CALL = 8;

    public static final int MAX_MEMORY_PAGES = 64;
    public static final int MAX_STACK_HEIGHT = 65536;
    public static final int MEMORY_GROW_GAS_PER_PAGE = 8192;
    public static final int MAX_WASM_BYTES = 1024 * 1024;

    public static final int GAS_PER_WITNESS_BYTE = 10;
    /** Matches {@code ChainL1Processor.MAX_CALL_GAS_CAP}. The witness bound below is derived from it. */
    public static final long MAX_CALL_GAS = 10_000_000L;
    /**
     * {@code maxCallGas / gasPerWitnessByte + 32KiB}. Any call that finishes inside the gas cap has a
     * fraud-proof witness that fits in this many bytes (§8.3).
     */
    public static final int MAX_WITNESS_BYTES = (int) (MAX_CALL_GAS / GAS_PER_WITNESS_BYTE + 32 * 1024);

    public static final int ADDRESS_BYTES = 20;
    public static final int SCAN_LIMIT = 256;

    public static final int INPUT_LEN = 10;
    public static final int INPUT_READ = 10;
    public static final int PER_32_INPUT = 1;
    public static final int SELECTOR = 5;
    public static final int RETURN_DATA = 10;
    public static final int PER_32_RETURN = 1;
    public static final int ADDRESS_WRITE = 10;
    public static final int VALUE = 5;
    public static final int HEIGHT = 5;
    public static final int STORAGE_GET = 200;
    public static final int STORAGE_SET = 1000;
    public static final int PER_32_STORAGE_SET = 100;
    public static final int STORAGE_SET_NEW_KEY = 2000;
    public static final int STORAGE_REMOVE = 500;
    public static final int STORAGE_REMOVE_REFUND = 500;
    public static final int STORAGE_SCAN = 500;
    public static final int STORAGE_SCAN_PER_ENTRY = 200;
    public static final int BALANCE = 100;
    public static final int TRANSFER = 500;
    public static final int TRANSFER_FROM_CALLER = 200;
    public static final int WITHDRAW = 2000;
    public static final int CALL = 500;
    public static final int SEND = 2000;
    public static final int PER_32_SEND = 50;
    public static final int EMIT = 100;
    public static final int PER_32_EMIT = 8;
    public static final int HASH = 60;
    public static final int PER_32_HASH = 12;
    public static final int SECP256K1 = 3000;
    public static final int GAS_LEFT = 5;

    public static final GasSchedule V1 = new GasSchedule(MAX_STACK_HEIGHT);

    private final int maxStackHeight;

    public GasSchedule(int maxStackHeight) {
        if (maxStackHeight <= 0) {
            throw new IllegalArgumentException("maxStackHeight");
        }
        this.maxStackHeight = maxStackHeight;
    }

    public int maxStackHeight() {
        return maxStackHeight;
    }

    public static int opcodeCost(OpCode op) {
        return switch (op) {
            case NOP, DROP, SELECT, SELECT_T, I32_CONST, I64_CONST, LOCAL_GET, LOCAL_SET, LOCAL_TEE, GLOBAL_GET,
                    GLOBAL_SET -> OP_CONST;
            case I32_LOAD, I64_LOAD, I32_LOAD8_S, I32_LOAD8_U, I32_LOAD16_S, I32_LOAD16_U, I64_LOAD8_S, I64_LOAD8_U,
                    I64_LOAD16_S, I64_LOAD16_U, I64_LOAD32_S, I64_LOAD32_U, I32_STORE, I64_STORE, I32_STORE8,
                    I32_STORE16, I64_STORE8, I64_STORE16, I64_STORE32, MEMORY_SIZE, MEMORY_GROW, MEMORY_INIT,
                    DATA_DROP, MEMORY_COPY, MEMORY_FILL -> OP_MEM;
            case CALL, CALL_INDIRECT -> OP_CALL;
            case UNREACHABLE, BLOCK, LOOP, IF, ELSE, END, BR, BR_IF, BR_TABLE, RETURN -> OP_CONTROL;
            default -> OP_ARITH;
        };
    }

    /** {@code ceil(nbytes / 32) * gasPer32}. Zero-length payloads cost nothing. */
    public static int bytesGas(int nbytes, int gasPer32) {
        if (nbytes <= 0 || gasPer32 == 0) {
            return 0;
        }
        return ((nbytes + 31) / 32) * gasPer32;
    }

    /** {@code 64 + 32 * nonEmptySiblings + valueLen}. {@code valueLen} is the pre-write length. */
    public static int witnessBytes(int valueLen, int nonEmptySiblings) {
        return 64 + 32 * nonEmptySiblings + Math.max(valueLen, 0);
    }

    public static int witnessGas(int valueLen, int nonEmptySiblings) {
        return GAS_PER_WITNESS_BYTE * witnessBytes(valueLen, nonEmptySiblings);
    }
}
