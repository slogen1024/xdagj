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

package io.xdag;

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;

import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.repair.ChainConsistencyCheck;
import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.config.spec.ChainSpec;
import io.xdag.consensus.SyncManager;
import io.xdag.consensus.XdagPow;
import io.xdag.consensus.XdagSync;
import io.xdag.core.*;
import io.xdag.consensus.RandomX;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.*;
import io.xdag.db.mysql.TransactionHistoryStoreImpl;
import io.xdag.db.rocksdb.*;
import io.xdag.net.*;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.NodeManager;
import io.xdag.pool.WebSocketServer;
import io.xdag.pool.PoolAwardManagerImpl;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.api.impl.XdagApiImpl;
import io.xdag.utils.XdagTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Getter
@Setter
public class Kernel {

    // Node status
    protected Status status = Status.STOPPED;
    protected Config config;
    protected Wallet wallet;
    protected ECKeyPair coinbase;
    protected DatabaseFactory dbFactory;
    /**
     * SP0b-2: what a consensus state transition needs from the persistence layer — drain the
     * queued writes, then run the transition in direct-write mode. {@link PersistControl#NONE}
     * (both a no-op) whenever no write-behind layer is installed: {@code chain.persist.maxPending}
     * is 0, or the stores were built by something other than {@link #testStart()} (the offline
     * tools and the test fixtures). Read once, by {@code BlockchainImpl}'s constructor, so it has
     * to be set before the blockchain is built.
     */
    protected PersistControl persist = PersistControl.NONE;
    protected AddressStore addressStore;
    protected BlockStore blockStore;
    protected OrphanBlockStore orphanBlockStore;
    protected TransactionHistoryStore txHistoryStore;
    protected ChainL1Store chainL1Store;
    /**
     * Chain extension-kind semantics, keyed by kind. Handlers SP2/SP3 register BEFORE the kernel
     * constructs {@code BlockchainImpl}; consulted once in the {@code BlockchainImpl} constructor,
     * never afterwards — that constructor creates the {@code ChainL1Processor} and hands it every
     * entry of this map before the check-main loop can confirm anything, and the processor refuses
     * a registration once its first hook has run. Putting a handler in here after construction
     * therefore has no effect at all. Never null; empty in SP0a, which ships no handlers.
     *
     * @see io.xdag.chain.l1.ChainKindHandler
     */
    protected final Map<ExtKind, ChainKindHandler> chainKindHandlers = new EnumMap<>(ExtKind.class);

    /**
     * SP0b-1: when true, {@code BlockchainImpl}'s constructor records a non-clean consistency report
     * in {@link #consistencyReport} instead of throwing, and does not start the check-main loop —
     * nothing may be confirmed behind the repair tool's back while the main chain is being fixed.
     * Entered by the offline repair tool ({@code --repairchain}) only, through
     * {@link #enterRepairMode()}; there is deliberately no generated setter and no way back.
     */
    @Setter(AccessLevel.NONE)
    protected boolean repairMode;

    /**
     * SP0b-1: the startup consistency report of the most recent {@code BlockchainImpl} construction.
     * Always filed by that constructor, clean or not, through {@link #recordConsistencyReport}.
     * The repair tool re-scans the stores at entry and files its own reports here too; it reads the
     * boot copy only for what the constructor already did (marker initialization). Null before the
     * blockchain has been built.
     */
    @Setter(AccessLevel.NONE)
    protected ChainConsistencyCheck.Report consistencyReport;

    protected SnapshotStore snapshotStore;
    protected Blockchain blockchain;
    protected NetDB netDB;
    protected PeerClient client;
    protected ChannelManager channelMgr;
    protected NodeManager nodeMgr;
    protected NetDBManager netDBMgr;
    protected PeerServer p2p;
    protected XdagSync sync;
    protected XdagPow pow;
    private SyncManager syncMgr;

    protected Bytes firstAccount;
    protected Block firstBlock;
    protected WebSocketServer webSocketServer;
    protected PoolAwardManagerImpl poolAwardManager;
    protected XdagState xdagState;

    // Counter for connected channels
    protected AtomicInteger channelsAccount = new AtomicInteger(0);

    protected TelnetServer telnetServer;

    protected RandomX randomx;

    // Running status flag
    protected AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Start time epoch
    protected long startEpoch;

    // RPC related components
    protected XdagApi api;

    public Kernel(Config config, Wallet wallet) {
        this.config = config;
        this.wallet = wallet;
        this.coinbase = wallet.getDefKey();
        this.xdagState = XdagState.INIT;
    }

    public Kernel(Config config, ECKeyPair coinbase) {
        this.config = config;
        this.coinbase = coinbase;
    }

