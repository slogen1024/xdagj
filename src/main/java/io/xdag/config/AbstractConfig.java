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
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.net.Capability;
import io.xdag.net.CapabilityTreeSet;
import io.xdag.net.message.MessageCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.*;

@Slf4j
@Getter
@Setter
public class AbstractConfig implements Config, AdminSpec, NodeSpec, WalletSpec, RPCSpec, SnapshotSpec, RandomxSpec,
        FundSpec, EvmSpec {

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

    // RPC WebSocket configuration
    protected boolean rpcWsEnabled = false;
    protected String rpcWsHost = "127.0.0.1";
    protected int rpcWsPort = 10002;

    // Snapshot configuration
    protected boolean snapshotEnabled = false;
    protected long snapshotHeight;
    protected long snapshotTime;
    protected boolean isSnapshotJ;

    // RandomX configuration
    protected boolean flag;

    // Embedded-EVM configuration (spec §9); disabled unless the network conf opts in.
    protected boolean evmEnabled = false;
    protected long evmActivationHeight = 0;
    protected long evmBatchActivationHeight = Long.MAX_VALUE;
    protected long evmType2ActivationHeight = Long.MAX_VALUE;
    protected long evmBridgeActivationHeight = Long.MAX_VALUE;
    protected long evmEip3529ActivationHeight = Long.MAX_VALUE;
    protected long evmStateRootActivationHeight = Long.MAX_VALUE;
    protected long evmStateRootLag = 16;
    protected boolean evmStateRootHardReject = false;
    protected String evmBridgeRecoveryAddress;
    protected long evmBridgeWithdrawalDelay = 16;
    protected long evmChainId = 0xCAFE; // 51966 (0xCAFE), reserved devnet id; per-network evm.chainId overrides
    protected long evmBlockGasLimit = 30_000_000L;
    protected long evmTxPoolTtlSeconds = 3600;
    protected int evmMaxP2pTxBytes = 131_072;
    protected BigInteger evmMinGasPrice = BigInteger.valueOf(1_000_000_000L);
    protected long evmMaxLogScanRange = 1024;
    protected int evmStateHistoryWindow = 128;
    protected List<GenesisAllocEntry> evmGenesisAlloc = List.of();

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
    public EvmSpec getEvmSpec() {
        return this;
    }

    @Override
    public boolean isEvmEnabled() {
        return evmEnabled;
    }

    @Override
    public long getEvmActivationHeight() {
        return evmActivationHeight;
    }

    @Override
    public long getEvmBatchActivationHeight() {
        return evmBatchActivationHeight;
    }

    @Override
    public long getEvmType2ActivationHeight() {
        return evmType2ActivationHeight;
    }

    @Override
    public long getEvmBridgeActivationHeight() {
        return evmBridgeActivationHeight;
    }

    @Override
    public long getEvmEip3529ActivationHeight() {
        return evmEip3529ActivationHeight;
    }

    @Override
    public long getEvmStateRootActivationHeight() {
        return evmStateRootActivationHeight;
    }

    @Override
    public long getEvmStateRootLag() {
        return evmStateRootLag;
    }

    @Override
    public boolean isEvmStateRootHardReject() {
        return evmStateRootHardReject;
    }

    @Override
    public String getEvmBridgeRecoveryAddress() {
        return evmBridgeRecoveryAddress;
    }

    @Override
    public long getEvmBridgeWithdrawalDelay() {
        return evmBridgeWithdrawalDelay;
    }

    @Override
    public long getEvmChainId() {
        return evmChainId;
    }

    @Override
    public long getEvmBlockGasLimit() {
        return evmBlockGasLimit;
    }

    @Override
    public long getEvmTxPoolTtlSeconds() {
        return evmTxPoolTtlSeconds;
    }

    @Override
    public int getEvmMaxP2pTxBytes() {
        return evmMaxP2pTxBytes;
    }

    @Override
    public BigInteger getEvmMinGasPrice() {
        return evmMinGasPrice;
    }

    @Override
    public long getEvmMaxLogScanRange() {
        return evmMaxLogScanRange;
    }

    @Override
    public int getEvmStateHistoryWindow() {
        return evmStateHistoryWindow;
    }

    @Override
    public List<GenesisAllocEntry> getEvmGenesisAlloc() {
        return evmGenesisAlloc;
    }

    /**
     * Parses and validates the {@code evm.alloc} genesis-funding list (the on-ramp). Rejects a
     * duplicate address, a non-positive balance, or a balance above the wei ceiling (2^256-1) with a
     * clear error, so a config mistake aborts startup rather than seeding a broken genesis. An
     * absent/empty list yields an empty allocation.
     */
    static List<GenesisAllocEntry> parseEvmAlloc(com.typesafe.config.Config config) {
        if (!config.hasPath("evm.alloc")) {
            return List.of();
        }
        List<GenesisAllocEntry> alloc = new ArrayList<>();
        Set<Address> seen = new HashSet<>();
        for (com.typesafe.config.Config entry : config.getConfigList("evm.alloc")) {
            Address address = Address.fromHexString(entry.getString("address"));
            BigInteger balance = new BigInteger(entry.getString("balance"));
            if (balance.signum() <= 0 || balance.bitLength() > 256) {
                throw new IllegalArgumentException(
                        "evm.alloc balance must be in 1..2^256-1: " + entry.getString("balance"));
            }
            if (!seen.add(address)) {
                throw new IllegalArgumentException("evm.alloc duplicate address: " + address);
            }
            alloc.add(new GenesisAllocEntry(address, Wei.of(balance)));
        }
        return List.copyOf(alloc);
    }

    /**
     * A scheduled bridge without a recovery address (or with a malformed one) is a
     * misconfiguration that would strand mis-remarked deposits — refuse to start (S-36
     * fund.address precedent). Static so the rule is unit-testable without a full config load.
     */
    static void validateBridgeConfig(com.typesafe.config.Config config) {
        boolean scheduled = config.hasPath("evm.bridgeActivationHeight")
                && config.getLong("evm.bridgeActivationHeight") != Long.MAX_VALUE;
        if (!scheduled) {
            return;
        }
        if (!config.hasPath("evm.bridgeRecoveryAddress")) {
            throw new IllegalStateException("Missing required configuration 'evm.bridgeRecoveryAddress'. "
                    + "A network that schedules evm.bridgeActivationHeight must set the recovery address."
                    + " Set 'evm.bridgeRecoveryAddress' in the node configuration file,"
                    + " or remove 'evm.bridgeActivationHeight' to leave the bridge unscheduled.");
        }
        String addr = config.getString("evm.bridgeRecoveryAddress");
        if (!addr.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalStateException(
                    "evm.bridgeRecoveryAddress must be a 0x-prefixed 20-byte hex address, got: " + addr);
        }
        if (!(config.hasPath("evm.enabled") && config.getBoolean("evm.enabled"))) {
            throw new IllegalStateException("evm.bridgeActivationHeight is scheduled but evm.enabled is not "
                    + "true (unset or false): an EVM-disabled node would collect and silently drop deposits.");
        }
        if (config.hasPath("evm.bridgeWithdrawalDelay") && config.getLong("evm.bridgeWithdrawalDelay") < 1) {
            throw new IllegalStateException("evm.bridgeWithdrawalDelay must be >= 1, got "
                    + config.getLong("evm.bridgeWithdrawalDelay"));
        }
        // Delta-lagged EVM execution (evm.stateRootLag) defers a withdrawal burn at height K to
        // setMain(K + stateRootLag - 1). releaseMaturedWithdrawals(M) reads burns at M - withdrawalDelay,
        // so the burn height is executed by release time iff withdrawalDelay >= stateRootLag - 1. Below
        // that, hasUnexecutedHeightAtOrBelow(burnHeight) is always true and the release CRITICAL-skips
        // on every height -- the bridge silently never releases. Consensus-critical: fail fast at load.
        long lag = config.hasPath("evm.stateRootLag") ? config.getLong("evm.stateRootLag") : 16L;
        long withdrawalDelay = config.hasPath("evm.bridgeWithdrawalDelay")
                ? config.getLong("evm.bridgeWithdrawalDelay") : 16L;
        if (withdrawalDelay < lag - 1) {
            throw new IllegalStateException("evm.bridgeWithdrawalDelay (" + withdrawalDelay
                    + ") must be >= evm.stateRootLag - 1 (" + (lag - 1) + "): under delta-lagged EVM "
                    + "execution a burn at height K is not executed until setMain(K + stateRootLag - 1), "
                    + "so a shorter withdrawal delay would defer every release indefinitely.");
        }
        // A bridge scheduled before the EVM itself would deterministically drop every deposit
        // confirmed in [bridgeActivation, evmActivation) — funds stranded at the lock address.
        // Absent evm.activationHeight resolves to 0 (the evmActivationHeight field default), so
        // mirror that here rather than treating absence as "never".
        long evmActivation = config.hasPath("evm.activationHeight")
                ? config.getLong("evm.activationHeight") : 0L;
        long bridgeActivation = config.getLong("evm.bridgeActivationHeight");
        if (bridgeActivation < evmActivation) {
            throw new IllegalStateException("evm.bridgeActivationHeight (" + bridgeActivation
                    + ") must not precede evm.activationHeight (" + evmActivation
                    + "): deposits confirmed before the EVM activates would be dropped.");
        }
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

        // Embedded-EVM section; every key optional, EVM off unless evm.enabled = true.
        evmEnabled = config.hasPath("evm.enabled") && config.getBoolean("evm.enabled");
        evmActivationHeight = config.hasPath("evm.activationHeight")
                ? config.getLong("evm.activationHeight") : evmActivationHeight;
        evmBatchActivationHeight = config.hasPath("evm.batchActivationHeight")
                ? config.getLong("evm.batchActivationHeight") : evmBatchActivationHeight;
        evmType2ActivationHeight = config.hasPath("evm.type2ActivationHeight")
                ? config.getLong("evm.type2ActivationHeight") : evmType2ActivationHeight;
        validateBridgeConfig(config);
        evmBridgeActivationHeight = config.hasPath("evm.bridgeActivationHeight")
                ? config.getLong("evm.bridgeActivationHeight") : evmBridgeActivationHeight;
        evmEip3529ActivationHeight = config.hasPath("evm.eip3529ActivationHeight")
                ? config.getLong("evm.eip3529ActivationHeight") : evmEip3529ActivationHeight;
        evmStateRootActivationHeight = config.hasPath("evm.stateRootActivationHeight")
                ? config.getLong("evm.stateRootActivationHeight") : evmStateRootActivationHeight;
        evmStateRootLag = config.hasPath("evm.stateRootLag")
                ? config.getLong("evm.stateRootLag") : evmStateRootLag;
        evmStateRootHardReject = config.hasPath("evm.stateRootHardReject")
                ? config.getBoolean("evm.stateRootHardReject") : evmStateRootHardReject;
        evmBridgeRecoveryAddress = config.hasPath("evm.bridgeRecoveryAddress")
                ? config.getString("evm.bridgeRecoveryAddress") : evmBridgeRecoveryAddress;
        evmBridgeWithdrawalDelay = config.hasPath("evm.bridgeWithdrawalDelay")
                ? config.getLong("evm.bridgeWithdrawalDelay") : evmBridgeWithdrawalDelay;
        evmChainId = config.hasPath("evm.chainId") ? config.getLong("evm.chainId") : evmChainId;
        evmBlockGasLimit = config.hasPath("evm.blockGasLimit")
                ? config.getLong("evm.blockGasLimit") : evmBlockGasLimit;
        evmTxPoolTtlSeconds = config.hasPath("evm.txPoolTtlSeconds")
                ? config.getLong("evm.txPoolTtlSeconds") : evmTxPoolTtlSeconds;
        evmMaxP2pTxBytes = config.hasPath("evm.maxP2pTxBytes")
                ? config.getInt("evm.maxP2pTxBytes") : evmMaxP2pTxBytes;
        evmMinGasPrice = config.hasPath("evm.minGasPrice")
                ? BigInteger.valueOf(config.getLong("evm.minGasPrice")) : evmMinGasPrice;
        evmMaxLogScanRange = config.hasPath("evm.maxLogScanRange")
                ? config.getLong("evm.maxLogScanRange") : evmMaxLogScanRange;
        evmStateHistoryWindow = config.hasPath("evm.stateHistoryWindow")
                ? config.getInt("evm.stateHistoryWindow") : evmStateHistoryWindow;
        evmGenesisAlloc = parseEvmAlloc(config);
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
            rpcHttpCorsOrigins = config.hasPath("rpc.http.corsOrigins")
                    ? config.getString("rpc.http.corsOrigins").trim() : "";
            if (rpcHttpApiToken.isEmpty() && !isLoopbackHost(rpcHttpHost)) {
                log.warn("RPC HTTP is bound to non-loopback host '{}' WITHOUT rpc.http.apiToken set: "
                        + "money-moving methods are reachable unauthenticated. Set rpc.http.apiToken "
                        + "or bind rpc.http.host to 127.0.0.1.", rpcHttpHost);
            }
        }
        // RPC WebSocket configuration
        rpcWsEnabled = config.hasPath("rpc.ws.enabled") && config.getBoolean("rpc.ws.enabled");
        rpcWsHost = config.hasPath("rpc.ws.host") ? config.getString("rpc.ws.host") : rpcWsHost;
        rpcWsPort = config.hasPath("rpc.ws.port") ? config.getInt("rpc.ws.port") : rpcWsPort;
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
    public boolean isRpcWsEnabled() {return rpcWsEnabled;}

    @Override
    public String getRpcWsHost() {return rpcWsHost;}

    @Override
    public int getRpcWsPort() {return rpcWsPort;}

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
