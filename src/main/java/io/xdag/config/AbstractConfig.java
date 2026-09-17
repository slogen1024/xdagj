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

package io.xdag.config;

import com.google.common.collect.Lists;
import com.typesafe.config.ConfigFactory;
import io.xdag.Network;
import io.xdag.config.spec.*;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagField;
import io.xdag.net.Capability;
import io.xdag.net.CapabilityTreeSet;
import io.xdag.net.message.MessageCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.net.InetSocketAddress;
import java.util.*;

@Slf4j
@Getter
@Setter
public class AbstractConfig implements Config, AdminSpec, NodeSpec, WalletSpec, RPCSpec, SnapshotSpec, RandomxSpec, FundSpec, ChainSpec {

    protected String configName;

    // Admin configuration
    protected String adminTelnetIp = "127.0.0.1";
    protected int adminTelnetPort = 7001;
    protected String adminTelnetPassword;

    // Pool websocket configuration 
    protected int websocketServerPort;
    protected int maxShareCountPerChannel = 20;
    protected int awardEpoch = 0xf;
    protected int waitEpoch = 32;

    // Foundation configuration
    protected String fundAddress;
    protected double fundRation;
    protected double nodeRation;

    // Network configuration
    protected Network network;
    protected short networkVersion;
    protected int netMaxOutboundConnections = 128;
    protected int netMaxInboundConnections = 512;
    protected int netMaxInboundConnectionsPerIp = 5;
    protected int netMaxFrameBodySize = 128 * 1024;
    protected int netMaxPacketSize = 16 * 1024 * 1024;
    protected int netRelayRedundancy = 8;
    protected int netHandshakeExpiry = 5 * 60 * 1000;
    protected int netChannelIdleTimeout = 2 * 60 * 1000;

    // Prioritized network messages
    protected Set<MessageCode> netPrioritizedMessages = new HashSet<>(Arrays.asList(
            MessageCode.NEW_BLOCK,
            MessageCode.BLOCK_REQUEST,
            MessageCode.BLOCKS_REQUEST));

    // Node configuration
    protected String nodeIp;
    protected int nodePort;
    protected String nodeTag;
    protected int maxConnections = 1024;
    protected int maxInboundConnectionsPerIp = 8;
    protected int connectionTimeout = 10000;
    protected int connectionReadTimeout = 10000;
    protected boolean enableTxHistory = false;
    protected long txPageSizeLimit = 500;
    protected boolean enableGenerateBlock = false;

    // Storage configuration
    protected String rootDir;
    protected String storeDir;
    protected String storeBackupDir;
    protected String whiteListDir;
    protected String rejectAddress;
    protected String netDBDir;

    protected int storeMaxOpenFiles = 1024;
    protected int storeMaxThreads = 1;
    protected boolean storeFromBackup = false;
    protected String originStoreDir = "./testdate";

    // Whitelist configuration
    protected String walletKeyFile;

    protected int TTL = 5;
    protected List<InetSocketAddress> whiteIPList = Lists.newArrayList();
    protected List<String> poolWhiteIPList = Lists.newArrayList();

    // Wallet configuration
    protected String walletFilePath;

    // XDAG configuration
    protected long xdagEra;
    protected XdagField.FieldType xdagFieldHeader;
    protected XAmount mainStartAmount;
    protected long apolloForkHeight;
    protected XAmount apolloForkAmount;

    // RPC configuration
    protected boolean rpcHttpEnabled = false;
    protected String rpcHttpHost = "127.0.0.1";
    protected int rpcHttpPort = 10001;
    protected boolean rpcEnableHttps = false;
    protected String rpcHttpCorsOrigins = "*";
    protected String  rpcHttpsCertFile;
    protected String rpcHttpsKeyFile;
    protected int rpcHttpMaxContentLength = 1024 * 1024; // 1MB

    // RPC netty configuration
    protected int rpcHttpBossThreads = 1;
    protected int rpcHttpWorkerThreads = 4; // 0 means use Netty default (2 * CPU cores)


    // Snapshot configuration
    protected boolean snapshotEnabled = false;
    protected long snapshotHeight;
    protected long snapshotTime;
    protected boolean isSnapshotJ;

    // Chain (DAG-native contracts) configuration
    // volatile: chainActivationHeight/chainActivationHeightOverride may be flipped by tests
    // (see ChainSpec#setChainActivationHeight) while another thread is importing blocks.
    protected volatile long chainActivationHeight = Long.MAX_VALUE;
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    protected volatile Long chainActivationHeightOverride;
    @Setter(AccessLevel.NONE)
    protected int chainMaxChunksPerChain = ChainSpec.DEFAULT_MAX_CHUNKS_PER_CHAIN;
    @Setter(AccessLevel.NONE)
    protected int chainMaxWasmBytes = 1024 * 1024;
    @Setter(AccessLevel.NONE)
    protected XAmount chainChunkFee = XAmount.of(10, XUnit.MILLI_XDAG);

