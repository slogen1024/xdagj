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
package io.xdag.chain.rpc;

/**
 * The four read views. {@code pending} and {@code confirmed} are the executed head: soft pre-exec
 * of unconfirmed calls is not built, so they are the same state. {@code anchored} is the latest
 * non-void anchor at that head. {@code final} is the latest FINAL anchor at that head.
 */
public enum ChainView {
    PENDING,
    CONFIRMED,
    ANCHORED,
    FINAL;

    public static ChainView parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("view");
        }
        return switch (raw) {
            case "pending" -> PENDING;
            case "confirmed" -> CONFIRMED;
            case "anchored" -> ANCHORED;
            case "final" -> FINAL;
            default -> throw new IllegalArgumentException("view");
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
