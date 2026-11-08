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

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.ext.ExtCodec;

/**
 * {@code 0x09 ‖ height u64 LE} value: the items due at that height, in append order.
 * {@code fromHeight} is the height that deferred a challenge here; 0 if it was filed here.
 */
public final class TriggerList {

    public static final int FINALITY = 1;
    public static final int UNBOND = 2;
    public static final int CHALLENGE = 3;
    public static final int ITEM = 134;

    public record Item(int kind, byte[] chain, long seq, byte[] attester, long amount, long paid, byte[] other,
            long headSeq, Bytes32 headHash, long fromHeight, boolean done) {

        public Item {
            chain = chain.clone();
            attester = attester.clone();
            other = other.clone();
        }

        public Item done(long paidNow) {
            return new Item(kind, chain, seq, attester, amount, paidNow, other, headSeq, headHash, fromHeight, true);
        }

        public Item undone() {
            return new Item(kind, chain, seq, attester, amount, 0L, other, headSeq, headHash, fromHeight, false);
        }

        public Item deferredTo(long from) {
            return new Item(kind, chain, seq, attester, amount, paid, other, headSeq, headHash, from, false);
        }

        public Item home() {
            return new Item(kind, chain, seq, attester, amount, 0L, other, headSeq, headHash, 0L, false);
        }
    }

    private TriggerList() {
    }

    public static byte[] encode(List<Item> items) {
        byte[] raw = new byte[4 + items.size() * ITEM];
        ExtCodec.putU32(raw, 0, items.size());
        int o = 4;
        for (Item item : items) {
            raw[o] = (byte) item.kind();
            System.arraycopy(item.chain(), 0, raw, o + 1, 20);
            ExtCodec.putU64(raw, o + 21, item.seq());
            System.arraycopy(item.attester(), 0, raw, o + 29, 20);
            ExtCodec.putU64(raw, o + 49, item.amount());
            ExtCodec.putU64(raw, o + 57, item.paid());
            System.arraycopy(item.other(), 0, raw, o + 65, 20);
            ExtCodec.putU64(raw, o + 85, item.headSeq());
            System.arraycopy(item.headHash().toArray(), 0, raw, o + 93, 32);
            ExtCodec.putU64(raw, o + 125, item.fromHeight());
            raw[o + 133] = (byte) (item.done() ? 1 : 0);
            o += ITEM;
        }
        return raw;
    }

    public static List<Item> decode(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return List.of();
        }
        int n = (int) ExtCodec.u32(raw, 0);
        if (n < 0 || raw.length != 4 + n * ITEM) {
            return null;
        }
        List<Item> out = new ArrayList<>(n);
        int o = 4;
        for (int i = 0; i < n; i++) {
            byte[] chain = Arrays.copyOfRange(raw, o + 1, o + 21);
            byte[] attester = Arrays.copyOfRange(raw, o + 29, o + 49);
            byte[] other = Arrays.copyOfRange(raw, o + 65, o + 85);
            Bytes32 head = Bytes32.wrap(Arrays.copyOfRange(raw, o + 93, o + 125));
            out.add(new Item(raw[o] & 0xff, chain, ExtCodec.u64(raw, o + 21), attester, ExtCodec.u64(raw, o + 49),
                    ExtCodec.u64(raw, o + 57), other, ExtCodec.u64(raw, o + 85), head, ExtCodec.u64(raw, o + 125),
                    raw[o + 133] == 1));
            o += ITEM;
        }
        return out;
    }
}
