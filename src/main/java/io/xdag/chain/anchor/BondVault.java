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
package io.xdag.chain.anchor;

import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;

import io.xdag.crypto.hash.HashUtils;

/** Protocol addresses. The bond vault is {@code sha256("xdag-chain-bond")[0..20]}. */
public final class BondVault {

    public static final Bytes ADDRESS = tag("xdag-chain-bond");
    /** Half of a slashed bond is sent here and is not spendable by any handler. */
    public static final Bytes BURN = tag("xdag-chain-burn");
    public static final byte[] GLOBAL = new byte[20];

    private BondVault() {
    }

    private static Bytes tag(String name) {
        return HashUtils.sha256(Bytes.wrap(name.getBytes(StandardCharsets.US_ASCII))).slice(0, 20);
    }

    public static boolean isGlobal(byte[] chain) {
        for (byte b : chain) {
            if (b != 0) {
                return false;
            }
        }
        return chain.length == 20;
    }
}