    // RandomX configuration
    protected boolean flag;

    protected AbstractConfig(String rootDir, String configName, Network network, short networkVersion) {
        this.rootDir = rootDir;
        this.configName = configName;
        this.network = network;
        this.networkVersion = networkVersion;
        getSetting();
        setDir();
    }

    public void setDir() {
        storeDir = getRootDir() + "/rocksdb/xdagdb";
        storeBackupDir = getRootDir() + "/rocksdb/xdagdb/backupdata";
    }

    @Override
    public RPCSpec getRPCSpec() {
        return this;
    }

    @Override
    public SnapshotSpec getSnapshotSpec() {
        return this;
    }

    @Override
    public ChainSpec getChainSpec() {
        return this;
    }

    @Override
    public long getChainActivationHeight() {
        return chainActivationHeightOverride != null ? chainActivationHeightOverride : chainActivationHeight;
    }

    @Override
    public void setChainActivationHeight(long height) {
        this.chainActivationHeightOverride = null;
        this.chainActivationHeight = height;
    }

    @Override
    public int getChainMaxChunksPerChain() {
        return chainMaxChunksPerChain;
    }

    @Override
    public int getChainMaxWasmBytes() {
        return chainMaxWasmBytes;
    }

    @Override
    public int getChainMaxInlineArgs() {
        return ChainSpec.CHAIN_MAX_INLINE_ARGS;
    }

    @Override
    public XAmount getChainChunkFee() {
        return chainChunkFee;
    }

    @Override
    public RandomxSpec getRandomxSpec() {
        return this;
    }

    @Override
    public FundSpec getFundSpec() {
        return this;
    }

    @Override
    public Network getNetwork() {
        return this.network;
    }

    @Override
    public short getNetworkVersion() {
        return this.networkVersion;
    }

    @Override
    public String getNodeTag() {
        return this.nodeTag;
    }

    @Override
    public Set<MessageCode> getNetPrioritizedMessages() {
        return this.netPrioritizedMessages;
    }

    @Override
    public String getClientId() {
        return String.format("%s/v%s-%s/%s",
                Constants.CLIENT_NAME,
                Constants.CLIENT_VERSION,
                SystemUtils.OS_NAME,
                SystemUtils.OS_ARCH);
    }

    @Override
    public CapabilityTreeSet getClientCapabilities() {
        return CapabilityTreeSet.of(Capability.FULL_NODE, Capability.LIGHT_NODE);
    }

    @Override
    public NodeSpec getNodeSpec() {
        return this;
    }

    @Override
    public AdminSpec getAdminSpec() {
        return this;
    }

    @Override
    public WalletSpec getWalletSpec() {
        return this;
    }

