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
import io.xdag.chain.l1.ChainL1Hooks;
import io.xdag.chain.l1.ChainL1Processor;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
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
    private static final long MAX_ORPHAN_SIZE = 3750;

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

    // In-memory pools and maps
    private final LinkedHashMap<Bytes, Block> memOrphanPool = new LinkedHashMap<>();
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
    public synchronized ImportResult tryToConnect(Block block) {

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

            if (isAccountTx(block) && orphanBlockStore.getOrphanSize() >= MAX_ORPHAN_SIZE) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("Orphan block pool is full");
                log.debug("Orphan block pool is full");
                return result;
            }

            // Check if block already exists
            if (isExist(block.getHashLow())) {
                return ImportResult.EXIST;
            }

            if (isExistInMem(block.getHashLow())) {
                return ImportResult.IN_MEM;
            }

            // Check if extra block
            if (isExtraBlock(block)) {
                updateBlockFlag(block, BI_EXTRA, true);
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
                    Block refBlock = getBlockByHash(ref.getAddress(), false);
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

            // Validate block inputs
            if (!canUseInput(block)) {
                result = ImportResult.INVALID_BLOCK;
                result.setHashlow(block.getHashLow());
                result.setErrorInfo("Block's input can't be used");
                log.debug("Block's input can't be used");
                return ImportResult.INVALID_BLOCK;
            }

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
                saveBlock(block);
                dealOrphan(block);
                xdagStats.nnoref++;
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

    public void dealOrphan(Block block) {
        if (kernel.getConfig().getEnableGenerateBlock() && kernel.getPow() != null) {
            UInt64 nonce = UInt64.ZERO;
            XAmount fee = getTxFee(block);
            byte[] address = null;
            if (isAccountTx(block)) {
                List<Address> refs = block.getLinks();
                for (Address txRef : refs) {
                    if (txRef.getType().equals(XDAG_FIELD_INPUT)) {
                        address = BytesUtils.byte32ToArray(txRef.getAddress()).toArray();
                        nonce = block.getTxNonceField().getTransactionNonce();
                        break;
                    }
                }
            }
            getOrphanBlockStore().addOrphan(block, isTxBlock(block), nonce, fee, address);
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
            Block reuse = memOrphanPool.entrySet().iterator().next().getValue();
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
                    addressStore.updateTxQuantity(BasicUtils.hash2byte(linkAddress).toArray(), executedNonce);
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
                Block ref = getBlockByHash(linkAddress, false);
                if (link.getType() == XDAG_FIELD_IN) {
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
                    Block ref = getBlockByHash(link.getAddress(), false);
                    if (link.getType() == XDAG_FIELD_IN) {
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
                        addressStore.updateTxQuantity(address, exeNonce.subtract(UInt64.ONE));
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
                        addressStore.updateTxQuantity(address.toArray(), exeNonce.subtract(UInt64.ONE));
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
            if (CollectionUtils.isNotEmpty(orphans)) {
                refs.addAll(orphans);
            }
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
        List<PublicKey> keys = block.verifiedKeys();
        List<Address> inputs = block.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return true;
        }
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
     * Check if block already exists
     */
    public boolean isExist(Bytes32 hashlow) {
        return blockStore.hasBlock(hashlow) || isExitInSnapshot(hashlow);
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
