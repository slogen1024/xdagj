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

import io.xdag.utils.XdagTime;
import java.util.NavigableSet;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;

/**
 * One orphan as the pool holds it: the {@link OrphanMeta} the store already builds, plus the three
 * facts the old queues had nowhere to put — which category it belongs to, which peer it arrived
 * from, and, for a chunk, which chain it hangs off and the block body that is never written to
 * disk.
 *
 * <p><b>The meta is a snapshot and must stay one.</b> It is the sort key of a {@code TreeSet}, so
 * mutating {@code time}, {@code fee}, {@code nonce} or {@code hashlow} on an entry that is already
 * pooled corrupts the tree it sits in — the search path built at insertion no longer leads to it.
 * Nothing does that today: {@code removeOrphan} builds a <em>fresh</em> meta from freshly read
 * fields rather than editing the stored one, which is precisely why removal has to go through the
 * pool's index instead of that fresh copy. See {@link ChainOrphanPool#remove}.
 *
 * <p>{@code equals}/{@code hashCode} are by hashlow alone, matching {@link OrphanMeta}. They are
 * what the hashlow index uses; the ordered sets use their comparators and never consult them.
 */
public final class OrphanEntry {

    private final OrphanMeta meta;
    private final OrphanCategory category;

    /** Hex of the 20-byte address for {@link OrphanCategory#ACCOUNT_TX}, null for every other. */
    private final String addressKey;

    /**
     * The peer the block arrived from, as <b>its IP address</b>; null for a locally produced block,
     * which owes no peer.
     *
     * <p><b>Whoever fills this in must pass the IP and not {@code peerId}.</b> The pool charges
     * this key for the per-peer chunk quota, and the quota is only worth having if evading it costs
     * something. {@code peerId} is cryptographically bound — the handshake checks it is the Base58
     * address of the presented public key and verifies the signature ({@code
     * HandshakeMessage.java:170-172}) — so it cannot be borrowed; but minting a fresh keypair is
     * free, so a flooder keyed on it resets its own budget by reconnecting. The IP is the real
     * socket address {@code XdagP2pHandler} already carries, {@code channel.getRemoteIp()} ({@code
     * :256}, {@code :285}). See {@link ChainOrphanPool}'s per-peer map for the trade this accepts.
     */
    private final String peerKey;

    /**
     * The chunk chain this block hangs off; null when it could not be grouped, or when this is not
     * a chunk.
     *
     * <p>A chain position, not a target chain id: a chain id is not computable at admission —
     * {@code ChunkExt} carries no chain identity and the paying block that would name one has
     * usually not arrived. What the caller supplies instead is the point on the chain it could name
     * by following {@code next}: the successor's hashlow for a chunk that has one, the chunk's own
     * hashlow for a tail, which is the root of its own chain. Every chunk naming the same block
     * therefore shares a bucket, which is the shape the per-chain tier exists to bound.
     *
     * <p>The grouping is the caller's walk, not the pool's; the pool only counts what it is given.
     * See {@code OrphanBlockStoreImpl}'s derivation for how far that walk goes and why.
     */
    private final Bytes32 chainHead;

    /**
     * The chunk's own 512 wire bytes, held only in memory; null for every other category.
     *
     * <p><b>Bytes and not a {@code Block}, deliberately.</b> This is the one thing in the pool that
     * is read off the blockchain monitor — {@link ChainOrphanPool#chunkBody} hands it to netty's
     * serve path and to the import path's reference check — and a {@code Block} could not be. A
     * {@code Block} is mutable (the chain writes {@code info.fee}, {@code info.flags} and
     * {@code info.height} into the instance it holds) and it is a <em>lazy</em> mutator besides:
     * {@code getXdagBlock()} builds and caches the 512 bytes when they are missing, so even a
     * reader that only wanted the bytes could be writing. Both are exactly the class of bug
     * {@code a51e09c5} fixed on this path, and the fix it settled on — serve a copy re-parsed from
     * the raw bytes, never the pooled instance — is only available if the pool keeps the raw bytes.
     *
     * <p>So the bytes are copied out once, here, on the thread that admits the block and under the
     * monitor, and what is stored is immutable for the rest of the entry's life. A reader takes its
     * own array from it and parses its own {@code Block}; two readers share nothing.
     *
     * <p>It is also the cheap half: 512 bytes against the ~2 KB a parsed {@code Block} retains
     * (the {@code XdagBlock}, sixteen {@code Bytes32} fields, the {@code BlockInfo}, the link
     * list), which at the chunk cap is the difference between tens and hundreds of megabytes.
     */
    private final Bytes body;

    /**
     * When this node took the block in, on its own wall clock, in milliseconds. This is the clock
     * the flat fifteen-minute TTL runs on for every category <b>except</b> {@link
     * OrphanCategory#CHUNK}, and it is exactly what {@code addOrphanToMemory} stamps into {@code
     * orphanInsertTimeMap} today ({@code OrphanBlockStoreImpl.java:315}).
     *
     * <p><b>Ignored for a chunk, deliberately.</b> A chunk is timed in epochs of its own header —
     * see {@link #epoch()} and {@link ChainOrphanPool#evictExpired} — because its retention is on
     * the consensus path and a local clock would make it a node-local answer to a network-wide
     * question. Stamping it anyway costs a long and keeps one shape for all four categories; what
     * it must not do is become the thing a chunk is judged on.
     */
    private final long receivedAtMillis;