    /**
     * Start the kernel.
     */
    public synchronized void testStart() {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);
        startEpoch = XdagTime.getCurrentEpoch();

        // Initialize channel manager
        channelMgr = new ChannelManager(this);
        channelMgr.start();

        netDBMgr = new NetDBManager(this.config);
        netDBMgr.start();

        // Initialize database components
        dbFactory = new RocksdbFactory(this.config);

        // SP0b-2: the write-behind layer, when the node is configured for it. Installed here, before
        // any store is built from the factory, so BLOCK/TIME/INDEX/ORPHANIND are wrapped for every
        // store at once; the queue's writer thread is owned by this kernel and is stopped by
        // dbFactory.close() (WriteBehindFactory.close() flushes first). Only the wrapping happens
        // out here -- the thread is started inside the try below, so no failure path can leave a
        // running non-daemon writer that nothing owns.
        ChainSpec chainSpec = config.getChainSpec();
        WriteBehindQueue persistQueue = null;
        if (chainSpec.getChainPersistMaxPending() > 0) {
            persistQueue = new WriteBehindQueue(chainSpec.getChainPersistMaxPending(),
                    chainSpec.getChainPersistFlushEntries(), chainSpec.getChainPersistFlushMs(), true);
            dbFactory = new WriteBehindFactory(dbFactory, persistQueue, chainSpec.getChainPersistReadCache());
            persist = persistQueue;
        }

