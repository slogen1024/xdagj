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
package io.xdag.chain.asset;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

/**
 * CHAIN_L1 {@code 0x06 ‖ chainId(20) ‖ seq u64 LE} value. SP2 defines the layout and reads it.
 * SP3's anchor handler is the production writer; until then a missing record fails a CLAIM closed.
 *
 * <pre>
 * status u8 ‖ anchorHash 32 ‖ outboxMapRoot 32
 * </pre>
 *
 * {@code anchorHash} is the 32 bytes {@code ClaimExt.anchor} reads from link[0] after
 * {@code Address.parse}: the first 8 bytes are zero and bytes 8..31 are the anchor block's hashLow.
 * CLAIM accepts {@link Status#FINAL} only.
 */
public record AnchorRecord(Status status, Bytes32 anchorHash, Bytes32 outboxMapRoot) {

    public static final int BYTES = 1 + Bytes32.SIZE + Bytes32.SIZE;

    public enum Status {
        CANONICAL(1), FINAL(2), VOIDED(3), IGNORED(4);

        public final int code;

        Status(int code) {
            this.code = code;
        }

        public static Status from(int code) {
            for (Status s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            return null;
        }
    }

    public AnchorRecord {
        if (status == null || anchorHash == null || outboxMapRoot == null) {
            throw new IllegalArgumentException("anchor record");
        }
        anchorHash = Bytes32.wrap(anchorHash.toArray());
        outboxMapRoot = Bytes32.wrap(outboxMapRoot.toArray());
    }

    public byte[] encode() {
        byte[] raw = new byte[BYTES];
        raw[0] = (byte) status.code;
        System.arraycopy(anchorHash.toArray(), 0, raw, 1, Bytes32.SIZE);
        System.arraycopy(outboxMapRoot.toArray(), 0, raw, 1 + Bytes32.SIZE, Bytes32.SIZE);
        return raw;
    }

    public static AnchorRecord decode(byte[] raw) {
        if (raw == null || raw.length != BYTES) {
            return null;
        }
        Status status = Status.from(raw[0] & 0xff);
        if (status == null) {
            return null;
        }
        return new AnchorRecord(status, Bytes32.wrap(Arrays.copyOfRange(raw, 1, 1 + Bytes32.SIZE)),
                Bytes32.wrap(Arrays.copyOfRange(raw, 1 + Bytes32.SIZE, BYTES)));
    }
}
