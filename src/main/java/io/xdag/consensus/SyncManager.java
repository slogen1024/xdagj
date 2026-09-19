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

package io.xdag.consensus;

import com.google.common.collect.Queues;
import io.xdag.Kernel;
import io.xdag.chain.ingest.IngestPipeline;
import io.xdag.chain.ingest.PreValidated;
import io.xdag.config.*;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.*;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.encoding.Base58;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.net.Channel;
import io.xdag.net.ChannelManager;
import io.xdag.net.Peer;
import io.xdag.net.node.Node;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static io.xdag.core.ImportResult.*;
import static io.xdag.core.XdagState.*;
import static io.xdag.utils.BasicUtils.hash2byte;
import static io.xdag.utils.XdagTime.msToXdagtimestamp;

@Slf4j
@Getter
@Setter
public class SyncManager extends AbstractXdagLifecycle {
    // Maximum size of syncMap
    public static final int MAX_SIZE = 500000;
    // Number of keys to remove when syncMap exceeds MAX_SIZE
    public static final int DELETE_NUM = 5000;

    private static final ThreadFactory factory = new BasicThreadFactory.Builder()
            .namingPattern("SyncManager-thread-%d")
            .daemon(true)
            .build();
    private Kernel kernel;
    private Blockchain blockchain;
    private long importStart;
    private AtomicLong importIdleTime = new AtomicLong();
    private AtomicBoolean syncDone = new AtomicBoolean(false);
    private AtomicBoolean isUpdateXdagStats = new AtomicBoolean(false);
    private ChannelManager channelMgr;

    // Monitor whether to start itself
    private StateListener stateListener;
    /**
     * Queue with validated blocks to be added to the blockchain
     */
    private Queue<BlockWrapper> blockQueue = new ConcurrentLinkedQueue<>();
    /**
     * Queue for blocks with missing links
     */
    private ConcurrentHashMap<Bytes32, Queue<BlockWrapper>> syncMap = new ConcurrentHashMap<>();
    /**
     * Queue for polling oldest blocks
     */
    private ConcurrentLinkedQueue<Bytes32> syncQueue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService checkStateTask;

    private ScheduledFuture<?> checkStateFuture;
    private final TransactionHistoryStore txHistoryStore;
    /**
     * Parallel pre-validation in front of the import (SP0b-2), or null when {@code chain.ingest.threads}
     * is 0 and the node imports synchronously as it did before. Built and started by {@link #doStart},
     * stopped by {@link #doStop}.
     */
    private IngestPipeline pipeline;

    public SyncManager(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelMgr();
        this.stateListener = new StateListener();
        checkStateTask = new ScheduledThreadPoolExecutor(1, factory);
        this.txHistoryStore = kernel.getTxHistoryStore();
    }

    @Override
    protected void doStart() {
        log.debug("Download receiveBlock run...");
        new Thread(this.stateListener, "xdag-stateListener").start();
        checkStateFuture = checkStateTask.scheduleAtFixedRate(this::checkState, 64, 5, TimeUnit.SECONDS);
        ChainSpec chainSpec = kernel.getConfig().getChainSpec();
        int threads = chainSpec.getChainIngestThreads();
        int queue = chainSpec.getChainIngestQueue();
        if (threads > 0) {
            pipeline = new IngestPipeline(threads, queue, this::importPreValidated);
            pipeline.start();
            log.info("Ingest pipeline on: threads={}, queue={}", threads, queue);
        }
    }

    @Override
    protected void doStop() {
        log.debug("sync manager stop");
        // First: the pipeline drains into tryToConnect, so it has to be done importing before the
        // kernel goes on to close the stores underneath it.
        if (pipeline != null) {
            pipeline.stop();
        }
        if (this.stateListener.isRunning) {
            this.stateListener.isRunning = false;
        }
        stopStateTask();
    }