        try {
            if (persistQueue != null) {
                persistQueue.setFailureHandler(this::onPersistFailure);
                persistQueue.start();
                log.info("Write-behind persistence on: maxPending={}, flushEntries={}, flushMs={}, readCache={}",
                        chainSpec.getChainPersistMaxPending(), chainSpec.getChainPersistFlushEntries(),
                        chainSpec.getChainPersistFlushMs(), chainSpec.getChainPersistReadCache());
            }

            // BlockStoreImpl.forNode, not the constructor: the databases named BLOCK and TIME go into
            // swapped roles here, and that is the on-disk layout of every node (see forNode's javadoc).
            blockStore = BlockStoreImpl.forNode(dbFactory);
            log.info("Block Store init.");
            blockStore.start();

            addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
            addressStore.start();


            orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND) , this);
            orphanBlockStore.start();

            // Chain contracts (SP0a): CHAIN_L1 index consumed by the chain hooks. Must exist before
            // new BlockchainImpl(this) below, whose constructor installs the ChainL1Processor from it.
            // Stopped in testStop() before the databases are closed, so isRunning() stays truthful.
            chainL1Store = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
            chainL1Store.start();

            if (config.getEnableTxHistory()) {
                long txPageSizeLimit = config.getTxPageSizeLimit();
                txHistoryStore = new TransactionHistoryStoreImpl(txPageSizeLimit);
                log.info("Transaction History Store init.");
            }

            // Initialize network components
            netDB = new NetDB();

            // Initialize RandomX
            randomx = new RandomX(config);
            randomx.start();

            // Initialize blockchain
            blockchain = new BlockchainImpl(this);
            XdagStats xdagStats = blockchain.getXdagStats();
        
            // Create genesis block if first startup
            if (xdagStats.getOurLastBlockHash() == null) {
                firstAccount = toBytesAddress(wallet.getDefKey().getPublicKey());
                firstBlock = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                        null, null, -1, XAmount.ZERO, null);
                firstBlock.signOut(wallet.getDefKey());
                xdagStats.setOurLastBlockHash(firstBlock.getHashLow().toArray());
                if (xdagStats.getGlobalMiner() == null) {
                    xdagStats.setGlobalMiner(firstAccount.toArray());
                }
                blockchain.tryToConnect(new Block(firstBlock.getXdagBlock()));
            } else {
                firstAccount = toBytesAddress(wallet.getDefKey().getPublicKey());
            }

            // Initialize RandomX based on snapshot configuration
            if (config.getSnapshotSpec().isSnapshotJ()) {
                randomx.randomXLoadingSnapshotJ();
                blockStore.setSnapshotBoot();
            } else {
                if (config.getSnapshotSpec().isSnapshotEnabled() && !blockStore.isSnapshotBoot()) {
                    System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                    randomx.randomXLoadingSnapshot(blockchain.getPreSeed(), 0);
                    blockStore.setSnapshotBoot();
                } else if (config.getSnapshotSpec().isSnapshotEnabled() && blockStore.isSnapshotBoot()) {
                    System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                    randomx.randomXLoadingForkTimeSnapshot(blockchain.getPreSeed(), 0);
                } else {
                    randomx.randomXLoadingForkTime();
                }
            }

            // Set initial state based on network type
            if (config instanceof MainnetConfig) {
                xdagState = XdagState.WAIT;
            } else if (config instanceof TestnetConfig) {
                xdagState = XdagState.WTST;
            } else if (config instanceof DevnetConfig) {
                xdagState = XdagState.WDST;
            }

            // Initialize P2P networking
            p2p = new PeerServer(this);
            p2p.start();
            client = new PeerClient(this.config, this.coinbase);

            // Initialize node management
            nodeMgr = new NodeManager(this);
            nodeMgr.start();

            // Initialize synchronization
            sync = new XdagSync(this);
            sync.start();

            syncMgr = new SyncManager(this);
            syncMgr.start();

            poolAwardManager = new PoolAwardManagerImpl(this);

            // Initialize mining
            pow = new XdagPow(this);

            if (webSocketServer == null) {
                webSocketServer = new WebSocketServer(this, config.getPoolWhiteIPList(), config.getWebsocketServerPort());
            }
            webSocketServer.start();

            // Start RPC
            api = new XdagApiImpl(this);
            api.start();

            // Start Telnet Server
            telnetServer = new TelnetServer(this);
            telnetServer.start();

            blockchain.registerListener(pow);

            Launcher.registerShutdownHook("kernel", this::testStop);
        } catch (RuntimeException | Error e) {
            // G3 (SP0a §12.2): a failure anywhere after the databases opened used to leak every
            // store — and with SP0b-2 the write-behind writer thread, which is not a daemon. What
            // started before the failure is stopped in the usual order FIRST: closing RocksDB under
            // a running P2P/RPC/check-main thread is worse than the leak it would fix. Then the
            // stores go, so the next attempt in this JVM can open the same directory again.
            // The kernel is not running: without this a later testStop() would pass its isRunning
            // guard and NPE on a half-built component, and a retried testStart() would no-op.
            isRunning.set(false);
            log.error("kernel start failed; stopping what started and closing the stores", e);
            try {
                stopServices();
            } catch (RuntimeException | Error ex) {
                e.addSuppressed(ex);
            }
            if (blockStore != null) {
                try {
                    // Sums are written back in batches: without this, those of a genesis import
                    // that did land would be dropped for good.
                    blockStore.flushSums();
                } catch (RuntimeException ex) {
                    e.addSuppressed(ex);
                }
            }
            if (chainL1Store != null) {
                try {
                    chainL1Store.stop();
                } catch (RuntimeException ex) {
                    e.addSuppressed(ex);
                }
            }
            if (dbFactory != null) {
                try {
                    dbFactory.close();
                } catch (RuntimeException ex) {
                    e.addSuppressed(ex);
                }
            }
            throw e;
        }
    }

    /**
     * I2 (SP0b-2): the write-behind queue has failed and accepts no further write. Before the
     * layer existed a RocksDB error was thrown synchronously, turned into {@code ImportResult.ERROR}
     * and retried by the next write; now the first failure poisons the stream for good, so a node
     * left running would keep mining and answering RPC over state that will never be persisted.
     * It has to stop.
     *
     * <p>On its own thread, and never inline: the handler runs on whichever thread failed, which is
     * normally the writer — and the writer may not flush (it would wait for itself) nor join itself
     * in {@code stop()}. Nothing here can block indefinitely either: {@code fail()} has already
     * woken and poisoned every waiter before calling this, so no producer stays parked and every
     * flush on the way out throws instead of waiting. {@code System.exit} rather than
     * {@code halt} so the registered shutdown hooks still run; {@code testStop()} is called first
     * because the hook's own call would be a no-op once it has flipped {@code isRunning}.
     */
    private void onPersistFailure(Throwable failure) {
        log.error("write-behind persistence failed: this node can no longer store anything durably "
                + "and is stopping", failure);
        if (!isRunning.get()) {
            return; // already stopping: the stores are being closed anyway
        }
        Thread stopper = new Thread(() -> {
            try {
                testStop();
            } catch (RuntimeException | Error e) {
                log.error("stopping after a persistence failure did not complete cleanly", e);
            } finally {
                System.exit(1);
            }
        }, "xdag-persist-failure");
        stopper.setDaemon(false);
        stopper.start();
    }

    /**
     * SP0b-1: enters repair mode. A one-way transition, entered by the offline repair tool
     * ({@code --repairchain}) before it builds a {@code BlockchainImpl} — from here on that
     * constructor records a non-clean consistency report instead of throwing, and starts no
     * check-main loop. A node that has decided to repair is restarted to run normally again, so
     * there is no way back out of this state.
     */
    public void enterRepairMode() {
        this.repairMode = true;
    }

    /**
     * SP0b-1: files the startup consistency report of a {@code BlockchainImpl} construction.
     * Public rather than package-visible because that constructor lives in {@code io.xdag.core};
     * named for what it is — a report being recorded, not a knob being set.
     */
    public void recordConsistencyReport(ChainConsistencyCheck.Report report) {
        this.consistencyReport = report;
    }

    /**
     * Stops the kernel in an orderly fashion.
     */
    public synchronized void testStop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        stopServices();

        // The timer is JVM-global, not a kernel component, so it is stopped here rather than in
        // stopServices(): a failed testStart() must not leave the next attempt in this JVM with a
        // dead message queue.
        MessageQueue.timer.shutdown();

        try {
            // Both saves go through the blockchain monitor, the lock that excludes every other
            // writer of these databases (SP0b-2 §3.2): stopCheckMain() above waits only five
            // seconds for the check-main thread, so a transition that outlives that wait must not
            // race a queued write from here. Sums are written back in batches and saveXdagStatus
            // deliberately does not flush them (it runs once per import), so this is their last
            // chance; the stats save is I3 from SP0b-1 — without it a clean shutdown could leave
            // the completion marker written by the last setMain ahead of the stats the next boot
            // loads, the shape the boot consistency check reports as a crash.
            if (blockchain != null) {
                synchronized (blockchain) {
                    saveOnStop();
                }
            } else {
                saveOnStop();
            }
        } catch (RuntimeException e) {
            // A poisoned write-behind queue throws here. Log and carry on: the databases still have
            // to be closed, and everything this would have written is advisory or re-derivable.
            log.error("could not write the final stats and sums; closing the databases anyway", e);
        } finally {
            // Stop the chain store before its database is closed below
            if (chainL1Store != null) {
                try {
                    chainL1Store.stop();
                } catch (RuntimeException e) {
                    log.error("could not stop the chain store; closing the databases anyway", e);
                }
            }

            // Close all databases. Through the factory, not by closing each DatabaseName in turn:
            // with SP0b-2 the factory may be a WriteBehindFactory, whose close() stops the writer
            // thread (flushing what it still holds) before the databases go. That thread is not a
            // daemon, so leaving it running would outlive the kernel. Closing the factory also
            // stops opening databases this node never used just to close them again.
            dbFactory.close();
        }
    }

    /** The two writes a clean shutdown owes the store; the caller holds the blockchain monitor. */
    private void saveOnStop() {
        if (blockStore != null) {
            blockStore.flushSums();
        }
        if (blockStore != null && blockchain != null && blockchain.getXdagStats() != null) {
            blockStore.saveXdagStatus(blockchain.getXdagStats());
        }
    }

    /**
     * Everything that runs on a thread of its own, stopped in the order {@link #testStop()} has
     * always used, before anything touches the databases. Every component is null-guarded: the G3
     * path in {@link #testStart()} calls this on a half-built kernel, where most of them are still
     * null. Writes nothing itself — the stats and sums saves stay in {@code testStop}, which owns
     * the "clean shutdown" semantics the boot consistency check relies on.
     */
    private void stopServices() {
        // Stop Api
        if (api != null) {
            api.stop();
        }

        // With the API, and for the same reason: the telnet console answers admin commands that
        // read the stores, so it has to be shut before anything closes them. It was started in
        // testStart() and never stopped at all, which left its listening socket and the threads
        // jline's telnetd runs it on behind after the node had otherwise shut down.
        if (telnetServer != null) {
            telnetServer.stop();
        }

        // Stop consensus
        if (sync != null) {
            sync.stop();
        }
        if (syncMgr != null) {
            syncMgr.stop();
        }
        if (pow != null) {
            pow.stop();
        }

        // Stop networking layer
        if (channelMgr != null) {
            channelMgr.stop();
        }
        if (nodeMgr != null) {
            nodeMgr.stop();
        }

        // Close P2P networking
        if (p2p != null) {
            p2p.close();
        }
        if (client != null) {
            client.close();
        }

        // Stop data layer
        if (blockchain != null) {
            blockchain.stopCheckMain();
        }

        // I4 (SP0b-2): with the check-main loop, because it is a scheduler of the same kind — a
        // non-daemon thread that writes ORPHANIND and the stats. A tick after the databases close
        // would write into a closed RocksDB and poison the write-behind queue on the way out. It
        // closes its own source as well; RocksdbKVSource.close() is idempotent, so the factory
        // close below still does the right thing.
        if (orphanBlockStore != null) {
            orphanBlockStore.stop();
        }

        // Stop remaining services. Before the databases close, not after as they used to be: both
        // of these can still be serving requests that read the stores.
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
        if (poolAwardManager != null) {
            poolAwardManager.stop();
        }
    }

    public enum Status {
        STOPPED, SYNCING, BLOCK_PRODUCTION_ON, SYNCDONE
    }
}
