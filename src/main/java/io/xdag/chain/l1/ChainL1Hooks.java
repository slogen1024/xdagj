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
package io.xdag.chain.l1;

import io.xdag.core.Block;

/**
 * The five points where {@code BlockchainImpl} hands control to the chain layer. Every
 * implementation must be reorg-symmetric: whatever {@link #onBlockApplied} writes,
 * {@link #onBlockUnapplied} must undo exactly, so that {@code apply -> unwind -> apply} and a plain
 * {@code apply} leave byte-identical state (SP0a principle P3).
 *
 * <p>{@link #onSetMainBegin} and {@link #onSetMainEnd} bracket the {@code applyBlock} DFS of one
 * newly confirmed main block, and {@link #onUnsetMain} closes the bracket on the unwind path;
 * {@link #onBlockApplied} is invoked once per block the DFS applies, in DFS order, after value
 * settlement. {@link #onBlockUnapplied} runs on the unwind path, in reverse DFS order, and is
 * deliberately NOT bracketed by any begin/end pair: an implementation must be able to undo a block
 * knowing only the block itself and the state recorded when it was applied.
 *
 * <p><b>Where P3 stops holding.</b> Symmetry is only as good as the caller's own unwind: P3 assumes
 * {@code unApplyBlock} actually reaches every block {@code applyBlock} reached. It does not always.
 * A throw out of the {@code applyBlock} DFS, or the {@code mainBlockFee < 0} early return in
 * {@code setMain}, leaves the main block with {@code BI_MAIN_REF} set but {@code ref == null}, and
 * {@code unApplyBlock} skips exactly those blocks — so CHAIN_L1 records already committed for that
 * main block's children are never undone and are stranded at a height that no longer confirms them.
 * This is inherited from {@code BlockchainImpl}'s pre-existing value-settlement asymmetry (the same
 * blocks keep their settled amounts), not introduced by the chain layer; it is a tracked follow-up
 * and deliberately not fixed in SP0a.
 *
 * <p><b>Crash consistency.</b> {@code BI_APPLIED} is persisted eagerly for stored blocks before the
 * chain records for the same block are committed, and the two stores are written independently, so a
 * crash in between loses that block's chain records: the block stays flagged applied and is never
 * re-offered to {@link #onBlockApplied} unless a deep reorg unapplies and re-applies it. This is on
 * a par with the ADDRESS and BLOCK stores, which are likewise not atomic with each other, and there
 * is no boot-time replay that would repair it. An accepted gap, recorded here so it is not mistaken
 * for a chain-layer invariant.
 *
 * <p><b>Raw blocks required.</b> {@link #onBlockApplied} and {@link #onBlockUnapplied} must be
 * handed blocks parsed from their 512 bytes ({@code new Block(XdagBlock)} or
 * {@code getBlockByHash(hash, true)}). A {@link Block} built from a {@code BlockInfo} alone
 * ({@code isRaw = false}) carries neither extension fields nor links and has no outputs, so a chain
 * implementation would silently classify it as carrying nothing and record no input at all — a
 * consensus divergence, not a visible error. The same holds for any block lookup an implementation
 * is given (e.g. for walking chunk chains).
 */
public interface ChainL1Hooks {

    void onSetMainBegin(long height, Block mainBlock);

    void onBlockApplied(Block block);

    void onSetMainEnd(long height, Block mainBlock);

    void onBlockUnapplied(Block block);

    void onUnsetMain(long height, Block mainBlock);

    ChainL1Hooks NOOP = new ChainL1Hooks() {
        @Override
        public void onSetMainBegin(long height, Block mainBlock) {
        }

        @Override
        public void onBlockApplied(Block block) {
        }

        @Override
        public void onSetMainEnd(long height, Block mainBlock) {
        }

        @Override
        public void onBlockUnapplied(Block block) {
        }

        @Override
        public void onUnsetMain(long height, Block mainBlock) {
        }
    };
}
