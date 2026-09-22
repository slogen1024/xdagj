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
 * Chain (DAG-native channel contract) protocol parameters.
 *
 * <p>With the node-local exceptions ({@link #getChainConsistencyWindow()} and the SP0b-2 ingest and
 * persistence keys, {@link #getChainIngestThreads()} through {@link #getChainPersistReadCache()}),
 * these parameters gate every chain hook wired into {@code setMain}/{@code applyBlock}
 * (see the SP0a design: principle P2, "channel semantics only activate in setMain, gated
 * by main-block height"). None of them ever change L1 block validity ({@code tryToConnect}
 * is untouched by chain semantics, per principle P1) — they only affect whether/how the
 * chain hooks classify and index extension fields (field code {@code 0x0F}) once a block
 * is confirmed as main.
 *
 * <p><b>Never set these on a shared network.</b> The three conf keys
 * {@code chain.chunk.maxPerChain}, {@code chain.wasm.maxBytes} and
 * {@code chain.chunk.feeMilliXdag} — and the activation height {@code chain.activation.height}
 * — are consensus parameters, not node-local tuning knobs: a node running a non-default
 * value computes different CHAIN_L1 verdicts from everyone else and silently forks the chain
 * state, with no error of its own to show for it. They exist for devnet and tests only;
 * {@code AbstractConfig.getSetting()} logs a warning whenever a conf file supplies one.
 */
public interface ChainSpec {

    /**
     * Protocol default for {@code chain.chunk.maxPerChain}: the largest number of CHUNK
     * blocks a single chunk chain (code chain or args chain) may hold, on every network,
     * absent a conf-file override.
     *
     * <p>Shared with {@code io.xdag.chain.ext.ChainBlockBuilder#MAX_CHUNKS_PER_CHAIN} so the
     * two never drift apart.
     */
    int DEFAULT_MAX_CHUNKS_PER_CHAIN = 4096;

    /** Node-local default for {@code chain.consistency.window} (main blocks scanned below the tip at boot). */
    int DEFAULT_CONSISTENCY_WINDOW = 128;

    /** Node-local defaults for the SP0b-2 ingest pipeline and write-behind persistence. */
    int DEFAULT_INGEST_THREADS = Runtime.getRuntime().availableProcessors();
    int DEFAULT_INGEST_QUEUE = 4096;
    int DEFAULT_PERSIST_MAX_PENDING = 4096;
    int DEFAULT_PERSIST_FLUSH_MS = 20;
    int DEFAULT_PERSIST_FLUSH_ENTRIES = 256;
    int DEFAULT_PERSIST_READ_CACHE = 65536;

    /** Node-local defaults for the SP0b-3 orphan pool: the whole pool, then its four category tiers. */
    int DEFAULT_ORPHAN_POOL_LIMIT = 100000;
    int DEFAULT_ORPHAN_ACCOUNT_TX_LIMIT = 3750;
    int DEFAULT_ORPHAN_MTX_LIMIT = 3750;
    int DEFAULT_ORPHAN_CHUNK_LIMIT = 60000;
    int DEFAULT_ORPHAN_LINK_LIMIT = 30000;

    /** Node-local defaults for the two sub-tiers that carve up the CHUNK tier, by peer and by chain. */
    int DEFAULT_ORPHAN_CHUNK_PER_PEER = 5000;
    int DEFAULT_ORPHAN_CHUNK_PER_CHAIN = 20000;

    /**
     * Retention for a CHUNK block, in epochs.
     *
     * <p>NOT a free tunable below {@link #MIN_ORPHAN_CHUNK_TTL_EPOCHS}: the chunk age rule (see the
     * "Age rule" section of {@code io.xdag.chain.ext.ChunkChain}) accepts a chunk only from the
     * paying block's epoch or the one immediately before it, so two epochs is the floor at which
     * eviction can no longer change a consensus outcome. Below it this node would evict a chunk
     * another node still holds, and would then reject a chain that node accepts.
     */
    int DEFAULT_ORPHAN_CHUNK_TTL_EPOCHS = 2;

    /**
     * The hard floor under {@code chain.orphan.chunkTtlEpochs}, refused at startup rather than
     * clamped. Anyone tempted to lower it should read {@link #DEFAULT_ORPHAN_CHUNK_TTL_EPOCHS}
     * first: one epoch of retention is a consensus divergence, not a tighter memory budget.
     */
    int MIN_ORPHAN_CHUNK_TTL_EPOCHS = 2;

    /** Node-local default for {@code chain.ingest.feePolicy}: the import-time fee gate is on. */
    boolean DEFAULT_INGEST_FEE_POLICY = true;

    /**
     * The maximum size, in bytes, of the inline argument payload a CALL/DEPLOY ext may
     * carry directly in its fixed block fields (larger argument sets must instead reference
     * an off-block args chain via {@code argsChainHead}).
     *
     * <p>Fixed by the 512-byte block wire format, not configurable: mirrors
     * {@code io.xdag.chain.ext.CallExt#MAX_INLINE_ARGS}, which is derived as
     * {@code (XDAG_BLOCK_FIELDS - 8) * ExtCodec.FIELD = 256}. There is deliberately no
     * {@code chain.args.maxInline} conf key — changing this value without changing the wire
     * format would make blocks that fit one node's inline-args bound and not another's.
     */
    int CHAIN_MAX_INLINE_ARGS = 256;

    /**
     * The main-block height at which the chain protocol activates.
     *
     * <p>Unit: main block height (same domain as {@link io.xdag.core.XdagStats#nmain}).
     * Protocol default: {@code 0} on devnet (active from genesis); {@link Long#MAX_VALUE}
     * on testnet and mainnet (unscheduled — the shared networks have no hard-fork height
     * yet). Before this height every chain hook is a no-op and {@code CHAIN_L1} stays empty.
     *
     * <p><b>Consensus-relevant:</b> yes. This is the hard-fork activation height for the
     * chain protocol; changing it changes what every node computes as canonical state from
     * this height onward.
     *
     * @return the activation height (a main-block height, inclusive)
     */
    long getChainActivationHeight();

    /**
     * Overrides the activation height programmatically (e.g. from tests and tooling).
     *
     * <p>Unit: main block height. Calling this clears any conf-file-sourced override
     * captured by {@code AbstractConfig.getSetting()} at construction time, so the value
     * passed here always takes effect.
     *
     * <p><b>Consensus-relevant:</b> yes (see {@link #getChainActivationHeight()}). Production
     * callers must set this before the kernel starts importing/validating blocks — flipping
     * it while the node is running can make already-applied blocks retroactively disagree
     * with newly applied ones.
     *
     * @param height the new activation height
     */
    void setChainActivationHeight(long height);

    /**
     * The maximum number of CHUNK blocks a single chunk chain (code chain or args chain)
     * may hold.
     *
     * <p>Unit: count of chunks. Protocol default: {@link #DEFAULT_MAX_CHUNKS_PER_CHAIN}
     * ({@code 4096}), identical on every network — 4096 chunks &times; 352 bytes/chunk
     * ({@code io.xdag.chain.ext.ChunkExt#MAX_DATA_LEN}) &asymp; 1,441,792 bytes, i.e.
     * about 1.44 MB (1.375 MiB) of payload at most.
     *
     * <p><b>Consensus-relevant:</b> yes. It bounds how large a DEPLOY's code chain or a
     * CALL's args chain may grow before assembly is rejected as {@code CODE_TOO_LARGE}/
     * {@code INVALID_FORMAT}; every node must agree on this limit to agree on which
     * DEPLOY/CALL inputs are valid.
     *
     * @return the maximum chunk count per chain
     */
    int getChainMaxChunksPerChain();

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
    int getChainMaxWasmBytes();

    /**
     * The maximum size, in bytes, of the inline argument payload a CALL/DEPLOY ext may
     * carry directly (larger argument sets must instead reference an off-block args chain
     * via {@code argsChainHead}).
     *
     * <p>Unit: bytes. Always {@link #CHAIN_MAX_INLINE_ARGS} ({@code 256}) on every network:
     * this bound is derived from the fixed 512-byte block layout, not a conf key — there is
     * no {@code chain.args.maxInline} setting to override it.
     *
     * <p><b>Consensus-relevant:</b> yes. It is part of the wire-format/validity envelope
     * for CALL and DEPLOY exts that every node must agree on.
     *
     * @return the maximum inline argument size in bytes ({@link #CHAIN_MAX_INLINE_ARGS})
     */
    int getChainMaxInlineArgs();

    /**
     * The per-chunk fee rate a block must pay (via its {@code header.fee}) to cover every
     * chunk directly linked from it, before the chain hooks accept the input.
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
     * chain-level input classification recorded in {@code CHAIN_L1}.
     *
     * @return the fee owed per linked chunk
     */
    XAmount getChainChunkFee();

    /**
     * How many main heights below the tip the startup consistency check scans for main blocks
     * left without a self reference (see {@code io.xdag.chain.repair.ChainConsistencyCheck}).
     *
     * <p>Unit: main blocks. Default: {@link #DEFAULT_CONSISTENCY_WINDOW}. May be set in the conf
     * as {@code chain.consistency.window}; must be positive.
     *
     * <p><b>Consensus-relevant:</b> no (node-local) — it changes only what this node inspects at
     * boot, never a judgement other nodes must share.
     *
     * @return the scan window in main blocks, always positive
     */
    int getChainConsistencyWindow();

    /** Pre-validation threads ({@code chain.ingest.threads}); 0 disables the pipeline. Node-local. */
    int getChainIngestThreads();

    /** Bound of the pipeline's receive queue ({@code chain.ingest.queue}); positive. Node-local. */
    int getChainIngestQueue();

    /** Bound of the write-behind stream ({@code chain.persist.maxPending}); 0 disables write-behind. Node-local. */
    int getChainPersistMaxPending();

    /** Maximum delay before a queued write reaches the database ({@code chain.persist.flushMs}); positive. Node-local. */
    int getChainPersistFlushMs();

    /** Writes per group ({@code chain.persist.flushEntries}); positive. Node-local. */
    int getChainPersistFlushEntries();

    /** INDEX read cache entries ({@code chain.persist.readCache}); 0 disables it. Node-local. */
    int getChainPersistReadCache();

    /** Entries the whole orphan pool may hold ({@code chain.orphan.poolLimit}); positive. Node-local. */
    int getChainOrphanPoolLimit();

    /** Account-transaction tier bound ({@code chain.orphan.accountTxLimit}); positive. Node-local. */
    int getChainOrphanAccountTxLimit();

    /** Chain-transaction (mtx) tier bound ({@code chain.orphan.mtxLimit}); positive. Node-local. */
    int getChainOrphanMtxLimit();

    /** CHUNK tier bound ({@code chain.orphan.chunkLimit}); positive. Node-local. */
    int getChainOrphanChunkLimit();

    /** Link-block tier bound ({@code chain.orphan.linkLimit}); positive. Node-local. */
    int getChainOrphanLinkLimit();

    /** CHUNK entries one peer may hold ({@code chain.orphan.chunkPerPeer}); at most the CHUNK tier. Node-local. */
    int getChainOrphanChunkPerPeer();

    /** CHUNK entries one chunk chain may hold ({@code chain.orphan.chunkPerChain}); at most the CHUNK tier. Node-local. */
    int getChainOrphanChunkPerChain();

    /**
     * How long a CHUNK entry is retained, in epochs ({@code chain.orphan.chunkTtlEpochs}).
     *
     * <p>The one key here that is not merely node-local below its floor: values under
     * {@link #MIN_ORPHAN_CHUNK_TTL_EPOCHS} are refused at startup, because they would make this
     * node reject chunk chains other nodes accept.
     */
    int getChainOrphanChunkTtlEpochs();

    /** Whether the import-time chunk fee gate is applied ({@code chain.ingest.feePolicy}). Node-local. */
    boolean isChainIngestFeePolicy();
}
