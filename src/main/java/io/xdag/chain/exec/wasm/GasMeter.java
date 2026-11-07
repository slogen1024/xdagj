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

import com.dylibso.chicory.runtime.TrapException;

/** One call's gas. Running out consumes the rest of the limit and traps. */
final class GasMeter {

    private final long limit;
    private long used;

    GasMeter(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("gasLimit");
        }
        this.limit = limit;
        this.used = 0;
    }

    void charge(long cost) {
        if (cost < 0) {
            throw new TrapException("negative gas");
        }
        if (cost > limit - used) {
            used = limit;
            throw new TrapException("out of gas");
        }
        used += cost;
    }

    void refund(long amount) {
        if (amount > 0) {
            used -= Math.min(used, amount);
        }
    }

    long used() {
        return used;
    }

    long left() {
        return limit - used;
    }
}
