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
package io.xdag.net;

import io.xdag.core.*;
import io.xdag.crypto.core.CryptoProvider;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;

import com.google.common.util.concurrent.SettableFuture;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.spec.NodeSpec;
import io.xdag.consensus.SyncManager;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.message.ReasonCode;
import io.xdag.net.message.consensus.BlockExtRequestMessage;
import io.xdag.net.message.consensus.BlockRequestMessage;
import io.xdag.net.message.consensus.BlocksReplyMessage;
import io.xdag.net.message.consensus.BlocksRequestMessage;
import io.xdag.net.message.consensus.NewBlockMessage;
import io.xdag.net.message.consensus.SumReplyMessage;
import io.xdag.net.message.consensus.SumRequestMessage;
import io.xdag.net.message.consensus.SyncBlockMessage;
import io.xdag.net.message.consensus.SyncBlockRequestMessage;
import io.xdag.net.message.consensus.XdagMessage;
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import org.hyperledger.besu.datatypes.Hash;
import io.xdag.net.message.p2p.DisconnectMessage;
import io.xdag.net.message.p2p.EvmStateRootMessage;
import io.xdag.net.message.p2p.EvmTxBroadcastMessage;
import io.xdag.net.message.p2p.EvmTxReplyMessage;
import io.xdag.net.message.p2p.EvmTxRequestMessage;
import io.xdag.net.message.p2p.HelloMessage;
import io.xdag.net.message.p2p.InitMessage;
import io.xdag.net.message.p2p.PingMessage;
import io.xdag.net.message.p2p.PongMessage;
import io.xdag.net.message.p2p.WorldMessage;
import io.xdag.net.node.NodeManager;
import io.xdag.utils.XdagTime;
import io.xdag.utils.exception.UnreachableException;
import lombok.extern.slf4j.Slf4j;

import static io.xdag.config.Constants.*;
import static io.xdag.config.Constants.BI_MAIN_REF;

/**
 * Xdag P2P message handler
 */
@Slf4j
public class XdagP2pHandler extends SimpleChannelInboundHandler<Message> {

    /** Maximum number of blocks returned for a single BLOCKS_REQUEST, to bound reply size. */
    private static final int MAX_BLOCKS_PER_REQUEST = 16384;

    /** Absolute ceiling on remote block/main counts; anything beyond is treated as bogus. */
    private static final long MAX_PLAUSIBLE_BLOCK_COUNT = 1L << 48;

    /** How often each peer connection re-requests the blobs a deferred EVM height still lacks (I4). */
    private static final long EVM_BLOB_RETRY_SECONDS = 15;

