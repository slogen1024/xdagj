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
 * (highest seq, the tail) last. {@code split} itself enforces no bound on the number of chunks
 * produced; the caller is responsible for keeping {@code ceil(payload.size() / MAX_DATA_LEN) <=
 * lane.chunk.maxPerChain} (the protocol-level chunk count limit), since only the caller knows that
 * configured limit.
 *
 * <p>Because the head is the newest block in the chain and every other chunk is older, and because
 * XDAG requires a block's timestamp to be no later than any block it references, the chunks must be
 * imported tail-first (oldest first) and the head last; and whatever block goes on to reference the
 * chain (typically the head, to make it reachable) must itself carry a timestamp strictly later than
 * {@code headTimestamp}, i.e. later than every chunk in the chain. A chain may still cross an epoch
 * boundary (its chunks span more than one {@code XdagTime.getEpoch} value) and remain within
 * {@link ChunkChain}'s age rule, as long as the referencing (paying) block is built shortly after
 * {@code headTimestamp} — in practice within about 4 seconds of it, i.e. before the head chunk's own
 * epoch is more than one epoch behind the paying block's.
 *
 * <p>Each built block carries no on-chain value transfer and is signed with nothing: it is
 * constructed with {@code keys == null} and {@code defKeyIndex == -1}, so its wire form ends up with
 * two zero-valued {@code XDAG_FIELD_SIGN_OUT} fields (re-parsing such a block yields a non-null
 * {@link Block#getOutsig()} — the all-zero signature parses as the (1,1) pseudo signature) — by
 * construction, not by any special-casing in this method. Because it is also built with
 * {@code mining == false}, field 15 of its wire form is never typed {@code XDAG_FIELD_SIGN_IN} (that
 * only happens for a block built with {@code mining == true}), so {@code Block.getNonce()} is always
 * {@code null} for a chunk block and it can therefore never be flagged {@code BI_EXTRA} — even a
 * chunk whose timestamp happens to land at the very end of an epoch is stored as an ordinary block,
 * not as a mining "extra" block, and so still lands on disk normally.
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
            // The chunk's OUT link (if any) is passed as the constructor's `pendings` argument, with
            // its `links` argument left null: Block's constructor emits field type-nibbles in the
            // order links, then pendings, then remark, then extFields, and the CHUNK ext header must
            // land in field 1 (right after the field-0 block header) for LaneBlockClassifier/ChunkExt
            // to see the layout they expect. Passing this same address list as `links` instead would
            // shift the ext header to field 2 and desynchronize the codec from the wire encoding.
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
