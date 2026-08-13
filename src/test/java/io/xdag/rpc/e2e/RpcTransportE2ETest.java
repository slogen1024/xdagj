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
package io.xdag.rpc.e2e;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.xdag.Kernel;
import io.xdag.config.DevnetConfig;
import io.xdag.config.spec.RPCSpec;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.net.ChannelManager;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.server.core.JsonRpcServer;
import io.xdag.rpc.server.core.RpcHandlers;
import io.xdag.rpc.server.handler.JsonRpcHandler;
import io.xdag.rpc.ws.RpcWebSocketServer;
import io.xdag.rpc.ws.SubscriptionManager;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * In-process, real-socket RPC E2E harness (E2E sub-project). Boots the REAL {@link JsonRpcServer}
 * (HTTP) and {@link RpcWebSocketServer} (WS) on ephemeral ports over a mocked {@link Kernel} that
 * returns the in-memory EVM services, then drives them with the JDK's built-in
 * {@link java.net.http.HttpClient} — exercising exactly the transport MetaMask/Hardhat use.
 *
 * <p>"Mining" here = the test submits a signed tx over HTTP then calls
 * {@link EvmBlockProcessor#processMainBlock} to confirm it, mirroring native block execution.
 * Later E2E tasks reuse {@link #rpc}, {@link #openWs}, and {@link #mineTx}.
 */
public class RpcTransportE2ETest {

    private static final Bytes LOG0_INIT = Bytes.fromHexString("0x6006600c60003960066000f360006000a000");
    private static final Address KEY1_SENDER =
            Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(0xCAFE);

    private InMemoryKVSource state;
    private EvmTxStore txStore;
    private EvmMetaStore metaStore;
    private EvmBlockProcessor proc;
    private SubscriptionManager mgr;
    private JsonRpcServer http;
    private RpcWebSocketServer ws;
    private HttpClient client;
    private final AtomicInteger idSeq = new AtomicInteger(1);

    @Before
    public void setUp() {
        // --- real EVM services over in-memory stores (the servers read/write these) ---
        state = new InMemoryKVSource();
        txStore = new EvmTxStore(new InMemoryKVSource());
        metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txStore, metaStore, 0L,
                List.of(new GenesisAllocEntry(KEY1_SENDER, Wei.fromEth(1))), journal, 128); // 128 = C4 history window
        proc.seedGenesisIfAbsent();
        mgr = new SubscriptionManager();
        proc.setSubscriptionSink(mgr);

        EvmTxPool txPool = new EvmTxPool(new EvmTxStore(new InMemoryKVSource()), state,
                CHAIN_ID, 30_000_000L, Wei.ONE, 3600L, () -> 1000L);

        // --- mocked Blockchain / ChannelManager / Kernel (avoids booting a full node) ---
        Blockchain blockchain = Mockito.mock(Blockchain.class);
        Mockito.when(blockchain.getLatestMainBlockNumber()).thenReturn(1000L);
        Block block = Mockito.mock(Block.class);
        Mockito.when(block.getHash()).thenReturn(Bytes32.fromHexString("0x" + "ab".repeat(32)));
        Mockito.when(block.getTimestamp()).thenReturn(0L);
        Mockito.when(blockchain.getBlockByHeight(Mockito.anyLong())).thenReturn(block);

        ChannelManager channelMgr = Mockito.mock(ChannelManager.class);
        Mockito.when(channelMgr.getActiveChannels()).thenReturn(List.of());

        Kernel kernel = Mockito.mock(Kernel.class);
        Mockito.when(kernel.getConfig()).thenReturn(new DevnetConfig());
        Mockito.when(kernel.getEvmStateStore()).thenReturn(state);
        Mockito.when(kernel.getEvmStateJournal()).thenReturn(journal);
        Mockito.when(kernel.getEvmTxStore()).thenReturn(txStore);
        Mockito.when(kernel.getEvmMetaStore()).thenReturn(metaStore);
        Mockito.when(kernel.getEvmTxPool()).thenReturn(txPool);
        Mockito.when(kernel.getBlockchain()).thenReturn(blockchain);
        Mockito.when(kernel.getChannelMgr()).thenReturn(channelMgr);
        XdagApi api = Mockito.mock(XdagApi.class);

        RPCSpec rpc = Mockito.mock(RPCSpec.class);
        Mockito.when(rpc.getRpcHttpHost()).thenReturn("127.0.0.1");
        Mockito.when(rpc.getRpcHttpPort()).thenReturn(0);
        Mockito.when(rpc.getRpcWsHost()).thenReturn("127.0.0.1");
        Mockito.when(rpc.getRpcWsPort()).thenReturn(0);
        Mockito.when(rpc.getRpcHttpBossThreads()).thenReturn(1);
        Mockito.when(rpc.getRpcHttpWorkerThreads()).thenReturn(2);
        Mockito.when(rpc.getRpcHttpMaxContentLength()).thenReturn(1_048_576);
        Mockito.when(rpc.getRpcHttpCorsOrigins()).thenReturn("*");
        Mockito.when(rpc.getRpcHttpApiToken()).thenReturn("");

        // --- boot the REAL servers on ephemeral ports (0) + a real HTTP/WS client ---
        http = new JsonRpcServer(rpc, api, kernel);
        http.start();
        ws = new RpcWebSocketServer(rpc, mgr, RpcHandlers.build(api, kernel));
        ws.start();
        client = HttpClient.newHttpClient();
    }

    @After
    public void tearDown() {
        if (ws != null) {
            ws.stop();
        }
        if (http != null) {
            http.stop();
        }
    }

    /**
     * POST a JSON-RPC request over HTTP; returns the parsed {@code result} node (fails on an {@code
     * error}). Each param must be a valid JSON value — use {@link #quote(String)} for string params.
     */
    private JsonNode rpc(String method, String... params) throws Exception {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            p.append(i == 0 ? "" : ",").append(params[i]);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idSeq.getAndIncrement()
                + ",\"method\":\"" + method + "\",\"params\":[" + p + "]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.boundPort()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals("HTTP 200", 200, resp.statusCode());
        JsonNode node = JsonRpcHandler.MAPPER.readTree(resp.body());
        assertTrue(method + " returned an error: " + resp.body(),
                node.get("error") == null || node.get("error").isNull());
        return node.get("result");
    }

    private WebSocket openWs(BlockingQueue<String> frames) throws Exception {
        return client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + ws.boundPort() + "/"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        frames.add(data.toString());
                        webSocket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    private EvmTransaction mineTx(long nonce, Optional<Address> to, Bytes payload, long height)
            throws Exception {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 200_000L, to, Wei.ZERO, payload, CHAIN_ID)
                .sign(key, algo);
        rpc("eth_sendRawTransaction", quote(tx.getRawRlp().toHexString()));
        proc.processMainBlock(List.of(Bytes32.wrap(tx.getHash().getBytes())), height, 1000L + height,
                Bytes32.leftPad(Bytes.ofUnsignedLong(height)));
        return tx;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    @Test
    public void http_chainId_and_ws_subscribe_smoke() throws Exception {
        assertEquals("\"0xcafe\"", rpc("eth_chainId").toString());

        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocket sock = openWs(frames);
        sock.sendText("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}",
                true);
        String reply = frames.poll(5, TimeUnit.SECONDS);
        assertTrue("subscribe returns a 0x id", reply != null && reply.contains("\"result\":\"0x"));
        sock.abort(); // JDK HttpClient has no close() on 17; abort the socket so it doesn't linger
    }

    @Test
    public void http_deploy_call_getLogs_and_historical_balance() throws Exception {
        String eoa = KEY1_SENDER.toHexString();
        // Genesis balance over HTTP: Wei.fromEth(1) = 1e18 wei = 0xde0b6b3a7640000.
        assertEquals("\"0xde0b6b3a7640000\"",
                rpc("eth_getBalance", quote(eoa), quote("latest")).toString());

        // Deploy the LOG0 contract (h1); read the receipt over HTTP.
        EvmTransaction deploy = mineTx(0, Optional.empty(), LOG0_INIT, 1L);
        JsonNode receipt = rpc("eth_getTransactionReceipt", quote(deploy.getHash().getBytes().toHexString()));
        assertEquals("\"0x1\"", receipt.get("status").toString());
        String contract = receipt.get("contractAddress").asText();

        // Call it (h2) -> one log. getLogs by the contract address returns exactly one, removed=false.
        mineTx(1, Optional.of(Address.fromHexString(contract)), Bytes.EMPTY, 2L);
        JsonNode logs = rpc("eth_getLogs",
                "{\"fromBlock\":\"0x1\",\"toBlock\":\"0x2\",\"address\":" + quote(contract) + "}");
        assertEquals(1, logs.size());
        assertEquals("\"" + contract + "\"", logs.get(0).get("address").toString());
        assertEquals("false", logs.get(0).get("removed").toString());

        // Historical block tag (C4): balance at height 1 exceeds latest — gas was paid between h1 and h2.
        String atOne = rpc("eth_getBalance", quote(eoa), quote("0x1")).asText();
        String atLatest = rpc("eth_getBalance", quote(eoa), quote("latest")).asText();
        assertTrue("historical (h1) balance must exceed latest (gas paid at h2)",
                new BigInteger(atOne.substring(2), 16).compareTo(new BigInteger(atLatest.substring(2), 16)) > 0);

        // A topic filter the LOG0 (topic-less) log cannot satisfy returns nothing (full positional filter, C5).
        JsonNode none = rpc("eth_getLogs", "{\"fromBlock\":\"0x1\",\"toBlock\":\"0x2\",\"topics\":[\""
                + "0x" + "77".repeat(32) + "\"]}");
        assertEquals(0, none.size());
    }
}