    private static final ScheduledExecutorService exec = Executors
            .newSingleThreadScheduledExecutor(new ThreadFactory() {
                private final AtomicInteger cnt = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "p2p-" + cnt.getAndIncrement());
                }
            });

    private final Channel channel;

    private final Kernel kernel;
    private final Config config;
    private final NodeSpec nodeSpec;
    private final Blockchain chain;
    private final ChannelManager channelMgr;
    private final NodeManager nodeMgr;
    private final PeerClient client;
    private final SyncManager syncMgr;

    private final NetDBManager netdbMgr;
    private final MessageQueue msgQueue;

    private final AtomicBoolean isHandshakeDone = new AtomicBoolean(false);

    private ScheduledFuture<?> getNodes = null;
    private ScheduledFuture<?> pingPong = null;
    private ScheduledFuture<?> evmBlobRetry = null;

    private byte[] secret = CryptoProvider.nextBytes(InitMessage.SECRET_LENGTH);
    private long timestamp = System.currentTimeMillis();

    public XdagP2pHandler(Channel channel, Kernel kernel) {
        this.channel = channel;
        this.kernel = kernel;
        this.config = kernel.getConfig();
        this.nodeSpec = kernel.getConfig().getNodeSpec();

        this.chain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelMgr();
        this.nodeMgr = kernel.getNodeMgr();
        this.client = kernel.getClient();

        this.syncMgr = kernel.getSyncMgr();
        this.netdbMgr = kernel.getNetDBMgr();
        this.msgQueue = channel.getMessageQueue();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("P2P handler active, remoteIp = {}, remotePort = {}", channel.getRemoteIp(), channel.getRemotePort());

        // activate message queue
        msgQueue.activate(ctx);

        // disconnect if too many connections
        if (channel.isInbound() && channelMgr.size() >= config.getNodeSpec().getNetMaxInboundConnections()) {
            msgQueue.disconnect(ReasonCode.TOO_MANY_PEERS);
            return;
        }

        if (channel.isInbound()) {
            msgQueue.sendMessage(new InitMessage(secret, timestamp));
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("P2P handler inactive, remoteIp = {}", channel.getRemoteIp());

        // deactivate the message queue
        msgQueue.deactivate();

        // stop scheduled workers
        if (getNodes != null) {
            getNodes.cancel(false);
            getNodes = null;
        }

        if (pingPong != null) {
            pingPong.cancel(false);
            pingPong = null;
        }

        if (evmBlobRetry != null) {
            evmBlobRetry.cancel(false);
            evmBlobRetry = null;
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Exception in P2P handler, remoteIp = {}, remotePort = {}", channel.getRemoteIp(), channel.getRemotePort(),cause);

        // close connection on exception
        ctx.close();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, Message msg) {
        log.trace("Received message: {}", msg);

        switch (msg.getCode()) {
            /* p2p */
            case DISCONNECT -> onDisconnect(ctx, (DisconnectMessage) msg);
            case PING -> onPing();
            case PONG -> onPong();
            case HANDSHAKE_INIT -> onHandshakeInit((InitMessage) msg);
            case HANDSHAKE_HELLO -> onHandshakeHello((HelloMessage) msg);
            case HANDSHAKE_WORLD -> onHandshakeWorld((WorldMessage) msg);

            /* sync */
            case BLOCKS_REQUEST, BLOCKS_REPLY, SUMS_REQUEST, SUMS_REPLY, BLOCKEXT_REQUEST, BLOCKEXT_REPLY, BLOCK_REQUEST, NEW_BLOCK, SYNC_BLOCK, SYNCBLOCK_REQUEST ->
                    onXdag(msg);
            /* evm — routed to onXdag where the EVM handlers live; without this they never dispatch */
            case EVM_TX_BROADCAST, EVM_TX_REQUEST, EVM_TX_REPLY, EVM_STATE_ROOT -> onXdag(msg);
            default -> ctx.fireChannelRead(msg);
        }
    }

    protected void onDisconnect(ChannelHandlerContext ctx, DisconnectMessage msg) {
        ReasonCode reason = msg.getReason();
        log.info("Received a DISCONNECT message: reason = {}, remoteIP = {}",
                reason, channel.getRemoteIp());

        ctx.close();
    }

    protected void onHandshakeInit(InitMessage msg) {
        // unexpected
        if (channel.isInbound()) {
            return;
        }

        // check message
        if (!msg.validate()) {
            this.msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }

        // record the secret
        this.secret = msg.getSecret();
        this.timestamp = msg.getTimestamp();

        // send the HELLO message
        this.msgQueue.sendMessage(new HelloMessage(nodeSpec.getNetwork(), nodeSpec.getNetworkVersion(),
                client.getPeerId(), client.getPort(), config.getClientId(), config.getClientCapabilities().toArray(),
                chain.getLatestMainBlockNumber(), secret, client.getCoinbase(), config.getEnableGenerateBlock(),
                config.getNodeTag()));
    }

    protected void onHandshakeHello(HelloMessage msg) {
        // unexpected
        if (channel.isOutbound()) {
            return;
        }
        Peer peer = msg.getPeer(channel.getRemoteIp());

        // check peer
        ReasonCode code = checkPeer(peer, true);
        if (code != null) {
            msgQueue.disconnect(code);
            return;
        }

        // check message
        if (!Arrays.equals(secret, msg.getSecret()) || !msg.validate(config)) {
            msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }
        // send the WORLD message
        this.msgQueue.sendMessage(new WorldMessage(nodeSpec.getNetwork(), nodeSpec.getNetworkVersion(),
                client.getPeerId(), client.getPort(), config.getClientId(), config.getClientCapabilities().toArray(),
                chain.getLatestMainBlockNumber(), secret, client.getCoinbase(), config.getEnableGenerateBlock(),
                config.getNodeTag()));

        // handshake done
        onHandshakeDone(peer);
    }

    protected void onHandshakeWorld(WorldMessage msg) {
        // unexpected
        if (channel.isInbound()) {
            return;
        }
        Peer peer = msg.getPeer(channel.getRemoteIp());

        // check peer
        ReasonCode code = checkPeer(peer, true);
        if (code != null) {
            msgQueue.disconnect(code);
            return;
        }

        // check message
        if (!Arrays.equals(secret, msg.getSecret()) || !msg.validate(config)) {
            msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }

        // handshake done
        onHandshakeDone(peer);
    }

    private long lastPing;

    protected void onPing() {
        PongMessage pong = new PongMessage();
        msgQueue.sendMessage(pong);
        lastPing = System.currentTimeMillis();
    }

    protected void onPong() {
        // getRemotePeer() is null before the handshake completes; guard against NPE.
        Peer remotePeer = channel.getRemotePeer();
        if (lastPing > 0 && remotePeer != null) {
            long latency = System.currentTimeMillis() - lastPing;
            remotePeer.setLatency(latency);
        }
    }

    protected void onXdag(Message msg) {
        if (!isHandshakeDone.get()) {
            return;
        }

        switch (msg.getCode()) {
            case NEW_BLOCK -> processNewBlock((NewBlockMessage) msg);
            case BLOCK_REQUEST -> processBlockRequest((BlockRequestMessage) msg);
            case BLOCKS_REQUEST -> processBlocksRequest((BlocksRequestMessage) msg);
            case BLOCKS_REPLY -> processBlocksReply((BlocksReplyMessage) msg);
            case SUMS_REQUEST -> processSumsRequest((SumRequestMessage) msg);
            case SUMS_REPLY -> processSumsReply((SumReplyMessage) msg);
            case BLOCKEXT_REQUEST -> processBlockExtRequest((BlockExtRequestMessage) msg);
            case SYNC_BLOCK -> processSyncBlock((SyncBlockMessage) msg);
            case SYNCBLOCK_REQUEST -> processSyncBlockRequest((SyncBlockRequestMessage) msg);
            case EVM_TX_BROADCAST -> processEvmTxBroadcast((EvmTxBroadcastMessage) msg);
            case EVM_TX_REQUEST -> processEvmTxRequest((EvmTxRequestMessage) msg);
            case EVM_TX_REPLY -> processEvmTxReply((EvmTxReplyMessage) msg);
            case EVM_STATE_ROOT -> processEvmStateRoot((EvmStateRootMessage) msg);
            default -> throw new UnreachableException();
        }
    }

    /**
     * Check whether the peer is valid to connect.
     */
    private ReasonCode checkPeer(Peer peer, boolean newHandShake) {
        // has to be same network
        if (newHandShake && !nodeSpec.getNetwork().equals(peer.getNetwork())) {
            return ReasonCode.BAD_NETWORK;
        }

        // has to be compatible version
        if (nodeSpec.getNetworkVersion() != peer.getNetworkVersion()) {
            return ReasonCode.BAD_NETWORK_VERSION;
        }

        return null;
    }

    private void onHandshakeDone(Peer peer) {
        if (isHandshakeDone.compareAndSet(false, true)) {
            // register into channel manager
            channelMgr.onChannelActive(channel, peer);

            // start ping pong
            pingPong = exec.scheduleAtFixedRate(() -> msgQueue.sendMessage(new PingMessage()),
                    channel.isInbound() ? 1 : 0, 1, TimeUnit.MINUTES);

            // Periodic per-peer EVM housekeeping: (I4) re-request any blob a deferred height still lacks
            // so a stalled height self-heals, and (Phase 2) gossip this node's latest EVM state root so
            // peers can detect cross-node divergence. Both no-op when EVM is disabled or nothing applies.
            evmBlobRetry = exec.scheduleAtFixedRate(() -> {
                requestPendingEvmBlobs();
                gossipEvmStateRoot();
            }, EVM_BLOB_RETRY_SECONDS, EVM_BLOB_RETRY_SECONDS, TimeUnit.SECONDS);
        } else {
            msgQueue.disconnect(ReasonCode.HANDSHAKE_EXISTS);
        }
    }

    /**
     * ********************** Message Processing * ***********************
     */
    protected void processNewBlock(NewBlockMessage msg) {
        Block block = msg.getBlock();
        if (syncMgr.isSyncOld()) {
            return;
        }
        requestMissingEvmBlob(block);

        log.debug("processNewBlock:{} from node {}", block.getHashLow(), channel.getRemoteAddress());
        BlockWrapper bw = new BlockWrapper(block, msg.getTtl() - 1, channel.getRemotePeer(), false);
        syncMgr.validateAndAddNewBlock(bw);
    }

    /** Spec §6.2: pull a referenced-but-unknown EVM tx blob from the peer that announced the block. */
    private void requestMissingEvmBlob(Block block) {
        EvmTxStore evmTxStore = kernel.getEvmTxStore();
        if (block.getEvmTxRef() == null || evmTxStore == null) {
            return;
        }
        Hash txHash = Hash.wrap(block.getEvmTxRef());
        if (!evmTxStore.contains(txHash)) {
            msgQueue.sendMessage(new EvmTxRequestMessage(block.getEvmTxRef()));
        }
    }

    /**
     * I4 convergence: re-request from this peer every blob that a deferred (pending) EVM height still
     * lacks, so a stalled height self-heals — a dropped {@link EvmTxRequestMessage}, a reply that
     * arrived before the block was deferred, or a peer that connected after the defer. Runs on the
     * shared scheduler once per {@link #EVM_BLOB_RETRY_SECONDS}. No-op when EVM is disabled
     * ({@code evmBlockProcessor == null}) or nothing is pending. Any failure is swallowed so a single
     * bad tick cannot cancel the periodic task.
     */
    private void requestPendingEvmBlobs() {
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        if (evmProcessor == null) {
            return;
        }
        try {
            for (Bytes32 ref : evmProcessor.pendingMissingBlobHashes()) {
                msgQueue.sendMessage(new EvmTxRequestMessage(ref));
            }
        } catch (RuntimeException e) {
            log.debug("requestPendingEvmBlobs failed for node {}: {}", channel.getRemoteAddress(), e.toString());
        }
    }

    /**
     * Phase 2: gossip this node's latest executed EVM (height, chained root) to the peer so it can
     * detect cross-node divergence. Fire-and-forget; no-op when EVM is disabled or nothing has executed.
     * The field-type space is exhausted, so the commitment travels as a message, not a native block
     * field. Failures are swallowed so a bad tick cannot cancel the periodic task.
     */
    private void gossipEvmStateRoot() {
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        if (evmProcessor == null) {
            return;
        }
        try {
            evmProcessor.latestExecutedStateRoot().ifPresent(latest ->
                    msgQueue.sendMessage(new EvmStateRootMessage(latest.height(), latest.root())));
        } catch (RuntimeException e) {
            log.debug("gossipEvmStateRoot failed for node {}: {}", channel.getRemoteAddress(), e.toString());
        }
    }

    /**
     * Phase 2: compare a peer's claimed EVM state root against this node's own checkpoint. A definite
     * mismatch (both nodes executed the height, roots differ) is a fork of the EVM world states — logged
     * loudly. This is detection only (v1): the node still follows the PoW-heaviest native chain.
     */
    private void processEvmStateRoot(EvmStateRootMessage msg) {
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        if (evmProcessor == null) {
            return;
        }
        EvmBlockProcessor.RootComparison verdict = evmProcessor.compareStateRoot(msg.getHeight(), msg.getRoot());
        if (verdict == EvmBlockProcessor.RootComparison.DIVERGE) {
            log.error("EVM STATE DIVERGENCE at height {}: peer {} reports root {} but this node computed a "
                    + "different root — the EVM world states have forked", msg.getHeight(),
                    channel.getRemoteAddress(), msg.getRoot().toHexString());
        } else if (verdict == EvmBlockProcessor.RootComparison.AGREE) {
            log.trace("EVM state root agrees with peer {} at height {}",
                    channel.getRemoteAddress(), msg.getHeight());
        }
        // UNKNOWN: this node has no checkpoint at that height (behind / no EVM txs); nothing to compare.
    }

    private void processEvmTxBroadcast(EvmTxBroadcastMessage msg) {
        if (ingestEvmTxBlob(msg.getRawRlp())) {
            // First sight of this tx: relay to every other peer (dedup falls out of DUPLICATE).
            for (Channel other : channelMgr.getActiveChannels()) {
                if (other != channel) {
                    other.getMsgQueue().sendMessage(new EvmTxBroadcastMessage(msg.getRawRlp()));
                }
            }
        }
    }

    private void processEvmTxRequest(EvmTxRequestMessage msg) {
        EvmTxStore evmTxStore = kernel.getEvmTxStore();
        if (evmTxStore == null) {
            return;
        }
        evmTxStore.get(Hash.wrap(msg.getTxHash()))
                .ifPresent(rlp -> msgQueue.sendMessage(new EvmTxReplyMessage(rlp)));
    }

    private void processEvmTxReply(EvmTxReplyMessage msg) {
        ingestEvmTxBlob(msg.getRawRlp());
    }

    /**
     * Feeds a gossiped blob to both the consensus store (to satisfy a referenced-but-missing ref,
     * then resume deferred execution) and the mempool (for future block inclusion). Only blobs the
     * processor is actually awaiting are persisted, so unsolicited junk cannot fill the disk.
     *
     * @return true if the blob was newly accepted into the mempool (drives broadcast relay)
     */
    private boolean ingestEvmTxBlob(org.apache.tuweni.bytes.Bytes rawRlp) {
        EvmTxPool evmTxPool = kernel.getEvmTxPool();
        EvmTxStore evmTxStore = kernel.getEvmTxStore();
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        if (evmTxPool == null || evmTxStore == null
                || rawRlp.size() > config.getEvmSpec().getEvmMaxP2pTxBytes()) {
            return false;
        }
        // Consensus path: if a deferred main block is waiting for exactly this blob, store it and
        // resume execution in height order (I4 convergence).
        if (evmProcessor != null) {
            Hash txHash;
            try {
                txHash = Hash.hash(rawRlp);
            } catch (RuntimeException e) {
                return false;
            }
            if (evmProcessor.isAwaitingBlob(txHash)) {
                evmTxStore.putRaw(txHash, rawRlp);
                evmProcessor.onBlobsAvailable();
            }
        }
        // Mempool path: offer for future inclusion (its own validation + size cap apply).
        return evmTxPool.add(rawRlp) == EvmTxPool.AddResult.ADDED;
    }

    protected void processSyncBlock(SyncBlockMessage msg) {
        Block block = msg.getBlock();
        chain.putSyncTxStatus(block.getHashLow(), msg.getExecutionState());
        requestMissingEvmBlob(block); // a block arriving via sync also needs its referenced blob
        log.debug("processSyncBlock:{}  from node {}", block.getHashLow(), channel.getRemoteAddress());
        BlockWrapper bw = new BlockWrapper(block, msg.getTtl() - 1, channel.getRemotePeer(), true);
        syncMgr.validateAndAddNewBlock(bw);
    }

    /**
     * A block request responds to a block and starts a thread to continuously send blocks over a period of time. *
     */
    protected void processBlocksRequest(BlocksRequestMessage msg) {
        // Update the status of the entire network
        updateXdagStats(msg);
        long startTime = msg.getStarttime();
        long endTime = msg.getEndtime();
        long random = msg.getRandom();

        // Validate the peer-supplied time range to avoid resource-exhaustion attacks:
        // negative bounds, an inverted range, or an over-wide window are all rejected.
        if (startTime < 0 || endTime < 0 || endTime < startTime
                || (endTime - startTime) > REQUEST_BLOCKS_MAX_TIME) {
            log.warn("Rejecting BLOCKS_REQUEST with invalid time range [{}, {}] from node {}",
                    startTime, endTime, channel.getRemoteAddress());
            return;
        }

        log.debug("Send blocks between {} and {} to node {}",
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(XdagTime.xdagTimestampToMs(startTime)),
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(XdagTime.xdagTimestampToMs(endTime)),
                channel.getRemoteAddress());
        List<Block> blocks = chain.getBlocksByTime(startTime, endTime);
        int sent = 0;
        for (Block block : blocks) {
            if (sent >= MAX_BLOCKS_PER_REQUEST) {
                log.warn("BLOCKS_REQUEST reply truncated at {} blocks for node {}",
                        MAX_BLOCKS_PER_REQUEST, channel.getRemoteAddress());
                break;
            }
            byte executionState = 0;
            if (chain.isTxBlock(block)) {
                int flag = block.getInfo().getFlags() & ~(BI_OURS | BI_REMARK);
                // 1C
                if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED)) {
                    executionState = 1;
                } else if (flag == (BI_REF | BI_MAIN_REF)) {// 18
                    executionState = 2;
                }
            }
            SyncBlockMessage blockMsg = new SyncBlockMessage(block, 1, executionState);
            msgQueue.sendMessage(blockMsg);
            sent++;
        }
        msgQueue.sendMessage(new BlocksReplyMessage(startTime, endTime, random, chain.getXdagStats()));
    }

    protected void processBlocksReply(BlocksReplyMessage msg) {
        updateXdagStats(msg);
        long randomSeq = msg.getRandom();
        SettableFuture<Bytes> sf = kernel.getSync().getBlocksRequestMap().get(randomSeq);
        if (sf != null) {
            sf.set(Bytes.wrap(new byte[]{0}));
        }
    }

    /**
     * Fill the last 8 fields of the sumRequest with your own sum, change the type to reply, and send.
     */
    protected void processSumsRequest(SumRequestMessage msg) {
        updateXdagStats(msg);
        MutableBytes sums = MutableBytes.create(256);
        // TODO: paulochen Handling SUM requests
        kernel.getBlockStore().loadSum(msg.getStarttime(),msg.getEndtime(),sums);
        SumReplyMessage reply = new SumReplyMessage(msg.getEndtime(), msg.getRandom(),
                chain.getXdagStats(), sums);
        msgQueue.sendMessage(reply);
    }

    protected void processSumsReply(SumReplyMessage msg) {
        updateXdagStats(msg);
        long randomSeq = msg.getRandom();
        SettableFuture<Bytes> sf = kernel.getSync().getSumsRequestMap().get(randomSeq);
        if (sf != null) {
            sf.set(msg.getSum());
        }
    }

    protected void processBlockExtRequest(BlockExtRequestMessage msg) {
    }

    protected void processBlockRequest(BlockRequestMessage msg) {
        Bytes hash = msg.getHash();
        Block block = chain.getBlockByHash(Bytes32.wrap(hash), true);
        int ttl = config.getNodeSpec().getTTL();
        if (block != null) {
            log.debug("processBlockRequest: findBlock{}", Bytes32.wrap(hash).toHexString());
            NewBlockMessage message = new NewBlockMessage(block, ttl);
            msgQueue.sendMessage(message);
        }
    }

    private void processSyncBlockRequest(SyncBlockRequestMessage msg) {
        Bytes hash = msg.getHash();
        Block block = chain.getBlockByHash(Bytes32.wrap(hash), true);
        if (block != null) {
            log.debug("processSyncBlockRequest, findBlock: {}, to node: {}", Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
            byte executionState = 0;
            if (chain.isTxBlock(block)) {
                int flag = block.getInfo().getFlags() & ~(BI_OURS | BI_REMARK);
                // 1C,applied
                if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED)) {
                    executionState = 1;
                } else if (flag == (BI_REF | BI_MAIN_REF)) {// 18 rejected
                    executionState = 2;
                }
            }
            SyncBlockMessage message = new SyncBlockMessage(block, 1, executionState);
            msgQueue.sendMessage(message);
        }
    }

    /**
     * ********************** Xdag Message ************************
     */
    public void sendNewBlock(Block newBlock, int TTL) {
        log.debug("send block:{} to node:{}", newBlock.getHashLow(), channel.getRemoteAddress());
        NewBlockMessage msg = new NewBlockMessage(newBlock, TTL);
        sendMessage(msg);
    }

    public long sendGetBlocks(long startTime, long endTime) {
        log.debug("Request blocks between {} and {} from node {}",
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(XdagTime.xdagTimestampToMs(startTime)),
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(XdagTime.xdagTimestampToMs(endTime)),
                channel.getRemoteAddress());
        BlocksRequestMessage msg = new BlocksRequestMessage(startTime, endTime, chain.getXdagStats());
        sendMessage(msg);
        return msg.getRandom();
    }

    public long sendGetBlock(MutableBytes32 hash, boolean isOld) {
        XdagMessage msg;
        //        log.debug("sendGetBlock:[{}]", Hex.toHexString(hash));
        msg = isOld ? new SyncBlockRequestMessage(hash, kernel.getBlockchain().getXdagStats())
                : new BlockRequestMessage(hash, kernel.getBlockchain().getXdagStats());
        log.debug("Request block {} isold: {} from node {}", hash, isOld,channel.getRemoteAddress());
        sendMessage(msg);
        return msg.getRandom();
    }

    public long sendGetSums(long startTime, long endTime) {
        SumRequestMessage msg = new SumRequestMessage(startTime, endTime, chain.getXdagStats());
        sendMessage(msg);
        log.debug("Request blocks time from startTime:{} ,endEime:{}" , startTime, endTime);
        return msg.getRandom();
    }

    public void sendMessage(Message message) {
        msgQueue.sendMessage(message);
    }

    public void updateXdagStats(XdagMessage message) {
        // Confirm that the remote stats has been updated, used to check local state.
        syncMgr.getIsUpdateXdagStats().compareAndSet(false, true);
        XdagStats remoteXdagStats = message.getXdagStats();
        if (remoteXdagStats == null) {
            return;
        }
        // Reject implausible peer-supplied stats (negative or absurdly large counts) so a
        // malicious peer cannot poison our local view of the network.
        if (remoteXdagStats.getTotalnblocks() < 0 || remoteXdagStats.getTotalnmain() < 0
                || remoteXdagStats.getTotalnhosts() < 0
                || remoteXdagStats.getTotalnblocks() > MAX_PLAUSIBLE_BLOCK_COUNT
                || remoteXdagStats.getTotalnmain() > MAX_PLAUSIBLE_BLOCK_COUNT) {
            log.warn("Ignoring implausible remote XdagStats from node {}: {}",
                    channel.getRemoteAddress(), remoteXdagStats);
            return;
        }
        chain.getXdagStats().update(remoteXdagStats);
    }

}
