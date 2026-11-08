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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.crypto.hash.HashUtils;

/**
 * One reward input per canonical or final anchor whose {@code confirmHeight} is {@code h}.
 * {@code inputHash = sha256("reward" ‖ anchorHash)}. Voided and ignored anchors are not inputs.
 * The anchor under validation is not stored yet, so {@link #count} does not include it.
 */
public final class RewardInputs {

    public record Reward(long seq, AnchorBody body) {
    }

    private RewardInputs() {
    }

    public static Bytes32 hash(Bytes32 anchorHash) {
        byte[] tag = "reward".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[tag.length + 32];
        System.arraycopy(tag, 0, raw, 0, tag.length);
        System.arraycopy(anchorHash.toArray(), 0, raw, tag.length, 32);
        return HashUtils.sha256(Bytes.wrap(raw));
    }

    /** {@code prev < height <= seq}, unsigned. */
    public static boolean in(long height, long prev, long seq) {
        return Long.compareUnsigned(height, prev) > 0 && Long.compareUnsigned(height, seq) <= 0;
    }

    public static List<Reward> at(ChainL1Store store, byte[] chain, long height) {
        List<Reward> out = new ArrayList<>();
        for (Reward reward : all(store, chain)) {
            if (reward.body().confirmHeight() == height) {
                out.add(reward);
            }
        }
        return out;
    }

    public static long count(ChainL1Store store, byte[] chain, long prev, long seq) {
        long n = 0L;
        for (Reward reward : all(store, chain)) {
            if (in(reward.body().confirmHeight(), prev, seq)) {
                n++;
            }
        }
        return n;
    }

    private static List<Reward> all(ChainL1Store store, byte[] chain) {
        List<Reward> out = new ArrayList<>();
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(Arrays.copyOfRange(key, 1, 21), chain)) {
                continue;
            }
            long seq = ExtCodec.u64(key, 21);
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(chain), seq));
            if (body == null) {
                continue;
            }
            if (body.status() != AnchorRecord.Status.CANONICAL && body.status() != AnchorRecord.Status.FINAL) {
                continue;
            }
            out.add(new Reward(seq, body));
        }
        out.sort((a, b) -> {
            int c = Long.compareUnsigned(a.seq(), b.seq());
            return c != 0 ? c : a.body().anchorHash().compareTo(b.body().anchorHash());
        });
        return out;
    }
}
