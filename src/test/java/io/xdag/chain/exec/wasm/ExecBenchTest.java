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
import static org.junit.Assert.assertTrue;

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
import io.xdag.crypto.hash.HashUtils;

/**
 * One seq of transfer calls, after a warmup. The timed region is {@link ChainExecutor#apply}.
 * SHA-256 of 32 bytes and a 256-hash path are timed beside it so the hash choice can be read off
 * the same run.
 */
public class ExecBenchTest {

    private static final int N = 4000;
    private static final Bytes CHAIN = address(1);
    private static final Bytes SENDER = address(2);
    private static final Bytes CONTRACT = address(3);
    private static final long GAS_LIMIT = 10_000L;

    @Test
    public void compilerTransfersClearTwoThousandPerSecond() {
        byte[] code = transferModule();
        long[] interpreter = rounds(ChicoryEngine.interpreter(), code);
        long[] compiled = rounds(ChicoryEngine.compiler(), code);
        long sha = timeSha();
        long path = timePath();
        long interpreterMedian = median(interpreter);
        long compiledMedian = median(compiled);
        System.out.println("exec-bench interpreter calls/s=" + rates(interpreter) + " median=" + rate(interpreterMedian));
        System.out.println("exec-bench compiler calls/s=" + rates(compiled) + " median=" + rate(compiledMedian));
        System.out.println("exec-bench sha256 ns=" + sha / N + " path256 ns=" + path / N);
        assertTrue("compiler median " + rate(compiledMedian), rate(compiledMedian) >= 2000d);
        assertTrue("interpreter median " + rate(interpreterMedian), rate(interpreterMedian) >= 2000d);
    }

    private static long[] rounds(WasmEngine engine, byte[] code) {
        time(engine, code);
        long[] out = new long[3];
        for (int i = 0; i < out.length; i++) {
            out[i] = time(engine, code);
        }
        return out;
    }

    private static long median(long[] samples) {
        long[] copy = samples.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String rates(long[] samples) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                out.append(" / ");
            }
            out.append((long) rate(samples[i]));
        }
        return out.toString();
    }

    private static long time(WasmEngine engine, byte[] code) {
        ChainExecutor warm = executor(engine);
        warm.apply(CHAIN, 0L, 1L, GAS_LIMIT, 0L, batch(code, 32, 0));
        ChainExecutor exec = executor(engine);
        long start = System.nanoTime();
        List<ChainExecutor.Outcome> out = exec.apply(CHAIN, 0L, 1L, GAS_LIMIT, 0L, batch(code, N, 0));
        long elapsed = System.nanoTime() - start;
        assertEquals(N, out.size());
        assertEquals(ExecStatus.OK, out.get(N - 1).status());
        assertEquals(N, exec.balance(CHAIN, CONTRACT.toArray()));
        return elapsed;
    }

    private static long timeSha() {
        byte[] body = new byte[32];
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            body[0] = (byte) i;
            HashUtils.sha256(Bytes.wrap(body));
        }
        return System.nanoTime() - start;
    }

    private static long timePath() {
        byte[] body = new byte[32];
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            body[0] = (byte) i;
            Bytes wrapped = Bytes.wrap(body);
            for (int d = 0; d < 256; d++) {
                HashUtils.sha256(wrapped);
            }
        }
        return System.nanoTime() - start;
    }

    private static double rate(long nanos) {
        return N * 1_000_000_000d / nanos;
    }

    private static List<ChainExecutor.Tx> batch(byte[] code, int n, int salt) {
        List<ChainExecutor.Tx> txs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] hash = new byte[32];
            hash[0] = (byte) salt;
            hash[30] = (byte) (i >> 8);
            hash[31] = (byte) i;
            txs.add(new ChainExecutor.Tx(Bytes32.wrap(hash), ChainInput.Kind.CALL, null, SENDER, CONTRACT,
                    GAS_LIMIT + 1, GAS_LIMIT, 0, new byte[0], code));
        }
        return txs;
    }

    private static ChainExecutor executor(WasmEngine engine) {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        return new ChainExecutor(engine, l1, store, null, 16);
    }

    private static byte[] transferModule() {
        byte[] body = new byte[] {0x42, 0x01, 0x10, 0x00, 0x1a, 0x0b};
        WasmAsm asm = new WasmAsm();
        int host = asm.addType(new int[] {0x7e}, new int[] {0x7f});
        int empty = asm.addType(new int[0], new int[0]);
        asm.addImport("xdag", "transfer_from_caller", host);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, body);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
