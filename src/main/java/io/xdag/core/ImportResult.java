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

package io.xdag.core;

import org.apache.tuweni.bytes.MutableBytes32;

import lombok.Getter;
import lombok.Setter;

/**
 * Enum representing different results of block import operations
 * ERROR - Import failed with error
 * EXIST - Block already exists
 * NO_PARENT - Parent block not found
 * INVALID_BLOCK - Block validation failed
 * IN_MEM - Block is already in memory
 * IMPORTED_EXTRA - Block imported as extra
 * IMPORTED_NOT_BEST - Block imported but not in main chain
 * IMPORTED_BEST - Block imported into main chain
 * CHAIN_FEE_POLICY - This node declined the block on its own fee policy
 */
public enum ImportResult {
    ERROR,
    EXIST, 
    NO_PARENT,
    INVALID_BLOCK,
    IN_MEM,

    IMPORTED_EXTRA,
    IMPORTED_NOT_BEST,
    IMPORTED_BEST,

    /**
     * This node declined the block under a policy of its own, and makes no claim that the block is
     * wrong. Nothing returns it yet; the gate that will is a later task.
     *
     * <p><b>Not a synonym for {@link #INVALID_BLOCK}, and the difference is not about blame.</b>
     * There is no scoring, banning or blacklisting anywhere in this codebase — the {@code
     * INVALID_BLOCK} arm of {@code SyncManager.releaseWaiters} is an empty block with its one log
     * line commented out — so nothing would punish a sender for either code today. What that arm
     * does instead is nothing at all, and nothing is the expensive part: it never calls {@code
     * syncPopBlock}, so every child parked in {@code syncMap} waiting on the refused block stays
     * there until it is evicted. Answering a block with "I am full" in that code strands its whole
     * subtree.
     *
     * <p>So a node that declines a block on policy returns this instead, and {@code releaseWaiters}
     * releases the waiters rather than leaving them parked. Nothing returns this code yet, so what
     * that buys today is only the better of two behaviours: a released child re-imports, finds the
     * parent genuinely absent and is parked again — a re-import, against a stranded subtree.
     *
     * <p><b>It is not yet a loop that closes.</b> A released child re-requests the block that was
     * refused, and there is nothing today that would stop the second copy being refused exactly as
     * the first was. Closing it needs the exemption the design's §5.2 describes — a block this node
     * asked for must not be refused by the policy that turned away the unsolicited broadcast — and
     * that exemption does not exist. It is Task 14's to deliver, together with the gate that first
     * returns this code; neither is here.
     *
     * <p>Declared last so that no existing constant's ordinal moves. Nothing persists an ordinal
     * today, and this keeps it that way by construction rather than by audit.
     */
    CHAIN_FEE_POLICY;

    // Truncated hash of the block
    private MutableBytes32 hashLow;

    // Error message if import failed
    @Setter
    @Getter
    private String errorInfo;

    /**
     * Get the truncated hash of the block
     * @return The truncated hash as MutableBytes32
     */
    public MutableBytes32 getHashlow() {
        return hashLow;
    }

    /**
     * Set the truncated hash of the block
     * @param hashLow The truncated hash to set
     */
    public void setHashlow(MutableBytes32 hashLow) {
        this.hashLow = hashLow;
    }

}
