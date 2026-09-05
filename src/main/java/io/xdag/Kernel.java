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

import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
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
import io.xdag.rpc.server.core.RpcHandlers;
import io.xdag.rpc.ws.RpcWebSocketServer;
import io.xdag.rpc.ws.SubscriptionManager;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;

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
    protected AddressStore addressStore;
    protected BlockStore blockStore;
    protected OrphanBlockStore orphanBlockStore;
    protected TransactionHistoryStore txHistoryStore;

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

    // Embedded EVM services (null unless evm.enabled = true for this network)
    protected io.xdag.db.rocksdb.KVSource<byte[], byte[]> evmStateStore;
    protected io.xdag.evm.tx.EvmTxStore evmTxStore;
    protected io.xdag.evm.state.EvmMetaStore evmMetaStore;
    protected io.xdag.evm.state.EvmStateJournal evmStateJournal;
    protected io.xdag.evm.tx.EvmTxPool evmTxPool;
    protected io.xdag.evm.EvmBlockProcessor evmBlockProcessor;

    // Running status flag
    protected AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Start time epoch
    protected long startEpoch;

    // RPC related components
    protected XdagApi api;

    // WebSocket subscription server + sink (C6), null unless rpc.ws.enabled for this network.
    protected SubscriptionManager subscriptionManager;
    protected RpcWebSocketServer rpcWebSocketServer;

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
        try {
            startComponents();
            // Register the shutdown hook only after every component started successfully.
            Launcher.registerShutdownHook("kernel", this::testStop);
        } catch (RuntimeException | Error e) {
            // A failure mid-startup must not leak already-started subsystems (Q-12).
            log.error("Kernel startup failed; rolling back partially started components", e);
            testStop();
            throw e;
        }
    }

    private void startComponents() {
        startEpoch = XdagTime.getCurrentEpoch();

        // Initialize channel manager
        channelMgr = new ChannelManager(this);
        channelMgr.start();

        netDBMgr = new NetDBManager(this.config);
        netDBMgr.start();

        // Initialize database components
        dbFactory = new RocksdbFactory(this.config);
        blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        log.info("Block Store init.");
        blockStore.start();

        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.start();


        orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND) , this);
        orphanBlockStore.start();

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

        // Initialize the embedded EVM (spec §8) ahead of the blockchain so setMain can execute refs.
        if (config.getEvmSpec().isEvmEnabled()) {
            KVSource<byte[], byte[]> evmStateSource = dbFactory.getDB(DatabaseName.EVM_STATE);
            evmStateSource.init();
            this.evmStateStore = evmStateSource; // retained so JsonRpcServer can build the HistoricalStateReader
            KVSource<byte[], byte[]> evmTxSource = dbFactory.getDB(DatabaseName.EVM_TX);
            evmTxSource.init();
            KVSource<byte[], byte[]> evmMetaSource = dbFactory.getDB(DatabaseName.EVM_META);
            evmMetaSource.init();
            KVSource<byte[], byte[]> evmJournalSource = dbFactory.getDB(DatabaseName.EVM_STATE_JOURNAL);
            evmJournalSource.init();
            evmStateJournal = new io.xdag.evm.state.EvmStateJournal(evmJournalSource);
            java.math.BigInteger evmChainId = java.math.BigInteger.valueOf(config.getEvmSpec().getEvmChainId());
            io.xdag.evm.EvmConfig evmConfig = new io.xdag.evm.EvmConfig(
                    org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI, evmChainId,
                    config.getEvmSpec().getEvmBlockGasLimit(), config.getEvmSpec().getEvmMinGasPrice(),
                    config.getEvmSpec().getEvmType2ActivationHeight(),
                    config.getEvmSpec().getEvmBridgeActivationHeight(),
                    config.getEvmSpec().getEvmEip3529ActivationHeight());
            evmTxStore = new io.xdag.evm.tx.EvmTxStore(evmTxSource);
            evmMetaStore = new io.xdag.evm.state.EvmMetaStore(evmMetaSource);
            evmTxPool = new io.xdag.evm.tx.EvmTxPool(evmTxStore, evmStateSource, evmChainId,
                    config.getEvmSpec().getEvmBlockGasLimit(),
                    org.hyperledger.besu.datatypes.Wei.of(config.getEvmSpec().getEvmMinGasPrice()),
                    config.getEvmSpec().getEvmTxPoolTtlSeconds(),
                    () -> System.currentTimeMillis() / 1000,
                    config.getEvmSpec().getEvmMaxP2pTxBytes()); // P2: admission cap == P2P ingest cap
            evmBlockProcessor = new io.xdag.evm.EvmBlockProcessor(evmConfig, evmStateSource,
                    evmTxStore, evmMetaStore, config.getEvmSpec().getEvmActivationHeight(),
                    config.getEvmSpec().getEvmGenesisAlloc(),
                    evmStateJournal, config.getEvmSpec().getEvmStateHistoryWindow());
            // Seed the genesis allocation now so pre-funded balances are visible to the eth RPC before
            // the first EVM main block (idempotent; a restart with the marker present is a no-op).
            evmBlockProcessor.seedGenesisIfAbsent();
            // Likewise the bridge contract code (no-op unless the bridge is scheduled), so eth_getCode
            // and pool-side calls see it before the first bridge-height main block.
            evmBlockProcessor.seedBridgeContractIfAbsent();
            // A4 transfer-from-lock: the genesis alloc is a "genesis deposit" — credit the lock
            // with its native equivalent exactly once per chain lifetime (ADDRESS-CF marker), so
            // every EVM wei is lock-backed and fee credits transfer from the lock, never minting.
            long seededNano = io.xdag.evm.bridge.GenesisLockSeeder.seedIfAbsent(
                    addressStore, config.getEvmSpec());
            if (seededNano > 0) {
                log.info("Seeded the bridge lock with {} nano — genesis-deposit backing for {} "
                        + "evm.alloc entr(ies).", seededNano,
                        config.getEvmSpec().getEvmGenesisAlloc().size());
            }
            log.info("EVM services init (chain id {}).", evmChainId);
            if (config.getEvmSpec().getEvmBridgeActivationHeight() != Long.MAX_VALUE) {
                log.info("XDAG<->EVM bridge deposits active from height {}: lock address {} (native form {})",
                        config.getEvmSpec().getEvmBridgeActivationHeight(),
                        io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20,
                        io.xdag.crypto.encoding.Base58.encodeCheck(
                                io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20));
                if (!config.getEvmSpec().getEvmGenesisAlloc().isEmpty()) {
                    log.info("evm.alloc has {} entr(ies): lock-backed as a genesis deposit "
                            + "(A4 transfer-from-lock) and counted in getSupply.",
                            config.getEvmSpec().getEvmGenesisAlloc().size());
                }
            }
        }

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

        // Start the WebSocket subscription server (C6). Guarded on rpc.ws.enabled so a disabled node
        // builds nothing and injects no sink (byte-identical to the pre-C6 path). Wired here — after
        // both blockchain and evmBlockProcessor are constructed — so the sink is injected into already
        // live components; the WS server shares the same XdagApi + handler list as the HTTP server.
        if (config.getRPCSpec().isRpcWsEnabled()) {
            subscriptionManager = new SubscriptionManager();
            // blockchain is always the concrete BlockchainImpl (constructed above); the sink setter is
            // not on the Blockchain interface, so cast to reach it.
            ((BlockchainImpl) blockchain).setSubscriptionSink(subscriptionManager); // newHeads
            if (evmBlockProcessor != null) {
                evmBlockProcessor.setSubscriptionSink(subscriptionManager); // logs (only when EVM enabled)
            }
            rpcWebSocketServer = new RpcWebSocketServer(config.getRPCSpec(), subscriptionManager,
                    RpcHandlers.build(api, this));
            rpcWebSocketServer.start();
        }

        // Start Telnet Server
        telnetServer = new TelnetServer(this);
        telnetServer.start();

        blockchain.registerListener(pow);
    }

    /**
     * Stops the kernel in an orderly fashion.
     */
    public synchronized void testStop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        // Stop Api
        if (api != null) {
            api.stop();
        }

        // Stop the WebSocket subscription server (C6); null unless rpc.ws.enabled.
        if (rpcWebSocketServer != null) {
            rpcWebSocketServer.stop();
        }

        // Stop consensus (null-guarded so testStop works after a partial start)
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

        // Close message queue timer
        MessageQueue.timer.shutdown();

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

        // Close all databases
        if (dbFactory != null) {
            for (DatabaseName name : DatabaseName.values()) {
                dbFactory.getDB(name).close();
            }
        }

        // Stop remaining services
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
        if (poolAwardManager != null) {
            poolAwardManager.stop();
        }

        // Stop telnet admin server and RandomX (Q-11)
        if (telnetServer != null) {
            telnetServer.stop();
        }
        if (randomx != null) {
            randomx.stop();
        }
    }

    public enum Status {
        STOPPED, SYNCING, BLOCK_PRODUCTION_ON, SYNCDONE
    }
}
