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

package io.xdag.lane.ext;

/**
 * Kinds of extension blocks. The code is stored in byte 0 of the extension header field.
 */
public enum ExtKind {
    CALL(1), DEPLOY(2), CHUNK(3), ANCHOR(4), BOND(5), CHALLENGE(6), CLAIM(7);

    private final byte code;

    ExtKind(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    /** Returns the kind for a code, or null when the code is not assigned. */
    public static ExtKind fromCode(int code) {
        for (ExtKind k : values()) {
            if ((k.code & 0xff) == code) {
                return k;
            }
        }
        return null;
    }
}
