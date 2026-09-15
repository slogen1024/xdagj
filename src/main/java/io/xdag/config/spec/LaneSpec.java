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

package io.xdag.config.spec;

import io.xdag.core.XAmount;

/**
 * Lane (DAG-native channel contract) protocol parameters.
 *
 * <p>These parameters gate every lane hook wired into {@code setMain}/{@code applyBlock}
 * (see the SP0a design: principle P2, "channel semantics only activate in setMain, gated
 * by main-block height"). None of them ever change L1 block validity ({@code tryToConnect}
 * is untouched by lane semantics, per principle P1) — they only affect whether/how the
 * lane hooks classify and index extension fields (field code {@code 0x0F}) once a block
 * is confirmed as main.
 *
 * <p><b>Never set these on a shared network.</b> The three conf keys
 * {@code lane.chunk.maxPerChain}, {@code lane.wasm.maxBytes} and
 * {@code lane.chunk.feeMilliXdag} — and the activation height {@code lane.activation.height}
 * — are consensus parameters, not node-local tuning knobs: a node running a non-default
 * value computes different LANE_L1 verdicts from everyone else and silently forks the lane
 * state, with no error of its own to show for it. They exist for devnet and tests only;
 * {@code AbstractConfig.getSetting()} logs a warning whenever a conf file supplies one.
 */
public interface LaneSpec {

    /**
     * Protocol default for {@code lane.chunk.maxPerChain}: the largest number of CHUNK
     * blocks a single chunk chain (code chain or args chain) may hold, on every network,
     * absent a conf-file override.
     *
     * <p>Shared with {@code io.xdag.lane.ext.LaneBlockBuilder#MAX_CHUNKS_PER_CHAIN} so the
     * two never drift apart.
     */
    int DEFAULT_MAX_CHUNKS_PER_CHAIN = 4096;

    /**
     * The maximum size, in bytes, of the inline argument payload a CALL/DEPLOY ext may
     * carry directly in its fixed block fields (larger argument sets must instead reference
     * an off-block args chain via {@code argsChainHead}).
     *
     * <p>Fixed by the 512-byte block wire format, not configurable: mirrors
     * {@code io.xdag.lane.ext.CallExt#MAX_INLINE_ARGS}, which is derived as
     * {@code (XDAG_BLOCK_FIELDS - 8) * ExtCodec.FIELD = 256}. There is deliberately no
     * {@code lane.args.maxInline} conf key — changing this value without changing the wire
     * format would make blocks that fit one node's inline-args bound and not another's.
     */
    int LANE_MAX_INLINE_ARGS = 256;

    /**
     * The main-block height at which the lane protocol activates.
     *
     * <p>Unit: main block height (same domain as {@link io.xdag.core.XdagStats#nmain}).
     * Protocol default: {@code 0} on devnet (active from genesis); {@link Long#MAX_VALUE}
     * on testnet and mainnet (unscheduled — the shared networks have no hard-fork height
     * yet). Before this height every lane hook is a no-op and {@code LANE_L1} stays empty.
     *
     * <p><b>Consensus-relevant:</b> yes. This is the hard-fork activation height for the
     * lane protocol; changing it changes what every node computes as canonical state from
     * this height onward.
     *
     * @return the activation height (a main-block height, inclusive)
     */
    long getLaneActivationHeight();

    /**
     * Overrides the activation height programmatically (e.g. from tests and tooling).
     *
     * <p>Unit: main block height. Calling this clears any conf-file-sourced override
     * captured by {@code AbstractConfig.getSetting()} at construction time, so the value
     * passed here always takes effect.
     *
     * <p><b>Consensus-relevant:</b> yes (see {@link #getLaneActivationHeight()}). Production
     * callers must set this before the kernel starts importing/validating blocks — flipping
     * it while the node is running can make already-applied blocks retroactively disagree
     * with newly applied ones.
     *
     * @param height the new activation height
     */
    void setLaneActivationHeight(long height);

    /**
     * The maximum number of CHUNK blocks a single chunk chain (code chain or args chain)
     * may hold.
     *
     * <p>Unit: count of chunks. Protocol default: {@link #DEFAULT_MAX_CHUNKS_PER_CHAIN}
     * ({@code 4096}), identical on every network — 4096 chunks &times; 352 bytes/chunk
     * ({@code io.xdag.lane.ext.ChunkExt#MAX_DATA_LEN}) &asymp; 1,441,792 bytes, i.e.
     * about 1.44 MB (1.375 MiB) of payload at most.
     *
     * <p><b>Consensus-relevant:</b> yes. It bounds how large a DEPLOY's code chain or a
     * CALL's args chain may grow before assembly is rejected as {@code CODE_TOO_LARGE}/
     * {@code INVALID_FORMAT}; every node must agree on this limit to agree on which
     * DEPLOY/CALL inputs are valid.
     *
     * @return the maximum chunk count per chain
     */
    int getLaneMaxChunksPerChain();

    /**
     * The maximum size, in bytes, of the assembled WASM bytecode a DEPLOY ext may install.
     *
     * <p>Unit: bytes. Protocol default: {@code 1024 * 1024} (1 MiB), identical on every
     * network.
     *
     * <p><b>Consensus-relevant:</b> yes. Code larger than this is rejected under the SP0a
     * spec's checkDeploy rule with {@code CODE_TOO_LARGE}; every node must agree on this
     * bound to agree on which DEPLOY inputs are valid.
     *
     * @return the maximum WASM code size in bytes
     */
    int getLaneMaxWasmBytes();

    /**
     * The maximum size, in bytes, of the inline argument payload a CALL/DEPLOY ext may
     * carry directly (larger argument sets must instead reference an off-block args chain
     * via {@code argsChainHead}).
     *
     * <p>Unit: bytes. Always {@link #LANE_MAX_INLINE_ARGS} ({@code 256}) on every network:
     * this bound is derived from the fixed 512-byte block layout, not a conf key — there is
     * no {@code lane.args.maxInline} setting to override it.
     *
     * <p><b>Consensus-relevant:</b> yes. It is part of the wire-format/validity envelope
     * for CALL and DEPLOY exts that every node must agree on.
     *
     * @return the maximum inline argument size in bytes ({@link #LANE_MAX_INLINE_ARGS})
     */
    int getLaneMaxInlineArgs();

    /**
     * The per-chunk fee rate a block must pay (via its {@code header.fee}) to cover every
     * chunk directly linked from it, before the lane hooks accept the input.
     *
     * <p>Unit: {@link XAmount} (native XDAG amount). Protocol default:
     * {@code XAmount.of(10, XUnit.MILLI_XDAG)} (0.01 XDAG per chunk), identical on every
     * network.
     *
     * <p><b>Consensus-relevant:</b> yes, at apply time. The SP0a spec's checkFee rule
     * (implemented as {@code feeCovers} in the L1 processor) requires
     * {@code header.fee >= chunkFee * (linked chunk count)}; a block that pays less is
     * classified with {@code InputStatus.INVALID_FEE} by every node applying the same
     * rule — it never affects raw L1 block validity ({@code tryToConnect}), only the
     * lane-level input classification recorded in {@code LANE_L1}.
     *
     * @return the fee owed per linked chunk
     */
    XAmount getLaneChunkFee();
}
