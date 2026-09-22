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

import io.xdag.core.Block;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl.OrphanMeta;
import java.util.NavigableSet;
import java.util.Objects;
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

    /** The peer the block arrived from; null for a locally produced block, which owes no peer. */
    private final String peerKey;

    /** The chunk chain this block hangs off; null when it could not be grouped, or not a chunk. */
    private final Bytes32 chainHead;

    /** The chunk body, held only in memory; null for every other category. */
    private final Block body;

    /**
     * The set that currently holds this entry, written by the pool on add and cleared on remove.
     * This is what keeps {@code remove(hashlow)} logarithmic: the index leads to the entry, and the
     * entry leads to its holding collection, so nothing has to search the categories to find out
     * where it lives. Owned by {@link ChainOrphanPool}; null when the entry is not pooled.
     */
    NavigableSet<OrphanEntry> holder;

    /** Which of the two account lanes this entry went into; meaningless outside ACCOUNT_TX. */
    boolean vip;

    public OrphanEntry(OrphanMeta meta, OrphanCategory category, String peerKey, Bytes32 chainHead,
            Block body) {
        this.meta = Objects.requireNonNull(meta, "meta");
        this.category = Objects.requireNonNull(category, "category");
        this.peerKey = peerKey;
        this.chainHead = chainHead;
        this.body = body;
        this.addressKey = category == OrphanCategory.ACCOUNT_TX
                ? Hex.toHexString(meta.getAddress())
                : null;
    }

    public static OrphanEntry link(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.LINK, peerKey, null, null);
    }

    public static OrphanEntry mtx(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.MTX, peerKey, null, null);
    }

    public static OrphanEntry accountTx(OrphanMeta meta, String peerKey) {
        return new OrphanEntry(meta, OrphanCategory.ACCOUNT_TX, peerKey, null, null);
    }

    public static OrphanEntry chunk(OrphanMeta meta, String peerKey, Bytes32 chainHead, Block body) {
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

    public Block body() {
        return body;
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
