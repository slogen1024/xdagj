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
package io.xdag.chain.p2p;

/**
 * What a {@code CHAIN_PROOF_REQUEST} is asking for. The blob itself is opaque here: its layout
 * belongs to the anchor and witness format, which this layer only carries.
 */
public enum ProofKind {
    /** Outbox segment contents plus the MMR membership proof. */
    SEGMENT(0),
    /** outboxMap SMT proof for one destination chain. */
    OUTBOX_MAP(1),
    /** Opening of one per-input commitment {@code C_k}. */
    COMMITMENT(2),
    /** One event. */
    EVENT(3);

    private final int code;

    ProofKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ProofKind of(int code) {
        for (ProofKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw ChainCodec.malformed("proof kind " + code);
    }
}
