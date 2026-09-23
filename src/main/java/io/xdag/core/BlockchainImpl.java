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

import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ingest.PreValidated;
import io.xdag.chain.ingest.PreValidator;
import io.xdag.chain.l1.ChainL1Hooks;
import io.xdag.chain.l1.ChainL1Processor;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.orphan.OrphanAdmission;
import io.xdag.chain.orphan.OrphanCategory;
import io.xdag.chain.repair.ChainConsistencyCheck;
import io.xdag.config.MainnetConfig;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.XdagField.FieldType;
import io.xdag.consensus.RandomX;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PublicKey;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;
import io.xdag.db.*;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.db.rocksdb.SnapshotStoreImpl;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Listener;
import io.xdag.listener.PretopMessage;
import io.xdag.net.Peer;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static io.xdag.config.Constants.*;
import static io.xdag.config.Constants.MessageType.NEW_LINK;
import static io.xdag.config.Constants.MessageType.PRE_TOP;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.XdagField.FieldType.*;
import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.BytesUtils.*;
import static io.xdag.utils.BytesUtils.equalBytes;
import static io.xdag.utils.WalletUtils.checkAddress;

@Slf4j
@Getter
public class BlockchainImpl implements Blockchain {

    // Static gas fee accumulator
    private static XAmount sumGas = XAmount.ZERO;

    /**
     * The address an orphan with no account behind it is filed under: twenty zero bytes, which is
     * what {@code OrphanBlockStore.addOrphan} writes into the ORPHANIND row for a link or main
     * transaction block. Shared and never handed out, so it cannot be written through.
     */
    private static final byte[] NO_ORPHAN_ADDRESS = new byte[20];

    // Thread factory for main chain checking
    private static final ThreadFactory factory = BasicThreadFactory.builder()
            .namingPattern("check-main-%d")
            .daemon(true)
            .build();

    // Wallet instance
    private final Wallet wallet;

    // Storage components
    private final AddressStore addressStore;
    private final BlockStore blockStore;
    private final TransactionHistoryStore txHistoryStore;

    // Store for non-Extra orphan blocks
    private final OrphanBlockStore orphanBlockStore;

    /**
     * SP0b-2 §3.2: a consensus state transition drains the write-behind stream and then writes
     * straight to the databases, so the on-disk state a transition leaves behind is exactly the one
     * it would leave without the write-behind layer. {@link PersistControl#NONE} when there is no
     * such layer, which makes every wrapper below a plain call.
     */
    private final PersistControl persist;

    /**
     * D2: written only under the blockchain monitor, but read with no synchronization at all by
     * {@link #getBlockByHash}, which is public and reached from netty I/O threads (the two P2P
     * serve paths), RPC, the CLI and the award thread. As a plain {@code LinkedHashMap} that was
     * both a data race — a {@code get} concurrent with a structural modification can return a
     * spurious null, so a peer's request for a freshly arrived extra block goes unanswered — and a
     * missing happens-before edge, which let a reader see a half-published block.
     *
     * <p>{@code Collections.synchronizedMap} rather than a {@code ConcurrentHashMap}: {@link
     * #processExtraBlock()} evicts the oldest extra block by taking the map's first entry, which is
     * {@code LinkedHashMap}'s insertion order. Keeping the {@code LinkedHashMap} keeps that
     * behaviour exactly, where a {@code ConcurrentHashMap} would need a second, separately
     * maintained key deque to say the same thing. Every reader now goes through the map's own lock;
     * only iteration has to say so explicitly, and the one place that iterates does.
     */
    // In-memory pools and maps
    private final Map<Bytes, Block> memOrphanPool = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Bytes, Integer> memOurBlocks = new ConcurrentHashMap<>();

    // Stats and status tracking
    private final XdagStats xdagStats;
    private final Kernel kernel;
    private final XdagTopStatus xdagTopStatus;

    // Main chain checking components
    private final ScheduledExecutorService checkLoop;
    private final RandomX randomx;
    private final List<Listener> listeners = Lists.newArrayList();
    private ScheduledFuture<?> checkLoopFuture;

    // Snapshot related fields
    private final long snapshotHeight;
    private SnapshotStore snapshotStore;
    private SnapshotStore snapshotAddressStore;
    private final XdagExtStats xdagExtStats;

    // roll back transaction
    @Getter
    private final Map<Bytes32, Bytes32> mBlockTx = new ConcurrentHashMap<>();
    @Getter
    private final Map<Bytes32, Long> mBlockTimedOut = new ConcurrentHashMap<>();
    private final ScheduledExecutorService rollBackLoop = Executors.newSingleThreadScheduledExecutor();

    private List<Block> rollTxList = new LinkedList<>();

    private final Cache<Bytes32, Byte> syncTxStatusCache = CacheBuilder.newBuilder()
            .maximumSize(500000)
            .expireAfterWrite(60, TimeUnit.MINUTES)
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build();

    private static final Logger execLog = LogManager.getLogger("ExecutionStatusLog");

    @Getter
    private byte[] preSeed;

    /**
     * Chain contracts (SP0a): hooks invoked from setMain/applyBlock. Installed by this class's own
     * constructor from {@code kernel.getChainL1Store()}, before {@link #startCheckMain(long)} is
     * called: the check-main loop is scheduled with an initial delay of zero, so its very first
     * {@code checkNewMain -> setMain} can run before the constructor's caller gets control back.
     * Wiring the processor from outside (as the kernel used to) would therefore let a main block be
     * confirmed while these hooks were still {@link ChainL1Hooks#NOOP}, and that block's chain inputs
     * would be missing from CHAIN_L1 forever. Stays NOOP when the kernel has no chain store (tests
     * that build a blockchain without one).
     */
    private volatile ChainL1Hooks chainHooks = ChainL1Hooks.NOOP;

    // Constructor initializes all components and starts main chain checking
    public BlockchainImpl(Kernel kernel) {
        // Initialize core components
        this.kernel = kernel;
        this.wallet = kernel.getWallet();
        this.xdagExtStats = new XdagExtStats();

        // Initialize storage components
        this.addressStore = kernel.getAddressStore();
        this.blockStore = kernel.getBlockStore();
        this.orphanBlockStore = kernel.getOrphanBlockStore();
        this.txHistoryStore = kernel.getTxHistoryStore();
        // SP0b-2: read exactly here, before anything that can run a consensus transition — the
        // snapshot branch below calls initSnapshotJ(), and the check-main loop started at the end of
        // this constructor can reach setMain before the caller gets control back. NONE (a no-op both
        // ways) whenever no write-behind layer is installed. Note that `this` escapes to that
        // scheduler before the constructor returns, so final-field freeze does not cover the
        // check-main thread; what does cover it is the monitor — startCheckMain publishes through
        // the executor's queue, and every transition on that thread enters a synchronized wrapper.
        PersistControl control = kernel.getPersist();
        this.persist = control == null ? PersistControl.NONE : control;
        snapshotHeight = kernel.getConfig().getSnapshotSpec().getSnapshotHeight();

        // Initialize snapshot if enabled
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()
                && kernel.getConfig().getSnapshotSpec().getSnapshotHeight() > 0
                && !blockStore.isSnapshotBoot()) {

            // Chain contracts (SP0a): CHAIN_L1 travels with the snapshot and is mandatory once the
            // snapshot height is at or past the chain activation height (hash-verified on import).
            // This runs first, on EVERY snapshot boot: the isSnapshotJ() branch below is not the
            // only way in (--enablesnapshot false H T boots from a snapshot too, and the kernel
            // records setSnapshotBoot() either way), and failing here is also cheaper than failing
            // after the block/address imports.
            ChainL1SnapshotGate.checkAndImport(kernel.getConfig(), snapshotHeight, kernel.getChainL1Store());

            this.xdagStats = new XdagStats();
            this.xdagTopStatus = new XdagTopStatus();

            if (kernel.getConfig().getSnapshotSpec().isSnapshotJ()) {
                initSnapshotJ();
            }

            // Save latest snapshot state
            blockStore.saveXdagTopStatus(xdagTopStatus);
            blockStore.saveXdagStatus(xdagStats);

            // SP0b-1: a freshly re-seeded block store is complete up to the tip it actually carries.
            // That is xdagStats.nmain, not the configured snapshotHeight: on the SnapshotJ path
            // initSnapshotJ() has just set nmain = snapshotHeight so the two agree, but when
            // SnapshotJ is off nothing was imported and nmain is still 0 — claiming snapshotHeight
            // there would mark a completion that never happened.
            blockStore.saveLastCompletedMain(xdagStats.nmain);
            // I2 (SP0b-1): the re-seeded chain supersedes whatever the store was in the middle of,
            // so a stale MAIN_IN_FLIGHT record from before the re-seed must not outlive it. (A
            // re-seed belongs in an empty store directory; this only covers re-seeding in place.)
            blockStore.clearMainInFlight();

        } else {
            // Load existing state
            XdagStats storedStats = blockStore.getXdagStatus();
            XdagTopStatus storedTopStatus = blockStore.getXdagTopStatus();

            if (storedStats != null) {
                storedStats.setNwaitsync(0);
                this.xdagStats = storedStats;
                this.xdagStats.nextra = 0;
            } else {
                this.xdagStats = new XdagStats();
            }

            this.xdagTopStatus = Objects.requireNonNullElseGet(storedTopStatus, XdagTopStatus::new);

            Block lastBlock = getBlockByHeight(xdagStats.nmain);
            if (lastBlock != null) {
                xdagStats.setMaxdifficulty(lastBlock.getInfo().getDifficulty());
                xdagStats.setDifficulty(lastBlock.getInfo().getDifficulty());
                xdagTopStatus.setTop(lastBlock.getHashLow().toArray());
                xdagTopStatus.setTopDiff(lastBlock.getInfo().getDifficulty());
                // No chainVersion bump needed here, unlike initSnapshotJDirect: the candidate cache
                // is still cold (candidateVersion = -1), so the first cachedCandidate() walks anyway.
            }
            preSeed = blockStore.getPreSeed();
        }

        // Initialize RandomX
        randomx = kernel.getRandomx();
        if (randomx != null) {
            randomx.setBlockchain(this);
        }

        // SP0b-1: refuse to run on a main chain the chain hooks cannot trust. Both boot paths reach
        // this point with xdagStats loaded (the snapshot branch wrote the marker itself, so it never
        // reports markerInitialized here), and both reach it before anything can confirm a block.
        ChainSpec spec = kernel.getConfig().getChainSpec();
        ChainConsistencyCheck.Report report = ChainConsistencyCheck.run(blockStore, xdagStats, spec,
                spec.getChainConsistencyWindow());
        if (report.markerInitialized()) {
            // A store from before SP0b-1, or a brand-new one: adopt the tip as the last complete
            // height, once, so the next boot has a marker to verify against (the check already
            // warned that the history below it cannot be verified). The value written is the one
            // the check adopted, so the store can never disagree with the report just logged.
            blockStore.saveLastCompletedMain(report.marker());
        }
        kernel.recordConsistencyReport(report);
        if (!report.clean()) {
            if (kernel.isRepairMode()) {
                log.warn("repair mode: {}", report.describe());
            } else {
                // Logged as well as thrown: the exception reaches the operator's terminal through
                // XdagCli.start(), the log line is what survives in the node's log for forensics.
                log.error("main chain consistency check failed: {}", report.describeForBoot());
                // Deliberately not swallowed: on the kernel path this aborts startup, and
                // XdagCli.start() catches Exception around startKernel(), prints getMessage() and
                // exits -1 -- so describeForBoot()'s repair hint is what the operator actually sees.
                throw new IllegalStateException(report.describeForBoot());
            }
        } else {
            // Every boot leaves nmain and the marker in the log, clean or not: the cheapest
            // forensics there is when a later boot does turn out to be inconsistent.
            log.info("{}", report.describe());
        }

        // Chain contracts (SP0a): install the hooks before the check-main loop can confirm anything.
        // kernel.getChainKindHandlers() is THE registration point for the SP2/SP3 per-kind semantics:
        // it is read exactly here, once, before any hook can run (the processor rejects a handler
        // registered after its first hook), so a handler must be in the kernel's map before
        // new BlockchainImpl(kernel).
        ChainL1Store chainStore = kernel.getChainL1Store();
        if (chainStore != null) {
            ChainL1Processor processor = new ChainL1Processor(chainStore, spec,
                    hash -> getBlockByHash(hash, true));
            kernel.getChainKindHandlers().forEach(processor::registerHandler);
            this.chainHooks = processor;
        }

        // Start main chain checking
        checkLoop = new ScheduledThreadPoolExecutor(1, factory);
        // SP0b-1: in repair mode nothing may be confirmed while the main chain is being unwound.
        if (!kernel.isRepairMode()) {
            this.startCheckMain(1024);
        }

