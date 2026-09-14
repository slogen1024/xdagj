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
package io.xdag.lane.l1;

import io.xdag.core.Block;

/**
 * The five points where {@code BlockchainImpl} hands control to the lane layer. Every
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
 * <p><b>Raw blocks required.</b> {@link #onBlockApplied} and {@link #onBlockUnapplied} must be
 * handed blocks parsed from their 512 bytes ({@code new Block(XdagBlock)} or
 * {@code getBlockByHash(hash, true)}). A {@link Block} built from a {@code BlockInfo} alone
 * ({@code isRaw = false}) carries neither extension fields nor links and has no outputs, so a lane
 * implementation would silently classify it as carrying nothing and record no input at all — a
 * consensus divergence, not a visible error. The same holds for any block lookup an implementation
 * is given (e.g. for walking chunk chains).
 */
public interface LaneL1Hooks {

    void onSetMainBegin(long height, Block mainBlock);

    void onBlockApplied(Block block);

    void onSetMainEnd(long height, Block mainBlock);

    void onBlockUnapplied(Block block);

    void onUnsetMain(long height, Block mainBlock);

    LaneL1Hooks NOOP = new LaneL1Hooks() {
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
