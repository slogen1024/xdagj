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
package io.xdag.db;

import io.xdag.chain.ext.Classified;
import io.xdag.chain.orphan.OrphanCategory;
import io.xdag.core.XAmount;
import io.xdag.core.XdagLifecycle;
import io.xdag.core.Address;
import io.xdag.core.Block;
import java.util.List;

import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;

public interface OrphanBlockStore extends XdagLifecycle {

    byte ORPHAN_PREFEX = 0x00;
    /**
     * size key
     */
    byte[] ORPHAN_SIZE = Hex.decode("FFFFFFFFFFFFFFFF");

    void reset();

    List<Address> getOrphan(long num, long[] sendTime, boolean isMain);

    void deleteByKey(byte[] hashlow, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address);

    void deleteFromQueue(Block block, boolean isTxBlock , UInt64 nonce, XAmount fee, byte[] address);

    /**
     * Pools an orphan that arrived with nothing known about where it came from or what it is.
     *
     * <p>Not a shorthand for the full call: this is the honest signature for the paths that really
     * do carry neither fact — a block this node produced itself (mined, RPC, CLI), and the
     * roll-back that re-orphans a transaction block it already holds. Unattributed is the correct
     * reading of those, not a degraded one; see the {@code peerKey} parameter below.
     */
    default void addOrphan(Block block, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address) {
        addOrphan(block, isTxBlock, nonce, fee, address, null, null);
    }

    /**
     * Pools an orphan together with the two facts the import path learns about it: who sent it, and
     * what kind of block it is.
     *
     * @param peerKey    the source peer's <b>IP address</b>, or null when the block came from no
     *                   peer. It must be the IP and never {@code Peer.getPeerId()}: the id is
     *                   cryptographically bound to a keypair at handshake so it cannot be borrowed,
     *                   but minting a fresh keypair costs nothing, so a flooder keyed on it zeroes
     *                   its own quota by reconnecting — and a quota that is free to evade is not a
     *                   quota. The IP comes off the real socket. The price, knowingly paid: several
     *                   honest nodes behind one NAT share one budget.
     * @param classified this block's chain-extension classification, or null on a path that did not
     *                   compute one. It decides the {@link OrphanCategory} — a null classification
     *                   can never be {@link OrphanCategory#CHUNK} — and, for a chunk, it is where
     *                   the chunk chain this block attaches to is read from.
     */
    void addOrphan(Block block, boolean isTxBlock, UInt64 nonce, XAmount fee, byte[] address,
            String peerKey, Classified classified);

    long getOrphanSize();

    /**
     * Whether an orphan of this category would be refused for want of room — its own category cap,
     * or the pool-wide one.
     *
     * <p>The import path's admission gate. It is asked per category rather than against one total
     * because a total is what let a flood of one kind of block close the door on every other kind:
     * before SP0b-3 the check was a single count of all four queues, consulted only when an account
     * transaction arrived, so link, chunk and mtx blocks filled the pool with nothing stopping them
     * and account transactions paid for it.
     */
    boolean isFull(OrphanCategory category);

}
