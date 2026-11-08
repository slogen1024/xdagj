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

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.ext.ExtCodec;

/**
 * Anchor commitment payload. Segments are little-endian. Commitments are raw 32-byte
 * {@code C_k} hashes, not openings.
 *
 * <pre>
 * (src 20 ‖ srcSeq u64 ‖ from u64 ‖ to u64) × segmentCount ‖ C_k × inputCount
 * </pre>
 */
public final class CommitmentChain {

    public static final int SEGMENT = 44;

    public record Segment(byte[] src, long srcSeq, long from, long to) {
    }

    public record Body(List<Segment> segments, List<Bytes32> commitments) {
    }

    private CommitmentChain() {
    }

    public static int length(long segments, long inputs) {
        if (segments < 0 || inputs < 0 || segments > Integer.MAX_VALUE / SEGMENT || inputs > Integer.MAX_VALUE / 32) {
            return -1;
        }
        long n = segments * SEGMENT + inputs * 32L;
        return n > Integer.MAX_VALUE ? -1 : (int) n;
    }

    public static byte[] encode(List<Segment> segments, List<Bytes32> commitments) {
        byte[] raw = new byte[segments.size() * SEGMENT + commitments.size() * 32];
        int o = 0;
        for (Segment s : segments) {
            System.arraycopy(s.src(), 0, raw, o, 20);
            ExtCodec.putU64(raw, o + 20, s.srcSeq());
            ExtCodec.putU64(raw, o + 28, s.from());
            ExtCodec.putU64(raw, o + 36, s.to());
            o += SEGMENT;
        }
        for (Bytes32 c : commitments) {
            System.arraycopy(c.toArray(), 0, raw, o, 32);
            o += 32;
        }
        return raw;
    }

    public static Body decode(byte[] raw, long segmentCount, long inputCount) {
        int n = length(segmentCount, inputCount);
        if (raw == null || n < 0 || raw.length != n) {
            return null;
        }
        List<Segment> segments = new ArrayList<>();
        int o = 0;
        for (long i = 0; i < segmentCount; i++) {
            byte[] src = new byte[20];
            System.arraycopy(raw, o, src, 0, 20);
            segments.add(new Segment(src, ExtCodec.u64(raw, o + 20), ExtCodec.u64(raw, o + 28),
                    ExtCodec.u64(raw, o + 36)));
            o += SEGMENT;
        }
        List<Bytes32> commitments = new ArrayList<>();
        for (long i = 0; i < inputCount; i++) {
            byte[] one = new byte[32];
            System.arraycopy(raw, o, one, 0, 32);
            commitments.add(Bytes32.wrap(one));
            o += 32;
        }
        return new Body(segments, commitments);
    }
}
