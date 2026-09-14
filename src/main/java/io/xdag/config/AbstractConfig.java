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
public class AbstractConfig implements Config, AdminSpec, NodeSpec, WalletSpec, RPCSpec, SnapshotSpec, RandomxSpec, FundSpec, LaneSpec {

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

    // Lane (DAG-native contracts) configuration
    // volatile: laneActivationHeight/laneActivationHeightOverride may be flipped by tests
    // (see LaneSpec#setLaneActivationHeight) while another thread is importing blocks.
    protected volatile long laneActivationHeight = Long.MAX_VALUE;
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    protected volatile Long laneActivationHeightOverride;
    @Setter(AccessLevel.NONE)
    protected int laneMaxChunksPerChain = LaneSpec.DEFAULT_MAX_CHUNKS_PER_CHAIN;
    @Setter(AccessLevel.NONE)
    protected int laneMaxWasmBytes = 1024 * 1024;
    @Setter(AccessLevel.NONE)
    protected XAmount laneChunkFee = XAmount.of(10, XUnit.MILLI_XDAG);

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
    public LaneSpec getLaneSpec() {
        return this;
    }

    @Override
    public long getLaneActivationHeight() {
        return laneActivationHeightOverride != null ? laneActivationHeightOverride : laneActivationHeight;
    }

    @Override
    public void setLaneActivationHeight(long height) {
        this.laneActivationHeightOverride = null;
        this.laneActivationHeight = height;
    }

    @Override
    public int getLaneMaxChunksPerChain() {
        return laneMaxChunksPerChain;
    }

    @Override
    public int getLaneMaxWasmBytes() {
        return laneMaxWasmBytes;
    }

    @Override
    public int getLaneMaxInlineArgs() {
        return LaneSpec.LANE_MAX_INLINE_ARGS;
    }

    @Override
    public XAmount getLaneChunkFee() {
        return laneChunkFee;
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

        // Lane configuration overrides (defaults live in the per-network constructors)
        laneActivationHeightOverride = config.hasPath("lane.activation.height") ? config.getLong("lane.activation.height") : null;
        if (config.hasPath("lane.chunk.maxPerChain")) {
            laneMaxChunksPerChain = config.getInt("lane.chunk.maxPerChain");
        }
        if (config.hasPath("lane.wasm.maxBytes")) {
            laneMaxWasmBytes = config.getInt("lane.wasm.maxBytes");
        }
        if (config.hasPath("lane.chunk.feeMilliXdag")) {
            long feeMilliXdag = config.getLong("lane.chunk.feeMilliXdag");
            if (feeMilliXdag < 0) {
                throw new IllegalArgumentException(
                        "Invalid lane.chunk.feeMilliXdag: " + feeMilliXdag + " (must not be negative)");
            }
            try {
                laneChunkFee = XAmount.of(feeMilliXdag, XUnit.MILLI_XDAG);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Invalid lane.chunk.feeMilliXdag: " + feeMilliXdag + " overflows XAmount", e);
            }
        }

        // Fail fast on lane parameters that would silently fork the node: every node must
        // agree on these values (see LaneSpec), so a bad conf value is rejected at startup
        // rather than producing divergent consensus state later.
        if (laneActivationHeightOverride != null) {
            if (laneActivationHeightOverride < 0) {
                throw new IllegalArgumentException(
                        "Invalid lane.activation.height: " + laneActivationHeightOverride
                                + " (must be >= 0; 0 means active from genesis)");
            }
            log.warn("Lane activation height overridden by configuration to {} (network default suppressed)",
                    laneActivationHeightOverride);
        }
        if (laneMaxChunksPerChain <= 0) {
            throw new IllegalArgumentException(
                    "Invalid lane.chunk.maxPerChain: " + laneMaxChunksPerChain + " (must be > 0)");
        }
        if (laneMaxWasmBytes <= 0) {
            throw new IllegalArgumentException(
                    "Invalid lane.wasm.maxBytes: " + laneMaxWasmBytes + " (must be > 0)");
        }
        // laneChunkFee can only be negative here if the conf-read path above admitted a negative
        // feeMilliXdag, which it now rejects before XAmount.of ever runs; the default is a
        // non-negative literal. Kept as a defensive assertion against that invariant drifting.
        assert !laneChunkFee.isNegative() : "laneChunkFee must not be negative: " + laneChunkFee;
        // A chunk chain carries at most maxPerChain chunks, each holding at most
        // CHUNK_DATA_LEN bytes of payload (mirrors io.xdag.lane.ext.ChunkExt.MAX_DATA_LEN;
        // not imported here to keep io.xdag.config free of a dependency on the lane.ext
        // wire-format package). A WASM bound above that ceiling could never be satisfied by
        // any chunk chain.
        final int CHUNK_DATA_LEN = 352;
        long maxChainCapacity = (long) laneMaxChunksPerChain * CHUNK_DATA_LEN;
        if (laneMaxWasmBytes > maxChainCapacity) {
            throw new IllegalArgumentException(
                    "Invalid lane.wasm.maxBytes: " + laneMaxWasmBytes
                            + " exceeds what any chunk chain could carry (lane.chunk.maxPerChain=" + laneMaxChunksPerChain
                            + " x " + CHUNK_DATA_LEN + " bytes/chunk = " + maxChainCapacity + " bytes max)");
        }
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
