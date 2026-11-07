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

import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.WasmModule;

/** A validated module plus its instrumented bytes. The compiled machine, if any, is reused; the memory is not. */
public final class PreparedModule {

    final WasmModule module;
    final Function<Instance, Machine> machineFactory;
    private final Bytes32 codeHash;
    private final Bytes32 instrumentedHash;
    private final byte[] instrumented;

    PreparedModule(WasmModule module, Function<Instance, Machine> machineFactory, Bytes32 codeHash,
            Bytes32 instrumentedHash, byte[] instrumented) {
        this.module = module;
        this.machineFactory = machineFactory;
        this.codeHash = codeHash;
        this.instrumentedHash = instrumentedHash;
        this.instrumented = instrumented.clone();
    }

    public Bytes32 codeHash() {
        return codeHash;
    }

    public Bytes32 instrumentedHash() {
        return instrumentedHash;
    }

    public byte[] instrumentedBytes() {
        return instrumented.clone();
    }
}