    /**
     * The set that currently holds this entry, written by the pool on add and cleared on remove.
     * This is what keeps {@code remove(hashlow)} logarithmic: the index leads to the entry, and the
     * entry leads to its holding collection, so nothing has to search the categories to find out
     * where it lives. Owned by {@link ChainOrphanPool}; null when the entry is not pooled.
     */
    NavigableSet<OrphanEntry> holder;

    /** Which of the two account lanes this entry went into; meaningless outside ACCOUNT_TX. */
    boolean vip;

    /**
     * Takes the receipt moment from the wall clock, which is when this constructor runs — the same
     * instant {@code addOrphanToMemory} stamps today. Every factory without an explicit millis
     * lands here, so the clock is read in exactly one place.
     */
    public OrphanEntry(OrphanMeta meta, OrphanCategory category, String peerKey, Bytes32 chainHead,
            Bytes body) {
        this(meta, category, peerKey, chainHead, body, System.currentTimeMillis());
    }

    public OrphanEntry(OrphanMeta meta, OrphanCategory category, String peerKey, Bytes32 chainHead,
            Bytes body, long receivedAtMillis) {
        this.meta = Objects.requireNonNull(meta, "meta");
        this.category = Objects.requireNonNull(category, "category");
        this.peerKey = peerKey;
        this.chainHead = chainHead;
        this.body = body;
        this.receivedAtMillis = receivedAtMillis;
        this.addressKey = category == OrphanCategory.ACCOUNT_TX
                ? Hex.toHexString(meta.getAddress())
                : null;
    }

    public static OrphanEntry link(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.LINK, peerKey, null, null);
    }

    /** As {@link #link(OrphanMeta, String)}, with the receipt moment supplied rather than read. */
    public static OrphanEntry link(OrphanMeta meta, String peerKey, long receivedAtMillis) {
        return new OrphanEntry(meta, OrphanCategory.LINK, peerKey, null, null, receivedAtMillis);
    }

    public static OrphanEntry mtx(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.MTX, peerKey, null, null);
    }

    /** As {@link #mtx(OrphanMeta, String)}, with the receipt moment supplied rather than read. */
    public static OrphanEntry mtx(OrphanMeta meta, String peerKey, long receivedAtMillis) {
        return new OrphanEntry(meta, OrphanCategory.MTX, peerKey, null, null, receivedAtMillis);
    }

    public static OrphanEntry accountTx(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.ACCOUNT_TX, peerKey, null, null);
    }

    /** As {@link #accountTx(OrphanMeta, String)}, with the receipt moment supplied. */
    public static OrphanEntry accountTx(OrphanMeta meta, String peerKey, long receivedAtMillis) {
        return new OrphanEntry(meta, OrphanCategory.ACCOUNT_TX, peerKey, null, null,
                receivedAtMillis);
    }

    public static OrphanEntry chunk(OrphanMeta meta, String peerKey, Bytes32 chainHead, Bytes body) {
        return new OrphanEntry(meta, OrphanCategory.CHUNK, peerKey, chainHead, body);
    }

    public OrphanMeta meta() {
        return meta;
    }

    public OrphanCategory category() {
        return category;
    }

    public Bytes32 hashlow() {
        return meta.getHashlow();
    }

    /** Hex of the account address, or null when this is not an account transaction. */
    public String addressKey() {
        return addressKey;
    }

    public String peerKey() {
        return peerKey;
    }

    public Bytes32 chainHead() {
        return chainHead;
    }

    /** The chunk's 512 wire bytes, immutable, or null. See {@link #body}. */
    public Bytes body() {
        return body;
    }

    /** When this node took the block in, on its own clock. Not what a chunk is judged on. */
    public long receivedAtMillis() {
        return receivedAtMillis;
    }

    /**
     * The epoch of the block's <b>own timestamp</b>, which is the only clock a chunk's retention
     * may be measured against.
     *
     * <p>Derived, not stored. {@link #meta} is a frozen snapshot — it is a {@code TreeSet} sort key
     * and mutating it is already forbidden above — so an epoch field would be a second copy of a
     * value that cannot change, and a second place for the conversion to drift from {@code
     * XdagTime.getEpoch}. The conversion is one arithmetic shift, and the sweep that asks for it
     * visits only the entries it is about to evict plus the first one it keeps.
     */
    public long epoch() {
        return XdagTime.getEpoch(meta.getTime());
    }

    /** True when this entry sits in the VIP account lane. Only meaningful for ACCOUNT_TX. */
    public boolean isVip() {
        return vip;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return meta.equals(((OrphanEntry) other).meta);
    }

    @Override
    public int hashCode() {
        return meta.hashCode();
    }

    @Override
    public String toString() {
        return "OrphanEntry{" + category + " " + meta.getHashlow() + " time=" + meta.getTime()
                + " fee=" + meta.getFee() + " nonce=" + meta.getNonce() + "}";
    }
}
