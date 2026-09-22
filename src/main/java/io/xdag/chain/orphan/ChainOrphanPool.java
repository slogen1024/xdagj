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
package io.xdag.chain.orphan;

import com.google.common.primitives.UnsignedBytes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The orphan pool's in-memory side: four categories kept in separate ordered sets, plus one hashlow
 * index over all of them.
 *
 * <h2>Not synchronized, on purpose</h2>
 *
 * <p>Nothing here is synchronized and none of the collections are concurrent. The pool is protected
 * by the blockchain monitor that every caller already holds — {@code addOrphan} through {@code
 * dealOrphan}, {@code deleteFromQueue}/{@code deleteByKey} through {@code removeOrphan}, both
 * inside the synchronized {@code tryToConnect}, and the packing path since {@code 811deec0} put it
 * under the same monitor. Adding a lock here would be a second lock over state that already has
 * one; the absence of {@code synchronized} is a decision, not an oversight.
 *
 * <h2>Why ordered sets and not priority queues</h2>
 *
 * <p>The old {@code PriorityBlockingQueue}s cost O(n) per removal, so removal got dearer as the
 * pool grew — measured at 10.0 µs against 5.4 µs when the buckets went from 125 to 500 deep. A
 * {@link TreeSet} removes in O(log n), which is what flattens that slope.
 *
 * <p>The swap is only safe because every comparator below is a <em>total</em> order: each one ends
 * by comparing the hashlow bytes, and a hashlow is unique per block, so no two distinct entries can
 * ever compare equal. A comparator that could return 0 for two different blocks would make the set
 * silently drop one of them.
 *
 * <h2>The removal trap</h2>
 *
 * <p>{@link #remove(Bytes32)} looks the entry up in the index and hands <em>that very instance</em>
 * to the set. See its javadoc for why rebuilding one would fail silently.
 *
 * <h2>What the caps are for</h2>
 *
 * <p>Each category is capped on its own, and the pool as a whole is capped behind them. Without the
 * per-category caps a flood of one kind fills the pool and every other kind starts being refused —
 * which is what the live code does today, where {@code MAX_ORPHAN_SIZE} counts all four queues
 * together but is only consulted when an account transaction arrives. The TTL and the VIP
 * admission rule arrive in later tasks.
 *
 * <p>Under the chunk category sit two more tiers, chunks only: one per source peer and one per
 * chunk chain. They exist because a category cap alone does not stop a single attacker — one
 * flooder can fill the whole chunk category on its own and lock every honest peer out of it. The
 * second tier makes a flood cost the flooder its own budget instead of everybody's.
 */
public final class ChainOrphanPool {

    /**
     * The two lanes an account transaction can go into, mirroring today's {@code accountTxMap} and
     * {@code vipTxMap}. Which lane a block earns is decided by the caller for now; the rule
     * ({@code nonce == executedNonce + 1} and {@code fee > averageFee}) moves in when the store
     * starts delegating.
     */
    public enum AccountLane {
        REGULAR, VIP
    }

    /*
     * The four comparators are copied from OrphanBlockStoreImpl's live declarations — linkQueue
     * (:56), mtxQueue (:60) and the two per-address queues (:342, :348) — term for term and in the
     * same order. The only change is mechanical: OrphanMeta's fields are private, so field access
     * (m.time) becomes the generated getter (e.meta().getTime()).
     *
     * The VIP order uses thenComparing where the regular account order uses thenComparingLong. That
     * difference is in the original and is kept: the two differ only in boxing the long, so the
     * resulting order is identical. It is preserved rather than tidied so that the delegation task
     * is a pure move with nothing to re-argue.
     */

    /** {@code linkQueue}: time ascending, then hashlow. Also the order CHUNK uses. */
    static final Comparator<OrphanEntry> LINK_ORDER = Comparator
            .comparingLong((OrphanEntry e) -> e.meta().getTime())
            .thenComparing(e -> e.meta().getHashlow().toArray(), UnsignedBytes.lexicographicalComparator());

    /** {@code mtxQueue}: fee descending, then time ascending, then hashlow. */
    static final Comparator<OrphanEntry> MTX_ORDER = Comparator
            .comparingLong((OrphanEntry e) -> -e.meta().getFee())
            .thenComparingLong(e -> e.meta().getTime())
            .thenComparing(e -> e.meta().getHashlow().toArray(), UnsignedBytes.lexicographicalComparator());

    /** {@code accountTxMap}'s per-address queue: nonce ascending, then time, then hashlow. */
    static final Comparator<OrphanEntry> ACCOUNT_TX_ORDER = Comparator
            .comparingLong((OrphanEntry e) -> e.meta().getNonce())
            .thenComparingLong(e -> e.meta().getTime())
            .thenComparing(e -> e.meta().getHashlow().toArray(), UnsignedBytes.lexicographicalComparator());

    /** {@code vipTxMap}'s per-address queue: nonce ascending, then time, then hashlow. */
    static final Comparator<OrphanEntry> VIP_TX_ORDER = Comparator
            .comparingLong((OrphanEntry e) -> e.meta().getNonce())
            .thenComparing(e -> e.meta().getTime())
            .thenComparing(e -> e.meta().getHashlow().toArray(), UnsignedBytes.lexicographicalComparator());

    private final NavigableSet<OrphanEntry> linkSet = new TreeSet<>(LINK_ORDER);
    private final NavigableSet<OrphanEntry> mtxSet = new TreeSet<>(MTX_ORDER);
    private final NavigableSet<OrphanEntry> chunkSet = new TreeSet<>(LINK_ORDER);

    /** Per-address buckets, the regular lane. Empty buckets are dropped as they empty. */
    private final Map<String, NavigableSet<OrphanEntry>> accountTxMap = new HashMap<>();

    /** Per-address buckets, the VIP fast lane. Empty buckets are dropped as they empty. */
    private final Map<String, NavigableSet<OrphanEntry>> vipTxMap = new HashMap<>();

    /** hashlow to the stored entry. The one way in to a removal. */
    private final Map<Bytes, OrphanEntry> index = new HashMap<>();

    /**
     * How many chunks each source peer currently holds. Chunks only — every other category is
     * bounded by its own cap and the global one, and nothing else.
     *
     * <p><b>The key is the peer's IP, never the id it announced.</b> {@code peerId} is
     * cryptographically bound — the handshake checks it is the Base58 address of the presented
     * public key and verifies the signature ({@code HandshakeMessage.java:170-172}) — so it cannot
     * be borrowed from somebody else. But a fresh keypair costs nothing, so keying on it lets a
     * flooder zero its own quota by reconnecting under a new identity, and a quota that is free to
     * evade is not a quota. The IP is not self-reported: {@code XdagP2pHandler} passes {@code
     * channel.getRemoteIp()} into {@code getPeer} ({@code :256}, {@code :285}), which is the real
     * socket address.
     *
     * <p>The price is known and accepted: several honest nodes can share one IP (NAT, or several
     * instances on one host) and they will share one budget. That is the trade — throttling by an
     * identity anyone can mint again costs an attacker nothing at all.
     *
     * <p>Buckets are dropped as they empty; see {@link #release}.
     */
    private final Map<String, Integer> chunkPerPeer = new HashMap<>();

    /**
     * How many chunks each chunk chain currently holds, across every source together. Chunks only,
     * and buckets are dropped as they empty.
     *
     * <p>The key is the chain's head hashlow, not a target chain id. A chain id is not computable
     * when a chunk is admitted: {@code ChunkExt} carries {@code (seq, totalLen, dataLen, next,
     * data)} and no chain identity at all, and the paying block that would name one has usually
     * not arrived yet. The head is derivable from what a chunk already carries.
     *
     * <p>The pool does not derive it. The head is supplied on the entry, the same way the peer key
     * is, and the caller that groups a chunk onto its chain owns that walk; a pool that followed
     * {@code next} itself would have to reach for the block store from inside the blockchain
     * monitor and would turn an O(log n) admission into a chain walk.
     */
    private final Map<Bytes32, Integer> chunkPerChain = new HashMap<>();

    /**
     * Per-category counts, kept in step with add and remove rather than summed from the sets.
     * Summing would be O(number of addresses) for account transactions, and it would also hide an
     * eviction that forgot to release what it held.
     */
    private final int[] counts = new int[OrphanCategory.values().length];

    private int total;

    private final OrphanLimits limits;

    /**
     * @param limits the caps this pool enforces, injected rather than read from a global so that a
     *     test can exercise a boundary with two entries instead of sixty thousand
     */
    public ChainOrphanPool(OrphanLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Adds an account transaction into the regular lane; other categories have only one lane. */
    public OrphanAdmission add(OrphanEntry entry) {
        return add(entry, AccountLane.REGULAR);
    }

    /**
     * Admits an entry, or says why it was refused. {@code lane} picks the account lane and is
     * ignored for every other category.
     *
     * <p>A hashlow already in the pool is {@link OrphanAdmission#DUPLICATE} and changes nothing —
     * not the stored entry, not any count. That check is by hashlow, never by comparator: two
     * entries for the same block whose fee has drifted apart would not compare equal, so a set
     * would happily hold both. It is asked first, before either cap: a duplicate has nothing to
     * admit, so no cap could be the reason it did not go in.
     *
     * <p><b>The global cap is asked before the category cap.</b> Both can be reached at once, and
     * the verdict has to name the constraint that admitting could not have got around. At the
     * global ceiling no amount of room in this category would have helped, so that is
     * {@link OrphanAdmission#POOL_FULL}; {@link OrphanAdmission#CATEGORY_FULL} then carries the
     * stronger statement that the pool <em>did</em> have room and this kind had used its share. The
     * configuration keeps the pool cap at or above the sum of the four category caps, so on a
     * configured node the global cap is a backstop that can only be reached once every category is
     * already at its own.
     *
     * <p>Then, for a chunk and only for a chunk, the two second-tier quotas: the source peer's
     * share and the chunk chain's share. <b>Over both at once the refusal names the peer.</b> The
     * two are orthogonal — neither one being full makes the other irrelevant the way the global cap
     * makes the category cap irrelevant — so the tie is broken on what the verdict says.
     * {@link OrphanAdmission#PEER_FULL} is a statement about this sender alone and is true whenever
     * it is returned; {@link OrphanAdmission#CHAIN_FULL} is about state the sender shares with
     * every other source and may well have been filled by one of them, so it is kept for the case
     * where this sender really was still inside its own budget. A sender over its own budget is
     * told so, rather than pointed at its neighbours.
     *
     * <p>A null peer key is unattributed and spends no peer budget: a block this node produced
     * itself — mined, or built over RPC or the CLI — arrived from no peer and owes none. A null
     * chain head likewise spends no chain budget, but it is still a chunk from somewhere, so the
     * peer and global tiers still hold it; otherwise "send chunks that group onto nothing" would be
     * a way past every tier at once.
     *
     * <p><b>Every refusal happens before anything is touched.</b> The checks come before {@link
     * #holderFor}, which is what would create an address bucket — a refused account transaction for
     * an address the pool has never seen must not leave an empty bucket behind for the reclaim on
     * removal to never come and collect. The same holds for the two quota maps: both are asked
     * before either is charged, so a chunk refused by one tier has not spent the other.
     */
    public OrphanAdmission add(OrphanEntry entry, AccountLane lane) {
        if (index.containsKey(entry.hashlow())) {
            return OrphanAdmission.DUPLICATE;
        }
        if (total >= limits.poolLimit()) {
            return OrphanAdmission.POOL_FULL;
        }
        if (counts[entry.category().ordinal()] >= limits.limit(entry.category())) {
            return OrphanAdmission.CATEGORY_FULL;
        }
        if (entry.category() == OrphanCategory.CHUNK) {
            if (held(chunkPerPeer, entry.peerKey()) >= limits.chunkPerPeer()) {
                return OrphanAdmission.PEER_FULL;
            }
            if (held(chunkPerChain, entry.chainHead()) >= limits.chunkPerChain()) {
                return OrphanAdmission.CHAIN_FULL;
            }
        }
        NavigableSet<OrphanEntry> holder = holderFor(entry, lane);
        holder.add(entry);
        entry.holder = holder;
        entry.vip = entry.category() == OrphanCategory.ACCOUNT_TX && lane == AccountLane.VIP;
        index.put(entry.hashlow(), entry);
        counts[entry.category().ordinal()]++;
        total++;
        reserveChunkQuotas(entry);
        return OrphanAdmission.ADMITTED;
    }

    /**
     * Removes the orphan with this hashlow and returns it, or null when it was not pooled.
     *
     * <p><b>The instance that comes out of the index is the instance handed to the set.</b> Never
     * rebuild an entry from a hashlow or from a caller's fields to remove it. {@code
     * PriorityBlockingQueue.remove} went through {@code equals}, and {@code OrphanMeta.equals}
     * compares the hashlow alone, so a rebuild with a stale fee still found its target.
     * {@code TreeSet.remove} navigates by the <b>comparator</b> instead, and the mtx order is by
     * fee. The two sides really do disagree in production: {@code dealOrphan} builds its meta from
     * the block instance {@code tryToConnect} received, while {@code removeOrphan} re-fetches the
     * block and recomputes {@code getTxFee} after the chain has already moved {@code info.fee}. A
     * rebuilt entry sorts somewhere else, the search never reaches the stored node, and removal
     * fails <em>silently</em> — the entry stays in the set and its quota slot is never returned.
     *
     * <p>Taking the set's {@code first()} is the same bug wearing a different hat: it removes some
     * other block's entry and reports it as this one's.
     */
    public OrphanEntry remove(Bytes32 hashlow) {
        OrphanEntry stored = index.remove(hashlow);
        if (stored == null) {
            return null;
        }
        NavigableSet<OrphanEntry> holder = stored.holder;
        if (!holder.remove(stored)) {
            // Unreachable while the meta stays the snapshot it is meant to be. If it ever fires,
            // something mutated a sort key under a pooled entry and the set can no longer find it.
            throw new IllegalStateException(
                    "the stored orphan entry was not in the set that claimed to hold it: " + stored);
        }
        stored.holder = null;
        reclaimIfEmpty(stored, holder);
        releaseChunkQuotas(stored);
        counts[stored.category().ordinal()]--;
        total--;
        return stored;
    }

    /** The stored entry for this hashlow, or null. Never a copy. */
    public OrphanEntry get(Bytes32 hashlow) {
        return index.get(hashlow);
    }

    public boolean contains(Bytes32 hashlow) {
        return index.containsKey(hashlow);
    }

    /**
     * How many orphans this category holds. For {@link OrphanCategory#ACCOUNT_TX} that is both
     * lanes together across every address, which is what today's {@code getOrphanSize} counts and
     * what a single account-transaction cap has to be measured against.
     */
    public int size(OrphanCategory category) {
        return counts[category.ordinal()];
    }

    public int totalSize() {
        return total;
    }

    /**
     * Every entry of a category, in the order its set holds them. Test-only.
     *
     * <p>Account transactions have no single order — they live in per-address buckets — so they are
     * enumerated address by address in hex order, the regular lane first and then the VIP lane.
     * That is a deterministic reading order for assertions and means nothing in production; use
     * {@link #peekAccount} to assert on one address.
     */
    public List<OrphanEntry> peekAll(OrphanCategory category) {
        if (category != OrphanCategory.ACCOUNT_TX) {
            return List.copyOf(setFor(category));
        }
        List<OrphanEntry> all = new ArrayList<>();
        appendLane(accountTxMap, all);
        appendLane(vipTxMap, all);
        return List.copyOf(all);
    }

    /** One address's bucket in one lane, in its set's order, or empty. Test-only. */
    public List<OrphanEntry> peekAccount(String addressKey, AccountLane lane) {
        NavigableSet<OrphanEntry> bucket = laneMap(lane).get(addressKey);
        return bucket == null ? List.of() : List.copyOf(bucket);
    }

    /** How many per-address buckets a lane currently holds. Test-only; see the reclaim rule. */
    public int accountBucketCount(AccountLane lane) {
        return laneMap(lane).size();
    }

    /** How many source peers currently hold a chunk. Test-only; see the reclaim rule. */
    public int peerBucketCount() {
        return chunkPerPeer.size();
    }

    /** How many chunk chains currently hold a chunk. Test-only; see the reclaim rule. */
    public int chainBucketCount() {
        return chunkPerChain.size();
    }

    private void appendLane(Map<String, NavigableSet<OrphanEntry>> lane, List<OrphanEntry> out) {
        List<String> addresses = new ArrayList<>(lane.keySet());
        Collections.sort(addresses);
        for (String address : addresses) {
            out.addAll(lane.get(address));
        }
    }

    private Map<String, NavigableSet<OrphanEntry>> laneMap(AccountLane lane) {
        return lane == AccountLane.VIP ? vipTxMap : accountTxMap;
    }

    private NavigableSet<OrphanEntry> setFor(OrphanCategory category) {
        return switch (category) {
            case LINK -> linkSet;
            case MTX -> mtxSet;
            case CHUNK -> chunkSet;
            case ACCOUNT_TX -> throw new IllegalArgumentException(
                    "account transactions live in per-address buckets, not one set");
        };
    }

    private NavigableSet<OrphanEntry> holderFor(OrphanEntry entry, AccountLane lane) {
        if (entry.category() != OrphanCategory.ACCOUNT_TX) {
            return setFor(entry.category());
        }
        Comparator<OrphanEntry> order = lane == AccountLane.VIP ? VIP_TX_ORDER : ACCOUNT_TX_ORDER;
        return laneMap(lane).computeIfAbsent(entry.addressKey(), k -> new TreeSet<>(order));
    }

    /**
     * Drops an address bucket the moment it empties. Without this, a flood across many addresses
     * would leave an unbounded number of empty buckets behind, and every walk over the lane would
     * keep paying for them.
     */
    private void reclaimIfEmpty(OrphanEntry stored, NavigableSet<OrphanEntry> holder) {
        if (stored.category() == OrphanCategory.ACCOUNT_TX && holder.isEmpty()) {
            laneMap(stored.isVip() ? AccountLane.VIP : AccountLane.REGULAR).remove(stored.addressKey());
        }
    }

    /** Charges an admitted chunk to its peer and its chain. A no-op for every other category. */
    private void reserveChunkQuotas(OrphanEntry entry) {
        if (entry.category() != OrphanCategory.CHUNK) {
            return;
        }
        reserve(chunkPerPeer, entry.peerKey());
        reserve(chunkPerChain, entry.chainHead());
    }

    /**
     * Hands back what a chunk held. <b>Every path that takes an entry out of the pool must come
     * through here</b> — removal today, TTL eviction next — because a path that forgets leaves the
     * slot spent forever and the peer or chain it belonged to slowly locked out.
     */
    private void releaseChunkQuotas(OrphanEntry entry) {
        if (entry.category() != OrphanCategory.CHUNK) {
            return;
        }
        release(chunkPerPeer, entry.peerKey());
        release(chunkPerChain, entry.chainHead());
    }

    /** What this key holds now; an unattributed entry (null key) holds nothing and never will. */
    private static <K> int held(Map<K, Integer> buckets, K key) {
        return key == null ? 0 : buckets.getOrDefault(key, 0);
    }

    private static <K> void reserve(Map<K, Integer> buckets, K key) {
        if (key == null) {
            return;
        }
        buckets.merge(key, 1, Integer::sum);
    }

    /**
     * Gives one slot back and <b>deletes the bucket the moment it reaches zero</b>. Without the
     * delete, one flood across many peers or many chains leaves an unbounded number of empty
     * entries behind and the leak outlives the flood that caused it — the pool drains, the maps do
     * not.
     */
    private static <K> void release(Map<K, Integer> buckets, K key) {
        if (key == null) {
            return;
        }
        buckets.compute(key, (bucket, count) -> {
            if (count == null) {
                // Unreachable: a pooled chunk was charged for this key when it was admitted.
                throw new IllegalStateException(
                        "released a chunk quota slot that was never held: " + bucket);
            }
            return count == 1 ? null : count - 1;
        });
    }
}
