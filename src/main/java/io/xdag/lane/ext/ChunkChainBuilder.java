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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;

import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Client-side helper: splits a payload into a chain of CHUNK blocks that {@link ChunkChain#assemble}
 * can walk back into the original bytes.
 *
 * <p>{@link #split} slices {@code payload} into {@link ChunkExt#MAX_DATA_LEN}-byte pieces and
 * builds one raw block per piece, tail piece first (so each block's {@code link[0]} can point at
 * the already-built next chunk's hashlow), then returns the list head-first — index 0 is the chunk
 * with {@code seq == 0}, the one a caller passes to {@code assemble} as {@code head}. Chunk
 * {@code i}'s block carries timestamp {@code headTimestamp - i}: the head (i = 0) gets
 * {@code headTimestamp} itself and every later chunk gets a strictly smaller timestamp, oldest
 * (highest seq, the tail) last.
 *
 * <p>Because the head is the newest block in the chain and every other chunk is older, and because
 * XDAG requires a block's timestamp to be no later than any block it references, the chunks must be
 * imported tail-first (oldest first) and the head last; and whatever block goes on to reference the
 * chain (typically the head, to make it reachable) must itself carry a timestamp strictly later than
 * {@code headTimestamp}, i.e. later than every chunk in the chain.
 *
 * <p>Each built block carries no on-chain value transfer and is signed with nothing: it is
 * constructed with {@code keys == null} and {@code defKeyIndex == -1}, so its wire form ends up with
 * two zero-valued {@code XDAG_FIELD_SIGN_OUT} fields (re-parsing such a block yields a non-null
 * {@link Block#getOutsig()} — the all-zero signature parses as the (1,1) pseudo signature) — by
 * construction, not by any special-casing in this method.
 */
public final class ChunkChainBuilder {

    private ChunkChainBuilder() {
    }

    public static List<Block> split(Config config, Bytes payload, long headTimestamp) {
        int total = payload.size();
        if (total == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = (total + ChunkExt.MAX_DATA_LEN - 1) / ChunkExt.MAX_DATA_LEN;
        List<Block> tailFirst = new ArrayList<>(n);
        Bytes32 next = null;
        for (int i = n - 1; i >= 0; i--) {
            int off = i * ChunkExt.MAX_DATA_LEN;
            int len = Math.min(ChunkExt.MAX_DATA_LEN, total - off);
            ChunkExt ext = new ChunkExt(i, total, len, next, payload.slice(off, len));
            List<Bytes32> fields = new ArrayList<>();
            fields.add(ext.encodeHeader());
            fields.addAll(ext.encodePayload());
            List<Address> links = next == null ? null : List.of(new Address(next, XDAG_FIELD_OUT, false));
            Block raw = new Block(config, headTimestamp - i, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            next = Bytes32.wrap(parsed.getHashLow().toArray());
            tailFirst.add(parsed);
        }
        Collections.reverse(tailFirst);
        return tailFirst;
    }
}
