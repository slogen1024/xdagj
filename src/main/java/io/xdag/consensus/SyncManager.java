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
import io.xdag.chain.ingest.PreValidator;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes32;

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

    /** How long the state listener waits after boot before it first looks at the node's state. */
    private static final long INITIAL_STATE_CHECK_DELAY_MS = 100_000;
    /** How long the state listener waits between two state checks. */
    private static final long STATE_CHECK_INTERVAL_MS = 10_000;
    /** How long {@link #doStop} waits for the state listener to finish after interrupting it. */
    private static final long STATE_LISTENER_STOP_WAIT_MS = 5_000;

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
     *
     * <p>{@code volatile}, and assigned only after {@code start()} has returned: the readers are
     * netty I/O threads, and one that saw the field before the commit thread was up would have its
     * submit refused and the block dropped — while without {@code volatile} it might not see the
     * field at all and keep importing synchronously. Not settable from outside, whatever the
     * class-level Lombok says: the lifecycle owns it.
     */
    @Setter(AccessLevel.NONE)
    private volatile IngestPipeline pipeline;

    /**
     * The thread {@link #stateListener} runs on, kept so {@link #doStop} can interrupt it out of a
     * sleep. Null until {@link #doStart} has started it. Not settable from outside: the lifecycle
     * owns it, whatever the class-level Lombok says.
     */
    @Setter(AccessLevel.NONE)
    private volatile Thread stateListenerThread;

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
        // The flag is raised here, before the thread exists, rather than by run() itself: a stop
        // that lands in the window between start() and the thread's first statement used to be
        // undone by run()'s own `isRunning = true`, and the interrupt was lost as well (a thread
        // that has not started yet cannot be interrupted). Raised first, the listener sees a stop
        // at its first check whenever the two race.
        this.stateListener.isRunning = true;
        Thread listener = new Thread(this.stateListener, "xdag-stateListener");
        // Daemon only as a backstop for a path that never reaches doStop(): the interrupt there is
        // the actual shutdown mechanism, and doStop waits for this thread, so a makeSyncDone() in
        // flight is never cut short by a stop.
        listener.setDaemon(true);
        listener.start();
        this.stateListenerThread = listener;
        checkStateFuture = checkStateTask.scheduleAtFixedRate(this::checkState, 64, 5, TimeUnit.SECONDS);
        ChainSpec chainSpec = kernel.getConfig().getChainSpec();
        int threads = chainSpec.getChainIngestThreads();
        int queue = chainSpec.getChainIngestQueue();
        if (threads > 0) {
            IngestPipeline started = new IngestPipeline(threads, queue, this::importPreValidated);
            started.start();
            pipeline = started; // published only once it accepts blocks; see the field's comment
            log.info("Ingest pipeline on: threads={}, queue={}", threads, queue);
        }
    }

    @Override
    protected void doStop() {
        log.debug("sync manager stop");
        // First: the pipeline drains into tryToConnect, so it has to be done importing before the
        // kernel goes on to close the stores underneath it.
        IngestPipeline running = pipeline;
        if (running != null) {
            running.stop();
        }
        stopStateListener();
        stopStateTask();
    }

    /**
     * Ends the state listener and waits a bounded time for it.
     *
     * <p>Clearing the flag is not enough on its own, and used to be all that happened here: the
     * listener spends its first 100 seconds asleep and every later loop 10 seconds asleep, so a
     * node that had been asked to stop kept a non-daemon thread — and with it the JVM — alive for
     * up to 100 seconds after a clean shutdown. The interrupt is what ends the sleep.
     *
     * <p>Then it joins, briefly: the listener calls {@link #makeSyncDone()}, which writes through
     * the transaction-history store and starts the miner, and none of that may still be running
     * when the kernel goes on to close the stores underneath it.
     */
    private void stopStateListener() {
        this.stateListener.isRunning = false;
        Thread listener = this.stateListenerThread;
        if (listener == null) {
            return;
        }
        listener.interrupt();
        try {
            listener.join(STATE_LISTENER_STOP_WAIT_MS);
        } catch (InterruptedException e) {
            // Somebody wants this thread to stop too. Pass it on rather than swallow it, and do
            // not wait any longer.
            Thread.currentThread().interrupt();
            return;
        }
        if (listener.isAlive()) {
            log.warn("xdag-stateListener did not stop within {} ms", STATE_LISTENER_STOP_WAIT_MS);
        }
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
        Block fresh = new Block(new XdagBlock(blockWrapper.getBlock().getXdagBlock().getData().toArray()));
        // reimport() rather than the bare-Block entry point: this block came from a peer and the
        // wrapper still says which one, so the orphan pool's per-source quota can charge it. The
        // bare entry point fabricates a peerless wrapper and would throw that away, which on the
        // NO_PARENT retry -- routine traffic while a node catches up, not an edge case -- would
        // hand a flooder an unattributed, unclassified chunk for nothing.
        return relayImported(blockWrapper, blockchain.tryToConnect(PreValidator.reimport(blockWrapper, fresh)));
    }

    /** Gossips a block this node has just accepted onward, unless it is old, ours, or out of ttl. */
    private ImportResult relayImported(BlockWrapper blockWrapper, ImportResult importResult) {
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
     * @throws IngestPipeline.SubmitRejectedException if the pipeline is not accepting blocks: a
     *                                                shutdown, or a commit thread that has died and
     *                                                left the node importing nothing. The caller
     *                                                tells the two apart with {@code
     *                                                isCommitterDead()} — the second one an operator
     *                                                has to be told about.
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
     * The pipeline's commit step: import the private copy the pre-validation parsed, with the facts
     * it computed off the monitor, then exactly the bookkeeping {@link #validateAndAddNewBlock}
     * does. Runs on the single commit thread, in arrival order.
     *
     * <p>The chain never sees {@code pv.wrapper().getBlock()} here: that instance stays untouched
     * for the relay, which re-serializes it after this returns. A pre-validation that failed has no
     * copy to offer, so it falls back to {@link #importBlock}, which makes one the old way.
     */
    public synchronized ImportResult importPreValidated(PreValidated pv) {
        BlockWrapper blockWrapper = pv.wrapper();
        if (pv.error() != null) {
            log.debug("ingest pre-validation failed, importing the block the old way", pv.error());
            ImportResult fallback = importBlock(blockWrapper);
            releaseWaiters(blockWrapper, fallback);
            return fallback;
        }
        ImportResult result = relayImported(blockWrapper, blockchain.tryToConnect(pv));
        log.debug("importPreValidated:{}, {}", pv.hashLow(), result);
        releaseWaiters(blockWrapper, result);
        return result;
    }

    public synchronized ImportResult validateAndAddNewBlock(BlockWrapper blockWrapper) {
        blockWrapper.getBlock().parse();
        ImportResult result = importBlock(blockWrapper);
        log.debug("validateAndAddNewBlock:{}, {}", blockWrapper.getBlock().getHashLow(), result);
        releaseWaiters(blockWrapper, result);
        return result;
    }

    /** Releases the children waiting on this block, or queues it behind the parent it is missing. */
    private void releaseWaiters(BlockWrapper blockWrapper, ImportResult result) {
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
            // Refused on this node's own policy, which is a statement about this node and not
            // about the block. It pops for the same reason EXIST does -- not because the block is
            // in the DAG (it is not), but because the children waiting on it have to be allowed to
            // move. A released child re-imports, finds the parent genuinely missing, answers
            // NO_PARENT and is pushed back under that hash, which re-sends the request for it; and
            // a block this node requests is exempt from the policy that refused the broadcast, so
            // the second copy is taken. Left in the default arm below -- where a new enum constant
            // lands silently -- it would strand the subtree exactly as INVALID_BLOCK does, which is
            // the cost this code exists to avoid.
            case CHAIN_FEE_POLICY -> syncPopBlock(blockWrapper);
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
            evictFromSyncMap();
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
     * Drops up to {@link #DELETE_NUM} entries once {@code syncMap} has reached {@link #MAX_SIZE}.
     * Everything in there is a block whose parent never turned up, so any of them is as good a
     * victim as any other; the only thing that matters is that the map stops growing.
     *
     * <p>The key snapshot is taken <b>once</b>. It used to be rebuilt inside the loop — 5,000 copies
     * of a 500,000-entry key set, some 2.5 billion reference copies and 5,000 allocations of a
     * multi-megabyte array, to delete 5,000 entries. Since SP0b-2 that runs on the single ingest
     * commit thread and holds this object's monitor throughout, so a peer that feeds the node enough
     * blocks with unknown parents could drive {@code syncMap} to {@code MAX_SIZE} and stall the whole
     * import path — every netty thread backed up behind the pipeline's backpressure semaphore — for
     * minutes at a time. One snapshot is the same eviction for 1/DELETE_NUM of the work.
     *
     * <p>Victims are still drawn at random, but now <b>without replacement</b>: a partial
     * Fisher-Yates over the snapshot hands out distinct keys. Drawing with replacement, as the old
     * code did, re-drew keys it had already removed, and each repeat was a {@code remove} that
     * returned null and freed nothing — it quietly under-deleted (about 4,975 of the 5,000 asked
     * for, at {@code MAX_SIZE}). {@code nwaitsync} stays right either way: it is only decremented
     * when {@code remove} actually took an entry out.
     *
     * <p>{@code syncMap} is concurrent and is emptied from elsewhere — see {@link #syncPopBlock} and
     * the {@code merge} in {@link #syncPushBlock} — so by the time the snapshot is taken it may hold
     * fewer than {@code MAX_SIZE} keys, or none. The loop is bounded by the snapshot's own length.
     * The old code instead re-read {@code keyList.size()} every iteration and, on a map that had
     * emptied under it, handed a zero to {@code CryptoProvider.nextInt(0, 0)} — which throws
     * {@code IllegalArgumentException("bound must be greater than origin")} straight out of the
     * import path.
     */
    private void evictFromSyncMap() {
        Bytes32[] keys = syncMap.keySet().toArray(new Bytes32[0]);
        int quota = Math.min(DELETE_NUM, keys.length);
        for (int i = 0; i < quota; i++) {
            // Partial Fisher-Yates: draw from the tail that has not been handed out yet and swap the
            // pick away, so no key is offered twice. The bound is exclusive and keys.length > i here,
            // so the empty-range throw above is unreachable.
            int pick = CryptoProvider.nextInt(i, keys.length);
            Bytes32 key = keys[pick];
            keys[pick] = keys[i];
            // A real check. What stood here was `assert key != null`, which is off unless the JVM was
            // started with -ea — surefire may enable assertions where a production node does not, so
            // that line protected the tests and nothing else.
            if (key == null) {
                continue;
            }
            if (syncMap.remove(key) != null) {
                blockchain.getXdagStats().nwaitsync--;
            }
        }
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
                    // CHAIN_FEE_POLICY rides with them for the reason releaseWaiters spells out:
                    // this switch is the same decision one level down, and a child refused on
                    // policy has waiters of its own to release. Handling it only in the outer
                    // switch would move the stranding down a generation rather than end it.
                    case EXIST, IN_MEM, IMPORTED_BEST, IMPORTED_NOT_BEST, CHAIN_FEE_POLICY -> {
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

        /**
         * Volatile: written by whichever thread stops the kernel (and by {@link #makeSyncDone()}),
         * read by the listener thread. A plain field gave the two no happens-before edge at all,
         * so a clear could go unseen indefinitely and the listener loop on forever.
         */
        volatile boolean isRunning = false;

        @Override
        public void run() {
            // Nothing is worth checking for the first stretch after boot: the node has not had
            // time to hear from a peer yet.
            if (!sleepUnlessStopping(INITIAL_STATE_CHECK_DELAY_MS)) {
                return;
            }
            while (this.isRunning) {
                if (isTimeToStart()) {
                    makeSyncDone();
                }
                if (!sleepUnlessStopping(STATE_CHECK_INTERVAL_MS)) {
                    return;
                }
            }
        }

        /**
         * Sleeps for {@code millis}, returning false if the listener has been asked to stop —
         * before the sleep, by the interrupt that ended it, or by the flag afterwards.
         *
         * <p>An interrupt means "stop now" and is never rethrown. The first sleep used to wrap it
         * in a {@code RuntimeException}, which ended the thread with an uncaught exception the
         * moment anybody interrupted it, and the second only logged it and slept again.
         */
        private boolean sleepUnlessStopping(long millis) {
            if (!this.isRunning) {
                return false;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return this.isRunning;
        }
    }

}
