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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Selectors a contract allows to re-enter. The mark is a custom section named {@code xdag.reentrant}
 * whose payload is a sequence of little-endian {@code u32} selectors. The instrumenter drops custom
 * sections, so this is read off the original code bytes. A missing section is an empty set. A
 * section whose payload length is not a multiple of 4 is rejected.
 */
public final class ReentrantMarks {

    public static final String NAME = "xdag.reentrant";

    private ReentrantMarks() {
    }

    public static Set<Integer> selectors(byte[] wasm) {
        if (wasm.length < 8 || wasm[0] != 0 || wasm[1] != 'a' || wasm[2] != 's' || wasm[3] != 'm') {
            return Set.of();
        }
        Set<Integer> out = new HashSet<>();
        int p = 8;
        boolean saw = false;
        while (p < wasm.length) {
            int id = wasm[p++] & 0xff;
            long size = uleb(wasm, p);
            p = ulebPos;
            if (size < 0 || p > wasm.length - size) {
                throw new IllegalArgumentException("wasm section");
            }
            int end = p + (int) size;
            if (id == 0) {
                int nameLen = (int) uleb(wasm, p);
                p = ulebPos;
                if (nameLen < 0 || p > end - nameLen) {
                    throw new IllegalArgumentException("custom name");
                }
                String name = new String(wasm, p, nameLen, StandardCharsets.UTF_8);
                p += nameLen;
                if (NAME.equals(name)) {
                    saw = true;
                    int body = end - p;
                    if ((body & 3) != 0) {
                        throw new IllegalArgumentException("reentrant selectors");
                    }
                    while (p + 4 <= end) {
                        int sel = (wasm[p] & 0xff) | ((wasm[p + 1] & 0xff) << 8) | ((wasm[p + 2] & 0xff) << 16)
                                | ((wasm[p + 3] & 0xff) << 24);
                        out.add(sel);
                        p += 4;
                    }
                }
            }
            p = end;
        }
        return saw ? Collections.unmodifiableSet(out) : Set.of();
    }

    private static int ulebPos;

    private static long uleb(byte[] wasm, int p) {
        long n = 0;
        int shift = 0;
        while (p < wasm.length) {
            int c = wasm[p++] & 0xff;
            n |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) {
                ulebPos = p;
                return n;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("uleb");
            }
        }
        throw new IllegalArgumentException("uleb");
    }
}