    private void checkState() {
        if (!isUpdateXdagStats.get()) {
            return;
        }
        if (syncDone.get()) {
            stopStateTask();
            return;
        }

        XdagStats xdagStats = kernel.getBlockchain().getXdagStats();
        XdagTopStatus xdagTopStatus = kernel.getBlockchain().getXdagTopStatus();
        long lastTime = kernel.getSync().getLastTime();
        long curTime = msToXdagtimestamp(System.currentTimeMillis());
        long curHeight = xdagStats.getNmain();
        long maxHeight = xdagStats.getTotalnmain();
        // Exit the syncOld state based on time and height.
        if (!isSync() && (curHeight >= maxHeight - 512 || lastTime >= curTime - 32 * REQUEST_BLOCKS_MAX_TIME)) {
            log.debug("our node height:{} the max height:{}, set sync state", curHeight, maxHeight);
            setSyncState();
        }
        // Confirm whether the synchronization is complete based on time and height.
        if (curHeight >= maxHeight || xdagTopStatus.getTopDiff().compareTo(xdagStats.maxdifficulty) >= 0) {
            log.debug("our node height:{} the max height:{}, our diff:{} max diff:{}, make sync done",
                    curHeight, maxHeight, xdagTopStatus.getTopDiff(), xdagStats.maxdifficulty);
            makeSyncDone();
        }

    }

    /**
     * Monitor kernel state to determine if it's time to start
     */
    public boolean isTimeToStart() {
        boolean res = false;
        Config config = kernel.getConfig();
        int waitEpoch = config.getNodeSpec().getWaitEpoch();
        if (!isSync() && !isSyncOld() && (XdagTime.getCurrentEpoch() > kernel.getStartEpoch() + waitEpoch)) {
            res = true;
        }
        if (res) {
            log.debug("Waiting time exceeded,starting pow");
        }
        return res;
    }

    /**
     * Process blocks in queue and add them to the chain
     */
    // TODO: Modify consensus
    public ImportResult importBlock(BlockWrapper blockWrapper) {
        log.debug("importBlock:{}", blockWrapper.getBlock().getHashLow());
        // Re-parsed from the raw 512 bytes on purpose, and that has to stay: a block sitting in
        // syncMap carries the flags a previous attempt wrote (tryToConnect sets BI_EXTRA before it
        // can decide NO_PARENT), so every attempt has to start from a clean BlockInfo.
        return finishImport(blockWrapper, blockchain
                .tryToConnect(new Block(new XdagBlock(blockWrapper.getBlock().getXdagBlock().getData().toArray()))));
    }

    /** What both entry points do with an import's verdict before the caller sees it: log, then relay. */
    private ImportResult finishImport(BlockWrapper blockWrapper, ImportResult importResult) {
        if (importResult == EXIST) {
            log.debug("Block have exist:{}", blockWrapper.getBlock().getHashLow());
        }

        if (!blockWrapper.isOld() && (importResult == IMPORTED_BEST || importResult == IMPORTED_NOT_BEST)) {
            Peer blockPeer = blockWrapper.getRemotePeer();
            Node node = kernel.getClient().getNode();
            if (blockPeer == null || !StringUtils.equals(blockPeer.getIp(), node.getIp()) || blockPeer.getPort() != node.getPort()) {
                if (blockWrapper.getTtl() > 0) {
                    distributeBlock(blockWrapper);
                }
            }
        }
        return importResult;
    }

    /**
     * The network entry point (SP0b-2): the pipeline when it is on, else the synchronous import.
     *
     * <p>Deliberately NOT {@code synchronized}, and it must never be called from a method that is.
     * {@code IngestPipeline.submit} holds its backpressure permit from here until the commit of that
     * block returns, and the committer is {@link #importPreValidated}, which IS synchronized on this
     * object: a caller parked here at queue capacity while holding this monitor would deadlock the
     * pipeline. For the same reason nothing here touches the block — {@code Block.parse()} and
     * {@code Block.getHashLow()} are unsynchronized lazy mutators, and a caller-side {@code parse()}
     * racing the pool's would duplicate the block's inputs and outputs.
     *
     * @throws IllegalStateException if the pipeline is no longer accepting blocks (shutdown)
     */
    public void submitBlock(BlockWrapper blockWrapper) {
        IngestPipeline p = pipeline;
        if (p != null) {
            p.submit(blockWrapper);
        } else {
            validateAndAddNewBlock(blockWrapper);
        }
    }

