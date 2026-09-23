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

package io.xdag.chain.ingest;

import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainL1Processor;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Block;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.XdagTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A node-local gate in front of BOTH ingest paths: a DEPLOY/CALL block that names chunk chains this
 * node is already holding, and whose header fee does not cover them, is refused before it is
 * imported — not stored, not put in the DAG, not passed on. Off with
 * {@code chain.ingest.feePolicy = false}, and then every other part of the orphan work stands on
 * its own.
 *
 * <p>It is deliberately not inside {@link PreValidator}: SP0b-2's P1 requires pre-validation to
 * accelerate acceptance and never reject early, and this is an early rejection based on off-lock
 * reads. Sitting equally in front of the pipeline and the synchronous path keeps the two paths in
 * agreement, which is what P1 actually asks for.
 *
 * <h2>A block this node asked for is never refused</h2>
 *
 * <p>The safety argument for refusing at all is "the main block will arrive and we will pull it" —
 * and the pull goes through {@code tryToConnect} too, so refusing a requested block would leave the
 * node unable to ever admit it, stuck at that height for good. Hence {@link #tryMarkRequested}: the
 * two places that ask a peer for a specific block record the hash here first, and a block whose
 * hash is on that record is let through whatever it pays.
 *
 * <p><b>The record lives here rather than being passed in as a flag</b>, and that is the one place
 * this class deviates from the design's sketch. "Did this node ask for it" is not visible on the
 * arriving block: a {@code BlockRequestMessage} is answered with a {@code NewBlockMessage}, which
 * reaches {@code SyncManager} as an ordinary gossip wrapper with {@code isOld == false} — byte for
 * byte what an unsolicited broadcast looks like. The nearest-looking flag, {@code
 * BlockWrapper.isOld}, is a sync-state flag and answers a different question in both directions: it
 * is false for the answer to a request this node made (which must be exempt) and true for every
 * block streamed during a bulk range sync (which was never individually asked for). A caller handed
 * a boolean would have to derive it, and deriving it from what is on the block is exactly the
 * mistake §5.2 rules out. Owning the record makes that unavailable.
 *
 * <h2>What is counted, and against what age bound</h2>
 *
 * <p>The count and the comparison are the consensus rule, reached through the same code so the two
 * cannot drift: {@link ChunkChain#countLenient} over each chain head the block declares, summed (a
 * DEPLOY may carry a code chain and an args chain) and bounded by
 * {@link ChainSpec#getChainMaxChunksPerChain()}, then handed to
 * {@link ChainL1Processor#feeCovers(Block, int, io.xdag.core.XAmount)} — the same method
 * {@code setMain} calls, not a second spelling of it.
 *
 * <p>The age bound is the consensus one as well — {@code epoch(payingBlock) - 1}, read off the
 * paying block's own timestamp. The lenient count only ever moves the verdict one way: more chunks counted means a larger fee required means
 * a likelier refusal. The 3-argument overload (no age bound) would therefore count chunks the
 * consensus check will not, and could refuse a block consensus would have accepted — a false
 * refusal, and one the design's §5.4 does not cover, since §5.4 only argues about chunks that are
 * missing and about chains {@code assemble} rejects. The paying block's epoch is available here for
 * free (no confirming main block is needed to know it), so the honest bound is the one consensus
 * uses, and with it the two counts read exactly the same chunks.
 *
 * <h2>A lenient count before any assemble, and the one false refusal it leaves</h2>
 *
 * <p>{@link ChunkChain}'s class documentation warns that a fee check built on {@code countLenient}
 * "must be evaluated together with, or strictly after, the {@code assemble} verdict — never in
 * place of it", because on a chain {@code assemble} rejects the lenient count may come out higher
 * than the chain's usable length. This gate runs strictly <em>before</em> any assemble. That is not
 * a violation of the rule the warning is about — the rule exists to stop a lenient count from
 * <em>replacing</em> an assemble verdict in a decision that binds consensus, and nothing here binds
 * consensus: the real verdict is still taken under the lock, from the real chain, later — but it
 * does leave a residual gap.
 *
 * <p><b>Where the gate charges more than consensus.</b> {@code ChainL1Processor.applyDeploy} adds
 * the code chain's chunks to the fee basis only <em>after</em> {@code assemble} has accepted it,
 * and reaches {@code feeCovers} only while the input's status is still OK. So for every DEPLOY or
 * CALL that consensus rejects before the fee check — a chain that does not assemble, code over
 * {@code chain.wasm.maxBytes}, a code hash that does not match the assembled bytes, a chain config
 * outside its bounds, a missing or mismatched vault output — consensus's required fee is zero,
 * while this gate has already counted whatever {@code countLenient} could reach and charges for it.
 * A block in that set can be refused here and taken by consensus.
 *
 * <p><b>And consensus does take it.</b> It is worth being exact, because the tempting shorthand is
 * wrong: a failed {@code assemble} does not make consensus refuse the block. It sets the input's
 * status to {@code INVALID_FORMAT} and records it; the block itself was admitted by
 * {@code tryToConnect} long before and stays in the DAG, with only its deploy or call failed. So
 * this is a genuine false refusal, in the direction §5.4's benign-error argument does not excuse.
 *
 * <p><b>Why it is tolerable.</b> It is node-local and recoverable, and it is one-directional: the
 * gate only ever charges more than consensus, never less, so it cannot admit something consensus
 * would reject. Nothing about a refusal reaches a verdict — the block is simply not stored here
 * until something wants it, and §5.2 hands it over the moment anything references it, so the node
 * can neither fork nor stall on the difference. And every block in the set is one whose chain
 * semantics are already doomed; declining to store such a block unprompted is the policy's purpose
 * rather than a failure of it.
 *
 * <p><b>Why the gate is not made to match.</b> Matching would mean reproducing the whole
 * pre-fee half of {@code applyDeploy} out here: assemble the code chain, size it, hash it, and read
 * {@code CHAIN_L1} for the contract and chain records. Two reasons not to. First, those store reads
 * are only meaningful relative to a confirmed height, and reading them off the lock would make the
 * gate's answer depend on where {@code setMain} happened to be when the block arrived — the same
 * block refused or admitted by timing, which is worse than a stable over-approximation. Second,
 * assembling at ingest buffers the whole payload (up to {@code chain.wasm.maxBytes}) on a netty I/O
 * thread for a block that has not paid, which is a cost that scales with the sender's payload — the
 * exact shape this gate exists to refuse to pay.
 *
 * <p>Under-counting is the direction that actually happens, and it is benign by construction: a
 * paying block that arrives before its own chunks (the normal order) has few or none of them to
 * count, so the required fee comes out small and the block is let through.
 *
 * <p><b>Thread-safe.</b> {@link #refuse} runs on netty I/O threads holding no lock, and
 * {@link #tryMarkRequested} on whichever thread is releasing waiters. Everything mutable is behind
 * {@link #requested}'s own monitor.
 */
@Slf4j
public final class ChunkFeePolicy {

    /**
     * How many hashes this node remembers asking for. Peer-suppliable hashes reach
     * {@link #tryMarkRequested} (the missing parent named by a block a peer sent), so this must be
     * bounded; past the bound the oldest is dropped. A dropped record costs nothing permanent: the
     * refusal it would have prevented releases the waiters, which re-request and re-arm it.
     */
    static final int MAX_REQUESTED = 65536;

    /**
     * How long a request is remembered. Long enough that a peer answering slowly, or several peers
     * answering the same broadcast request, all land inside it; short enough that a request nobody
     * ever answers stops costing memory. Expiry is what releases an unanswered request — nothing
     * retries from here; a retry is a fresh {@code syncPushBlock} round, which marks the hash again.
     */
    static final long REQUEST_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * How long a request suppresses a second request for the same block. The same 64 seconds
     * {@code SyncManager.syncPushBlock} applies per (waiting block, missing parent) pair, applied
     * once per missing block instead: a broadcast that goes to every channel gains nothing from
     * being repeated for each of several blocks waiting on the same parent.
     */
    static final long REASK_MS = 64_000L;

    private final ChainSpec spec;
    private final ChunkChain.RawBlockLookup lookup;

    /**
     * Hashlow to the wall-clock millisecond this node last asked a peer for it, oldest insertion
     * first. One timestamp, two thresholds: {@link #REQUEST_TTL_MS} decides whether the answer is
     * exempt from the policy, {@link #REASK_MS} whether another request is worth sending. Bounded by
     * {@link #MAX_REQUESTED} through {@code removeEldestEntry}; guarded by its own monitor, which is
     * only ever taken for a map operation, never around a chain walk.
     */
    private final Map<Bytes32, Long> requested = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Bytes32, Long> eldest) {
                return size() > MAX_REQUESTED;
        }
    };

    /**
     * @param spec  the chain parameters; read at each use rather than copied, so a gate built over a
     *              spec whose values are decided later still sees them
     * @param lookup raw-block lookup, which must be the one the consensus fee check uses
     *               ({@code getBlockByHash(hash, true)}); a lookup that answers non-raw blocks
     *               silently counts zero chunks, since a block with no extension fields never
     *               classifies as a CHUNK
     */
    public ChunkFeePolicy(ChainSpec spec, ChunkChain.RawBlockLookup lookup) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Records that this node is asking a peer for {@code hashLow} — which is what exempts the answer
     * from the policy that turned the unsolicited copy away — and answers whether the request is
     * worth putting on the wire.
     *
     * <p>Call it before the request goes out, and skip the request when it answers false: this node
     * asked every channel for the same block less than {@link #REASK_MS} ago and that request is
     * still outstanding. False never means "not recorded": it means the record was already there,
     * which is the only thing §5.2 needs.
     *
     * <p>A null hash is ignored rather than rejected: the callers read it off an {@link
     * io.xdag.core.ImportResult}, whose {@code hashLow} is a mutable per-constant field.
     *
     * @return true when the request should be sent
     */
    public boolean tryMarkRequested(Bytes32 hashLow) {
        if (hashLow == null) {
            return false;
        }
        // copy(): the callers pass ImportResult.getHashlow(), a MutableBytes32 the enum constant
        // hands out and the next NO_PARENT overwrites. A key that changes under a hash map is a
        // lookup that can never match again.
        Bytes32 key = Bytes32.wrap(hashLow.toArray());
        long now = System.currentTimeMillis();
        synchronized (requested) {
            Long asked = requested.get(key);
            if (asked != null && now - asked < REASK_MS) {
                return false;
            }
            // remove-then-put, so re-asking for an old hash moves it to the young end of the
            // insertion order and the bound evicts by age of the last request, not the first.
            requested.remove(key);
            requested.put(key, now);
            return true;
        }
    }

    /**
     * Whether this node asked a peer for {@code hashLow} recently enough for the answer to count.
     *
     * <p>Package-private: the exemption record is this class's own state, and {@code SyncManager}
     * hands out a {@code ChunkFeePolicy} through a Lombok getter, so anything holding a
     * {@code SyncManager} would otherwise be able to read and write what this node will admit.
     * {@link #refuses} is the only production reader; the tests sit in this package.
     */
    boolean wasRequested(Bytes32 hashLow) {
        if (hashLow == null) {
            return false;
        }
        Bytes32 key = Bytes32.wrap(hashLow.toArray());
        synchronized (requested) {
            Long asked = requested.get(key);
            if (asked == null) {
                return false;
            }
            if (System.currentTimeMillis() - asked >= REQUEST_TTL_MS) {
                requested.remove(key);
                return false;
            }
            return true;
        }
    }

    /**
     * The gate: true when this node declines to take {@code payingBlock}.
     *
     * <p>Cheap for everything that is not a chain block — a block with no extension field leaves at
     * the classification, having allocated nothing — and the record of what this node asked for is
     * consulted last, only once a refusal is otherwise decided, so the common path never touches it.
     *
     * <p><b>The block must already be parsed</b>, from its 512 bytes or in memory. An unparsed one
     * has an empty {@code extFields}, classifies as {@link Classified#NONE}, charges zero chunks and
     * is <em>admitted</em> — so a caller that gated a new entry point ahead of {@code parse()} would
     * get a gate that silently does nothing, and no test would notice. Both ingest paths satisfy
     * this today: a block off the wire is parsed by {@code new Block(XdagBlock)} before it ever
     * reaches {@code SyncManager}, and a block this node built itself is parsed by construction.
     *
     * <p>Reads the block, and does not write it: {@code getExtFields()} and {@code getBlockLinks()}
     * are plain reads of lists the parse already filled, and {@code getHashLow()} is a cached
     * derivation reached only for a chain block that has already failed the fee test. Both ingest
     * paths call this <em>before</em> handing the block anywhere, so the calling thread is still its
     * only owner.
     */
    public boolean refuses(Block payingBlock) {
        if (payingBlock == null || !spec.isChainIngestFeePolicy()) {
            return false;
        }
        try {
            int chunks = chunksCharged(payingBlock);
            if (chunks == 0) {
                // The same answer feeCovers gives for a zero count, reached without calling it:
                // headerFee re-encodes a locally built block's 512 bytes, and no block on the hot
                // path should pay for that to be told it owes nothing.
                return false;
            }
            if (ChainL1Processor.feeCovers(payingBlock, chunks, spec.getChainChunkFee())) {
                return false;
            }
            // Last, and only here: see §5.2 and this class's header.
            return !wasRequested(payingBlock.getHashLow());
        } catch (RuntimeException e) {
            // Fails open, and that is the only defensible direction for a node-local heuristic that
            // stands in front of the whole ingest path: nothing here decides validity, the consensus
            // fee check still runs under the lock later, and a gate that threw would take a netty
            // I/O thread's exceptionCaught for a block the chain is perfectly able to judge itself.
            // Logged at warn because it should not happen: every step is documented as total on a
            // block that parsed.
            log.warn("chunk fee gate could not decide, admitting the block", e);
            return false;
        }
    }

    /**
     * What a caller is told when this node declines a block it submitted itself — the CLI's transfer
     * commands and the RPC's, which otherwise print a success tail for a block that was never sent.
     *
     * <p>Here rather than at either of them because it describes this class's decision, and two
     * copies had already drifted into two wordings. Not an error string and not
     * {@code ImportResult.CHAIN_FEE_POLICY.errorInfo}: the block is well formed, other nodes may
     * well take it, and that field is a per-constant slot shared by every thread in the process.
     */
    public static String refusalMessage(Bytes32 hashLow) {
        return "Declined by this node's chunk fee policy (chain.ingest.feePolicy): the header fee does not"
                + " cover the chunk chains this block references. Tx hash:" + BasicUtils.hash2Address(hashLow);
    }

    /**
     * The number of chunk blocks the consensus chunk fee would be charged for, as far as this node
     * can see from outside the lock. Zero for anything that is not a well-formed DEPLOY or CALL
     * naming at least one chunk chain, which is every block on an ordinary node's hot path.
     */
    private int chunksCharged(Block payingBlock) {
        Classified c = ChainBlockClassifier.classify(payingBlock);
        if (c.kind() == null || !c.isOk()) {
            return 0;
        }
        long minEpoch = XdagTime.getEpoch(payingBlock.getTimestamp()) - 1;
        if (c.kind() == ExtKind.CALL) {
            return count(c.as(CallExt.class).argsChainHead(), minEpoch);
        }
        if (c.kind() == ExtKind.DEPLOY) {
            DeployExt d = c.as(DeployExt.class);
            return count(d.codeChainHead(), minEpoch) + count(d.argsChainHead(), minEpoch);
        }
        return 0;
    }

    /** {@link ChunkChain#countLenient} over one chain head; a block that names none charges none. */
    private int count(Bytes32 head, long minEpoch) {
        return head == null ? 0 : ChunkChain.countLenient(head, lookup, spec.getChainMaxChunksPerChain(), minEpoch);
    }
}
