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
package io.xdag.chain.exec;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.l1.InputStatus;

/**
 * One element of {@code inputs(chain, h)}. {@link #index()} is the position in that concatenation,
 * which is {@code k} in {@code C_k}. System and delivered prefixes are empty until SP3 and SP4, so
 * today this index equals the L1 input index.
 */
public record ChainInput(int index, Kind kind, Bytes32 inputHash, InputStatus l1Status, Bytes contract) {

    public enum Kind {
        CALL, DEPLOY, OTHER
    }

    public ChainInput {
        if (index < 0) {
            throw new IllegalArgumentException("index");
        }
        inputHash = Bytes32.wrap(inputHash.toArray());
        contract = Bytes.wrap(contract.toArray());
    }
}