        this.mBlockTx.clear();
        this.mBlockTimedOut.clear();
        this.startCleaner();
        List<Block> blocks = listMainBlocksByHeight(10);
        if (blocks != null) {
            blocks = blocks.reversed();
            this.saveMBlockTx(blocks);

        }
    }

    // Initialize snapshot data
    public synchronized void initSnapshotJ() {
        persist.direct(this::initSnapshotJDirect);
    }

    private void initSnapshotJDirect() {
        long start = System.currentTimeMillis();
        System.out.println("init snapshot...");

        // Initialize address snapshot store
        RocksdbKVSource snapshotAddressSource = new RocksdbKVSource("SNAPSHOT/ADDRESS");
        snapshotAddressStore = new SnapshotStoreImpl(snapshotAddressSource);
        snapshotAddressSource.setConfig(kernel.getConfig());
        snapshotAddressSource.init();
        snapshotAddressStore.saveAddress(this.blockStore, this.addressStore, this.txHistoryStore, kernel.getWallet().getAccounts(), kernel.getConfig().getSnapshotSpec().getSnapshotTime());

        // Initialize block snapshot store
        RocksdbKVSource snapshotSource = new RocksdbKVSource("SNAPSHOT/BLOCKS");
        snapshotStore = new SnapshotStoreImpl(snapshotSource);
        snapshotSource.setConfig(kernel.getConfig());
        snapshotStore.init();
        snapshotStore.saveSnapshotToIndex(this.blockStore, this.txHistoryStore, kernel.getWallet().getAccounts(), kernel.getConfig().getSnapshotSpec().getSnapshotTime());
        Block lastBlock = blockStore.getBlockByHeight(snapshotHeight);

        // Initialize stats
        xdagStats.balance = snapshotStore.getOurBalance();
        xdagStats.setNwaitsync(0);
        xdagStats.setNnoref(0);
        xdagStats.setNextra(0);
        xdagStats.setTotalnblocks(0);
        xdagStats.setNblocks(0);
        xdagStats.setTotalnmain(snapshotHeight);
        xdagStats.setNmain(snapshotHeight);
        xdagStats.setMaxdifficulty(lastBlock.getInfo().getDifficulty());
        xdagStats.setDifficulty(lastBlock.getInfo().getDifficulty());

        // Initialize top status
        xdagTopStatus.setPreTop(lastBlock.getHashLow().toArray());
        xdagTopStatus.setTop(lastBlock.getHashLow().toArray());
        xdagTopStatus.setTopDiff(lastBlock.getInfo().getDifficulty());
        xdagTopStatus.setPreTopDiff(lastBlock.getInfo().getDifficulty());
        bumpChainVersion(); // snapshot import rewrites flags and links without going through updateBlockFlag

        // Calculate total balance
        XAmount allBalance = snapshotStore.getAllBalance().add(snapshotAddressStore.getAllBalance());

        long end = System.currentTimeMillis();
        System.out.println("init snapshotJ done");
        System.out.println("time：" + (end - start) + "ms");
        System.out.println("Our balance: " + snapshotStore.getOurBalance().toDecimal(9, XUnit.XDAG).toPlainString());
        System.out.printf("All amount: %s%n", allBalance.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    // Register event listener
    @Override
    public void registerListener(Listener listener) {
        this.listeners.add(listener);
    }

    // Try to connect a new block to the chain
    @Override
    public ImportResult tryToConnect(Block block) {
        // inline(): the ECDSA of verifiedKeys() moves off the monitor, and parse()/getHashLow() are
        // materialised here rather than wherever inside the lock first asked for them. All of it
        // runs on the caller's thread and on the caller's own block -- inline() does not copy, so
        // the chain still imports this very instance, which is what every bare-Block caller (local
        // mining, the repair tools, syncPopBlock's re-import, the tests) expects: each of them owns
        // its block and no other thread is looking at it.
        return tryToConnect(PreValidator.inline(block));
    }

    /**
     * The import proper (SP0b-2). Everything the lock needs that is a pure function of the block's
     * bytes may already have been computed off the monitor; what {@code pv} does not carry is
     * recomputed here.
     *
     * <p>That is true of the facts, but not of the instance: a {@code pv} that carries an {@code
     * error} may alias the arriving wrapper's own {@code Block} — {@code PreValidated.failed} hands
     * back {@code wrapper.getBlock()} when the failure came before the private copy existed — so a
     * committer must route that one through a re-parse rather than through here. {@code
     * SyncManager.importPreValidated} does exactly that.
     *
     * <p>The block imported is {@code pv.block()}, which on the pipeline's path is a private copy
     * and never the instance the arriving {@code BlockWrapper} holds — this method mutates what it
     * imports and keeps it alive in {@code memOrphanPool}, while the relay re-serializes the
     * wrapper's own block.
     */
    @Override
    public synchronized ImportResult tryToConnect(PreValidated pv) {
        Block block = pv.block();
        // The two facts the orphan pool's quotas and its chunk clock need, taken once, here, and
        // carried to both places that must agree about them: the admission gate below and
        // dealOrphan at the end. Both null on a path that carries neither, which is correct rather
        // than degraded -- see peerKeyOf and OrphanBlockStore.addOrphan.
        String peerKey = peerKeyOf(pv);
        Classified classified = pv.classified();
        // Computed where the admission gate needs it and reused where the deferred persist does,
        // rather than worked out twice: the gate decides whether this block may occupy a chunk slot
        // and the commit below decides whether it is written to disk, and the two answering
        // differently is the imported-but-unpooled block orphanCategoryOf exists to prevent. Null
        // until the gate runs, because a BI_EXTRA block skips the gate; the commit fills it in.
        OrphanCategory category = null;

        // TODO: if current height is snapshot height, we need change logic to process new block

        try {
            ImportResult result = ImportResult.IMPORTED_NOT_BEST;

            // Validate block type
            long type = block.getType() & 0xf;
            if (kernel.getConfig() instanceof MainnetConfig) {
                if (type != XDAG_FIELD_HEAD.asByte()) {
                    result = ImportResult.ERROR;
                    result.setErrorInfo("Block type error, is not a mainnet block");
                    log.debug("Block type error, is not a mainnet block");
                    return result;
                }
            } else {
                if (type != XDAG_FIELD_HEAD_TEST.asByte()) {
                    result = ImportResult.ERROR;
                    result.setErrorInfo("Block type error, is not a testnet block");
                    log.debug("Block type error, is not a testnet block");
                    return result;
                }
            }

            // Validate block timestamp
            if (block.getTimestamp() > (XdagTime.getCurrentTimestamp() + MAIN_CHAIN_PERIOD / 4)
                    || block.getTimestamp() < kernel.getConfig().getXdagEra()
            ) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("Block's time is illegal");
                log.debug("Block's time is illegal");
                return result;
            }

            // Check if block already exists
            if (isExist(block.getHashLow())) {
                return ImportResult.EXIST;
            }

            if (isExistInMem(block.getHashLow())) {
                return ImportResult.IN_MEM;
            }

            // And the third place a block this node already holds can be: the orphan pool's chunk
            // body store. A chunk reaches neither the block store nor memOrphanPool, so without
            // this a peer could re-send the same chunk for ever and every copy would be imported
            // again -- nblocks and nnoref climbing once per delivery while the pool itself, which
            // refuses the repeat as a duplicate, stayed exactly where it was. EXIST rather than
            // IN_MEM because that is the honest statement (this node has the block) and because
            // SyncManager treats the two the same: both pop the waiting children.
            if (orphanBlockStore.getChunkBody(block.getHashLow()) != null) {
                return ImportResult.EXIST;
            }

            // Check if extra block
            if (isExtraBlock(block)) {
                updateBlockFlag(block, BI_EXTRA, true);
            }

            // Orphan-pool admission, per category (SP0b-3). This used to be
            // `isAccountTx(block) && getOrphanSize() >= MAX_ORPHAN_SIZE`: one count of all four
            // queues together, against one constant, consulted only when an account transaction
            // arrived. Link, chunk and mtx blocks were never asked, so they filled the pool for
            // free and the first category to be refused was the only one that had been checked.
            // Asking for the arriving block's own category makes that shape impossible: a full
            // category closes itself and nothing else.
            //
            // Placed here, after the existence checks and after the BI_EXTRA determination,
            // because those are what decide whether this block is ever going to occupy a pool
            // slot, and nothing above mutates any state:
            //
            //   - a block this node already holds (EXIST / IN_MEM) is not going to be pooled
            //     again, so a full pool is no reason to refuse it. Under the old account-tx-only
            //     gate that mattered rarely; now that link blocks are gated too it is the common
            //     case during a sync, where the same blocks arrive repeatedly.
            //   - a BI_EXTRA block is never pooled at all -- the import parks it in memOrphanPool
            //     and the removeOrphan that later evicts it does not call dealOrphan -- so gating
            //     one on a full LINK category would refuse traffic that costs the pool nothing.
            //     That traffic is every freshly mined block on the network, main blocks included,
            //     so gating it would let a link flood stop this node following the chain.
            //
            // The residual case is a block that is BI_EXTRA here and has the flag cleared further
            // down (a mined block carrying a non-zero-amount reference): it skips the gate and can
            // reach a full category, where the pool refuses it and it is imported unpooled. The
            // pool's refusal is the backstop for exactly that, and the shape cannot be reached by
            // any block this node's own builders produce -- a transaction block is built with
            // mining == false, so getNonce() is null and isExtraBlock() is false for it.
            if ((block.getInfo().flags & BI_EXTRA) == 0) {
                category = orphanCategoryOf(block, classified);
                if (orphanBlockStore.isFull(category)) {
                    result = ImportResult.INVALID_BLOCK;
                    result.setErrorInfo("Orphan block pool is full");
                    log.debug("Orphan block pool is full for category {}", category);
                    return result;
                }
            }

            if (isTxBlock(block) && XAmount.ZERO.compareTo(getTxFee(block)) == 0) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("There is a problem with the transaction fee of this transaction block");
                log.debug("Block's fee is illegal");
                return result;
            }

            // Validate block references
            List<Address> all = block.getLinks().stream().distinct().toList();
            int inputFieldCounter = 0;

            for (Address ref : all) {
                if (ref != null && !ref.isAddress) {
                    if (ref.getType() == XDAG_FIELD_OUT && !ref.getAmount().isZero()) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setHashlow(ref.getAddress());
                        result.setErrorInfo("Address's amount isn't zero");
                        log.debug("Address's amount isn't zero");
                        return result;
                    }
                    // The block store first, then the chunk bodies the pool is holding for blocks
                    // that were never written to it. Without the second half a chunk chain could
                    // not be received at all: chunk i names chunk i+1, and i+1 is memory-only until
                    // something pays for the chain, so every chunk past the tail would be
                    // NO_PARENT and the chain could never be completed or paid for.
                    //
                    // The getBlockByHash merge has landed and this fallback still stands, because
                    // the merge answers the RAW form only and this check is deliberately not the
                    // raw form. "Have you got this block" is satisfied here by a BlockInfo alone,
                    // and that is what lets a snapshot-bootstrapped node accept references to
                    // blocks from before its snapshot time -- it has their BlockInfo and not their
                    // bytes, so asking getBlockByHash(ref, true) instead would turn every one of
                    // those references into NO_PARENT. (It is also the reason ChunkChain has an age
                    // rule at all: see its class documentation.) So the two questions stay
                    // separate, and this is the one call site that needs both answered.
                    Block refBlock = getBlockByHash(ref.getAddress(), false);
                    if (refBlock == null) {
                        refBlock = pooledChunkBody(ref.getAddress());
                    }
                    if (refBlock == null) {
                        result = ImportResult.NO_PARENT;
                        result.setHashlow(ref.getAddress());
                        result.setErrorInfo("Block have no parent for " + result.getHashlow().toHexString());
                        log.debug("Block have no parent for {}", result.getHashlow().toHexString());
                        return result;
                    } else {
                        // Ensure ref block's time is earlier than block's time
                        if (refBlock.getTimestamp() >= block.getTimestamp()) {
                            result = ImportResult.INVALID_BLOCK;
                            result.setHashlow(refBlock.getHashLow());
                            result.setErrorInfo("Ref block's time >= block's time");
                            log.debug("Ref block's time >= block's time");
                            return result;
                        }
                        // Ensure TX block's amount is enough to subtract minGas, Amount must >= 0.1
                        if (ref.getType() == XDAG_FIELD_IN && ref.getAmount().subtract(getTxFee(block)).isNegative()) {
                            result = ImportResult.INVALID_BLOCK;
                            result.setHashlow(ref.getAddress());
                            result.setErrorInfo("Ref block's balance < fee");
                            log.debug("Ref block's balance < fee");
                            return result;
                        }
                    }
                } else {
                    // Ensure that there is only one input.
                    if (ref != null && ref.type == XDAG_FIELD_INPUT) {
                        inputFieldCounter = inputFieldCounter + 1;
                        if (inputFieldCounter > 1) {
                            result = ImportResult.INVALID_BLOCK;
                            result.setErrorInfo("The quantity of the input must be exactly one.");
                            log.debug("The quantity of the input must be exactly one.");
                            return result;
                        }
                    }
                    if (ref != null && ref.type == XDAG_FIELD_INPUT && !addressStore.addressIsExist(BytesUtils.byte32ToArray(ref.getAddress()).toArray())) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setErrorInfo("Address isn't exist " + Base58.encodeCheck(
                                BytesUtils.byte32ToArray(ref.getAddress())));
                        log.debug("Address isn't exist {}",
                                Base58.encodeCheck(BytesUtils.byte32ToArray(ref.getAddress())));
                        return result;
                    }
                    // Ensure TX block's input's & output's amount is enough to subtract minGas, Amount must >= 0.1
                    if (ref != null && (ref.getType() == XDAG_FIELD_INPUT || ref.getType() == XDAG_FIELD_OUTPUT)) {
                        if (getTxFee(block).isPositive() && outPutLimit(block).isPositive()) {
                            if (ref.getType() == XDAG_FIELD_INPUT && ref.getAmount().subtract(getTxFee(block)).isNegative()) {
                                result = ImportResult.INVALID_BLOCK;
                                result.setHashlow(ref.getAddress());
                                result.setErrorInfo("Ref input amount < Gas");
                                return result;
                            } else if (ref.getType() == XDAG_FIELD_OUTPUT && ref.getAmount().subtract(outPutLimit(block)).isNegative()) {
                                result = ImportResult.INVALID_BLOCK;
                                result.setHashlow(ref.getAddress());
                                result.setErrorInfo("Ref output amount < Gas");
                                log.debug("Ref output amount < Gas");
                                return result;
                            }
                        } else {
                            result = ImportResult.INVALID_BLOCK;
                            result.setErrorInfo("When constructing a block, the fee entered is illegal");
                            return result;
                        }
                    }
                }

                // Determine if ref is a block
                if (ref != null && compareAmountTo(ref.getAmount(), XAmount.ZERO) != 0) {
                    log.debug("Try to connect a tx Block:{}", block.getHash().toHexString());
                    updateBlockFlag(block, BI_EXTRA, false);
                }
            }

            if (isAccountTx(block)) {
                if(block.getTxNonceField() == null) {
                    result = ImportResult.INVALID_BLOCK;
                    result.setErrorInfo("Account transaction block must have nonce.");
                    return result;
                }
            } else if (isTxBlock(block)) {
                if(block.getTxNonceField() != null) {
                    result = ImportResult.INVALID_BLOCK;
                    result.setErrorInfo("The main block transaction block should not contain nonce.");
                    return result;
                }
            } else {
                if(block.getTxNonceField() != null) {
                    result = ImportResult.INVALID_BLOCK;
                    result.setErrorInfo("The main block or link block should not contain nonce.");
                    return result;
                }
            }

            // Reject before anything mutates. A block with no out-signature is malformed, and every
            // later step that reaches for it dereferences null: Block.verifiedKeys() does whenever
            // there are public-key fields to match against, and checkMineAndAdd does
            // unconditionally. Both of those sit past the link-removal loop below, where
            // removeOrphan deletes ORPHANIND rows, decrements nnoref and writes BI_REF into another
            // block's persisted BlockInfo — so a throw down there is a reject-AFTER-mutate that a
            // peer can aim at orphans of its choosing.
            //
            // Before SP0b-2, canUseInput computed verifiedKeys() unconditionally, so the
            // public-keys-but-no-signature shape threw exactly here and the catch(Throwable) below
            // turned it into ERROR with no state touched. SP0b-2's empty-inputs fast path is right
            // in itself — link and main blocks are most of the traffic and must not pay for an
            // ECDSA verification they cannot use — but it removed that accident. This restores the
            // verdict as an explicit rejection, and covers the no-public-keys variant too, which
            // reached checkMineAndAdd's null dereference even before SP0b-2.
            //
            // O(1), and that is the point: an ordinary link or main block carries no public-key
            // field at all (its signature is checked against the node's own wallet keys), so this
            // guard verifies nothing and costs nothing.
            if (block.getOutsig() == null) {
                result = ImportResult.INVALID_BLOCK;
                result.setHashlow(block.getHashLow());
                result.setErrorInfo("Block has no out-signature");
                log.debug("Block has no out-signature");
                return result;
            }

            // Validate block inputs
            if (!canUseInput(block, pv.hasKeys() ? pv.keys() : null)) {
                result = ImportResult.INVALID_BLOCK;
                result.setHashlow(block.getHashLow());
                result.setErrorInfo("Block's input can't be used");
                log.debug("Block's input can't be used");
                return ImportResult.INVALID_BLOCK;
            }

            if (category == null) {
                category = orphanCategoryOf(block, classified);
            }
            // The chunk chains this block references stop being memory-only here, before anything
            // reads them back. See persistReferencedChunkChains for why this is the place.
            persistReferencedChunkChains(block, all, category);

            int id = 0;
            // Remove links
            for (Address ref : all) {
                FieldType fType;
                if (!ref.isAddress) {
                    removeOrphan(ref.getAddress(),
                            (block.getInfo().flags & BI_EXTRA) != 0
                                    ? OrphanRemoveActions.ORPHAN_REMOVE_EXTRA
                                    : OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);

                    fType = ref.getType().equals(XDAG_FIELD_IN) ? XDAG_FIELD_OUT : XDAG_FIELD_IN;
                } else {
                    fType = ref.getType().equals(XDAG_FIELD_INPUT) ? XDAG_FIELD_OUTPUT : XDAG_FIELD_INPUT;
                }

                if (compareAmountTo(ref.getAmount(), XAmount.ZERO) != 0) {
                    if (fType.equals(XDAG_FIELD_OUT) || fType.equals(XDAG_FIELD_OUTPUT)) {
                        onNewTxHistory(ref.getAddress(), block.getHashLow(), fType, ref.getAmount(),
                                block.getTimestamp(), block.getInfo().getRemark(), ref.isAddress, id);
                    } else {
                        XAmount singleOutputFee = outPutLimit(block);
                        onNewTxHistory(ref.getAddress(), block.getHashLow(), fType, ref.getAmount().subtract(singleOutputFee),
                                block.getTimestamp(), block.getInfo().getRemark(), ref.isAddress, id);
                    }
                }
                id++;
            }

            // Check current main chain
            checkNewMain();

            // Check if block is ours
            if (checkMineAndAdd(block)) {
                log.debug("A block hash:{} become mine", block.getHashLow().toHexString());
                updateBlockFlag(block, BI_OURS, true);
            }

            // Calculate block difficulty
            BigInteger cuDiff = calculateCurrentBlockDiff(block);
            calculateBlockDiff(block, cuDiff);

            // Process extra blocks
            processExtraBlock();

            // Update main chain based on difficulty
            if (block.getInfo().getDifficulty().compareTo(xdagTopStatus.getTopDiff()) > 0) {
                // Fork chain
                long currentHeight = xdagStats.nmain;

                // Find common ancestor
                Block blockRef = findAncestor(block, isSyncFixFork(xdagStats.nmain));

                // Unwind main chain to ancestor
                unWindMain(blockRef);

                // Update new chain
                updateNewChain(block, isSyncFixFork(xdagStats.nmain));

                // Log unwind info
                if (currentHeight - xdagStats.nmain > 1) {
                    log.info("XDAG:Before unwind, height = {}, After unwind, height = {}, unwind number = {}",
                            currentHeight, xdagStats.nmain, currentHeight - xdagStats.nmain);
                }

                Block currentTop = getBlockByHash(xdagTopStatus.getTop() == null ? null :
                        Bytes32.wrap(xdagTopStatus.getTop()), false);
                BigInteger currentTopDiff = xdagTopStatus.getTopDiff();
                log.debug("update top: {}", block.getHashLow());

                // Update top status
                xdagTopStatus.setTopDiff(block.getInfo().getDifficulty());
                xdagTopStatus.setTop(block.getHashLow().toArray());

                // Update pre-top
                setPreTop(currentTop, currentTopDiff);

                // Notify PoW thread if needed
                if (XdagTime.getEpoch(block.getTimestamp()) < XdagTime.getCurrentEpoch()) {
                    onNewPretop();
                }

                result = ImportResult.IMPORTED_BEST;
                xdagStats.updateMaxDiff(xdagTopStatus.getTopDiff());
                xdagStats.updateDiff(xdagTopStatus.getTopDiff());
            }

            // Update block stats
            xdagStats.nblocks++;
            xdagStats.totalnblocks = Math.max(xdagStats.nblocks, xdagStats.totalnblocks);

            if ((block.getInfo().flags & BI_EXTRA) != 0) {
                block.getInfo().setFee(XAmount.ZERO);
                memOrphanPool.put(block.getHashLow(), block);
                xdagStats.nextra++;
            } else {
                if (category != OrphanCategory.CHUNK) {
                    saveBlock(block);
                }
                boolean admitted = dealOrphan(block, peerKey, classified);
                if (isHeldByThisNode(category, admitted)) {
                    xdagStats.nnoref++;
                }
            }
            blockStore.saveXdagStatus(xdagStats);

            // Log transaction info
            if (!block.getInputs().isEmpty()) {
                if ((block.getInfo().getFlags() & BI_OURS) != 0) {
                    log.info("XDAG:pool transaction(reward). block hash:{}", block.getHash().toHexString());
                }
            }

            // Update hashrate stats
            int i = (int) (XdagTime.getEpoch(block.getTimestamp()) & (HASH_RATE_LAST_MAX_TIME - 1));
            if (XdagTime.getEpoch(block.getTimestamp()) > XdagTime.getEpoch(xdagExtStats.getHashrate_last_time())) {
                xdagExtStats.getHashRateTotal()[i] = BigInteger.ZERO;
                xdagExtStats.getHashRateOurs()[i] = BigInteger.ZERO;
                xdagExtStats.setHashrate_last_time(block.getTimestamp());
            }

            if (cuDiff.compareTo(xdagExtStats.getHashRateTotal()[i]) > 0) {
                xdagExtStats.getHashRateTotal()[i] = cuDiff;
            }

            if ((block.getInfo().getFlags() & BI_OURS) != 0
                    && cuDiff.compareTo(xdagExtStats.getHashRateOurs()[i]) > 0) {
                xdagExtStats.getHashRateOurs()[i] = cuDiff;
            }

            return result;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            return ImportResult.ERROR;
        }
    }

    /**
     * Get the transaction block packaged from the main block of the forked chain.
     */
    public void rollTx(Block block) {
        List<Address> links = block.getLinks().reversed();

        for (Address link : links) {
            if (!link.isAddress && !link.getType().equals(XDAG_FIELD_IN)) {
                Block txBlock = getBlockByHash(link.getAddress(), true);
                if (block.getHashLow().equals(mBlockTx.get(link.addressHash)) || (txBlock.getInfo().getRef() != null && equalBytes(txBlock.getInfo().getRef(), block.getHashLow().toArray()))) {
                    if ((txBlock.getInfo().flags & BI_MAIN_CHAIN) == 0) {
                        rollTxList.add(txBlock);
                        if ((txBlock.getInfo().flags & BI_REF) != 0) {
                            txBlock=getBlockByHash(link.getAddress(),false);
                            updateBlockFlag(txBlock, BI_REF, false);
                            xdagStats.nnoref++;
                            blockStore.saveXdagStatus(xdagStats);
                        }
                        mBlockTx.remove(link.addressHash);
                        mBlockTimedOut.remove(link.addressHash);
                        log.debug("roll main block :{} , txBlock :{} , mBlockTx size :{}", block.getHashLow(), link.addressHash, mBlockTx.size());
                        continue;
                    }
                    List<Address> mTXs = txBlock.getLinks();
                    for (Address mTX : mTXs) {
                        if (mTX.getType().equals(XDAG_FIELD_IN)) {
                            mBlockTx.remove(link.addressHash);
                            mBlockTimedOut.remove(link.addressHash);
                            rollTxList.add(txBlock);
                            if ((txBlock.getInfo().flags & BI_REF) == 0) continue;
                            txBlock=getBlockByHash(link.getAddress(),false);
                            updateBlockFlag(txBlock, BI_REF, false);
                            xdagStats.nnoref++;
                            blockStore.saveXdagStatus(xdagStats);
                            log.debug("roll main txBlock :{} , txBlock :{} , mBlockTx size :{}", block.getHashLow(), link.addressHash, mBlockTx.size());
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Pools a block that arrived carrying nothing about its origin. In production its one caller is
     * the roll-back, which re-orphans a transaction block this node already holds: there is no peer
     * to name and no classification to pass, and a transaction block is never a chunk anyway.
     *
     * <p>Stays {@code void} where the three-argument form answers a question: that answer exists for
     * the import path's orphan counter, and the roll-back is not counting anything.
     */
    public void dealOrphan(Block block) {
        dealOrphan(block, null, null);
    }

    /**
     * Pools a block with what the import path learned about it. {@code peerKey} is the source
     * peer's IP or null; {@code classified} is the chain-extension classification or null.
     *
     * <p>Both travel all the way to {@code addOrphan} because the pool's two chunk-only quotas and
     * its epoch clock have no other way to learn them — a block carries neither its sender nor, as
     * far as the orphan store can see, its kind.
     *
     * <p>Answers {@link OrphanAdmission#ADMITTED} and nothing else, which is to say: did <em>this
     * call</em> put the block in the pool. {@link OrphanAdmission#DUPLICATE} is therefore false
     * along with every refusal, and so is a <em>non-chunk</em> block on a node that does not mine —
     * no PoW instance, or block generation turned off — because nothing went in there either.
     *
     * <p><b>{@link OrphanCategory#CHUNK} is the exception: the mining gate does not apply to it.</b>
     * Every other category is on disk by the time this runs — {@code tryToConnect} saved it — so
     * for them the pool is only the queue of work a miner draws references from, and a node that
     * does not mine buys nothing by filling it. A chunk has no disk copy at all: {@code
     * tryToConnect} skips {@code saveBlock} for it, so the pool is the only home it has. Gating
     * that on mining meant an explorer, a pure RPC node, or any node in the startup window before
     * its PoW instance exists stored an arriving chunk in neither place, and every block paying for
     * that chunk was {@code NO_PARENT} for ever.
     */
    public boolean dealOrphan(Block block, String peerKey, Classified classified) {
        // The address first because the category is computed from it, and then the pooling needs it
        // again: one walk of the links rather than two (see orphanCategoryOf).
        byte[] address = orphanAddressOf(block);
        // Pool it if it is a chunk -- this pool is a chunk's only home, so mining must not be what
        // decides whether this node keeps it -- or if this node mines, which is what the other
        // three categories are pooled for. See the javadoc.
        boolean chunk = orphanCategoryOf(block, address, classified) == OrphanCategory.CHUNK;
        boolean minesBlocks = kernel.getConfig().getEnableGenerateBlock() && kernel.getPow() != null;
        if (!(chunk || minesBlocks)) {
            return false;
        }
        UInt64 nonce = UInt64.ZERO;
        XAmount fee = getTxFee(block);
        if (address != null) {
            nonce = block.getTxNonceField().getTransactionNonce();
        }
        return getOrphanBlockStore().addOrphan(block, isTxBlock(block), nonce, fee, address,
                peerKey, classified) == OrphanAdmission.ADMITTED;
    }

    /**
     * Whether this node ends up holding the block anywhere — which is the thing {@code nnoref}
     * counts, and the condition an increment of it has to pair with.
     *
     * <p><b>Not "was it pooled", and the difference is the whole of it.</b> For every category but
     * {@link OrphanCategory#CHUNK} the block has just been written to the block store, and
     * {@code removeOrphan} decrements off that disk copy for any stored block not yet
     * {@code BI_REF} — without consulting the pool at all. Gating those on admission would take the
     * count one too low the first time anything referenced such a block. A chunk has no disk copy:
     * the pool is its only home, so one the pool turned away is held nowhere and its increment
     * could never be given back.
     *
     * <p>See {@code OrphanBlockStore#addOrphan} for why a chunk can be turned away at all after the
     * import path's gate has let it through, and for what the leak cost.
     *
     * @param admitted what {@link #dealOrphan(Block, String, Classified)} answered
     */
    private static boolean isHeldByThisNode(OrphanCategory category, boolean admitted) {
        return category != OrphanCategory.CHUNK || admitted;
    }

    /**
     * The key the orphan pool charges this block's source for, or null when it came from no peer.
     *
     * <p><b>The IP, never {@code getPeerId()}.</b> The id is cryptographically bound — the
     * handshake checks it is the Base58 address of the presented public key and verifies the
     * signature — so one peer cannot claim another's. That makes it unforgeable and useless as a
     * quota key at the same time, because nothing stops a flooder minting a fresh keypair and
     * reconnecting: a per-id budget is reset for free, as often as the flooder likes, and a quota
     * with no cost to evade is not a quota. {@code Peer.getIp()} is not self-reported — the handler
     * builds the peer with {@code channel.getRemoteIp()}, the socket's own address — so spending
     * that budget costs an attacker addresses.
     *
     * <p>The cost of this choice is real and deliberate: several honest nodes behind one NAT, or
     * several instances on one host, share a single chunk budget. Throttling something an attacker
     * can regenerate for nothing would not.
     *
     * <p>Null for every block that reached the chain without a wrapper from the network — mined
     * here, built over RPC or the CLI, replayed by a repair tool. Those owe no peer, and charging
     * them to some catch-all bucket would let this node's own mining shut its own intake down.
     */
    private static String peerKeyOf(PreValidated pv) {
        BlockWrapper wrapper = pv.wrapper();
        Peer peer = wrapper == null ? null : wrapper.getRemotePeer();
        return peer == null ? null : peer.getIp();
    }

    /**
     * The sender address this block is filed under in the orphan pool, or null when it is not an
     * account transaction — which the store turns into twenty zero bytes, the shared main-address
     * lane.
     *
     * <p>Walks the links rather than the inputs because that is what it always walked, and the two
     * cannot disagree for a block {@link #isAccountTx} accepted: {@code getLinks()} is the inputs
     * followed by the outputs, and the single {@code XDAG_FIELD_INPUT} that made it an account
     * transaction is in the inputs.
     */
    private byte[] orphanAddressOf(Block block) {
        if (!isAccountTx(block)) {
            return null;
        }
        for (Address txRef : block.getLinks()) {
            if (txRef.getType().equals(XDAG_FIELD_INPUT)) {
                return BytesUtils.byte32ToArray(txRef.getAddress()).toArray();
            }
        }
        return null;
    }

    /**
     * Which orphan category this block would occupy a slot in, for the admission gate in
     * {@link #tryToConnect(PreValidated)}.
     *
     * <p><b>It must give the same answer as the store does on the way in, always.</b> The gate and
     * {@code OrphanBlockStoreImpl.categoryOf} are the two halves of one decision: a gate that says
     * LINK where the store files CHUNK lets a block past a cap the store then enforces, and the
     * block is imported and silently not pooled — on disk, counted in {@code nnoref}, referenced by
     * nothing this node can ever mine. So both are computed from exactly the same two facts, in the
     * same call: the {@code isTxBlock} flag and the address {@link #orphanAddressOf} returns, fed to
     * {@code OrphanCategory.of}.
     *
     * <p>The kind comes from the same {@code Classified} that travels on to {@code addOrphan},
     * which is what keeps the two halves from coming apart: not "both compute it the same way" but
     * "both are handed the same value". A path that carries no classification passes null on both
     * sides, and a null kind is never {@link OrphanCategory#CHUNK}, so such a block is gated and
     * filed as a link block — consistently.
     */
    private OrphanCategory orphanCategoryOf(Block block, Classified classified) {
        return orphanCategoryOf(block, orphanAddressOf(block), classified);
    }

    /**
     * As {@link #orphanCategoryOf(Block, Classified)}, for a caller that has already asked
     * {@link #orphanAddressOf} and needs the answer again afterwards — {@code dealOrphan} pools
     * under that same address. Every category still comes out of this one expression, so the two
     * forms cannot drift from each other or from what the store files the block under.
     */
    private OrphanCategory orphanCategoryOf(Block block, byte[] address, Classified classified) {
        return OrphanCategory.of(isTxBlock(block), address == null ? NO_ORPHAN_ADDRESS : address,
                classified == null ? null : classified.kind());
    }

    /**
     * The chunk the orphan pool is holding in memory for this hash, re-parsed into a block of the
     * caller's own, or null when it holds none.
     *
     * <p><b>A copy, every time, and that is the rule rather than an optimisation left undone.</b>
     * {@code a51e09c5} fixed this node serving peers the very {@code Block} instance the chain goes
     * on mutating; the conclusion it left is that what leaves an in-memory pool is parsed afresh
     * from the raw bytes. The pool stores bytes precisely so that this is the only thing that can
     * be done with them.
     *
     * <p>The hash is normalised the way {@link #getBlockByHash} normalises it: a reference field
     * comes off the wire as a peer wrote it, and the pool is keyed by hashlow.
     */
    private Block pooledChunkBody(Bytes32 hash) {
        if (hash == null) {
            return null;
        }
        return chunkBodyAt(hashLowOf(hash));
    }

    /**
     * As {@link #pooledChunkBody}, for a hash already normalised to a hashlow — the form
     * {@link #getBlockByHash} has built by the time it gets here, so the merged lookup does not
     * normalise twice on a path that runs for every block that is not found at all.
     */
    private Block chunkBodyAt(Bytes32 hashlow) {
        Bytes body = orphanBlockStore.getChunkBody(hashlow);
        return body == null ? null : new Block(new XdagBlock(body.toArray()));
    }

    /** A reference field as a hashlow: the low 24 bytes, the high 8 zeroed. */
    private static Bytes32 hashLowOf(Bytes32 hash) {
        MutableBytes32 hashlow = MutableBytes32.create();
        hashlow.set(8, hash.slice(8, 24));
        return hashlow;
    }

    /**
     * Writes out every memory-only chunk chain this block references, and un-orphans what it wrote.
     * The other half of the deferred persist: a chunk arrives and is kept in memory at nobody's
     * expense but this node's, and it reaches disk here, when a block that references it is
     * imported — the block that, in the protocol, is the one paying for the chain.
     *
     * <h2>Why here, and not at the commit</h2>
     *
     * <p>Three later steps read these blocks straight back out of the block store, so the chain has
     * to be on disk before them or their answers change:
     *
     * <ul>
     *   <li>{@code removeOrphan} in the loop immediately below, which un-orphans each reference. It
     *       finds a block through {@link #getBlockByHash} and would find nothing at all for a
     *       memory-only chunk — so the chunk would stay pooled, keep its quota slot, and keep
     *       {@code nnoref} one too high until its TTL expired.</li>
     *   <li>{@link #calculateBlockDiff}, which reads each reference's stored difficulty. A missing
     *       reference stops that walk early ({@code break}), so the importing block would be given
     *       a different chain weight than it gets today, and chain weight decides the top.</li>
     *   <li>{@code ChainL1Processor}'s chunk-chain assembly at {@code setMain}, which is the point
     *       of the whole exercise and which only ever sees raw blocks from the store.</li>
     * </ul>
     *
     * <p>Placed here, those three are handed exactly what they were handed before chunks became
     * memory-only, so this change is invisible to all of them. Placed at the commit instead, all
     * three would read a hole.
     *
     * <p>It is also late enough to be honest about "the block imported". Every rejection
     * {@code tryToConnect} can return is behind it — the type, timestamp, existence, admission,
     * fee, reference and signature checks, and {@code canUseInput} — so what follows only fails by
     * throwing, and this sits beside a loop that already deletes ORPHANIND rows and writes flags
     * into other blocks at exactly the same point. A paying block that is rejected has therefore
     * not persisted anything, which is the property that matters: an unreferenced chunk stays off
     * disk, and a chunk whose paying block never arrives, or arrives and is refused, is simply aged
     * out in two epochs with nothing written.
     *
     * <h2>Tail first</h2>
     *
     * <p>The chain is collected head-first by following references and then written in reverse, so
     * a chunk is saved only after the chunk it names. That is the order the chunks were imported in
     * to begin with (a block may not reference one that came later), and it is what lets
     * {@link #calculateBlockDiff} give each one the same difficulty its own import would have
     * computed — the value a node that had them all on disk would have stored. Written head-first
     * instead, every chunk's difficulty walk would stop at a successor that was not there yet, and
     * two nodes could store different weights for the same block.
     *
     * <h2>Exactly once</h2>
     *
     * <p>A chunk is dropped from the body store by the {@code removeOrphan} that follows its save
     * (through {@code deleteFromQueue}, which is the pool's single exit), so the second block to
     * reference the same chain finds nothing here and writes nothing: it reads the chain out of the
     * block store like any other block. {@code removeOrphan} is idempotent in its own right as
     * well — it does nothing to a block already flagged {@code BI_REF}.
     *
     * @param block the importing block, needed only for the {@code BI_EXTRA} half of the removal
     *     action so that an extra block un-orphans exactly what it un-orphans today
     * @param links the importing block's distinct references
     * @param category the importing block's own orphan category. A chunk referencing a chunk
     *     persists nothing: a chain that is only referenced from inside itself has still not been
     *     paid for, and persisting on that would hand a flooder the disk write this change exists
     *     to withhold.
     */
    private void persistReferencedChunkChains(Block block, List<Address> links,
            OrphanCategory category) {
        if (category == OrphanCategory.CHUNK || links.isEmpty()) {
            return;
        }
        // One budget for the whole block, not one per reference: a block carries up to fifteen
        // references and each could name a chain of its own, so a per-reference bound would be a
        // fifteen-fold one. A chain longer than this can never assemble (ChunkChain refuses it), so
        // nothing that could ever be settled is left behind, and every node cuts at the same count.
        int budget = kernel.getConfig().getChainSpec().getChainMaxChunksPerChain();
        List<Block> chain = null;
        Set<Bytes> seen = null;
        Deque<Bytes32> pending = null;
        for (Address ref : links) {
            if (ref == null || ref.isAddress) {
                continue;
            }
            Bytes32 head = hashLowOf(ref.getAddress());
            // The whole cost for every block that references no memory-only chunk, which is very
            // nearly all of them: one lookup in a concurrent map per reference, and not a single
            // allocation. Everything below is built only once there is a chain to walk.
            if (orphanBlockStore.getChunkBody(head) == null) {
                continue;
            }
            if (pending == null) {
                pending = new ArrayDeque<>();
                seen = new HashSet<>();
                chain = new ArrayList<>();
            }
            pending.add(head);
            while (!pending.isEmpty() && chain.size() < budget) {
                Bytes32 hash = pending.poll();
                if (!seen.add(hash)) {
                    continue;
                }
                Bytes body = orphanBlockStore.getChunkBody(hash);
                if (body == null) {
                    continue;
                }
                Block chunk = new Block(new XdagBlock(body.toArray()));
                chain.add(chunk);
                for (Address next : chunk.getLinks()) {
                    if (next != null && !next.isAddress) {
                        pending.add(hashLowOf(next.getAddress()));
                    }
                }
            }
            pending.clear();
        }
        if (chain == null) {
            return;
        }
        OrphanRemoveActions action = (block.getInfo().flags & BI_EXTRA) != 0
                ? OrphanRemoveActions.ORPHAN_REMOVE_EXTRA
                : OrphanRemoveActions.ORPHAN_REMOVE_NORMAL;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Block chunk = chain.get(i);
            // The difficulty this chunk's own import would have written, recomputed now that its
            // successor is on disk. A block re-parsed from its bytes carries a virgin BlockInfo, and
            // saving that would leave a null difficulty behind for the next block's weight walk to
            // trip over.
            calculateBlockDiff(chunk, calculateCurrentBlockDiff(chunk));
            saveBlock(chunk);
            // Now that it is loadable, the ordinary un-orphaning applies to it -- the same call the
            // loop below makes for the reference itself, which is why the chain's interior blocks
            // are passed through it too: they were un-orphaned by their successor's import before
            // chunks became memory-only, and nothing else would do it now.
            removeOrphan(chunk.getHashLow(), action);
        }
    }

    public XAmount getTxFee(Block block) {
        if (!isTxBlock(block)) {
            return XAmount.ZERO;
        }
        XdagBlock xdagBlock = block.getXdagBlock();
        if (xdagBlock == null) {
            return XAmount.ZERO;
        } else {
            Bytes32 header = Bytes32.wrap(xdagBlock.getField(0).getData());
            XAmount fee = XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG);
            if (fee.compareTo(XAmount.ZERO) == 0) {
                return MIN_GAS.multiply(outPutNum(block));
            } else if (fee.isNegative()) {
                return XAmount.ZERO;
            } else {
                return fee.add(MIN_GAS.multiply(outPutNum(block)));
            }
        }
    }

    /**
     * Get the number of transactions executed in the main block package
     * @param refHashLow The hash of the transaction packaged in the main block
     * @param mHashLow The hash of the main block
     * @return Number of transactions executed
     */
    public int txNumber(Bytes32 refHashLow, Bytes32 mHashLow) {
        // Recorded, deliberately not guarded: the recursive call below passes a link of a block
        // this walk has already loaded, and it is guarded by the very lookup above it. Reaching it
        // at all takes an applied block, whose own links were on disk when it was applied -- so
        // unlike applyBlock's and unApplyBlock's walks, the memory-only chunk blocks this
        // subproject introduced do not widen this one.
        int sum = 0;
        if (getBlockByHash(refHashLow, true) != null) {
            Block block = getBlockByHash(refHashLow, true);
            if (!isTxBlock(block) && (block.getInfo().flags & BI_MAIN_CHAIN) == 0) {
                for (Address link : block.getLinks()) {
                    if (equalBytes(block.getInfo().getRef(), mHashLow.toArray())) {
                        sum += txNumber(link.getAddress(), block.getHashLow());
                    }
                }
                return sum;
            }
            if ((block.getInfo().flags & BI_APPLIED) != 0 && (block.getInfo().getRef() != null && equalBytes(block.getInfo().getRef(), mHashLow.toArray()))) {
                return outPutNum(block) == -1 ? 0 : outPutNum(block);
            }

        }
        return 0;
    }

    public void putSyncTxStatus(Bytes32 txHash, byte executionStatus){
        if(executionStatus != 0){
            syncTxStatusCache.put(txHash, executionStatus);
        }
    }

    public Byte getSyncTxStatus(Bytes32 txHash){
        Byte status = syncTxStatusCache.getIfPresent(txHash);
//        if (status != null) {
//            syncTxStatusCache.invalidate(txHash);
//        }
        return status;
    }

    public void clearAllSyncTxStatus() {
        syncTxStatusCache.invalidateAll();
        syncTxStatusCache.cleanUp();
    }

    public boolean isTxBlock(Block block) {
        return isAccountTx(block) || isMainTxBlock(block);
    }

    public boolean isAccountTx(Block block) {
        List<Address> inputs = block.getInputs();
        if (inputs == null) return false;

        int inputCount = 0;
        for (Address ref : inputs) {
            if (ref.getType() == XDAG_FIELD_IN) {
                return false; // 不允许出现 IN
            } else if (ref.getType() == XDAG_FIELD_INPUT) {
                inputCount++;
            }
        }
        return inputCount == 1;
    }

    public boolean isMainTxBlock(Block block) {
        List<Address> inputs = block.getInputs();
        if (inputs == null) return false;

        for (Address ref : inputs) {
            if (ref.getType() == XDAG_FIELD_INPUT) {
                return false; // no INPUT
            }
        }

        // At least one XDAG_FIELD_IN
        return inputs.stream().anyMatch(ref -> ref.getType() == XDAG_FIELD_IN);
    }

    public int outPutNum(Block block) {
        if (isTxBlock(block)) {
            return block.getOutputs().size();
        }
        return -1;
    }

    public XAmount outPutLimit(Block block) {
        if (!isTxBlock(block)) {
            return XAmount.ZERO;
        }
        XAmount allFee = getTxFee(block);
        int num = outPutNum(block);
        if (num == -1) {
            return XAmount.ZERO;
        } else if (MIN_GAS.compareTo(allFee.divide(num)) > 0) {
            return MIN_GAS;
        } else {
            return allFee.divide(num);
        }
    }

    // Record transaction history
    public void onNewTxHistory(Bytes32 addressHashlow, Bytes32 txHashlow, XdagField.FieldType type,
                               XAmount amount, long time, byte[] remark, boolean isAddress, int id) {
        if (txHistoryStore != null) {
            Address address = new Address(addressHashlow, type, amount, isAddress);
            TxHistory txHistory = new TxHistory();
            txHistory.setAddress(address);
            txHistory.setHash(BasicUtils.hash2Address(txHashlow));
            if (remark != null) {
                txHistory.setRemark(new String(remark, StandardCharsets.UTF_8));
            }
            txHistory.setTimestamp(time);
            try {
                if (kernel.getXdagState() == XdagState.CDST || kernel.getXdagState() == XdagState.CTST || kernel.getXdagState() == XdagState.CONN
                        || kernel.getXdagState() == XdagState.CDSTP || kernel.getXdagState() == XdagState.CTSTP || kernel.getXdagState() == XdagState.CONNP) {
                    txHistoryStore.batchSaveTxHistory(txHistory);
                } else {
                    if (!txHistoryStore.saveTxHistory(txHistory)) {
                        log.warn("tx history write to mysql fail:{}", txHistory);
                        // Mysql exception, transaction history transferred to Rocksdb
                        blockStore.saveTxHistoryToRocksdb(txHistory, id);
                    } else {
                        List<TxHistory> txHistoriesInRocksdb = blockStore.getAllTxHistoryFromRocksdb();
                        if (!txHistoriesInRocksdb.isEmpty()) {
                            for (TxHistory txHistoryInRocksdb : txHistoriesInRocksdb) {
                                txHistoryStore.batchSaveTxHistory(txHistoryInRocksdb, txHistoriesInRocksdb.size());
                            }
                            if (txHistoryStore.batchSaveTxHistory(null)) {
                                blockStore.deleteAllTxHistoryFromRocksdb();
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    // Get transaction history by address
    public List<TxHistory> getBlockTxHistoryByAddress(Bytes32 addressHashlow, int page, Object... parameters) {
        List<TxHistory> txHistory = Lists.newArrayList();
        if (txHistoryStore != null) {
            try {
                txHistory.addAll(txHistoryStore.listTxHistoryByAddress(checkAddress(addressHashlow) ?
                        BasicUtils.hash2PubAddress(addressHashlow) : BasicUtils.hash2Address(addressHashlow), page, parameters));
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        return txHistory;
    }

    // Check if should use sync fix fork
    public boolean isSyncFixFork(long currentHeight) {
        long syncFixHeight = SYNC_FIX_HEIGHT;
        return currentHeight >= syncFixHeight;
    }

    // Find common ancestor block
    public Block findAncestor(Block block, boolean isFork) {
        Block blockRef;
        Block blockRef0 = null;

        // Find highest difficulty non-main chain block
        for (blockRef = block;
             blockRef != null && ((blockRef.getInfo().flags & BI_MAIN_CHAIN) == 0);
             blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);
            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().compareTo(calculateBlockDiff(tmpRef, calculateCurrentBlockDiff(tmpRef))) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                if (!isFork) {
                    updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                }
                blockRef0 = blockRef;
            }
        }

        // Handle fork point
        if (blockRef != null
                && blockRef0 != null
                && !blockRef.equals(blockRef0)
                && XdagTime.getEpoch(blockRef.getTimestamp()) == XdagTime.getEpoch(blockRef0.getTimestamp())) {
            blockRef = getMaxDiffLink(blockRef, false);
        }
        return blockRef;
    }

    // Update new chain after fork
    public void updateNewChain(Block block, boolean isFork) {
        if (!isFork) {
            return;
        }
        Block blockRef;
        Block blockRef0 = null;
        List<Block> blocks = new ArrayList<>();

        // Update main chain flags
        for (blockRef = block;
             blockRef != null && ((blockRef.getInfo().flags & BI_MAIN_CHAIN) == 0);
             blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);
            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().compareTo(calculateBlockDiff(tmpRef, calculateCurrentBlockDiff(tmpRef))) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                blockRef0 = blockRef;
                blocks.add(blockRef);
            }
        }
        if (!blocks.isEmpty()) {
            blocks = blocks.reversed();
            if (blocks.size() > 1) {
                blocks.removeLast();
                for (Block b : blocks) {
                    b = getBlockByHash(b.getHashLow(), true);
                    if (b == null) continue;
                    for(Address link : b.getLinks()){
                        if (link.isAddress) continue;
                        Block tx = getBlockByHash(link.getAddress(), false);
                        // Same null as applyBlock's, from the same cause: a chunk block the
                        // persist budget left in memory has no BlockInfo to read BI_REF out of.
                        // One budget covers a whole importing block however many chains it names,
                        // so a main block that names two of them gets the first head written and
                        // leaves the second where it was -- and this loop walks a main block's
                        // links.
                        //
                        // Skipping is not a choice about semantics here, it is what the call this
                        // branch guards would itself do: removeOrphan opens with the very same
                        // getBlockByHash(hashlow, false) and returns without touching anything when
                        // it comes back null. The check only moves that no-op one line earlier,
                        // instead of crashing on the way to it.
                        if (tx == null) continue;
                        if((tx.getInfo().flags & BI_REF) == 0){
                            removeOrphan(link.getAddress(), OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                        }
                    }
                }
                saveMBlockTx(blocks);
            } else {
                Block currentBlock = blocks.getFirst();
                Block txBlock = getMaxDiffLink(currentBlock, false);
                if (txBlock != null) {
                    blocks.set(0, txBlock);
                    saveMBlockTx(blocks);
                }
            }
        }
    }

    // Process extra blocks
    public void processExtraBlock() {
        if (memOrphanPool.size() > MAX_ALLOWED_EXTRA) {
            // The oldest extra block, by LinkedHashMap insertion order. Iteration over a
            // synchronized map is the one operation the wrapper cannot lock for us, so it says so
            // here — and it holds the pool lock for the first entry only, never across
            // removeOrphan, which would nest the pool lock inside work that takes it again.
            Block reuse;
            synchronized (memOrphanPool) {
                Iterator<Map.Entry<Bytes, Block>> it = memOrphanPool.entrySet().iterator();
                if (!it.hasNext()) {
                    return;
                }
                reuse = it.next().getValue();
            }
            log.debug("Remove when extra too big");
            removeOrphan(reuse.getHashLow(), OrphanRemoveActions.ORPHAN_REMOVE_REUSE);
            xdagStats.nblocks--;
            xdagStats.totalnblocks = Math.max(xdagStats.nblocks, xdagStats.totalnblocks);

            if ((reuse.getInfo().flags & BI_OURS) != 0) {
                removeOurBlock(reuse);
            }
        }
    }

    // Notify listeners of new pretop
    protected void onNewPretop() {
        for (Listener listener : listeners) {
            listener.onMessage(new PretopMessage(Bytes.wrap(xdagTopStatus.getTop()), PRE_TOP));
        }
    }

    // Notify listeners of new block
    protected void onNewBlock(Block block) {
        for (Listener listener : listeners) {
            listener.onMessage(new BlockMessage(Bytes.wrap(block.getXdagBlock().getData()), NEW_LINK));
        }
    }

    /**
     * What {@link #checkNewMain()} decides on: the deepest {@code BI_MAIN_CHAIN} block the walk from
     * the top reached before the last main block, and how many such candidates it passed. A
     * {@code null} hash means the walk found none.
     */
    record MainCandidate(Bytes32 hashLow, int count) {

        static final MainCandidate NONE = new MainCandidate(null, 0);
    }

    // The candidate cache. Kept next to walkCandidate()/cachedCandidate() rather than with the
    // other instance fields because nothing else in the class may read or write any of it: the
    // whole invariant lives in those two methods plus the chainVersion bumps.

    /**
     * Bumped on every input to {@link #walkCandidate()} that is not the top itself: every
     * {@code BI_MAIN} / {@code BI_MAIN_CHAIN} flag change, every repair-mode tip move, and the one
     * place a walked block stops being loadable ({@code ORPHAN_REMOVE_REUSE} drops a block from
     * {@code memOrphanPool} without saving it). The remaining input, a block's
     * {@code maxDiffLink}, is written exactly once, while that block is being connected
     * ({@code calculateBlockDiff} returns early for any block that already has a difficulty), so it
     * needs no bump. A bump invalidates the candidate cache, except for the single expected
     * mutation {@link #cachedCandidate()} can absorb: see {@link #lastFlagged}.
     */
    private long chainVersion;
    /**
     * The block whose flag caused the most recent bump, or {@code null} when the bump came from
     * anywhere else (repair, snapshot, an evicted block). Lets {@link #cachedCandidate()} accept a
     * cache that is exactly one bump old when that bump was the {@code BI_MAIN_CHAIN} flag the
     * import just put on the block that is now the top — which is what every best import does, and
     * without it the one-step path would never be taken at all.
     */
    private byte[] lastFlagged;
    /** The top {@link #cachedCandidate()} last answered for, or {@code null} before the first call. */
    private byte[] candidateTop;
    private MainCandidate candidate = MainCandidate.NONE;
    private long candidateVersion = -1;
    /** Number of full walks {@link #walkCandidate()} has run: the cache's miss counter, read by tests. */
    long candidateWalks;

    /**
     * The full walk from the top down to the last main block, exactly as {@code checkNewMain} always
     * did it. Read-only: it touches neither the cache nor any block flag.
     */
    MainCandidate walkCandidate() {
        candidateWalks++;
        // If it's a snapshot point main block, return directly since data before snapshot is already determined
        if (xdagTopStatus.getTop() == null) {
            return MainCandidate.NONE;
        }
        Bytes32 p = null;
        int i = 0;
        for (Block block = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false); block != null
                && ((block.getInfo().flags & BI_MAIN) == 0);
             block = getMaxDiffLink(getBlockByHash(block.getHashLow(), true), true)) {

            if ((block.getInfo().flags & BI_MAIN_CHAIN) != 0) {
                p = Bytes32.wrap(block.getHashLow().toArray());
                ++i;
            }
        }
        return new MainCandidate(p, i);
    }

    /**
     * The same answer as {@link #walkCandidate()}, in O(1) whenever the top has not moved or has
     * moved by exactly one block: the walk from a new top whose {@code maxDiffLink} is the old top
     * is that new top followed by the walk from the old top, so the count grows by one if the new
     * top carries {@code BI_MAIN_CHAIN}, and the deepest candidate is unchanged unless there was
     * none. The new top cannot itself be on the walk from the old top — a link always points at a
     * strictly earlier timestamp, which {@code tryToConnect} enforces — so flagging it does not
     * change that walk, and the cache is accepted one bump old when that bump was exactly this
     * flag ({@link #lastFlagged}). That is the shape of every best import: {@code updateNewChain}
     * flags the arriving block {@code BI_MAIN_CHAIN} and only then is the top moved to it.
     *
     * <p>Everything else falls back to the full walk: a real fork (whose {@code unWindMain} clears
     * several flags and whose {@code updateNewChain} sets several, i.e. more than one bump), a
     * {@code setMain} (one bump, but on a block that is not the new top), a top that is itself a
     * main block, a repair or snapshot bump, a cold cache. Updates the cache; callers must hold
     * this monitor.
     */
    MainCandidate cachedCandidate() {
        byte[] top = xdagTopStatus.getTop();
        if (top == null) {
            candidateTop = null;
            candidate = MainCandidate.NONE;
            candidateVersion = chainVersion;
            return candidate;
        }
        MainCandidate result;
        // java.util.Arrays spelled out: the file's unqualified Arrays is org.bouncycastle.util.Arrays.
        boolean sameVersion = candidateTop != null && candidateVersion == chainVersion;
        if (sameVersion && java.util.Arrays.equals(top, candidateTop)) {
            result = candidate;
        } else {
            // One bump old is still usable when the bump was the flag on the block that is now the
            // top; two or more, or one on any other block, means the walk below the top may have moved.
            boolean oneFlagOnTheNewTop = candidateTop != null && candidateVersion + 1 == chainVersion
                    && lastFlagged != null && java.util.Arrays.equals(lastFlagged, top);
            Block topBlock = (sameVersion || oneFlagOnTheNewTop) ? getBlockByHash(Bytes32.wrap(top), false) : null;
            byte[] link = topBlock == null ? null : topBlock.getInfo().getMaxDiffLink();
            if (topBlock != null && (topBlock.getInfo().flags & BI_MAIN) == 0
                    && link != null && java.util.Arrays.equals(link, candidateTop)) {
                boolean onChain = (topBlock.getInfo().flags & BI_MAIN_CHAIN) != 0;
                result = new MainCandidate(
                        candidate.hashLow() != null ? candidate.hashLow()
                                : (onChain ? Bytes32.wrap(topBlock.getHashLow().toArray()) : null),
                        candidate.count() + (onChain ? 1 : 0));
            } else {
                result = walkCandidate();
            }
        }
        candidateTop = top.clone();
        candidate = result;
        candidateVersion = chainVersion;
        return result;
    }

    // Check and update main chain
    @Override
    public synchronized void checkNewMain() {
        MainCandidate c = cachedCandidate();
        if (c.hashLow() == null || c.count() <= 1) {
            return;
        }
        // Re-read the candidate rather than keeping the object the walk loaded: BI_REF is not part of
        // the cache key, and a later block may have set it since. Both the flags and the timestamp
        // the decision needs live in BlockInfo, so this is the cheap lookup; only setMain needs the
        // raw block (it walks the links), which the old code got because the walk loaded it raw.
        Block p = getBlockByHash(c.hashLow(), false);
        long ct = XdagTime.getCurrentTimestamp();
        if (p != null
                && ((p.getInfo().flags & BI_REF) != 0)
                && ct >= p.getTimestamp() + 2 * 1024) {
//            log.info("setMain success block:{}", Hex.toHexString(p.getHashLow()));
            Block raw = getBlockByHash(c.hashLow(), true);
            // Unreachable in a healthy store, and the old code would have thrown in the walk itself
            // (getMaxDiffLink dereferences the raw block); the cache reaches the decision without
            // that load, so skip rather than introduce a new NPE. The next call retries.
            if (raw != null) {
                setMain(raw);
            }
        }
    }

    @Override
    public long getLatestMainBlockNumber() {
        return xdagStats.nmain;
    }

    /**
     * Rollback to specified block
     */
    public synchronized void unWindMain(Block block) {
        persist.direct(() -> unWindMainDirect(block));
    }

    private void unWindMainDirect(Block block) {
        log.debug("Unwind main to block,{}", block == null ? "null" : block.getHashLow().toHexString());
        if (xdagTopStatus.getTop() != null) {
            log.debug("now pretop : {}", xdagTopStatus.getPreTop() == null ? "null" : Bytes32.wrap(xdagTopStatus.getPreTop()).toHexString());
            for (Block tmp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), true); tmp != null
                    && !blockEqual(block, tmp); tmp = getMaxDiffLink(tmp, true)) {
                BlockInfo info = blockStore.getBlockInfo(tmp.getHashLow());
                if (info != null) {
                    tmp.getInfo().setFee(info.getFee());
                }
                updateBlockFlag(tmp, BI_MAIN_CHAIN, false);
                log.debug("roll main block: {}", tmp.getHashLow());
                if ((tmp.getInfo().flags & BI_EXTRA) == 0) rollTx(tmp);
                // Update corresponding flag information
                if ((tmp.getInfo().flags & BI_MAIN) != 0) {
                    unSetMain(tmp);
                    // Fix: Need to update block info in database like height 210729
                    blockStore.saveBlockInfo(tmp.getInfo());
                }
            }
            rollTxList = rollTxList.reversed();
            for (Block txBlock : rollTxList) {
                dealOrphan(txBlock);
                log.debug("roll txBlock:{}", txBlock.getHashLow());
            }
            rollTxList.clear();
        }
    }

    /**
     * SP0b-1 offline repair: adopts a main block the store carries ABOVE the persisted tip as the
     * tip, so that block can be unwound at all.
     *
     * <p>Failure shape 3 of {@link io.xdag.chain.repair.ChainConsistencyCheck}: a {@code setMain}
     * that flagged and saved its block but died before (or during) its {@code saveXdagStatus} leaves
     * a {@code BI_MAIN} block above {@code xdagStats.nmain}. This class's constructor pins
     * {@code xdagTopStatus.top} to {@code getBlockByHeight(nmain)}, and {@link #unWindMain} only
     * ever walks down from that top — so such a block is invisible to the unwind, which would then
     * report success while leaving it confirmed. Reconciling the tip up to it first is what puts it
     * back on the path the unwind walks.
     *
     * <p>What is restored is what the constructor's "load existing state" branch derives from the
     * tip block: {@code nmain}, the chain difficulty pair, and the top hash and difficulty. The
     * pre-top is deliberately left alone — the constructor does not derive it either, it is only a
     * link candidate, and pointing it at a block that the caller is about to unwind would be worse
     * than keeping the persisted one.
     *
     * <p>One residual cannot be fixed from local state: {@code xdagStats.balance} — a node-local
     * statistic, not consensus — was persisted before that {@code setMain} credited its reward, so
     * unwinding a {@code BI_OURS} main block out of the torn window subtracts a reward the persisted
     * balance never carried, and the figure drifts by one reward per such block. It settles when the
     * node re-confirms those heights.
     *
     * <p>Only meaningful in repair mode, where the check-main loop is not running and nothing can
     * confirm a block behind the repair tool's back.
     *
     * @param height  the main height to adopt as the tip
     * @param hashLow the hash of the main block stored at that height
     * @throws IllegalStateException if that block is missing, is not a main block, or does not agree
     *                               that it sits at {@code height}
     */
    public synchronized void reconcileTipTo(long height, Bytes32 hashLow) {
        persist.direct(() -> reconcileTipToDirect(height, hashLow));
    }

    private void reconcileTipToDirect(long height, Bytes32 hashLow) {
        // Redundant: the wrapper holds this monitor. Kept so the body is unchanged from before SP0b-2.
        synchronized (this) {
            Block block = hashLow == null ? null : getBlockByHash(hashLow, false);
            if (block == null || block.getInfo().getHeight() != height
                    || (block.getInfo().flags & BI_MAIN) == 0) {
                throw new IllegalStateException("no main block stored at height " + height
                        + " to reconcile the tip to");
            }
            log.info("repair: reconciling the persisted tip from nmain={} up to {} ({})", xdagStats.nmain, height,
                    hashLow.toHexString());
            xdagStats.nmain = height;
            xdagStats.setMaxdifficulty(block.getInfo().getDifficulty());
            xdagStats.setDifficulty(block.getInfo().getDifficulty());
            xdagTopStatus.setTop(block.getHashLow().toArray());
            xdagTopStatus.setTopDiff(block.getInfo().getDifficulty());
            bumpChainVersion(); // the repair paths move the top without going through updateBlockFlag
            blockStore.saveXdagStatus(xdagStats);
            blockStore.saveXdagTopStatus(xdagTopStatus);
        }
    }

    /**
     * SP0b-1 offline repair: unwinds every main block above {@code height} exactly the way a fork
     * does, then re-flags the surviving branch so those same blocks can be confirmed again.
     *
     * <p>Three steps, in this order. {@link #unWindMain} walks the top down to {@code height},
     * clearing {@code BI_MAIN_CHAIN} and running {@code unSetMain} on every main block it passes —
     * which is what actually reverses state and moves {@code nmain} and the completion marker back.
     * The result is then verified: that walk fetches raw blocks and ends silently at the first one
     * whose bytes are missing, so "the unwind returned" is not the same as "the unwind arrived", and
     * only {@code nmain == height} proves it did.
     *
     * <p>The unwound branch is deliberately NOT re-flagged as main-chain candidates here, and the
     * top is neither moved nor persisted. Both would be pointless and the first is hazardous: the
     * next boot re-derives the top from {@code getBlockByHeight(nmain)} regardless of what is stored,
     * i.e. it pins the top to the {@code height} block, which carries {@code BI_MAIN} — so
     * {@code checkNewMain} (which walks from the top through non-main {@code BI_MAIN_CHAIN} blocks)
     * never even enters its loop and cannot see anything above the target. A branch re-flagged by
     * this method would therefore sit ABOVE the boot top, and the ordinary fork path answers a new
     * peer block with {@code unWindMain(findAncestor(block))}: an ancestor above the top is never met
     * by that downward walk, which then unwinds the whole main chain to genesis. Left un-flagged,
     * the same new block resolves its ancestor to the target itself, the unwind is a no-op, and
     * {@code updateNewChain} re-flags the branch as part of the normal fork handling.
     *
     * <p>What the node cannot do afterwards is walk its own chain back up from local state: it
     * moves again only when genuinely new blocks arrive from peers and build on the old head
     * (re-sent copies of stored blocks return EXIST and never move the top). A node repaired in
     * isolation stays at {@code height}.
     *
     * <p>Only meaningful in repair mode, where the check-main loop is not running and nothing can
     * confirm a block behind the repair tool's back. The stats are persisted here; the caller is
     * responsible for the completion marker and the in-flight record.
     *
     * @param height the last main height to keep; {@code 0} unwinds the whole main chain
     * @throws IllegalStateException if {@code height} carries no main block of its own, or if the
     *                               unwind did not reach {@code height}, i.e. the block data is
     *                               incomplete. Whatever the unwind did
     *                               reach stays reversed — each of those heights is an ordinary
     *                               completed {@code unSetMain} — but the completion marker and the
     *                               in-flight record are the caller's business and are untouched, so
     *                               a failed repair leaves its evidence behind.
     */
    public synchronized void repairUnwindTo(long height) {
        persist.direct(() -> repairUnwindToDirect(height));
    }

    private void repairUnwindToDirect(long height) {
        // Redundant: the wrapper holds this monitor. Kept so the body is unchanged from before SP0b-2.
        synchronized (this) {
            Block target = height <= 0 ? null : blockStore.getBlockByHeight(height);
            // I1: the height index is not authoritative -- saveBlockInfo never deletes a stale key,
            // so getBlockByHeight can hand back a block that was unwound and re-confirmed lower down.
            // Unwinding "to" such a block would walk straight past it and empty the whole chain.
            if (height > 0 && (target == null || target.getInfo().getHeight() != height
                    || (target.getInfo().flags & BI_MAIN) == 0)) {
                throw new IllegalStateException("no main block stored at height " + height);
            }
            unWindMain(target);
            // C3: checked before the caller writes the completion marker, so an unwind that stopped
            // short can never be recorded as a completed repair.
            if (xdagStats.nmain != height) {
                throw new IllegalStateException("unwind stopped at nmain=" + xdagStats.nmain + ", expected "
                        + height + "; block data is incomplete (raw bytes missing?)");
            }
            bumpChainVersion(); // the repair paths move the top without going through updateBlockFlag
            blockStore.saveXdagStatus(xdagStats);
        }
    }

    private boolean blockEqual(Block block1, Block block2) {
        if (block1 == null) {
            return block2 == null;
        } else {
            return block2.equals(block1);
        }
    }

    /**
     * Execute block and return gas fee
     */
    private XAmount applyBlock(boolean flag, Block block) {
        // Block already processed
        if ((block.getInfo().flags & BI_MAIN_REF) != 0) {
            return XAmount.ZERO.subtract(XAmount.ONE);
        }

        updateBlockFlag(block, BI_MAIN_REF, true);

        List<Address> links = block.getLinks();
        if (links == null || links.isEmpty()) {
            updateBlockFlag(block, BI_APPLIED, true);
            chainHooks.onBlockApplied(block);
            return XAmount.ZERO;
        }

        XAmount gasCollected = XAmount.ZERO;
        if (flag) {
            execLog.info("========== Main Block: {} ==========", block.getHashLow().toHexString());
        }
        for (Address link : links) {
            if (!link.isAddress) {
                Block ref = getBlockByHash(link.getAddress(), false);
                if (ref == null) {
                    // A reference this node has no chain metadata for. Unreachable until chunk
                    // blocks became memory-only: every block the import accepted used to be on
                    // disk, because the import itself refuses a block whose references it cannot
                    // resolve. Now a reference can also be resolved out of the orphan pool's body
                    // store, and persistReferencedChunkChains writes at most
                    // chain.chunk.maxPerChain of them per importing block -- so the tail of a
                    // longer chain, and every chain a block names after the budget has run out,
                    // stays in memory. getBlockByHash(.., false) answers null for exactly those:
                    // the merged lookup serves a memory-only chunk in its RAW form only, because a
                    // BlockInfo parsed fresh out of 512 bytes is not "unknown", it is a set of
                    // specific wrong claims about flags, difficulty and ref.
                    //
                    // Null here therefore means "a chunk this node never wrote", which is either
                    // "not persisted yet" or "aged out and gone". The two ARE distinguishable --
                    // the body store still answers for the first -- and the distinction changes
                    // nothing, which is why this branch does not make it. All an apply does with a
                    // reference is stamp BI_MAIN_REF and a ref into its BlockInfo and fold in its
                    // gas, and a block that is not in the block store can hold neither:
                    // updateBlockFlag and updateBlockRef persist only when block.isSaved, so a
                    // block parsed from the pool's bytes would take the flags and lose them on the
                    // next read. Persisting it here instead is worse -- it would hand back, at
                    // setMain time and unbounded, exactly the disk write the deferred persist
                    // exists to withhold from a chain nobody has paid for.
                    //
                    // So the link is skipped, and skipping costs nothing that was there to lose: a
                    // chunk carries no value (a non-zero-amount XDAG_FIELD_OUT reference is refused
                    // at import), so the subtree's gas contribution is zero whether it is walked or
                    // not, and everything reachable through an unwritten chunk is unwritten too.
                    // unApplyBlock skips the same link for the same reason, which is what keeps the
                    // two halves the mirror image they are documented to be.
                    //
                    // The link skipped here is always an XDAG_FIELD_OUT reference, never an
                    // XDAG_FIELD_IN one: verifySignature refuses any block whose IN names something
                    // this node cannot load, so such a block never reaches the DAG to be applied.
                    // That matters because the two loops below dereference an IN reference without
                    // asking, and a skip here that left one of them live would move the crash
                    // rather than close it.
                    continue;
                }
                if ((ref.getInfo().flags & BI_MAIN_REF) != 0) continue;
                ref = getBlockByHash(link.getAddress(), true);
                ref.getInfo().setFee(XAmount.ZERO);

                // I1 (SP0b-1): the same shape as the G1 fix one level down — point the child at this
                // main block BEFORE descending into it, so a throw out of the child's own apply
                // leaves a child that unApplyBlock can still reach (it skips a BI_MAIN_REF block
                // whose ref is null). ref lives only in the local BlockInfo; it is not hashed.
                updateBlockRef(ref, new Address(block));

                XAmount childGas = applyBlock(false, ref);

                int refFlag = ref.getInfo().getFlags() & ~(BI_OURS | BI_REMARK);
                int executionState = 0;
                if (refFlag == (BI_REF | BI_MAIN_REF | BI_APPLIED)) {
                    executionState = 1; // 1C: applied
                } else if (refFlag == (BI_REF | BI_MAIN_REF)) {
                    executionState = 2; // 18: rejected
                }
                String blockType = isTxBlock(ref) ? "TxBlock  " : "LinkBlock";
                execLog.info("{} | Hash: {} | State: {}", blockType, ref.getHashLow().toHexString(), executionState);

                if (childGas.equals(XAmount.ZERO.subtract(XAmount.ONE))) {
                    // rejected: restore the pre-call state. Two readers depend on it: unApplyBlock's
                    // trailing loop only clears BI_MAIN_REF on a ref == null tx block, and its
                    // recursive branch would otherwise descend into this rejected child (ref == M)
                    // and unwind a subtree that was never applied.
                    updateBlockRef(ref, null);
                } else {
                    gasCollected = gasCollected.add(childGas);
                }
            }
        }

        // Input/output processing
        XAmount sumIn = XAmount.ZERO;
        XAmount sumOut = XAmount.ZERO;
        for (Address link : links) {
            MutableBytes32 linkAddress = link.getAddress();

            if (link.getType() == XDAG_FIELD_INPUT) {
                XAmount balance = addressStore.getBalanceByAddress(BasicUtils.hash2byte(linkAddress).toArray());
                UInt64 executedNonce = addressStore.getExecutedNonceNum(BasicUtils.hash2byte(linkAddress).toArray());
                UInt64 blockNonce = block.getTxNonceField().getTransactionNonce();

                if (blockNonce.compareTo(executedNonce.add(UInt64.ONE)) > 0) {
                    log.info("tx nonce error, tx nonce: {}, executed nonce: {},hash:{}", blockNonce, executedNonce,block.getHashLow().toHexString());
                    // Deliberately LOWERS the issued counter, so resetTxQuantity and not the
                    // monotonic updateTxQuantity: a sender whose counter ran ahead of what actually
                    // executed heals itself here, at the cost of this one transaction. Safe to do
                    // unconditionally only because we hold the blockchain monitor - a submit that
                    // is mid-flight for this address holds its reservation lock and cannot be
                    // inside its own read-modify-write at the same time.
                    addressStore.resetTxQuantity(BasicUtils.hash2byte(linkAddress).toArray(), executedNonce);
                    return XAmount.ZERO.subtract(XAmount.ONE);
                }
                if (blockNonce.compareTo(executedNonce) <= 0) {
                    log.info("tx nonce is less than executed nonce,hash:{}",block.getHashLow().toHexString());
                    return XAmount.ZERO.subtract(XAmount.ONE);
                }
                if (compareAmountTo(balance, link.amount) < 0) {
                    log.info("balance is less than amount,hash:{}",block.getHashLow().toHexString());
                    processNonceAfterTransactionExecution(link);
                    return XAmount.ZERO;
                }
                sumIn = sumIn.add(link.getAmount());

            } else if (link.getType() == XDAG_FIELD_IN) {
                // Never null, and gated rather than lucky. canUseInput walks getInputs(), which
                // parse() fills with EVERY XDAG_FIELD_IN field, and getLinks() -- what this loop
                // walks -- is exactly getInputs() plus getOutputs(). So every IN link here was put
                // through verifySignature at import, which refuses the block outright when this
                // lookup would answer null, and a block on disk never leaves the store.
                Block ref = getBlockByHash(linkAddress, false);
                if (compareAmountTo(ref.getInfo().getAmount(), link.getAmount()) < 0) {
                    log.info("ref balance is less than amount");
                    return XAmount.ZERO;
                }
                sumIn = sumIn.add(link.getAmount());

            } else {
                sumOut = sumOut.add(link.getAmount());
            }
        }

        if (compareAmountTo(block.getInfo().getAmount().add(sumIn), sumOut) < 0 ||
                compareAmountTo(block.getInfo().getAmount(), XAmount.ZERO) < 0 ||
                compareAmountTo(sumIn, sumOut) != 0) {
            if (block.getInputs() != null) processNonceAfterTransactionExecution(block.getInputs().get(0));
            log.info("block amount is not equal to sumIn - sumOut");
            return XAmount.ZERO;
        }

        if(kernel.getSyncMgr() != null && (kernel.getSyncMgr().isSyncOld() || kernel.getSyncMgr().isSync()) && isTxBlock(block)){
            Byte executionStatus = getSyncTxStatus(block.getHashLow());
            if (executionStatus != null && executionStatus == 2){
                log.debug("Execute Synchronization of Node Transaction Status：{}",block.getHashLow().toHexString());
                return XAmount.ZERO.subtract(XAmount.ONE);
            }
        }else if(kernel.getSyncMgr() != null && !kernel.getSyncMgr().isSyncOld() && syncTxStatusCache.size() >0){
            clearAllSyncTxStatus();
        }

        // Actual amount processing
        XAmount blockGas = XAmount.ZERO;
        for (Address link : links) {
            MutableBytes32 linkAddress = link.addressHash;
            if (!link.isAddress) {
                // May be null: an OUT link to a memory-only chunk. Only the IN branch below
                // dereferences it; see there.
                Block ref = getBlockByHash(linkAddress, false);
                if (link.getType() == XDAG_FIELD_IN) {
                    // Same gate as the loop above: an IN reference that reaches an applied block
                    // was resolvable at import or the block was refused.
                    subtractAndAccept(ref, link.getAmount());
                    XAmount allBalance = addressStore.getAllBalance();
                    allBalance = allBalance.add(link.getAmount().subtract(getTxFee(block)));
                    addressStore.updateAllBalance(allBalance);
                }
            } else {
                if (link.getType() == XDAG_FIELD_INPUT) {
                    subtractAmount(BasicUtils.hash2byte(linkAddress), link.getAmount(), block);
                    processNonceAfterTransactionExecution(link);
                } else if (link.getType() == XDAG_FIELD_OUTPUT) {
                    addAmount(BasicUtils.hash2byte(linkAddress), link.getAmount().subtract(outPutLimit(block)), block);
                    blockGas = blockGas.add(outPutLimit(block));
                }
            }
        }



        updateBlockFlag(block, BI_APPLIED, true);
        chainHooks.onBlockApplied(block);

//        XAmount totalFee = gasCollected.add(blockGas);
//        block.getInfo().setFee(totalFee);
        if (!flag && isTxBlock(block)) {
            block.getInfo().setFee(blockGas);
            blockStore.saveBlockInfo(block.getInfo());
            return blockGas;
        } else if (!flag && !isTxBlock(block)) {
            block.getInfo().setFee(gasCollected);
            blockStore.saveBlockInfo(block.getInfo());
            return gasCollected;
        } else {
            // If the transaction block has become the main block, then get blockGas; otherwise, return gasCollected.
            return ((gasCollected.compareTo(XAmount.ZERO) == 0) && (blockGas.compareTo(XAmount.ZERO) > 0)) ? blockGas : gasCollected;
        }
    }

    // TODO: unapply block which in snapshot
    public void unApplyBlock(Block block, boolean flag) {
        if((block.getInfo().flags & BI_MAIN_REF) == 0 || block.getInfo().getRef() == null) {
            return;
        }
        List<Address> links = block.getLinks();
        Collections.reverse(links); // must be reverse
        if ((block.getInfo().flags & BI_APPLIED) != 0) {
            // TX block created by wallet or pool will not set fee = minGas, set here
//            if (!block.getInputs().isEmpty() && block.getFee().equals(XAmount.ZERO)) {
//                block.getInfo().setFee(getTxFee(block));
//            }
            // applyBlock credited every OUTPUT address link amount - L (L = outPutLimit) and
            // persisted fee = k * L for the k OUTPUT address links, so the exact reversal of each
            // credit is amount - fee / k (exact in nano). outPutLimit(block) would also work here
            // (every unwind path hands in a raw block, and an info-only block has no links at all),
            // but the persisted fee is what was actually charged and stays exact across a future
            // change to the fee rule, where re-deriving L at unwind time would apply the new rule
            // to an old block. Precondition: the caller must hand in a raw block whose info.fee was
            // restored from the store, because Block.parse() replaces it with the header fee
            // (unWindMain, the recursive call below and ChainRepairTool all do so). The one shape
            // where fee != k * L is flag == true: setMain persists gasCollected for a tx block that
            // was itself promoted to main, so such a block with fee-paying children reverses only
            // approximately, exactly as before; unreachable today, since createMainBlock emits no
            // XDAG_FIELD_IN and no XDAG_FIELD_OUTPUT. Dividing by outPutNum(block) instead was
            // wrong for any block that also carries XDAG_FIELD_OUT block links (chain DEPLOY/CALL
            // with chunk-chain heads): those count as outputs in outPutLimit's denominator but were
            // never credited, so the unwind over-debited each OUTPUT by fee * m / (k * (k + m)) and
            // could even skip it when the balance went below zero. For m = 0 (every legacy wallet
            // or pool transfer) fee / k == fee / outPutNum, byte for byte.
            int outputAddresses = 0;
            for (Address link : links) {
                if (link.isAddress && link.getType() == XDAG_FIELD_OUTPUT) {
                    outputAddresses++;
                }
            }
            XAmount perOutput = outputAddresses == 0 ? XAmount.ZERO : block.getFee().divide(outputAddresses);
            for (Address link : links) {
                if (!link.isAddress) {
                    // May be null: an OUT link to a memory-only chunk. Only the IN branch below
                    // dereferences it; see there.
                    Block ref = getBlockByHash(link.getAddress(), false);
                    if (link.getType() == XDAG_FIELD_IN) {
                        // The mirror of applyBlock's two IN loops, and unguarded for the same
                        // reason: a block being unapplied was applied, so it was imported, so
                        // verifySignature resolved every one of its IN references.
                        // Only input references to the main block transaction block will go through this.
                        addAndAccept(ref, link.getAmount());
                        XAmount allBalance = addressStore.getAllBalance();
                        // allBalance = allBalance.subtract(link.getAmount()); //fix subtract twice.
                        try {
                            allBalance = allBalance.subtract(link.getAmount().subtract(block.getFee()));
                        } catch (Exception e) {
                            log.debug("allBalance rollback");
                        }
                        addressStore.updateAllBalance(allBalance);
                    }
                } else {
                    if (link.getType() == XDAG_FIELD_INPUT) {
                        addAmount(BasicUtils.hash2byte(link.getAddress()), link.getAmount(), block);
                        byte[] address = BytesUtils.byte32ToArray(link.getAddress()).toArray();
                        UInt64 exeNonce = addressStore.getExecutedNonceNum(address);
                        addressStore.updateExcutedNonceNum(address, false);
                        // Lowering again: unwinding an applied transaction puts the issued counter
                        // back where the executed one now is.
                        addressStore.resetTxQuantity(address, exeNonce.subtract(UInt64.ONE));
                        log.info("current nonce subtract one");
                    } else if (link.getType() == XDAG_FIELD_OUTPUT) {
                        // Reverse exactly the credit applyBlock made: amount - fee / k.
                        subtractAmount(BasicUtils.hash2byte(link.getAddress()), link.getAmount().subtract(perOutput), block);
                    }
                }

            }

            // Unapply visits blocks in the exact reverse of the apply DFS order (this block first,
            // then its links in reversed order), which the chain processor's per-(chain,height) index
            // bookkeeping relies on.
            chainHooks.onBlockUnapplied(block);
            updateBlockFlag(block, BI_APPLIED, false);
        } else {
            //When rolling back, the unaccepted transactions in the main block need to be processed, which is the number of confirmed transactions sent corresponding to their account addresses, nonce, needs to be reduced by one
            for(Address link : links) {
                if (link.isAddress && link.getType() == XDAG_FIELD_INPUT){
                    Bytes address = byte32ToArray(link.getAddress());
                    UInt64 blockNonce = block.getTxNonceField().getTransactionNonce();
                    UInt64 exeNonce = addressStore.getExecutedNonceNum(address.toArray());
                    if (blockNonce.compareTo(exeNonce) == 0) {
                        addressStore.updateExcutedNonceNum(address.toArray(), false);
                        addressStore.resetTxQuantity(address.toArray(), exeNonce.subtract(UInt64.ONE));
                        log.debug("The transaction processed quantity of account {} is reduced by one, and the number of transactions processed now is nonce = {}",
                                Base58.encodeCheck(BytesUtils.byte32ToArray(link.getAddress())), addressStore.getExecutedNonceNum(address.toArray()).intValue()
                        );
                    }

                }
            }
        }

        if (!flag) {
            block.getInfo().setFee(XAmount.ZERO);
            updateBlockFlag(block, BI_MAIN_REF, false);
            updateBlockRef(block, null);
        }

        for (Address link : links) {
            if (!link.isAddress) {
                Block ref = getBlockByHash(link.getAddress(), false);
                // The mirror of applyBlock's skip, and it has to be here as well as there: this
                // loop dereferences the reference BEFORE it asks whether this main block ever
                // applied it, so a link the apply walk stepped over would be a crash on the way
                // back out. A memory-only chunk was never applied -- it holds no BI_MAIN_REF and
                // no ref, because neither could have been persisted into a block that is not in
                // the block store -- so there is nothing here to reverse.
                if (ref == null) continue;
                XAmount fee;
                // Even if mainBlock duplicate links the TX_block which other mainBlock handled, we can check if this TX ref is this mainBlock
                if (ref.getInfo().getRef() != null
                        && equalBytes(ref.getInfo().getRef(), block.getHashLow().toArray())
                        && ((ref.getInfo().flags & BI_MAIN_REF) != 0)) {
//                    addAndAccept(block, unApplyBlock(getBlockByHash(ref.getHashLow(), true)));
                    fee = ref.getFee();
                    ref = getBlockByHash(ref.getHashLow(), true);
                    ref.getInfo().setFee(fee);
                    unApplyBlock(ref, false);
                }
                // Remove the flag that was set for the transaction block with the nonce error, and restore it to the Pending state.
                fee = ref.getFee();
                ref = getBlockByHash(ref.getHashLow(), true);
                ref.getInfo().setFee(fee);
                if (isTxBlock(ref) && ref.getInfo().getRef() == null && (ref.getInfo().flags & BI_MAIN_REF) != 0) {
                    updateBlockFlag(ref, BI_MAIN_REF, false);
                }
            }
        }
    }

    /**
     * Set the main chain with block as the main block - either fork or extend
     */
    public synchronized void setMain(Block block) {
        persist.direct(() -> setMainDirect(block));
    }

    private void setMainDirect(Block block) {

        // The wrapper already holds this monitor; kept so the body stays byte-for-byte the pre-SP0b-2 one.
        synchronized (this) {
            // Set reward
            long mainNumber = xdagStats.nmain + 1;
            // I3 (SP0b-1): record the transition as in flight before ANY other store write, so a
            // setMain that dies half way through leaves proof of it at this height, tagged with the
            // operation so a repair tool can tell it from a half-done unSetMain. Cleared on both
            // normal exits, right after the completion marker.
            blockStore.saveMainInFlight(mainNumber, BlockStore.IN_FLIGHT_SET_MAIN);
            log.debug("mainNumber = {},hash = {}", mainNumber, Hex.toHexString(block.getInfo().getHash()));
            XAmount reward = getReward(mainNumber);
            block.getInfo().setHeight(mainNumber);
            updateBlockFlag(block, BI_MAIN, true);
            chainHooks.onSetMainBegin(mainNumber, block);

            try {
                // Main block REF points to itself.
                // G1 root fix (SP0b-1): point the main block at itself BEFORE the DFS. A throw out of
                // applyBlock then leaves ref == self, so unApplyBlock treats this block normally
                // instead of skipping a BI_MAIN_REF block whose ref is null (which could never be
                // unwound). ref lives only in the local BlockInfo; it is not part of any hash.
                updateBlockRef(block, new Address(block));

                // Accept reward
                acceptAmount(block, reward);
                xdagStats.nmain++;

                // Recursively execute blocks referenced by main block and get fees
                XAmount mainBlockFee = applyBlock(true, block); //the mainBlock may have tx, return the fee to itself.
                if (mainBlockFee.compareTo(XAmount.ZERO) < 0) {
                    // Reachable: a tx block promoted to main whose own input fails the nonce or
                    // sync-status checks in applyBlock (no test currently exercises this path).
                    // The block keeps BI_MAIN with no fee accepted, and setMain is DONE — this is a
                    // normal exit, so the completion marker advances here exactly as it does below.
                    // I3 (SP0b-1): stats first. The marker must never be ahead of the persisted
                    // stats: checkMain saves them only after setMain returns, so a benign crash in
                    // that gap used to leave a confirmed main block above the persisted nmain --
                    // which the boot check rightly reads as "main block above persisted stats".
                    blockStore.saveXdagStatus(xdagStats);
                    blockStore.saveLastCompletedMain(mainNumber);
                    blockStore.clearMainInFlight();
                    return;
                } else {
                    acceptAmount(block, mainBlockFee); //add the fee
                    block.getInfo().setFee(mainBlockFee);
                    blockStore.saveBlockInfo(block.getInfo());
                }

                if (randomx != null) {
                    randomx.randomXSetForkTime(block);
                }

                // G2 marker (SP0b-1): written on both normal exits, never in finally — so a boot can
                // tell whether the previous setMain finished. A RocksDB failure in this one write
                // surfaces as an exception thrown from an otherwise complete setMain; that is
                // accepted, because the next boot then sees the marker sitting behind nmain and
                // unwinds that height, which is safe (it undoes work that did land).
                // I3 (SP0b-1): stats first, as on the early-return exit above -- the marker must
                // never be ahead of the persisted stats, or a benign crash between setMain and
                // checkMain's own save reads as "main block above persisted stats" on the next boot.
                blockStore.saveXdagStatus(xdagStats);
                blockStore.saveLastCompletedMain(mainNumber);
                blockStore.clearMainInFlight();
            } finally {
                // Closes the apply context on every exit: the early return above, a normal finish,
                // and a throw out of the applyBlock DFS.
                chainHooks.onSetMainEnd(mainNumber, block);
            }
        }

    }

    /**
     * Cancel Block main block status
     */
    // TODO: Change to new way to cancel main block reward
    public synchronized void unSetMain(Block block) {
        persist.direct(() -> unSetMainDirect(block));
    }

    private void unSetMainDirect(Block block) {

        // The wrapper already holds this monitor; kept so the body stays byte-for-byte the pre-SP0b-2 one.
        synchronized (this) {

            if ((block.getInfo().flags & BI_MAIN) == 0) {
                // M6 (SP0b-1): not a main block, so there is nothing to undo — and undoing it anyway
                // would decrement nmain and push the completion marker below the real tip.
                log.warn("unSetMain on a block that is not main: {}", block.getHash().toHexString());
                return;
            }

            log.debug("UnSet main,{}, mainnumber = {}", block.getHash().toHexString(), xdagStats.nmain);
            // Height is still the confirmed height here; it is zeroed at the end of this method.
            long height = block.getInfo().getHeight();
            // I3 (SP0b-1): as in setMain, flag the transition before touching anything else — and
            // tag it as an unwind, which a repair tool cannot fix by unwinding further.
            blockStore.saveMainInFlight(height, BlockStore.IN_FLIGHT_UNSET_MAIN);
            // I4 (SP0b-1): randomXSetForkTime runs only AFTER the apply DFS, so a setMain that threw
            // never set this height's fork time, and unsetting it anyway would corrupt the seed
            // epoch bookkeeping (randomXHashEpochIndex is decremented by the unset). The completion
            // marker is what distinguishes the two: it never reaches a height whose setMain threw.
            // It is read here, before the height - 1 write at the end, so a multi-block unwind still
            // sees the marker at or above each height it is undoing. (It cannot distinguish the
            // early-return exit, which likewise skips randomXSetForkTime yet is a normal completion;
            // that far narrower asymmetry predates this gate and is left as is.) A store that has
            // never written the marker (-1, i.e. one written before SP0b-1 and not yet re-confirmed
            // by a setMain) knows nothing either way, so it keeps the old unconditional behaviour
            // rather than silently skipping an unset that a pre-upgrade setMain really did perform.
            long lastCompleted = blockStore.getLastCompletedMain();
            boolean forkTimeWasSet = lastCompleted < 0 || lastCompleted >= height;
            chainHooks.onUnsetMain(height, block);

            XAmount reward = getReward(height);
            updateBlockFlag(block, BI_MAIN, false);

            xdagStats.nmain--;

            acceptAmount(block, XAmount.ZERO.subtract(reward));
            unApplyBlock(block, true);

            acceptAmount(block, XAmount.ZERO.subtract(block.getFee()));
            if (randomx != null && forkTimeWasSet) {
                randomx.randomXUnsetForkTime(block);
            }
            block.getInfo().setFee(XAmount.ZERO);
            block.getInfo().setHeight(0);
            updateBlockFlag(block, BI_MAIN_REF, false);
            updateBlockRef(block, null);

            // G2 marker (SP0b-1): unSetMain removes the top main block, so completion now sits one
            // height lower — clamped at 0, since "completed up to height 0" (the genesis state, no
            // main block) is the floor and a negative marker would be read back as "never written".
            // I3 (SP0b-1): stats first here too, so the lowered marker is never ahead of the
            // persisted nmain it belongs to.
            blockStore.saveXdagStatus(xdagStats);
            blockStore.saveLastCompletedMain(Math.max(0, height - 1));
            blockStore.clearMainInFlight();
        }
    }

    /**
     * Keeps the issued-nonce counter at or above the executed one after a transaction executes.
     * The two reads below are outside any lock on purpose - they are only candidates. The compare
     * and the write happen inside {@code updateTxQuantity}, under that address' value lock, and
     * they are monotonic, so a submit raising the counter between these reads and that call is no
     * longer clobbered back down onto a nonce that is already in flight.
     */
    public void processNonceAfterTransactionExecution(Address link) {
        if (link.getType() != XDAG_FIELD_INPUT) {
            return;
        }
        Bytes address = BytesUtils.byte32ToArray(link.getAddress());
        addressStore.updateExcutedNonceNum(address.toArray(), true);
        UInt64 currentTxNonce = addressStore.getTxQuantity(address.toArray());
        UInt64 currentExeNonce = addressStore.getExecutedNonceNum(address.toArray());
        addressStore.updateTxQuantity(address.toArray(), currentTxNonce, currentExeNonce);
    }

    @Override
    public Block createNewBlock(
            Map<Address, ECKeyPair> pairs,
            List<Address> to,
            boolean mining,
            String remark,
            XAmount fee,
            UInt64 txNonce
    ) {

        int hasRemark = remark == null ? 0 : 1;

        if (pairs == null && to == null) {
            if (mining) {
                return createMainBlock();
            } else {
                return createLinkBlock(remark, false);
            }
        }
        int defKeyIndex = -1;

        // Check all keys to see if there is a default key
        assert pairs != null;
        List<ECKeyPair> keys = new ArrayList<>(Set.copyOf(pairs.values()));
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(wallet.getDefKey())) {
                defKeyIndex = i;
            }
        }

        List<Address> all = Lists.newArrayList();
        all.addAll(pairs.keySet());
        all.addAll(to);

        // TODO: Check if pairs have duplicates
        int res;
        if (txNonce != null) {
            res = 1 + 1 + pairs.size() + to.size() + 3 * keys.size() + (defKeyIndex == -1 ? 2 : 0) + hasRemark;
        } else {
            res = 1 + pairs.size() + to.size() + 3 * keys.size() + (defKeyIndex == -1 ? 2 : 0) + hasRemark;
        }

        // TODO: If block fields are insufficient
        if (res > 16) {
            return null;
        }
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getCurrentTimestamp();
        List<Address> refs = Lists.newArrayList();

        return new Block(kernel.getConfig(), sendTime[0], all, refs, mining, keys, remark, defKeyIndex, fee, txNonce);
    }

    public Block createMainBlock() {
        // <header + remark + outsig + nonce>
        int res = 1 + 1 + 2 + 1;
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getMainTime();
        Address preTop = null;
        Bytes32 pretopHash = getPreTopMainBlockForLink(sendTime[0]);
        if (pretopHash != null) {
            preTop = new Address(Bytes32.wrap(pretopHash), XdagField.FieldType.XDAG_FIELD_OUT, false);
            res++;
        }
        // The coinbase address of the block defaults to the default address of the node wallet
        Address coinbase = new Address(keyPair2Hash(wallet.getDefKey()),
                FieldType.XDAG_FIELD_COINBASE,
                true);
        List<Address> refs = Lists.newArrayList();
        if (preTop != null) {
            refs.add(preTop);
        }

        if (coinbase == null) {
            throw new ArithmeticException("Invalidate main block!");
        }
        refs.add(coinbase);
        res++;

        List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime, true);
        if (CollectionUtils.isNotEmpty(orphans)) {
            refs.addAll(orphans);
        }
        return new Block(kernel.getConfig(), sendTime[0], null, refs, true, null,
                kernel.getConfig().getNodeSpec().getNodeTag(), -1, XAmount.ZERO, null);
    }

    /**
     * A block whose only purpose is to reference orphans, so that they stop being orphans.
     *
     * @return the block, or {@code null} when the non-roll path found nothing to reference. A block
     *         with no references is one this node's own import refuses, so declining to build it is
     *         the whole of what the caller loses; {@code checkOrphan} ends its round on it.
     */
    public Block createLinkBlock(String remark, boolean isRoll) {
        // <header + remark + outsig + nonce>
        int hasRemark = remark == null ? 0 : 1;
        int res = 1 + hasRemark + 2;
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getCurrentTimestamp();

        List<Address> refs = Lists.newArrayList();
        if (isRoll) {
            for (int i = 16 - res; i > 0 && CollectionUtils.isNotEmpty(rollTxList); i--) {
                refs.add(new Address(rollTxList.getFirst().getHashLow(), FieldType.XDAG_FIELD_OUT, false));
                sendTime[1] = Math.max(sendTime[1], rollTxList.getFirst().getTimestamp());
                rollTxList.removeFirst();
            }
            sendTime[1] = Math.min(sendTime[1] + 1, sendTime[0]);
            log.debug("rollTxList.size:{}", rollTxList.size());
        } else {
            List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime, false);
            if (CollectionUtils.isEmpty(orphans)) {
                // Nothing the packing walk will hand out -- an empty pool, or one holding only
                // chunks, which selectBlocks never offers. Building anyway produced a block with no
                // references and, before getOrphanLocked stopped fabricating it, sendTime[1] == 1:
                // refused by this node's own import as "Block's time is illegal".
                //
                // Deliberately inside this branch and not shared with the roll branch above. That
                // one packs from rollTxList, a different source with a different emptiness: whether
                // a rollback with nothing to re-link should still emit a block is its own question,
                // and no caller passes isRoll = true today (createNewBlock is the only caller and
                // it passes false), so answering it here would be an unreviewed change to a path
                // nothing reaches.
                return null;
            }
            refs.addAll(orphans);
        }

        return new Block(kernel.getConfig(), sendTime[1], null, refs, false, null,
                remark, -1, XAmount.ZERO, null);
    }

    /**
     * Get a certain number of orphan blocks from orphan pool for linking
     */
    public List<Address> getBlockFromOrphanPool(int num, long[] sendtime, boolean isMain) {
        return orphanBlockStore.getOrphan(num, sendtime, isMain);
    }

    public Bytes32 getPreTopMainBlockForLink(long sendTime) {
        long mainTime = XdagTime.getEpoch(sendTime);
        Block topInfo;
        if (xdagTopStatus.getTop() == null) {
            return null;
        }

        topInfo = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (topInfo == null) {
            return null;
        }
        if (XdagTime.getEpoch(topInfo.getTimestamp()) == mainTime) {
            log.debug("use pretop:{}", Bytes32.wrap(xdagTopStatus.getPreTop()).toHexString());
            return Bytes32.wrap(xdagTopStatus.getPreTop());
        } else {
            log.debug("use top:{}", Bytes32.wrap(xdagTopStatus.getTop()).toHexString());
            return Bytes32.wrap(xdagTopStatus.getTop());
        }
    }

    /**
     * Update pretop
     *
     * @param target     target block
     * @param targetDiff difficulty of block
     */
    public void setPreTop(Block target, BigInteger targetDiff) {
        if (target == null) {
            return;
        }

        // Make sure the target's epoch is earlier than current top's epoch
        Block block = getBlockByHash(xdagTopStatus.getTop() == null ? null :
                Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (block != null) {
            if (XdagTime.getEpoch(target.getTimestamp()) >= XdagTime.getEpoch(block.getTimestamp())) {
                return;
            }
        }

        // If pretop is null, then update pretop to target
        if (xdagTopStatus.getPreTop() == null) {
            xdagTopStatus.setPreTop(target.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(targetDiff);
            target.setPretopCandidate(true);
            target.setPretopCandidateDiff(targetDiff);
            return;
        }

        // If targetDiff greater than pretop diff, then update pretop to target
        if (targetDiff.compareTo(xdagTopStatus.getPreTopDiff()) > 0) {
            log.debug("update pretop:{}", Bytes32.wrap(target.getHashLow()).toHexString());
            xdagTopStatus.setPreTop(target.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(targetDiff);
            target.setPretopCandidate(true);
            target.setPretopCandidateDiff(targetDiff);
        }
    }

    /**
     * Calculate current block difficulty
     */
    public BigInteger calculateCurrentBlockDiff(Block block) {
        if (block == null) {
            return BigInteger.ZERO;
        }
        if (block.getInfo().getDifficulty() != null) {
            return block.getInfo().getDifficulty();
        }
        //TX block would not set diff, fix a diff = 1;
        if (!block.getInputs().isEmpty()) {
            return BigInteger.ONE;
        }

        BigInteger blockDiff;
        // Set initial block difficulty
        if (randomx != null && randomx.isRandomxFork(XdagTime.getEpoch(block.getTimestamp()))
                && XdagTime.isEndOfEpoch(block.getTimestamp())) {
            blockDiff = getDiffByRandomXHash(block);
        } else {
            blockDiff = getDiffByRawHash(block.getHash());
        }

        return blockDiff;
    }

    /**
     * Set block difficulty and max difficulty connection and return block difficulty
     */
    public BigInteger calculateBlockDiff(Block block, BigInteger cuDiff) {
        if (block == null) {
            return BigInteger.ZERO;
        }
        if (block.getInfo().getDifficulty() != null) {
            return block.getInfo().getDifficulty();
        }

        block.getInfo().setDifficulty(cuDiff);

        BigInteger maxDiff = cuDiff;
        Address maxDiffLink = null;

        // Temporary block
        Block tmpBlock;
        if (block.getLinks().isEmpty()) {
            return cuDiff;
        }

        // Traverse all links to find maxLink
        List<Address> links = block.getLinks();
        for (Address ref : links) {
            /*
             * Only Blocks have difficulty
             */
            if (!ref.isAddress) {
                Block refBlock = getBlockByHash(ref.getAddress(), false);
                if (refBlock == null) {
                    break;
                }
                // If the referenced block's epoch is less than current block's round
                if (XdagTime.getEpoch(refBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp())) {
                    // If difficulty is greater than current max difficulty
                    BigInteger refDifficulty = refBlock.getInfo().getDifficulty();
                    if (refDifficulty == null) {
                        refDifficulty = BigInteger.ZERO;
                    }
                    BigInteger curDiff = refDifficulty.add(cuDiff);
                    if (curDiff.compareTo(maxDiff) > 0) {
                        maxDiff = curDiff;
                        maxDiffLink = ref;
                    }
                } else {
                    // Calculated diff
                    // 1. maxDiff+diff0 for different epochs
                    // 2. maxDiff for same epoch
                    tmpBlock = refBlock; // tmpBlock is from link
                    BigInteger curDiff = refBlock.getInfo().getDifficulty();
                    while ((tmpBlock != null)
                            && XdagTime.getEpoch(tmpBlock.getTimestamp()) == XdagTime.getEpoch(block.getTimestamp())) {
                        tmpBlock = getMaxDiffLink(tmpBlock, false);
                    }
                    if (tmpBlock != null
                            && (XdagTime.getEpoch(tmpBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp()))
                            && tmpBlock.getInfo().getDifficulty().add(cuDiff).compareTo(curDiff) > 0
                    ) {
                        curDiff = tmpBlock.getInfo().getDifficulty().add(cuDiff);
                    }
                    if (curDiff == null) {
                        curDiff = BigInteger.ZERO;
                    }
                    if (curDiff.compareTo(maxDiff) > 0) {
                        maxDiff = curDiff;
                        maxDiffLink = ref;
                    }
                }
            }
        }

        block.getInfo().setDifficulty(maxDiff);

        if (maxDiffLink != null) {
            block.getInfo().setMaxDiffLink(maxDiffLink.getAddress().toArray());
        }
        return maxDiff;
    }

    public BigInteger getDiffByRandomXHash(Block block) {
        long epoch = XdagTime.getEpoch(block.getTimestamp());
        MutableBytes data = MutableBytes.create(64);
        Bytes32 rxHash = HashUtils.sha256(block.getXdagBlock().getData().slice(0, 512 - 32));
        data.set(0, rxHash);
        data.set(32, block.getXdagBlock().getField(15).getData());
        byte[] blockHash = randomx.randomXBlockHash(data.toArray(), epoch);
        BigInteger diff;
        if (blockHash != null) {
            Bytes32 hash = Bytes32.wrap(Arrays.reverse(blockHash));
            diff = getDiffByRawHash(hash);
        } else {
            diff = getDiffByRawHash(block.getHash());
        }
        log.debug("block diff:{}, ", diff);
        return diff;
    }

    public BigInteger getDiffByRawHash(Bytes32 hash) {
        return getDiffByHash(hash);
    }

    // ADD: Get block by height using new version
    public Block getBlockByHeightNew(long height) {
        // TODO: if snapshot enabled, need height > snapshotHeight - 128
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled() && (height < snapshotHeight - 128)
                && !kernel.getConfig().getSnapshotSpec().isSnapshotJ()) {
            return null;
        }
        // Return null if height is less than 0
        if (height > xdagStats.nmain || height <= 0) {
            return null;
        }
        return blockStore.getBlockByHeight(height);
    }

    @Override
    public Block getBlockByHeight(long height) {
        return getBlockByHeightNew(height);
    }

    /**
     * The node's one block lookup, over the three places a block this node holds can be: the extra
     * blocks in {@link #memOrphanPool}, the block store, and — since the deferred persist — the
     * orphan pool's chunk body store, which is the only copy anywhere of a chunk nothing has paid
     * to store yet.
     *
     * <h2>Order: the body store goes last, and that is load-bearing</h2>
     *
     * <p><b>Equivalence.</b> The first two sources are consulted exactly as they were, in the same
     * order, with the same arguments, and the third is reached only when both answered null. So no
     * answer that used to be non-null can change — the merge is invisible to every block that could
     * already be found, by construction rather than by inspection of each caller. That is the
     * property {@code BlockLookupEquivalenceTest} pins, and this shape is why it holds.
     *
     * <p><b>The three are not disjoint.</b> {@code persistReferencedChunkChains} writes a chunk to
     * the block store and only then drops its body (through the {@code removeOrphan} that follows
     * each save), so between those two statements the hash is in both — and netty threads read this
     * method throughout, holding nothing. Store-first decides that race in favour of the persisted
     * form, which is the complete one: it carries the {@link BlockInfo} the import just computed,
     * where a body carries none at all. Body-first would hand a caller a difficulty-less,
     * flag-less block for one this node has fully imported.
     *
     * <p><b>Cost.</b> This is the lookup on the consensus path ({@code ChainL1Processor} reaches it
     * through the lambda this class hands its constructor) and the one the P2P serve paths call.
     * Last means a found block costs exactly what it cost before; only a lookup that was going to
     * return null pays for one extra hash-map read.
     *
     * <h2>{@code isRaw}: the body store answers the raw form only</h2>
     *
     * <p>{@code isRaw} does not choose a representation of one thing, it chooses <em>which stored
     * artifact</em> is wanted: {@code true} is {@code getRawBlockByHash}, the block's 512 wire
     * bytes; {@code false} is {@code getBlockInfoByHash}, the chain metadata the store keeps about
     * it — flags, difficulty, ref, amount, height — and a block loaded that way carries neither
     * links nor extension fields. The body store holds the wire bytes and nothing else, so it can
     * answer the first question exactly and has no answer at all to the second: a memory-only chunk
     * has no stored {@code BlockInfo} anywhere on this node, and a freshly parsed one is all
     * zeroes, which is not "unknown" but a set of specific wrong claims — not referenced, not main,
     * not applied, no difficulty — that {@code isRaw=false} callers read and act on.
     *
     * <p>Two of them show what that would cost, and they are why this is a rule and not a taste:
     *
     * <ul>
     *   <li>{@code removeOrphan} opens with {@code getBlockByHash(hashlow, false)} and, on any
     *       non-null answer whose {@code BI_REF} is clear, calls {@code deleteFromQueue}. A chunk
     *       names the chunk after it, so importing chunk <i>i</i> would evict chunk <i>i+1</i> from
     *       the pool and destroy the only copy of its bytes in existence — a chain that can then
     *       never be assembled and never be paid for. Before the deferred persist the identical
     *       removal was harmless, because the successor was already on disk; it is the storage that
     *       changed, not the removal.</li>
     *   <li>{@link #calculateBlockDiff} reads {@code refBlock.getInfo().getDifficulty()} off an
     *       {@code isRaw=false} lookup. A virgin body would contribute zero there, which is neither
     *       what a node holding the chunk on disk computes nor what one that never received it
     *       does.</li>
     * </ul>
     *
     * <p>This is also what every caller the merge exists for asks for. {@code ChunkChain} documents
     * that its lookup must return raw blocks and the consensus lambda passes {@code true};
     * {@code XdagP2pHandler}'s two serve paths pass {@code true}. What it does not reach is
     * {@code getBlocksByTime}, which walks the block store's TIME index — written only by
     * {@code saveBlock} — and resolves each row through the store's own lookup: a memory-only chunk
     * has no row there to be found by, and giving it one is a different change from this one.
     *
     * <p>What leaves the body store is a block parsed afresh from immutable bytes, so a caller can
     * do what it likes to it without any other reader seeing it — {@code a51e09c5}'s copy-on-serve
     * rule, made a property of the storage rather than a habit of each caller.
     */
    @Override
    public Block getBlockByHash(Bytes32 hashlow, boolean isRaw) {
        if (hashlow == null) {
            return null;
        }
        // Ensure that hashlow is hashlow
        MutableBytes32 keyHashlow = MutableBytes32.create();
        keyHashlow.set(8, Objects.requireNonNull(hashlow).slice(8, 24));

        Block b = memOrphanPool.get(Bytes32.wrap(keyHashlow));
        if (b == null) {
            b = blockStore.getBlockByHash(keyHashlow, isRaw);
        }
        if (b == null && isRaw) {
            b = chunkBodyAt(Bytes32.wrap(keyHashlow));
        }
        return b;
    }

    public Block getMaxDiffLink(Block block, boolean isRaw) {
        if (block.getInfo().getMaxDiffLink() != null) {
            return getBlockByHash(Bytes32.wrap(block.getInfo().getMaxDiffLink()), isRaw);
        }
        return null;
    }

    public void removeOrphan(Bytes32 hashlow, OrphanRemoveActions action) {
        Block b = getBlockByHash(hashlow, false);
        // TODO: snapshot
        if (b != null && b.getInfo() != null && b.getInfo().isSnapshot()) {
            return;
        }
        if (b != null && ((b.getInfo().flags & BI_REF) == 0) && (action != OrphanRemoveActions.ORPHAN_REMOVE_EXTRA
                || (b.getInfo().flags & BI_EXTRA) != 0)) {
            // If removeBlock is BI_EXTRA
            if ((b.getInfo().flags & BI_EXTRA) != 0) {
                // Then removeBlockInfo is complete
                // Remove from MemOrphanPool
                Bytes key = b.getHashLow();
                Block removeBlockRaw = memOrphanPool.get(key);
                memOrphanPool.remove(key);
                if (action == OrphanRemoveActions.ORPHAN_REMOVE_REUSE) {
                    // The one eviction that does NOT save the block first: it stops being loadable,
                    // which the candidate walk depends on, and no flag change records that.
                    bumpChainVersion();
                }
                if (action != OrphanRemoveActions.ORPHAN_REMOVE_REUSE) {
                    // Save block
                    saveBlock(removeBlockRaw);
                    // Remove all blocks linked by EXTRA block
                    if (removeBlockRaw != null) {
                        List<Address> all = removeBlockRaw.getLinks();
                        for (Address addr : all) {
                            removeOrphan(addr.getAddress(), OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                        }
                    }
                }
                // Update removeBlockRaw flag
                // Decrement nextra
                updateBlockFlag(removeBlockRaw, BI_EXTRA, false);
                xdagStats.nextra--;
            } else {
                b = getBlockByHash(b.getHashLow(), true);
                List<Address> in = b.getInputs();
                UInt64 nonce = UInt64.ZERO;
                XAmount fee = getTxFee(b);
                byte[] address = null;
                if (isAccountTx(b)) {
                    for(Address ref : in) {
                        if (ref.getType().equals(XDAG_FIELD_INPUT)) {
                            address = BytesUtils.byte32ToArray(ref.getAddress()).toArray();
                            nonce = b.getTxNonceField().getTransactionNonce();
                            break;
                        }
                    }
                }

                orphanBlockStore.deleteFromQueue(b, isTxBlock(b), nonce, fee, address);
                orphanBlockStore.deleteByKey(b.getHashLow().toArray(), isTxBlock(b), nonce, fee, address);
                xdagStats.nnoref--;
            }
            // Update this block's flag
            updateBlockFlag(b, BI_REF, true);
        }
    }

    /**
     * A {@link #chainVersion} bump whose cause is not "this block's flag changed": it clears
     * {@link #lastFlagged} so {@link #cachedCandidate()} cannot mistake it for the one mutation it
     * is allowed to absorb.
     */
    private void bumpChainVersion() {
        chainVersion++;
        lastFlagged = null;
    }

    public void updateBlockFlag(Block block, byte flag, boolean direction) {
        if (block == null) {
            return;
        }
        // The only two flags the main-block candidate walk reads; see cachedCandidate().
        if (flag == BI_MAIN || flag == BI_MAIN_CHAIN) {
            chainVersion++;
            lastFlagged = block.getHashLow().toArray();
        }
        if (direction) {
            block.getInfo().setFlags(block.getInfo().flags |= flag);
        } else {
            block.getInfo().setFlags(block.getInfo().flags &= ~flag);
        }
        if (block.isSaved) {
            if (!block.getInfo().getFee().equals(XAmount.ZERO)) {
                Block blockInfo = getBlockByHash(block.getHashLow(), false);
                block.getInfo().setFee(blockInfo.getFee());
            }
            blockStore.saveBlockInfo(block.getInfo());
        }
    }

    public void updateBlockRef(Block block, Address ref) {
        if (ref == null) {
            block.getInfo().setRef(null);
        } else {
            block.getInfo().setRef(ref.getAddress().toArray());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
    }

    public void saveBlock(Block block) {
        if (block == null) {
            return;
        }
        block.isSaved = true;
        blockStore.saveBlock(block);
        // If it's our account
        if (memOurBlocks.containsKey(block.getHash())) {
//            log.info("new account:{}", Hex.toHexString(block.getHash()));
            if (xdagStats.getOurLastBlockHash() == null) {
                blockStore.saveXdagStatus(xdagStats);
            }
            addOurBlock(memOurBlocks.get(block.getHash()), block);
            memOurBlocks.remove(block.getHash());
        }

        if (block.isPretopCandidate()) {
            xdagTopStatus.setPreTop(block.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(block.getPretopCandidateDiff());
            blockStore.saveXdagTopStatus(xdagTopStatus);
        }

    }

    public boolean isExtraBlock(Block block) {
        return (block.getTimestamp() & 0xffff) == 0xffff && block.getNonce() != null && !block.isSaved();
    }

    @Override
    public XdagStats getXdagStats() {
        return this.xdagStats;
    }

    public boolean canUseInput(Block block) {
        return canUseInput(block, null);
    }

    /**
     * {@code keys == null}: compute {@code block.verifiedKeys()} here, which is the pre-SP0b-2
     * behaviour. Otherwise the keys were verified off the monitor by the ingest pipeline; they are a
     * pure function of the block's bytes, so the verdict is the same either way.
     */
    public boolean canUseInput(Block block, List<PublicKey> preVerifiedKeys) {
        List<Address> inputs = block.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return true;
        }
        // After the empty check, never before it: link and main blocks have no inputs and are most
        // of the traffic, and verifying their signatures here only to return true is pure waste.
        // Safe to skip a verdict as well as a cost, but only because tryToConnect now rejects a
        // block with no out-signature before it gets here: that is the one shape verifiedKeys() is
        // not total on, and the fast path used to let it past on its way to a later NPE.
        List<PublicKey> keys = preVerifiedKeys != null ? preVerifiedKeys : block.verifiedKeys();
        /*
         * While "in" isn't address, need to verify signature
         */
        // TODO: Verify signature for non-address inputs
        for (Address in : inputs) {
            if (!in.isAddress) {
                if (!verifySignature(in, keys)) {
                    return false;
                }
            } else {
                if (!verifyBlockSignature(in, keys)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean verifyBlockSignature(Address in, List<PublicKey> keys) {
        Bytes pubHash = in.getAddress().mutableCopy().slice(8, 20);
        for (PublicKey key : keys) {
            if (pubHash.equals(toBytesAddress(key))) return true;
        }
        return false;
    }

    private boolean verifySignature(Address in, List<PublicKey> publicKeys) {
        // TODO: Check if block is in snapshot, get blockinfo with isRaw=false
        Block block = getBlockByHash(in.getAddress(), false);
        if (block == null) {
            // The input names a block this node holds ONLY as a pooled chunk body. The reference
            // check upstream accepts a non-address reference resolved either from the block store
            // or from that body store -- which is what lets a chunk chain be received at all, since
            // chunk i names chunk i+1 and i+1 is memory-only until something pays for the chain --
            // and the merged lookup then answers the non-raw form for such a block with null, on
            // the argument that a BlockInfo parsed out of 512 bytes is a set of specific wrong
            // claims rather than an unknown. So this shape needs no persist-budget overrun: one
            // chunk is enough, and before this check it was a peer-triggerable NPE that
            // tryToConnect's catch-all turned into ERROR.
            //
            // Refusing is NOT a new rule invented for the memory-only case. It is the verdict a
            // node that has already persisted the very same chunk reaches, and for a reason that
            // has nothing to do with persistence: a chunk block is built with no out-signature at
            // all, so there is nothing for verifiedKeys() to match and canUseInput answers false.
            // Measured both ways -- ChunkAsTransactionInputTest pins that the two node states now
            // return the same INVALID_BLOCK, which is the whole point of the check: the divergence
            // between "I have written this chunk down" and "I am still holding it" is what the
            // deferred persist introduced, and consensus must not be able to see it.
            //
            // Keeping the spender out is right rather than merely convenient. A chunk carries
            // XAmount.ZERO and no signature, so nothing can authorise a spend from it and
            // applyBlock's XDAG_FIELD_IN branch would reject it on balance even if it did get in;
            // the verdict is terminal on every node, so there is nothing to retry and no
            // re-request loop to start. NO_PARENT would have been the dishonest alternative -- this
            // node does have the block, just not in a form that can authorise anything.
            return false;
        }
        boolean isSnapshotBlock = block.getInfo().isSnapshot();
        if (isSnapshotBlock) {
            return verifySignatureFromSnapshot(in, publicKeys);
        } else {
            Block inBlock = getBlockByHash(in.getAddress(), true);
            MutableBytes subdata = inBlock.getSubRawData(inBlock.getOutsigIndex() - 2);
//            log.debug("verify encoded:{}", Hex.toHexString(subdata));
            Signature sig = inBlock.getOutsig();
            return verifySignature(subdata, sig, publicKeys, block.getInfo());
        }
    }

    // TODO: When input is a block in snapshot, need to verify snapshot's public key or signature data
    private boolean verifySignatureFromSnapshot(Address in, List<PublicKey> publicKeys) {
        BlockInfo blockInfo = blockStore.getBlockInfoByHash(in.getAddress()).getInfo();
        SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
        if (snapshotInfo.getType()) {
            // snapshotInfo.getData() contains 33-byte compressed public key format
            try {
                PublicKey targetPublicKey = PublicKey.fromBytes(snapshotInfo.getData());
                for (PublicKey publicKey : publicKeys) {
                    if (publicKey.equals(targetPublicKey)) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                // If public key parsing fails, verification fails
                return false;
            }
        } else {
            Block block = getBlockByHash(in.getAddress(), false);
            block.setXdagBlock(new XdagBlock(snapshotInfo.getData()));
            block.setParsed(false);
            block.parse();
            MutableBytes subdata = block.getSubRawData(block.getOutsigIndex() - 2);
            Signature sig = block.getOutsig();
            // Check if signature is canonical to prevent signature malleability attacks
            if (!sig.isCanonical()) {
                return false; // Reject non-canonical signatures
            }
            return verifySignature(subdata, sig, publicKeys, blockInfo);
        }


    }

    private boolean verifySignature(MutableBytes subdata, Signature sig, List<PublicKey> publicKeys, BlockInfo blockInfo) {
        for (PublicKey publicKey : publicKeys) {
            byte[] publicKeyBytes = publicKey.toBytes().toArray();
            Bytes digest = Bytes.wrap(subdata, Bytes.wrap(publicKeyBytes));
//            log.debug("verify encoded:{}", Hex.toHexString(digest));
            Bytes32 hash = HashUtils.doubleSha256(digest);
            if (Signer.verify(hash, sig, publicKey)) {
                SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
                byte[] pubkeyBytes = publicKey.toBytes().toArray();
                if (snapshotInfo != null) {
                    snapshotInfo.setData(pubkeyBytes);
                    snapshotInfo.setType(true);
                } else {
                    blockInfo.setSnapshotInfo(new SnapshotInfo(true, pubkeyBytes));
                }
                blockStore.saveBlockInfo(blockInfo);
                return true;
            }
        }
        return false;
    }

    public boolean checkMineAndAdd(Block block) {
        List<ECKeyPair> ourkeys = wallet.getAccounts();
        // Only one output signature
        Signature signature = block.getOutsig();
        // Iterate through all keys
        for (int i = 0; i < ourkeys.size(); i++) {
            ECKeyPair ecKey = ourkeys.get(i);
            // TODO: Optimize
            byte[] publicKeyBytes = ecKey.getPublicKey().toBytes().toArray();
            Bytes digest = Bytes.wrap(block.getSubRawData(block.getOutsigIndex() - 2), Bytes.wrap(publicKeyBytes));
            Bytes32 hash = HashUtils.doubleSha256(Bytes.wrap(digest));
            // Use hyperledger besu crypto native secp256k1
            if (Signer.verify(hash, signature, ecKey.getPublicKey())) {
                log.debug("verify block success hash={}.", hash.toHexString());
                addOurBlock(i, block);
                return true;
            }
        }
        return false;
    }

    public void addOurBlock(int keyIndex, Block block) {
        xdagStats.setOurLastBlockHash(block.getHash().toArray());
        if (!block.isSaved()) {
            memOurBlocks.put(block.getHash(), keyIndex);
        } else {
            blockStore.saveOurBlock(keyIndex, block.getInfo().getHashlow());
        }
    }

    public void removeOurBlock(Block block) {
        if (!block.isSaved) {
            memOurBlocks.remove(block.getHash());
        } else {
            blockStore.removeOurBlock(block.getHashLow().toArray());
        }
    }

    public XAmount getReward(long nmain) {
        XAmount start = getStartAmount(nmain);
        long nanoAmount = start.toXAmount().toLong();
        return XAmount.ofXAmount(nanoAmount >> (nmain >> MAIN_BIG_PERIOD_LOG));
    }

    @Override
    public XAmount getSupply(long nmain) {
        UnsignedLong res = UnsignedLong.ZERO;
        XAmount amount = getStartAmount(nmain);
        long nanoAmount = amount.toXAmount().toLong();
        long current_nmain = nmain;
        while ((current_nmain >> MAIN_BIG_PERIOD_LOG) > 0) {
            res = res.plus(UnsignedLong.fromLongBits(1L << MAIN_BIG_PERIOD_LOG).times(long2UnsignedLong(nanoAmount)));
            current_nmain -= 1L << MAIN_BIG_PERIOD_LOG;
            nanoAmount >>= 1;
        }
        res = res.plus(long2UnsignedLong(current_nmain).times(long2UnsignedLong(nanoAmount)));
        long fork_height = kernel.getConfig().getApolloForkHeight();
        if (nmain >= fork_height) {
            // Add before apollo amount
            XAmount diff = kernel.getConfig().getMainStartAmount().subtract(kernel.getConfig().getApolloForkAmount());
            long nanoDiffAmount = diff.toXAmount().toLong();
            res = res.plus(long2UnsignedLong(fork_height - 1).times(long2UnsignedLong(nanoDiffAmount)));
        }
        return XAmount.ofXAmount(res.longValue());
    }

    @Override
    public List<Block> getBlocksByTime(long starttime, long endtime) {
        return blockStore.getBlocksUsedTime(starttime, endtime);
    }

    @Override
    public void startCheckMain(long period) {
        if (checkLoop == null) {
            return;
        }
        checkLoopFuture = checkLoop.scheduleAtFixedRate(this::checkState, 0, period, TimeUnit.MILLISECONDS);
    }

    public void checkState() {
        // Prohibit Non-mining nodes generate link blocks
        if (kernel.getConfig().getEnableGenerateBlock() &&
                (kernel.getXdagState() == XdagState.SDST || XdagState.STST == kernel.getXdagState() || XdagState.SYNC == kernel.getXdagState())) {
            checkOrphan();
        }
        checkMain();
    }

    public void checkOrphan() {
        long nblk = xdagStats.nnoref / 11;
        if (nblk > 0) {
            boolean b = (nblk % 61) > CryptoProvider.nextLong(0, 61);
            nblk = nblk / 61 + (b ? 1 : 0);
        }
        while (nblk-- > 0) {
            Block linkBlock = createNewBlock(null, null, false,
                    kernel.getConfig().getNodeSpec().getNodeTag(), XAmount.ZERO, null);
            if (linkBlock == null) {
                // Nothing to link: createLinkBlock declines when the orphan pool hands out no
                // references. End the round rather than continue, on cost rather than on
                // impossibility -- this method is not synchronized while tryToConnect(PreValidated)
                // is, so a net thread really can pool a selectable orphan between two iterations.
                // Breaking defers at most one link block to the next checkState tick; continuing
                // would spend a blockchain-monitor round trip per iteration (getOrphan takes it) on
                // up to 61 futile selections for that chance.
                //
                // The regression witness for this guard lives in
                // ChunkFloodAdversarialTest#aChunkFloodMintsLinkBlocksThatCanReferenceNothing, not
                // beside the tests for createLinkBlock's own null. Reaching this line
                // deterministically needs nblk > 0 with nblk % 61 == 0, the one point where the
                // sampling draw above cannot change the answer, so nnoref must be at least 671 --
                // which is that class's 670-block flood fixture. A closer test would have to run a
                // second flood of the same size to get here.
                break;
            }
            linkBlock.signOut(kernel.getWallet().getDefKey());
            ImportResult result = this.tryToConnect(new Block(linkBlock.getXdagBlock()));
            if (result == IMPORTED_NOT_BEST || result == IMPORTED_BEST) {
                onNewBlock(linkBlock);
            }
        }
    }

    /**
     * C1 (SP0b-2): {@code synchronized}, not just {@code checkNewMain()} inside. The stats save has
     * to be under the same monitor as the transition that changed them. Two reasons, both real:
     * {@code xdagStats} is serialized here while {@code tryToConnect} on another thread would otherwise
     * be free to mutate it (SyncManager still bumps nwaitsync off the monitor, so a torn read of
     * THAT field is still possible), and — since the write-behind layer — a save left outside the monitor is QUEUED
     * while a concurrent {@code setMain} writes DIRECTLY, so the stale queued stats land after the
     * transition's own and persist {@code nmain} ahead of {@code LAST_COMPLETED_MAIN}: the exact
     * shape the boot consistency check refuses to start on. The try/catch stays — this runs on the
     * check-main scheduler, where a throw would silently cancel the periodic task.
     */
    public synchronized void checkMain() {
        try {
            checkNewMain();
            // xdagStats state will change after checkNewMain
            blockStore.saveXdagStatus(xdagStats);
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void stopCheckMain() {
        try {

            if (checkLoopFuture != null) {
                checkLoopFuture.cancel(true);
            }
            // Shutdown thread pool
            checkLoop.shutdownNow();
            checkLoop.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
        // M5 (SP0b-1): outside the try, so an interrupt above cannot leave the cleaner running.
        stopCleaner();
    }

    public XAmount getStartAmount(long nmain) {
        XAmount startAmount;
        long forkHeight = kernel.getConfig().getApolloForkHeight();
        if (nmain >= forkHeight) {
            startAmount = kernel.getConfig().getApolloForkAmount();
        } else {
            startAmount = kernel.getConfig().getMainStartAmount();
        }

        return startAmount;
    }

    /**
     * Add amount to block
     */
    // TODO: Accept amount to block which in snapshot
    private void addAndAccept(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        try {
            block.getInfo().setAmount(block.getInfo().getAmount().add(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  block {}", oldAmount, amount, block.getHashLow().toHexString());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
        XAmount finalAmount = blockStore.getBlockInfoByHash(block.getHashLow()).getInfo().getAmount();
        log.debug("Balance checker —— block:{} [old:{} add:{} fin:{}]",
                block.getHashLow().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    private void subtractAndAccept(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        try {
            block.getInfo().setAmount(block.getInfo().getAmount().subtract(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  block {}", oldAmount, amount, block.getHashLow().toHexString());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(xdagStats.getBalance().subtract(amount));
        }
        XAmount finalAmount = blockStore.getBlockInfoByHash(block.getHashLow()).getInfo().getAmount();
        log.debug("Balance checker —— block:{} [old:{} sub:{} fin:{}]",
                block.getHashLow().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    private void subtractAmount(Bytes addressHash, XAmount amount, Block block) {
        XAmount balance = addressStore.getBalanceByAddress(addressHash.toArray());
        try {
            addressStore.updateBalance(addressHash.toArray(), balance.subtract(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  addressHsh {}  block {}", balance, amount, Base58.encodeCheck(addressHash), block.getHashLow());
        }
        XAmount finalAmount = addressStore.getBalanceByAddress(addressHash.toArray());
        log.debug("Balance checker —— Address:{} [old:{} sub:{} fin:{}]",
                Base58.encodeCheck(addressHash),
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(xdagStats.getBalance().subtract(amount));
        }
    }

    private void addAmount(Bytes addressHash, XAmount amount, Block block) {
        XAmount balance = addressStore.getBalanceByAddress(addressHash.toArray());
        try {
            addressStore.updateBalance(addressHash.toArray(), balance.add(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  addressHsh {}  block {}", balance, amount, Base58.encodeCheck(addressHash), block.getHashLow());
        }
        XAmount finalAmount = addressStore.getBalanceByAddress(addressHash.toArray());
        log.warn("Balance checker —— Address:{} [old:{} add:{} fin:{}]",
                Base58.encodeCheck(addressHash),
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
    }

    // TODO: Accept amount to block which in snapshot
    private void acceptAmount(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        block.getInfo().setAmount(block.getInfo().getAmount().add(amount));
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
        XAmount finalAmount = blockStore.getBlockByHash(block.getHashLow(), false).getInfo().getAmount();
        log.warn("Balance checker —— Block:{} [old:{} acc:{} fin:{}]",
                block.getHashLow().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
    }

    /**
     * Check if block already exists.
     *
     * <p>Both halves of a saved block, not just the first one. {@code saveBlock} writes the raw 512
     * bytes ({@code blockSource}) before the {@code BlockInfo} ({@code indexSource}), and since
     * SP0b-2 both are queued through the write-behind layer, so a kill — or the failure handler's
     * {@code System.exit(1)}, which drops the queued tail by construction — can land between them.
     * Asking {@code hasBlock} alone then answers EXIST for a block {@code getBlockByHash} cannot
     * return (it reads the info), which is a state nothing repairs: {@code tryToConnect} returns
     * EXIST before it can re-save, every child stays NO_PARENT, and the re-request/EXIST loop
     * throttles sync to one retry per parent-request period, forever. Requiring the info when the
     * raw bytes are there makes the block re-importable instead.
     *
     * <p>The snapshot rule is untouched, and that is why the info is demanded only in the
     * {@code hasBlock} branch: snapshot-imported blocks are info-only on purpose — they have no raw
     * bytes at all — so {@code isExitInSnapshot} still answers for them.
     *
     * <p>Residual, and it is acceptable: the repairing re-import runs {@code saveBlockSums} a second
     * time for this block, so its 512 bytes are counted twice in the sums buckets. The sums are
     * advisory (they serve the {@code SUMS} P2P reply and nothing that validates a block), a torn
     * write is rare, and double-counting a little is strictly better than a permanently stalled
     * chain. Note also that the write order is deliberate: saving the raw bytes last would move the
     * torn state to "info without raw", where {@code getBlockByHash(h, false)} returns a block whose
     * raw lookup is null — and on a snapshot node {@code isExitInSnapshot} would answer EXIST for it
     * and reinstate exactly this stall.
     */
    public boolean isExist(Bytes32 hashlow) {
        return blockStore.hasBlock(hashlow)
                ? blockStore.hasBlockInfo(hashlow)
                : isExitInSnapshot(hashlow);
    }

    public boolean isExistInMem(Bytes32 hashlow) {
        return memOrphanPool.containsKey(hashlow);
    }

    /**
     * Check if exists in snapshot
     */
    public boolean isExitInSnapshot(Bytes32 hashlow) {
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()) {
            // Query block from public key snapshot and signature snapshot
            return blockStore.hasBlockInfo(hashlow);
        } else {
            return false;
        }
    }


    // ADD: Get main blocks using new version method
    public List<Block> listMainBlocksByHeight(int count) {
        List<Block> res = new ArrayList<>();
        long currentHeight = xdagStats.nmain;
        for (int i = 0; i < count; i++) {
            Block block = getBlockByHeightNew(currentHeight - i);
            if (block != null) {
                res.add(block);
            }
        }
        return res;
    }

    // Save the transaction information packaged in the main block
    public void saveMBlockTx(List<Block> blocks) {
        for (Block block : blocks) {
            long time = System.currentTimeMillis();
            if ((block.getInfo().flags & BI_EXTRA) == 0 && getBlockByHash(block.getHashLow(), true) != null) {
                block = getBlockByHash(block.getHashLow(), true);
            }
            List<Address> links = block.getLinks();
            for (Address link : links) {
                if (link.isAddress) continue;
                Block txBlock = getBlockByHash(link.getAddress(), true);
                if (txBlock != null && mBlockTx.get(link.addressHash) == null) {
                    if ((txBlock.getInfo().flags & BI_MAIN_CHAIN) == 0) {
                        mBlockTx.put(link.addressHash, block.getHashLow());
                        mBlockTimedOut.put(link.addressHash, time);
                        log.debug("Save main block: {} , tx: {} , mBlockTx size :{}", block.getHashLow().toHexString(), link.addressHash, mBlockTx.size());
                        continue;
                    }
                    for (Address txLink : txBlock.getLinks()) {
                        if (txLink.getType().equals(XDAG_FIELD_IN)) {
                            mBlockTx.put(link.addressHash, block.getHashLow());
                            mBlockTimedOut.put(link.addressHash, time);
                            log.debug("Save main txBlock: {} , tx: {} , mBlockTx size :{}", block.getHashLow().toHexString(), link.addressHash, mBlockTx.size());
                            break;
                        }
                    }
                }
            }
        }
    }

    // Regularly delete the data of transactions packaged in the main block.
    private void startCleaner() {
        rollBackLoop.scheduleAtFixedRate(() -> cleanMBlockTimeOut(10 * 60 * 1000L), 10, 5, TimeUnit.SECONDS);
    }

    /**
     * M5 (SP0b-1): stops the cleaner the constructor started. Its scheduler owns a non-daemon
     * thread, so a short-lived process that only builds a blockchain (the offline repair tool)
     * would never exit without this. Called from {@link #stopCheckMain()}; {@code shutdownNow} is
     * idempotent, so calling it twice is harmless.
     */
    public void stopCleaner() {
        if (rollBackLoop != null) {
            rollBackLoop.shutdownNow();
        }
    }

    private void cleanMBlockTimeOut(long maxAgeMillis) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Bytes32, Long>> it = mBlockTimedOut.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Bytes32, Long> entry = it.next();
            if (now - entry.getValue() > maxAgeMillis) {
                mBlockTx.remove(entry.getKey());
                it.remove();
                log.debug("Cleaned expired mBlockTX: {} , current mBlockTx size :{}", Hex.toHexString(entry.getKey().toArray()), mBlockTx.size());
            }
        }
    }

    @Override
    public List<Block> listMainBlocks(int count) {
        return listMainBlocksByHeight(count);
    }

    // TODO: List main blocks generated by this pool. If pool only generated blocks early or never generated blocks,
    // need to traverse all block data which needs optimization
    @Override
    public List<Block> listMinedBlocks(int count) {
        Block temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (temp == null) {
            temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getPreTop()), false);
        }
        List<Block> res = Lists.newArrayList();
        while (count > 0) {
            if (temp == null) {
                break;
            }
            if ((temp.getInfo().flags & BI_MAIN) != 0 && (temp.getInfo().flags & BI_OURS) != 0) {
                count--;
                res.add((Block) temp.clone());
            }
            if (temp.getInfo().getMaxDiffLink() == null) {
                break;
            }
            temp = getBlockByHash(Bytes32.wrap(temp.getInfo().getMaxDiffLink()), false);
        }
        return res;
    }

    enum OrphanRemoveActions {
        ORPHAN_REMOVE_NORMAL, ORPHAN_REMOVE_REUSE, ORPHAN_REMOVE_EXTRA
    }
}