    /**
     * The pipeline's commit step: import with the facts computed off the monitor, then exactly the
     * bookkeeping {@link #validateAndAddNewBlock} does. Runs on the single commit thread, in arrival
     * order.
     */
    public synchronized ImportResult importPreValidated(PreValidated pv) {
        BlockWrapper blockWrapper = pv.wrapper();
        ImportResult result = finishImport(blockWrapper, blockchain.tryToConnect(pv));
        log.debug("importPreValidated:{}, {}", blockWrapper.getBlock().getHashLow(), result);
        afterImport(blockWrapper, result);
        return result;
    }

    public synchronized ImportResult validateAndAddNewBlock(BlockWrapper blockWrapper) {
        blockWrapper.getBlock().parse();
        ImportResult result = importBlock(blockWrapper);
        log.debug("validateAndAddNewBlock:{}, {}", blockWrapper.getBlock().getHashLow(), result);
        afterImport(blockWrapper, result);
        return result;
    }

    /** Releases the children waiting on this block, or queues it behind the parent it is missing. */
    private void afterImport(BlockWrapper blockWrapper, ImportResult result) {
        switch (result) {
            case EXIST, IMPORTED_BEST, IMPORTED_NOT_BEST, IN_MEM -> syncPopBlock(blockWrapper);
            case NO_PARENT -> {
                if (syncPushBlock(blockWrapper, result.getHashlow())) {//Return true to indicate that it has been more than 60 seconds since the last time it was placed here due to the lack of a parent reference, and request to inquire about the parent block from other nodes again
                    log.debug("push block:{}, NO_PARENT {}", blockWrapper.getBlock().getHashLow(), result);
                    List<Channel> channels = channelMgr.getActiveChannels();
                    for (Channel channel : channels) {
                        // if (channel.getRemotePeer().equals(blockWrapper.getRemotePeer())) {
                        channel.getP2pHandler().sendGetBlock(result.getHashlow(), blockWrapper.isOld());
                        //}
                    }

                }
            }
            case INVALID_BLOCK -> {
//                log.error("invalid block:{}", Hex.toHexString(blockWrapper.getBlock().getHashLow()));
            }
            default -> {
            }
        }
    }

    /**
     * Synchronize missing blocks
     *
     * @param blockWrapper New block
     * @param hashLow Hash of missing parent block
     */
    public boolean syncPushBlock(BlockWrapper blockWrapper, Bytes32 hashLow) {
        if (syncMap.size() >= MAX_SIZE) {
            for (int j = 0; j < DELETE_NUM; j++) {
                List<Bytes32> keyList = new ArrayList<>(syncMap.keySet());

                Bytes32 key = keyList.get(CryptoProvider.nextInt(0, keyList.size()));
                assert key != null;
                if (syncMap.remove(key) != null) blockchain.getXdagStats().nwaitsync--;
            }
        }
        AtomicBoolean r = new AtomicBoolean(true);
        long now = System.currentTimeMillis();

        Queue<BlockWrapper> newQueue = Queues.newConcurrentLinkedQueue();
        blockWrapper.setTime(now);
        newQueue.add(blockWrapper);
        blockchain.getXdagStats().nwaitsync++;

        syncMap.merge(hashLow, newQueue,
                (oldQ, newQ) -> {
                    blockchain.getXdagStats().nwaitsync--;
                    for (BlockWrapper b : oldQ) {
                        if (b.getBlock().getHashLow().equals(blockWrapper.getBlock().getHashLow())) {
                            // after 64 sec must resend block request
                            if (now - b.getTime() > 64 * 1000) {
                                b.setTime(now);
                                r.set(true);
                            } else {
                                // TODO: Consider timeout for unreceived request block
                                r.set(false);
                            }
                            return oldQ;
                        }
                    }
                    oldQ.add(blockWrapper);
                    r.set(true);
                    return oldQ;
                });
        return r.get();
    }

