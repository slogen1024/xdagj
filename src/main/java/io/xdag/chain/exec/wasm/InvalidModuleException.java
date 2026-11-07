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

/**
 * A module failed §8.1. The chain records this as {@code INVALID_CODE}; {@link #rule()} is the
 * whitelist clause that fired ({@code float}, {@code simd}, {@code atomics}, {@code exceptions},
 * {@code multi-memory}, {@code reference-types}, {@code tail-call}, {@code memory},
 * {@code memory-max}, {@code memory-pages}, {@code import}, {@code export}, {@code size},
 * {@code code-hash}, {@code malformed}, {@code instrument}).
 */
public final class InvalidModuleException extends Exception {

    private final String rule;

    public InvalidModuleException(String rule, String detail) {
        super(rule + ": " + detail);
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
