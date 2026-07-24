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
import io.xdag.core.XdagField;
import io.xdag.net.Capability;
import io.xdag.net.CapabilityTreeSet;
import io.xdag.net.message.MessageCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.net.InetSocketAddress;
import java.util.*;

@Slf4j
@Getter
@Setter
public class AbstractConfig implements Config, AdminSpec, NodeSpec, WalletSpec, RPCSpec, SnapshotSpec, RandomxSpec, FundSpec {

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
    // Deny cross-origin by default; operators opt-in by listing specific origins.
    protected String rpcHttpCorsOrigins = "";
    // Optional bearer token; when non-empty every RPC request must carry "Authorization: Bearer <token>".
    protected String rpcHttpApiToken = "";
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
        // S-30: tolerate a trimmed config; S-08: warn loudly on a weak/default admin telnet password.
        adminTelnetPassword = config.hasPath("admin.telnet.password") ? config.getString("admin.telnet.password") : "";
        Set<String> weakTelnetPasswords = Set.of("123", "root", "admin", "password", "test");
        if (adminTelnetPassword == null || adminTelnetPassword.isEmpty()
                || weakTelnetPasswords.contains(adminTelnetPassword)) {
            log.warn("************************************************************************************");
            log.warn("SECURITY WARNING: a weak or default admin telnet password is configured. Set a "
                    + "strong 'admin.telnet.password' before running this node in production.");
            log.warn("************************************************************************************");
        }

        poolWhiteIPList = config.hasPath("pool.whiteIPs") ? config.getStringList("pool.whiteIPs") : Collections.singletonList("127.0.0.1");
        log.info("Pool whitelist {}. Any IP allowed? {}", poolWhiteIPList, poolWhiteIPList.contains("0.0.0.0"));
        // S-09: 0.0.0.0 opens the pool websocket to any IP. Warn only; do not change the shipped value.
        if (poolWhiteIPList.contains("0.0.0.0")) {
            log.warn("************************************************************************************");
            log.warn("SECURITY WARNING: the pool websocket whitelist contains 0.0.0.0 and is open to ANY IP.");
            log.warn("************************************************************************************");
        }
        websocketServerPort = config.hasPath("pool.ws.port") ? config.getInt("pool.ws.port") : 7001;
        nodeIp = config.hasPath("node.ip") ? config.getString("node.ip") : "127.0.0.1";
        nodePort = config.hasPath("node.port") ? config.getInt("node.port") : 8001;
        nodeTag = config.hasPath("node.tag") ? config.getString("node.tag") : "xdagj";
        rejectAddress = config.hasPath("node.reject.transaction.address") ? config.getString("node.reject.transaction.address") : "";
        maxInboundConnectionsPerIp = config.hasPath("node.maxInboundConnectionsPerIp")
                ? config.getInt("node.maxInboundConnectionsPerIp") : maxInboundConnectionsPerIp;
        enableTxHistory = config.hasPath("node.transaction.history.enable") && config.getBoolean("node.transaction.history.enable");
        enableGenerateBlock = config.hasPath("node.generate.block.enable") && config.getBoolean("node.generate.block.enable");
        txPageSizeLimit = config.hasPath("node.transaction.history.pageSizeLimit") ? config.getInt("node.transaction.history.pageSizeLimit") : 500;
        // S-36: refuse to silently fall back to a hardcoded foundation address.
        if (!config.hasPath("fund.address")) {
            throw new IllegalStateException("Missing required configuration 'fund.address'. "
                    + "Set 'fund.address' in the node configuration file.");
        }
        fundAddress = config.getString("fund.address");
        fundRation = config.hasPath("fund.ration") ? config.getDouble("fund.ration") : 5;
        nodeRation = config.hasPath("node.ration") ? config.getDouble("node.ration") : 5;
        // S-30: tolerate a missing/trimmed whiteIPs list and skip malformed entries instead of aborting startup.
        List<String> whiteIpList = config.hasPath("node.whiteIPs")
                ? config.getStringList("node.whiteIPs") : Collections.emptyList();
        log.debug("{} IP access", whiteIpList.size());
        for (String addr : whiteIpList) {
            String[] parts = addr == null ? new String[0] : addr.split(":");
            if (parts.length != 2 || parts[0].isEmpty()) {
                log.warn("Skipping malformed node.whiteIPs entry '{}' (expected host:port)", addr);
                continue;
            }
            try {
                int port = Integer.parseInt(parts[1]);
                whiteIPList.add(new InetSocketAddress(parts[0], port));
            } catch (NumberFormatException e) {
                log.warn("Skipping node.whiteIPs entry '{}' with invalid port", addr);
            }
        }
        // RPC configuration
        rpcHttpEnabled = config.hasPath("rpc.http.enabled") && config.getBoolean("rpc.http.enabled");
        if (rpcHttpEnabled) {
            rpcHttpHost = config.hasPath("rpc.http.host") ? config.getString("rpc.http.host") : "127.0.0.1";
            rpcHttpPort = config.hasPath("rpc.http.port") ? config.getInt("rpc.http.port") : 10001;
            rpcHttpApiToken = config.hasPath("rpc.http.apiToken") ? config.getString("rpc.http.apiToken").trim() : "";
            if (rpcHttpApiToken.isEmpty() && !isLoopbackHost(rpcHttpHost)) {
                log.warn("RPC HTTP is bound to non-loopback host '{}' WITHOUT rpc.http.apiToken set: "
                        + "money-moving methods are reachable unauthenticated. Set rpc.http.apiToken "
                        + "or bind rpc.http.host to 127.0.0.1.", rpcHttpHost);
            }
        }
        flag = config.hasPath("randomx.flags.fullmem") && config.getBoolean("randomx.flags.fullmem");

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
                    if (i + 1 >= args.length) {
                        System.out.println("Missing argument for -f (root directory)");
                        return;
                    }
                    this.rootDir = args[++i];
                    break;
                case "-p":
                    if (i + 1 >= args.length) {
                        System.out.println("Missing argument for -p (node host:port)");
                        return;
                    }
                    this.changeNode(args[++i]);
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
        if (host == null) {
            throw new IllegalArgumentException("Node address must not be null, expected host:port");
        }
        String[] args = host.split(":");
        if (args.length != 2 || args[0].isEmpty()) {
            throw new IllegalArgumentException("Invalid node address '" + host + "', expected host:port");
        }
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid node port in '" + host + "', expected host:port", e);
        }
        this.nodeIp = args[0];
        this.nodePort = port;
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
    public String getRpcHttpApiToken() {return rpcHttpApiToken;}

    /** True if the host is a loopback literal — used to decide whether an unauthenticated RPC is local-only. */
    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim();
        return h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

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