    /**
     * Release child blocks based on received block
     */
    public void syncPopBlock(BlockWrapper blockWrapper) {
        Block block = blockWrapper.getBlock();

        Queue<BlockWrapper> queue = syncMap.getOrDefault(block.getHashLow(), null);
        if (queue != null) {
            syncMap.remove(block.getHashLow());
            blockchain.getXdagStats().nwaitsync--;
            queue.forEach(bw -> {
                ImportResult importResult = importBlock(bw);
                switch (importResult) {
                    case EXIST, IN_MEM, IMPORTED_BEST, IMPORTED_NOT_BEST -> {
                        // TODO: Need to remove after successful import
                        syncPopBlock(bw);
                        queue.remove(bw);
                    }
                    case NO_PARENT -> {
                        if (syncPushBlock(bw, importResult.getHashlow())) {
                            log.debug("push block:{}, NO_PARENT {}", bw.getBlock().getHashLow(),
                                    importResult.getHashlow().toHexString());
                            List<Channel> channels = channelMgr.getActiveChannels();
                            for (Channel channel : channels) {
//                            Peer remotePeer = channel.getRemotePeer();
//                            Peer blockPeer = bw.getRemotePeer();
                                // if (StringUtils.equals(remotePeer.getIp(), blockPeer.getIp()) && remotePeer.getPort() == blockPeer.getPort() ) {
                                channel.getP2pHandler().sendGetBlock(importResult.getHashlow(), blockWrapper.isOld());
                                //}
                            }
                        }
                    }
                    default -> {
                    }
                }
            });
        }
    }

    // TODO: Currently stays in sync by default, not responsible for block generation
    public void makeSyncDone() {
        if (syncDone.compareAndSet(false, true)) {
            // Stop state check process
            this.stateListener.isRunning = false;
            Config config = kernel.getConfig();
            if (config instanceof MainnetConfig) {
                if (kernel.getXdagState() != XdagState.SYNC) {
                    kernel.setXdagState(XdagState.SYNC);
                }
            } else if (config instanceof TestnetConfig) {
                if (kernel.getXdagState() != XdagState.STST) {
                    kernel.setXdagState(XdagState.STST);
                }
            } else if (config instanceof DevnetConfig) {
                if (kernel.getXdagState() != XdagState.SDST) {
                    kernel.setXdagState(XdagState.SDST);
                }
            }

            log.info("sync done, the last main block number = {}", blockchain.getXdagStats().nmain);
            kernel.getSync().setStatus(XdagSync.Status.SYNC_DONE);
            if (config.getEnableTxHistory() && txHistoryStore != null) {
                // Sync done, batch write remaining history
                txHistoryStore.batchSaveTxHistory(null);
            }

            if (config.getEnableGenerateBlock()) {
                log.info("start pow at:{}",
                        FastDateFormat.getInstance("yyyy-MM-dd 'at' HH:mm:ss z").format(new Date()));
                // Check main chain
//                kernel.getMinerServer().start();
                kernel.getPow().start();
            } else {
                log.info("A non-mining node, will not generate blocks.");
            }
        }
    }

    public void setSyncState() {
        Config config = kernel.getConfig();
        if (config instanceof MainnetConfig) {
            kernel.setXdagState(CONN);
        } else if (config instanceof TestnetConfig) {
            kernel.setXdagState(CTST);
        } else if (config instanceof DevnetConfig) {
            kernel.setXdagState(CDST);
        }
    }

    public boolean isSync() {
        return kernel.getXdagState() == CONN || kernel.getXdagState() == CTST
                || kernel.getXdagState() == CDST;
    }

    public boolean isSyncOld() {
        return kernel.getXdagState() == CONNP || kernel.getXdagState() == CTSTP
                || kernel.getXdagState() == CDSTP;
    }

    private void stopStateTask() {
        if (checkStateFuture != null) {
            checkStateFuture.cancel(true);
        }
        // Shutdown thread pool
        checkStateTask.shutdownNow();
    }

    public void distributeBlock(BlockWrapper blockWrapper) {
        channelMgr.onNewForeignBlock(blockWrapper);
    }

    private class StateListener implements Runnable {

        boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            try {
                Thread.sleep(100000);

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (this.isRunning) {
                if (isTimeToStart()) {
                    makeSyncDone();
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

}
