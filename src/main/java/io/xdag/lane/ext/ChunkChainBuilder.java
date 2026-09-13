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
 * {@code i}'s block carries timestamp {@code head - i}, where {@code head} is {@code headTimestamp}
 * itself whenever that already keeps the whole chain in one epoch, or otherwise a value {@code split}
 * computes by snapping {@code headTimestamp} down to the second-to-last tick of the epoch before it (see
 * below): the returned head (index 0) gets the highest timestamp and every later chunk gets a
 * strictly smaller one, oldest (highest seq, the tail) last. {@code split} itself enforces no bound
 * on the number of chunks produced; the caller is responsible for keeping
 * {@code ceil(payload.size() / MAX_DATA_LEN) <= lane.chunk.maxPerChain} (the protocol-level chunk
 * count limit), since only the caller knows that configured limit.
 *
 * <p>{@code headTimestamp} is a hint, not a guarantee: for any chain of at most 65535 chunks (far
 * above the protocol's {@code maxPerChain}) the returned chunks always share a single
 * {@code XdagTime.getEpoch} value, so {@code split} never returns a chain that straddles an epoch
 * boundary. This matters because {@link ChunkChain}'s age rule requires
 * {@code epoch(tail) >= epoch(payingBlock) - 1} — the tail is the oldest chunk and therefore the
 * binding hop, since every other chunk in the chain has an epoch at least as high. A straddling
 * chain's tail would sit one epoch behind its own head, so it could only be paid for inside the
 * head's own epoch — a window that can be arbitrarily short, down to zero ticks, depending on where
 * {@code headTimestamp} falls. Keeping the whole chain in one epoch instead means the paying block
 * may be built in that same epoch or the next one, so a caller that submits the paying block right
 * after building the chain always gets that full window.
 *
 * <p>Because the head is the newest block in the chain and every other chunk is older, and because
 * XDAG requires a block's timestamp to be no later than any block it references, the chunks must be
 * imported tail-first (oldest first) and the head last. The paying block's own timestamp is still
 * entirely the caller's choice — {@code split} only guarantees that the chunks themselves do not
 * straddle an epoch — but it must carry a timestamp strictly later than the head chunk's, i.e. later
 * than every chunk in the chain.
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
        long head = headTimestamp;
        if ((head & 0xffffL) < n - 1) {
            // The chain would cross into the previous epoch: move it wholly into that epoch. The head is
            // placed on the second-to-last tick (low 16 bits 0xfffe), not the last one, because a timestamp
            // with low bits 0xffff is XdagTime.isEndOfEpoch and would route the block down the RandomX
            // difficulty path in calculateCurrentBlockDiff; 65535 ticks of capacity still cover any chain
            // the protocol allows (maxPerChain = 4096).
            head = (head & ~0xffffL) - 2;
        }
        List<Block> tailFirst = new ArrayList<>(n);
        Bytes32 next = null;
        for (int i = n - 1; i >= 0; i--) {
            int off = i * ChunkExt.MAX_DATA_LEN;
            int len = Math.min(ChunkExt.MAX_DATA_LEN, total - off);
            ChunkExt ext = new ChunkExt(i, total, len, next, payload.slice(off, len));
            List<Bytes32> fields = new ArrayList<>();
            fields.add(ext.encodeHeader());
            fields.addAll(ext.encodePayload());
            // Only one of links/pendings is non-empty here (links is always null), so the
            // constructor's links-then-pendings field-type-nibble order and the encoder's
            // inputs-then-outputs write order cannot disagree; the OUT reference is simply passed as
            // a pending output.
            List<Address> links = next == null ? null : List.of(new Address(next, XDAG_FIELD_OUT, false));
            Block raw = new Block(config, head - i, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            next = Bytes32.wrap(parsed.getHashLow().toArray());
            tailFirst.add(parsed);
        }
        Collections.reverse(tailFirst);
        return tailFirst;
    }
}
