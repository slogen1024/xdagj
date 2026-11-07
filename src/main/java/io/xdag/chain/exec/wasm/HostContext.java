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

import java.util.List;

/**
 * Deterministic inputs of one call. No clock, no random, no I/O: {@code height} and
 * {@code timestamp} are the host's main-block seq and timestamp. SP1-2 plugs SMT siblings in
 * through {@link #nonEmptySiblings(byte[])}; SP1-3 replaces {@link #call} with a real callee.
 * Storage methods see the pre-write value ({@code null} means the key is absent, an empty array
 * means it is present and empty).
 */
public interface HostContext {

    byte[] input();

    int selector();

    byte[] caller();

    byte[] selfAddress();

    /** Exactly {@link GasSchedule#ADDRESS_BYTES} bytes, written by {@code xdag.chain_id}. */
    byte[] chainId();

    long value();

    long height();

    long timestamp();

    byte[] returnData();

    void setReturnData(byte[] data);

    byte[] storageGet(byte[] key);

    void storagePut(byte[] key, byte[] value);

    void storageDelete(byte[] key);

    List<StorageEntry> storageScan(byte[] prefix, int max);

    int nonEmptySiblings(byte[] key);

    long balanceOf(byte[] address);

    /** 0 on success, 1 if the contract balance cannot cover {@code amount}. */
    int transfer(byte[] to, long amount);

    /** Pull {@code amount} out of this call's {@link #value()}. 0 on success, 1 if it was already taken. */
    int transferFromCaller(long amount);

    int withdraw(byte[] to, long amount);

    int call(byte[] contract, int selector, byte[] args, long value);

    int send(byte[] dstChain, byte[] contract, int selector, byte[] args, long value);

    void emit(byte[] topic, byte[] data);

    /** False forces every state write to trap, the same rule the engine applies to {@code query}. */
    boolean writesAllowed();

    record StorageEntry(byte[] key, byte[] value) {
    }
}