    public void getSetting() {
        com.typesafe.config.Config config = ConfigFactory.load(getConfigName());

        adminTelnetIp = config.hasPath("admin.telnet.ip") ? config.getString("admin.telnet.ip") : "127.0.0.1";
        adminTelnetPort = config.hasPath("admin.telnet.port") ? config.getInt("admin.telnet.port") : 6001;
        adminTelnetPassword = config.getString("admin.telnet.password");

        poolWhiteIPList = config.hasPath("pool.whiteIPs") ? config.getStringList("pool.whiteIPs") : Collections.singletonList("127.0.0.1");
        log.info("Pool whitelist {}. Any IP allowed? {}", poolWhiteIPList, poolWhiteIPList.contains("0.0.0.0"));
        websocketServerPort = config.hasPath("pool.ws.port") ? config.getInt("pool.ws.port") : 7001;
        nodeIp = config.hasPath("node.ip") ? config.getString("node.ip") : "127.0.0.1";
        nodePort = config.hasPath("node.port") ? config.getInt("node.port") : 8001;
        nodeTag = config.hasPath("node.tag") ? config.getString("node.tag") : "xdagj";
        rejectAddress = config.hasPath("node.reject.transaction.address") ? config.getString("node.reject.transaction.address") : "";
        maxInboundConnectionsPerIp = config.getInt("node.maxInboundConnectionsPerIp");
        enableTxHistory = config.hasPath("node.transaction.history.enable") && config.getBoolean("node.transaction.history.enable");
        enableGenerateBlock = config.hasPath("node.generate.block.enable") && config.getBoolean("node.generate.block.enable");
        txPageSizeLimit = config.hasPath("node.transaction.history.pageSizeLimit") ? config.getInt("node.transaction.history.pageSizeLimit") : 500;
        fundAddress = config.hasPath("fund.address") ? config.getString("fund.address") : "4duPWMbYUgAifVYkKDCWxLvRRkSByf5gb";
        fundRation = config.hasPath("fund.ration") ? config.getDouble("fund.ration") : 5;
        nodeRation = config.hasPath("node.ration") ? config.getDouble("node.ration") : 5;
        List<String> whiteIpList = config.getStringList("node.whiteIPs");
        log.debug("{} IP access", whiteIpList.size());
        for (String addr : whiteIpList) {
            String ip = addr.split(":")[0];
            int port = Integer.parseInt(addr.split(":")[1]);
            whiteIPList.add(new InetSocketAddress(ip, port));
        }
        // RPC configuration
        rpcHttpEnabled = config.hasPath("rpc.http.enabled") && config.getBoolean("rpc.http.enabled");
        if (rpcHttpEnabled) {
            rpcHttpHost = config.hasPath("rpc.http.host") ? config.getString("rpc.http.host") : "127.0.0.1";
            rpcHttpPort = config.hasPath("rpc.http.port") ? config.getInt("rpc.http.port") : 10001;
        }
        flag = config.hasPath("randomx.flags.fullmem") && config.getBoolean("randomx.flags.fullmem");

        // Chain configuration overrides (defaults live in the per-network constructors)
        chainActivationHeightOverride = config.hasPath("chain.activation.height") ? config.getLong("chain.activation.height") : null;
        if (config.hasPath("chain.chunk.maxPerChain")) {
            int overridden = config.getInt("chain.chunk.maxPerChain");
            warnChainConsensusOverride("chain.chunk.maxPerChain", overridden, chainMaxChunksPerChain);
            chainMaxChunksPerChain = overridden;
        }
        if (config.hasPath("chain.wasm.maxBytes")) {
            int overridden = config.getInt("chain.wasm.maxBytes");
            warnChainConsensusOverride("chain.wasm.maxBytes", overridden, chainMaxWasmBytes);
            chainMaxWasmBytes = overridden;
        }
        if (config.hasPath("chain.chunk.feeMilliXdag")) {
            long feeMilliXdag = config.getLong("chain.chunk.feeMilliXdag");
            if (feeMilliXdag < 0) {
                throw new IllegalArgumentException(
                        "Invalid chain.chunk.feeMilliXdag: " + feeMilliXdag + " (must not be negative)");
            }
            try {
                XAmount overridden = XAmount.of(feeMilliXdag, XUnit.MILLI_XDAG);
                warnChainConsensusOverride("chain.chunk.feeMilliXdag", overridden, chainChunkFee);
                chainChunkFee = overridden;
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Invalid chain.chunk.feeMilliXdag: " + feeMilliXdag + " overflows XAmount", e);
            }
        }

        // Fail fast on chain parameters that would silently fork the node: every node must
        // agree on these values (see ChainSpec), so a bad conf value is rejected at startup
        // rather than producing divergent consensus state later.
        if (chainActivationHeightOverride != null) {
            if (chainActivationHeightOverride < 0) {
                throw new IllegalArgumentException(
                        "Invalid chain.activation.height: " + chainActivationHeightOverride
                                + " (must be >= 0; 0 means active from genesis)");
            }
            log.warn("Chain activation height overridden by configuration to {} (network default suppressed)",
                    chainActivationHeightOverride);
        }
        if (chainMaxChunksPerChain <= 0) {
            throw new IllegalArgumentException(
                    "Invalid chain.chunk.maxPerChain: " + chainMaxChunksPerChain + " (must be > 0)");
        }
        if (chainMaxWasmBytes <= 0) {
            throw new IllegalArgumentException(
                    "Invalid chain.wasm.maxBytes: " + chainMaxWasmBytes + " (must be > 0)");
        }
        // chainChunkFee can only be negative here if the conf-read path above admitted a negative
        // feeMilliXdag, which it now rejects before XAmount.of ever runs; the default is a
        // non-negative literal. Kept as a defensive assertion against that invariant drifting.
        assert !chainChunkFee.isNegative() : "chainChunkFee must not be negative: " + chainChunkFee;
        // A chunk chain carries at most maxPerChain chunks, each holding at most
        // CHUNK_DATA_LEN bytes of payload (mirrors io.xdag.chain.ext.ChunkExt.MAX_DATA_LEN;
        // not imported here to keep io.xdag.config free of a dependency on the chain.ext
        // wire-format package). A WASM bound above that ceiling could never be satisfied by
        // any chunk chain.
        final int CHUNK_DATA_LEN = 352;
        long maxChainCapacity = (long) chainMaxChunksPerChain * CHUNK_DATA_LEN;
        if (chainMaxWasmBytes > maxChainCapacity) {
            throw new IllegalArgumentException(
                    "Invalid chain.wasm.maxBytes: " + chainMaxWasmBytes
                            + " exceeds what any chunk chain could carry (chain.chunk.maxPerChain=" + chainMaxChunksPerChain
                            + " x " + CHUNK_DATA_LEN + " bytes/chunk = " + maxChainCapacity + " bytes max)");
        }
        // The consensus chunk-fee re-check (ChainL1Processor.feeCovers) multiplies the configured
        // chunk fee (in nano-XDAG) by the chunk count of every chain a paying block links: a DEPLOY
        // may carry a code chain AND an args chain, so the worst case is 2 x chainMaxChunksPerChain.
        // An overflow there would throw inside setMain, so the combination is rejected here instead,
        // at startup.
        try {
            chainChunkFee.multiply(2L * chainMaxChunksPerChain);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Invalid combination of chain.chunk.feeMilliXdag and chain.chunk.maxPerChain: " + chainChunkFee
                            + " x 2 x " + chainMaxChunksPerChain + " overflows a long", e);
        }
    }

    /**
     * Warns that a chain consensus parameter was taken from the conf file instead of the protocol
     * default. These are not node-local tuning knobs: every node must compute the same CHAIN_L1
     * verdicts, so a node running a non-default value simply forks, silently and without any error
     * of its own. Mirrors the {@code chain.activation.height} warning above.
     */
    private static void warnChainConsensusOverride(String key, Object value, Object protocolDefault) {
        log.warn("{} overridden by configuration to {} (protocol default {}); this is a consensus "
                + "parameter, never set it on testnet/mainnet", key, value, protocolDefault);
    }

    @Override
    public void changePara(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("Use default configuration");
            return;
        }

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-a":
                case "-c":
                case "-m":
                case "-s":
                    i++;
                    // TODO: Set mining thread count
                    break;
                case "-f":
                    i++;
                    this.rootDir = args[i];
                    break;
                case "-p":
                    i++;
                    this.changeNode(args[i]);
                    break;
                case "-r":
                    // TODO: Only load block but no run
                    break;
                case "-d":
                case "-t":
                    // Only devnet or testnet
                    break;
                default:
                    // log.error("Illegal instruction");
            }
        }
    }

    public void changeNode(String host) {
        String[] args = host.split(":");
        this.nodeIp = args[0];
        this.nodePort = Integer.parseInt(args[1]);
    }

    @Override
    public int getNetMaxFrameBodySize() {
        return this.netMaxFrameBodySize;
    }

    @Override
    public int getNetMaxPacketSize() {
        return this.netMaxPacketSize;
    }

    @Override
    public int getMaxInboundConnectionsPerIp() {
        return this.maxInboundConnectionsPerIp;
    }

    @Override
    public List<String> getPoolWhiteIPList() {
        return poolWhiteIPList;
    }

    @Override
    public int getWebsocketServerPort() {
        return websocketServerPort;
    }

    @Override
    public boolean isRpcHttpEnabled() {
        return rpcHttpEnabled;
    }

    @Override
    public String getRpcHttpHost() {
        return rpcHttpHost;
    }

    @Override
    public int getRpcHttpPort() { return rpcHttpPort;}

    @Override
    public boolean isRpcEnableHttps() {return rpcEnableHttps;}

    @Override
    public String getRpcHttpCorsOrigins() {return rpcHttpCorsOrigins;}

    @Override
    public int getRpcHttpMaxContentLength() {return rpcHttpMaxContentLength;}

    @Override
    public int getRpcHttpBossThreads() {return rpcHttpBossThreads;}

    @Override
    public int getRpcHttpWorkerThreads() {return rpcHttpWorkerThreads;}

    @Override
    public String getRpcHttpsCertFile() {return rpcHttpsCertFile;}

    @Override
    public String getRpcHttpsKeyFile() {return rpcHttpsKeyFile;}

    @Override
    public boolean isSnapshotEnabled() {
        return snapshotEnabled;
    }

    @Override
    public boolean isSnapshotJ() {
        return isSnapshotJ;
    }

    @Override
    public long getSnapshotHeight() {
        return snapshotHeight;
    }

    @Override
    public boolean getRandomxFlag() {
        return flag;
    }

    @Override
    public boolean getEnableTxHistory() {
        return enableTxHistory;
    }

    @Override
    public long getTxPageSizeLimit() {
        return txPageSizeLimit;
    }

    @Override
    public boolean getEnableGenerateBlock() {
        return enableGenerateBlock;
    }

    @Override
    public void setSnapshotJ(boolean isSnapshot) {
        this.isSnapshotJ = isSnapshot;
    }

    @Override
    public void snapshotEnable() {
        snapshotEnabled = true;
    }

    @Override
    public long getSnapshotTime() {
        return snapshotTime;
    }
}
