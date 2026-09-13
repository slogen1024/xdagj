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

import io.xdag.core.Block;
import io.xdag.utils.XdagTime;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Walks a chunk chain head -&gt; tail via {@code link[0]} and reassembles the payload.
 *
 * <p>{@link #assemble} never throws of its own accord; an exception thrown by {@code lookup}
 * itself propagates unchanged to the caller. A {@code null} {@code head} fails immediately with
 * {@link ExtError#MISSING_LINK} (there is nothing to look up). Otherwise each hop applies these
 * checks, in order, and returns the first one that fires:
 * <ol>
 *   <li>the hop's hash was already visited: {@link ExtError#CHUNK_CYCLE};</li>
 *   <li>visiting the hop would exceed {@code maxChunks} hashes: {@link ExtError#CHUNK_TOO_MANY};</li>
 *   <li>{@code lookup} returns {@code null} for the hop's hash: {@link ExtError#MISSING_LINK};</li>
 *   <li>the looked-up block's timestamp falls in an epoch older than {@code minEpoch}:
 *       {@link ExtError#CHUNK_TOO_OLD} — see below;</li>
 *   <li>{@link LaneBlockClassifier#classify} on the block does not report {@link ExtKind#CHUNK} as
 *       its kind (no extension field, an unassigned kind byte, or an extension field of some other
 *       assigned kind): {@link ExtError#NOT_A_CHUNK};</li>
 *   <li>the block classifies as {@code CHUNK} but the CHUNK codec itself failed to decode it: the
 *       codec's own {@link ExtError} (e.g. {@code BAD_LENGTH}, {@code RESERVED_NONZERO},
 *       {@code EXTRA_LINK}, {@code PAYLOAD_COUNT_MISMATCH}), propagated unchanged — {@code
 *       assemble} does not fold codec-level errors into {@code NOT_A_CHUNK};</li>
 *   <li>the decoded chunk's {@code seq} does not equal the count of hops taken so far (i.e. it does
 *       not immediately follow the previous chunk's {@code seq}, starting from 0 at the head):
 *       {@link ExtError#CHUNK_SEQ_GAP};</li>
 *   <li>on the very first (head) chunk, once its {@code totalLen} is read: if
 *       {@code totalLen > maxChunks * ChunkExt.MAX_DATA_LEN}, {@link ExtError#CHUNK_TOO_MANY} — see
 *       below; on every later chunk, if its {@code totalLen} differs from the head's:
 *       {@link ExtError#CHUNK_TOTAL_MISMATCH};</li>
 *   <li>appending the chunk's data would make the running payload longer than {@code totalLen}, or
 *       (once the loop ends) the running payload is shorter than {@code totalLen}:
 *       {@link ExtError#CHUNK_TOTAL_MISMATCH};</li>
 *   <li>the chunk that exactly completes {@code totalLen} still carries a non-null {@code next}
 *       link: {@link ExtError#CHUNK_TAIL_HAS_LINK}.</li>
 * </ol>
 *
 * <p>The {@code totalLen > maxChunks * MAX_DATA_LEN} check fires as soon as the head chunk is
 * decoded, before any further hop is looked up or any byte is buffered. Without it, a single
 * forged head block could declare an arbitrarily large {@code totalLen} and force the caller to
 * either walk many blocks hunting for a link chain that can never complete, or size a reassembly
 * buffer to an absurd length, well before the per-hop {@code maxChunks} count bound would ever
 * trigger on its own.
 *
 * <p><b>Age rule ({@code minEpoch}).</b> A chunk block may lie no earlier than {@code minEpoch}:
 * callers implementing "same epoch as the paying block, or the epoch immediately before it" pass
 * {@code minEpoch = XdagTime.getEpoch(payingBlock.getTimestamp()) - 1}. This exists because a
 * snapshot-bootstrapped node has no raw bytes for blocks older than its snapshot time, while
 * {@code tryToConnect}'s NO_PARENT check is satisfied by a {@code BlockInfo} alone; without an age
 * bound, a DEPLOY/CALL block could reference an old, content-addressed chunk chain that assembles
 * on a full node (which kept the raw bytes) but fails with {@code MISSING_LINK} on a snapshot node
 * (which did not) — the same chain would then have two different verdicts depending on which node
 * evaluated it. {@code getBlockByHash(hash, true)} returns {@code null} exactly when the raw bytes
 * are absent (pruned, or before the snapshot boot time); the age rule is what makes the outcome
 * identical on full and snapshot nodes, since both are expected to retain raw bytes for recent
 * epochs. The 3-argument overloads of {@link #assemble} and {@link #countLenient} pass
 * {@code minEpoch = Long.MIN_VALUE}, i.e. no age bound.
 *
 * <p><b>Fee contract between {@link #assemble} and {@link #countLenient}.</b> When {@code assemble}
 * succeeds on a chain of N chunks, {@code countLenient} (called with the same {@code lookup},
 * {@code maxChunks} and {@code minEpoch}) returns exactly N. On a chain {@code assemble} rejects,
 * {@code countLenient} may return more than the number of chunks a caller might expect, but never
 * fewer — it counts every existing, well-formed {@code CHUNK} block reachable before the first
 * problem, which can include chunks past whatever point made {@code assemble} fail (for example, a
 * {@code CHUNK_TOTAL_MISMATCH} caused by a later chunk does not stop {@code countLenient} from
 * still counting that chunk, since it does classify as a well-formed CHUNK on its own). A fee check
 * built on {@code countLenient} must therefore be evaluated together with, or strictly after, the
 * {@code assemble} verdict — never in place of it.
 *
 * <p>{@code lookup} must return raw blocks: parsed from their 512 bytes via
 * {@code new Block(XdagBlock)}, or fetched from storage with {@code getBlockByHash(hash, true)}. A
 * block loaded with {@code isRaw=false} (built from a {@code BlockInfo} alone) carries neither
 * extension fields nor links; {@link LaneBlockClassifier#classify} reports such a block as
 * {@link Classified#NONE}, which {@code assemble} turns into {@link ExtError#NOT_A_CHUNK} — not a
 * storage-shaped error — so a caller that accidentally passes a {@code BlockInfo}-only lookup gets
 * a structural error rather than a silently-wrong answer.
 */
public final class ChunkChain {

    @FunctionalInterface
    public interface RawBlockLookup {
        /** Returns the raw (parsed) block for a hashlow, or null when unknown. */
        Block get(Bytes32 hashLow);
    }

    private ChunkChain() {
    }

    /** Equivalent to {@link #assemble(Bytes32, RawBlockLookup, int, long)} with no age bound. */
    public static ExtResult<Bytes> assemble(Bytes32 head, RawBlockLookup lookup, int maxChunks) {
        return assemble(head, lookup, maxChunks, Long.MIN_VALUE);
    }

    public static ExtResult<Bytes> assemble(Bytes32 head, RawBlockLookup lookup, int maxChunks, long minEpoch) {
        if (head == null) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<Bytes32> visited = new HashSet<>();
        long expectSeq = 0;
        long total = -1;
        Bytes32 cur = head;
        while (cur != null) {
            if (!visited.add(cur)) {
                return ExtResult.fail(ExtError.CHUNK_CYCLE);
            }
            if (visited.size() > maxChunks) {
                return ExtResult.fail(ExtError.CHUNK_TOO_MANY);
            }
            Block b = lookup.get(cur);
            if (b == null) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
            if (XdagTime.getEpoch(b.getTimestamp()) < minEpoch) {
                return ExtResult.fail(ExtError.CHUNK_TOO_OLD);
            }
            Classified c = LaneBlockClassifier.classify(b);
            if (c.kind() != ExtKind.CHUNK) {
                return ExtResult.fail(ExtError.NOT_A_CHUNK);
            }
            if (!c.isOk()) {
                return ExtResult.fail(c.error());
            }
            ChunkExt chunk = c.as(ChunkExt.class);
            if (chunk.seq() != expectSeq) {
                return ExtResult.fail(ExtError.CHUNK_SEQ_GAP);
            }
            if (total < 0) {
                total = chunk.totalLen();
                // Fail fast on a forged head before walking further hops or buffering more bytes.
                if (total > (long) maxChunks * ChunkExt.MAX_DATA_LEN) {
                    return ExtResult.fail(ExtError.CHUNK_TOO_MANY);
                }
            } else if (chunk.totalLen() != total) {
                return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
            }
            out.writeBytes(chunk.data().toArray());
            if (out.size() > total) {
                return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
            }
            if (out.size() == total && chunk.next() != null) {
                return ExtResult.fail(ExtError.CHUNK_TAIL_HAS_LINK);
            }
            expectSeq++;
            cur = chunk.next();
        }
        if (out.size() != total) {
            return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
        }
        return ExtResult.ok(Bytes.wrap(out.toByteArray()));
    }

    /** Equivalent to {@link #countLenient(Bytes32, RawBlockLookup, int, long)} with no age bound. */
    public static int countLenient(Bytes32 head, RawBlockLookup lookup, int maxChunks) {
        return countLenient(head, lookup, maxChunks, Long.MIN_VALUE);
    }

    /**
     * Number of existing, well-formed CHUNK blocks reachable from {@code head}, stopping at the
     * first missing, too-old, or non-chunk block, at most {@code maxChunks}. Used for fee checks
     * (the chunk fee charges for chunk blocks the network actually stores): a hash whose
     * {@code lookup} fails, whose block's timestamp falls in an epoch older than {@code minEpoch},
     * or whose block does not classify as {@link ExtKind#CHUNK} with {@link Classified#isOk()},
     * ends the walk without being counted — only a block that was found, new enough, and decoded as
     * a chunk increments the count. Unlike {@link #assemble}, this never fails and has no return
     * value other than the count; it is bounded by {@code maxChunks} and cycle-safe, and never
     * throws (barring an exception thrown by {@code lookup} itself). See the class documentation
     * for how this count relates to {@link #assemble}'s verdict.
     */
    public static int countLenient(Bytes32 head, RawBlockLookup lookup, int maxChunks, long minEpoch) {
        Set<Bytes32> visited = new HashSet<>();
        int count = 0;
        Bytes32 cur = head;
        while (cur != null && count < maxChunks && visited.add(cur)) {
            Block b = lookup.get(cur);
            if (b == null) {
                break;
            }
            if (XdagTime.getEpoch(b.getTimestamp()) < minEpoch) {
                break;
            }
            Classified c = LaneBlockClassifier.classify(b);
            if (c.kind() != ExtKind.CHUNK || !c.isOk()) {
                break;
            }
            count++;
            cur = c.as(ChunkExt.class).next();
        }
        return count;
    }
}
