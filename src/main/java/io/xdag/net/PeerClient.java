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

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.xdag.config.Config;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.net.node.Node;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

/**
 * Client implementation for peer-to-peer network communication
 */
@Slf4j
@Getter
@Setter
public class PeerClient {

    // Thread factory for client worker threads
    private static final ThreadFactory factory = BasicThreadFactory.builder()
            .namingPattern("XdagClient-thread-%d")
            .daemon(true)
            .build();

    /**
     * Shutdown budget for the worker group. No quiet period: nothing submits work to these loops
     * once the node is stopping, so waiting for one to pass would only add latency to every clean
     * shutdown. The timeout is netty's own deadline for a graceful shutdown, and the wait is this
     * class's bound on the termination future — a little longer, so a group that is going to make
     * netty's deadline is still awaited rather than abandoned one millisecond short of it.
     */
    private static final long SHUTDOWN_QUIET_PERIOD_MS = 0;
    private static final long SHUTDOWN_TIMEOUT_MS = 3_000;
    private static final long SHUTDOWN_WAIT_MS = 5_000;

    private final String ip;
    private final int port;
    private final ECKeyPair coinbase;
    private final EventLoopGroup workerGroup;
    private final Config config;
    private final Set<InetSocketAddress> whitelist;
    private Node node;

    /**
     * Constructor for PeerClient
     * @param config Network configuration
     * @param coinbase Keypair for node identity
     */
    public PeerClient(Config config, ECKeyPair coinbase) {
        this.config = config;
        this.ip = config.getNodeSpec().getNodeIp();
        this.port = config.getNodeSpec().getNodePort();
        this.coinbase = coinbase;
        this.workerGroup = new MultiThreadIoEventLoopGroup(0, factory, NioIoHandler.newFactory());
        this.whitelist = new HashSet<>();
        initWhiteIPs();
    }

    /**
     * Get peer ID derived from coinbase key
     */
    public String getPeerId() {
        return Base58.encodeCheck(toBytesAddress(coinbase));
    }

    /**
     * Connect to a remote node
     * @param remoteNode Target node to connect to
     * @param xdagChannelInitializer Channel initializer
     * @return ChannelFuture for the connection
     */
    public ChannelFuture connect(Node remoteNode, XdagChannelInitializer xdagChannelInitializer) {
        if (!isAcceptable(new InetSocketAddress(remoteNode.getIp(), remoteNode.getPort()))) {
            return null;
        }
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getNodeSpec().getConnectionTimeout());
        b.remoteAddress(remoteNode.toAddress());
        b.handler(xdagChannelInitializer);
        return b.connect();
    }

    /**
     * Gracefully shutdown the client.
     *
     * <p>The wait is bounded, and it used to be a {@code syncUninterruptibly()} on the termination
     * future with no timeout at all: one worker stuck on a slow peer or a blocking store call
     * parked the whole shutdown there, with no log line to say where the node had gone. A worker
     * that will not finish now costs a warning and the node carries on stopping.
     */
    public void close() {
        log.debug("Shutdown XdagClient");
        workerGroup.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_MS, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            if (!workerGroup.terminationFuture().await(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("PeerClient worker group did not terminate within {} ms; "
                        + "continuing shutdown without it", SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            // Somebody wants the stopping thread to stop too: pass it on rather than swallow it,
            // and do not wait any longer.
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for the PeerClient worker group to terminate");
        }
    }

    /**
     * Get or create the local node instance
     */
    public Node getNode() {
        if (node == null) {
            node = new Node(ip, port);
        }
        return node;
    }

    /**
     * Check if an address is acceptable based on whitelist
     * @param address Address to check
     * @return true if address is acceptable
     */
    public boolean isAcceptable(InetSocketAddress address) {
        if (!whitelist.isEmpty()) {
            return whitelist.contains(address);
        }
        return true;
    }

    /**
     * Initialize whitelist from config
     */
    private void initWhiteIPs() {
        whitelist.addAll(config.getNodeSpec().getWhiteIPList());
    }

    /**
     * Add an IP to the whitelist
     * @param host Host address
     * @param port Port number
     */
    public void addWhilteIP(String host, int port) {
        whitelist.add(new InetSocketAddress(host, port));
    }

}
