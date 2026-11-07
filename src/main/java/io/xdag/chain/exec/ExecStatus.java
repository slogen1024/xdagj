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

/**
 * The status byte inside {@code C_k}. Distinct from {@link io.xdag.chain.l1.InputStatus}: an L1
 * rejection is still an input, and it is recorded here so the commitment chain has a byte for it.
 */
public enum ExecStatus {
    OK(0),
    FAILED(1),
    INSUFFICIENT_GAS(2),
    INVALID_CODE(3),
    INVALID_FORMAT(4),
    INVALID_FEE(5),
    CODE_TOO_LARGE(6);

    private final int code;

    ExecStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ExecStatus fromCode(int code) {
        for (ExecStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("exec status " + code);
    }
}
