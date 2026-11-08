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
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;

/**
 * Source anchors a destination may consume at {@code seq}. Order is
 * {@code (confirmHeight, src, seq)}. When {@code D == W} only a final anchor is due.
 */
public final class DueSegments {

    public record Due(byte[] src, long seq, long confirm) {
        public Due {
            src = src.clone();
        }
    }

    private DueSegments() {
    }

    public static List<Due> due(ChainL1Store store, Bytes chain, long seq, long window) {
        ChainRecord self = store.getChain(chain);
        long delay = self == null ? Long.MAX_VALUE : self.deliveryDelayD();
        boolean finalOnly = delay == window;
        List<Due> out = new ArrayList<>();
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            byte[] src = Arrays.copyOfRange(key, 1, 21);
            if (Arrays.equals(src, chain.toArray())) {
                continue;
            }
            long srcSeq = ExtCodec.u64(key, 21);
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(src), srcSeq));
            if (body == null) {
                continue;
            }
            if (finalOnly) {
                if (body.status() != AnchorRecord.Status.FINAL) {
                    continue;
                }
            } else if (body.status() != AnchorRecord.Status.CANONICAL
                    && body.status() != AnchorRecord.Status.FINAL) {
                continue;
            }
            long ready = body.confirmHeight() + delay;
            if (Long.compareUnsigned(ready, body.confirmHeight()) < 0 || Long.compareUnsigned(ready, seq) > 0) {
                continue;
            }
            SegmentCursor cursor = SegmentCursor.decode(store.getCursor(chain, Bytes.wrap(src)));
            if (cursor != null && Long.compareUnsigned(srcSeq, cursor.srcSeq()) <= 0) {
                continue;
            }
            out.add(new Due(src, srcSeq, body.confirmHeight()));
        }
        out.sort((a, b) -> {
            int c = Long.compareUnsigned(a.confirm(), b.confirm());
            if (c != 0) {
                return c;
            }
            c = Arrays.compareUnsigned(a.src(), b.src());
            return c != 0 ? c : Long.compareUnsigned(a.seq(), b.seq());
        });
        return out;
    }
}
