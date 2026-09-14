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
import io.xdag.lane.ext.Classified;

/**
 * Per-kind L1 semantics for {@code ANCHOR} / {@code BOND} / {@code CHALLENGE} / {@code CLAIM}. SP0a
 * ships no implementations; SP2/SP3 register theirs with
 * {@link LaneL1Processor#registerHandler(io.xdag.lane.ext.ExtKind, LaneKindHandler)}.
 *
 * <p>{@code CALL}, {@code DEPLOY} and {@code CHUNK} are built into {@link LaneL1Processor} and are
 * not pluggable; registering a handler for them is rejected.
 *
 * <p>{@link #onApplied} is invoked only inside an activated {@code setMain} (hence the non-null
 * {@link ApplyContext}); {@link #onUnapplied} runs on the unwind path outside any {@code setMain}
 * and therefore gets no context. An implementation must be reorg-symmetric (SP0a principle P3) and
 * must depend only on the block's raw bytes, the {@code LANE_L1} state at that moment, and the
 * protocol parameters — never on wall-clock time or any node-local state.
 *
 * <p><b>{@code onUnapplied} may see a block {@code onApplied} never saw.</b> The processor
 * dispatches {@link #onUnapplied} for any block whose classified kind is handled, unconditionally
 * — the unwind path has no apply context and therefore no way to know whether this block's height
 * was ever active, so it cannot tell whether the matching {@link #onApplied} actually ran. An
 * implementation must therefore be undo-idempotent: {@link #onUnapplied} must be safe to call for
 * a block it never applied, which means it must be keyed on its own recorded state — undoing only
 * what a stored record proves it did — rather than assuming the pairing with {@link #onApplied} is
 * exact.
 *
 * <p>Both methods receive the single {@code LANE_L1} batch the processor is building for this
 * block (principle P5: one batch, one commit, per applied or unapplied block); an implementation
 * writes its own state into that batch rather than committing anything itself.
 */
public interface LaneKindHandler {

    void onApplied(Block block, Classified classified, ApplyContext ctx, LaneL1Batch batch);

    void onUnapplied(Block block, Classified classified, LaneL1Batch batch);
}
