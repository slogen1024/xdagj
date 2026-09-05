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
package io.xdag.rpc.server.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.eth.EthObjects;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class EthRequestHandlerTest {

    private EthRequestHandler handler;

    @Before
    public void setUp() {
        Blockchain blockchain = Mockito.mock(Blockchain.class);
        Mockito.when(blockchain.getLatestMainBlockNumber()).thenReturn(4096L);
        InMemoryKVSource store = new InMemoryKVSource();
        handler = new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L),
                BigInteger.valueOf(1_000_000_000L), blockchain, null, null, null, null, 1024L,
                new HistoricalStateReader(store, new EvmStateJournal(new InMemoryKVSource()), 128));
    }

    private JsonRpcRequest request(String method, Object... params) {
        JsonRpcRequest r = new JsonRpcRequest();
        r.setMethod(method);
        r.setParams(params);
        r.setId(1);
        return r;
    }

    private final Address contract = Address.fromHexString("0x2222222222222222222222222222222222222222");
    private final Address eoa = Address.fromHexString("0x1111111111111111111111111111111111111111");

    private String eoaHex() { return "0x1111111111111111111111111111111111111111"; }
    private String contractHex() { return "0x2222222222222222222222222222222222222222"; }

    private void seedState(InMemoryKVSource store) {
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(store);
        MutableAccount a = world.createAccount(eoa, 7L, Wei.of(new BigInteger("1234")));
        MutableAccount c = world.createAccount(contract, 1L, Wei.ZERO);
        c.setCode(Bytes.fromHexString("0x602a60005260206000f3")); // returns uint 42
        c.setStorageValue(UInt256.ZERO, UInt256.valueOf(99));
        world.commit();
    }

    private EthRequestHandler handlerOver(InMemoryKVSource store, long head) {
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(head);
        return new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, null, null, null, null, 1024L,
                new HistoricalStateReader(store, new EvmStateJournal(new InMemoryKVSource()), 128));
    }

    private EvmTxPool poolFor(InMemoryKVSource stateStore) {
        return new EvmTxPool(new EvmTxStore(new InMemoryKVSource()), stateStore,
                BigInteger.valueOf(0xCAFE), 30_000_000L, Wei.ONE, 3600L, () -> 1000L);
    }

    private EvmTransaction signedTx(long nonce, BigInteger chainId) {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        return EvmTransaction.unsigned(nonce, Wei.ONE, 100_000L, Optional.empty(),
                Wei.ZERO, Bytes.fromHexString("0x6001600155"), chainId).sign(key, algo);
    }

    private EthRequestHandler writeHandler(InMemoryKVSource stateStore, EvmTxPool pool, List<Bytes> broadcasts) {
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(1L);
        Consumer<Bytes> broadcaster = broadcasts::add;
        return new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, pool, broadcaster, null, null,
                1024L, new HistoricalStateReader(stateStore, new EvmStateJournal(new InMemoryKVSource()), 128));
    }

    private void fundSender(InMemoryKVSource stateStore) {
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(stateStore);
        w.createAccount(KEY1_SENDER, 0L, Wei.fromEth(1));
        w.commit();
    }

    @Test
    public void eth_sendRawTransaction_enqueues_broadcasts_and_returns_hash() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        fundSender(stateStore);
        EvmTxPool pool = poolFor(stateStore);
        List<Bytes> broadcasts = new ArrayList<>();
        EthRequestHandler h = writeHandler(stateStore, pool, broadcasts);

        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        String result = (String) h.handle(request("eth_sendRawTransaction", tx.getRawRlp().toHexString()));

        assertEquals(tx.getHash().getBytes().toHexString(), result);
        assertEquals(1, pool.size());
        assertEquals(1, broadcasts.size());
        assertEquals(tx.getRawRlp(), broadcasts.get(0));
    }

    @Test
    public void eth_sendRawTransaction_duplicate_returns_hash_without_rebroadcast() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        fundSender(stateStore);
        EvmTxPool pool = poolFor(stateStore);
        List<Bytes> broadcasts = new ArrayList<>();
        EthRequestHandler h = writeHandler(stateStore, pool, broadcasts);

        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        h.handle(request("eth_sendRawTransaction", tx.getRawRlp().toHexString()));
        String again = (String) h.handle(request("eth_sendRawTransaction", tx.getRawRlp().toHexString()));

        assertEquals(tx.getHash().getBytes().toHexString(), again);
        assertEquals("duplicate must not re-broadcast", 1, broadcasts.size());
    }

    @Test
    public void eth_sendRawTransaction_oversized_tx_errors_with_invalid_params() {
        // Audit round 2, P2: RPC admission must reject a blob peers could never accept.
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxPool pool = new EvmTxPool(new EvmTxStore(new InMemoryKVSource()), stateStore,
                BigInteger.valueOf(0xCAFE), 30_000_000L, Wei.ONE, 3600L, () -> 1000L, 400);
        EthRequestHandler h = writeHandler(stateStore, pool, new ArrayList<>());
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction big = EvmTransaction.unsigned(0L, Wei.ONE, 200_000L, Optional.empty(),
                Wei.ZERO, Bytes.wrap(new byte[500]), BigInteger.valueOf(0xCAFE)).sign(key, algo);
        JsonRpcException e = assertThrows(JsonRpcException.class,
                () -> h.handle(request("eth_sendRawTransaction", big.getRawRlp().toHexString())));
        assertTrue("must surface as an invalid-params error, got: " + e.getMessage(),
                e.getMessage().toLowerCase().contains("large"));
    }

    @Test
    public void eth_sendRawTransaction_wrong_chain_id_errors() {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxPool pool = poolFor(stateStore);
        EthRequestHandler h = writeHandler(stateStore, pool, new ArrayList<>());
        EvmTransaction wrong = signedTx(0, BigInteger.ONE); // mainnet chain id, pool expects 0xCAFE
        assertThrows(JsonRpcException.class,
                () -> h.handle(request("eth_sendRawTransaction", wrong.getRawRlp().toHexString())));
    }

    @Test
    public void account_state_methods() throws Exception {
        InMemoryKVSource store = new InMemoryKVSource();
        seedState(store);
        EthRequestHandler h = handlerOver(store, 10L);

        assertEquals("0x4d2", h.handle(request("eth_getBalance", eoaHex(), "latest")));   // 1234
        assertEquals("0x7", h.handle(request("eth_getTransactionCount", eoaHex(), "latest")));
        assertEquals("0x", h.handle(request("eth_getCode", eoaHex(), "latest")));          // EOA
        assertEquals("0x602a60005260206000f3", h.handle(request("eth_getCode", contractHex(), "latest")));
        assertEquals("0x" + "0".repeat(62) + "63",
                h.handle(request("eth_getStorageAt", contractHex(), "0x0", "latest")));    // slot0 = 99
        assertEquals("0x0", h.handle(request("eth_getBalance",
                "0x9999999999999999999999999999999999999999", "latest")));                 // absent
    }

    @Test
    public void block_height_above_evm_head_is_unavailable() {
        // handlerOver has no evmMetaStore, so evmHead == 0: only the anchor height (latest/0x0/earliest)
        // resolves to the live store; any height above it falls outside the window and maps to -32000.
        EthRequestHandler h = handlerOver(new InMemoryKVSource(), 10L);
        assertThrows(JsonRpcException.class,
                () -> h.handle(request("eth_getBalance", eoaHex(), "0x5")));
        // latest == earliest == 0x0 == evmHead(0): the live store, empty account -> 0.
        assertEquals("0x0", h.handle(request("eth_getBalance", eoaHex(), "latest")));
        assertEquals("0x0", h.handle(request("eth_getBalance", eoaHex(), "0x0")));
    }

    @Test
    public void getBalanceAtHistoricalHeightReadsJournal() throws Exception {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmStateJournal journal = new EvmStateJournal(new InMemoryKVSource());
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());

        // Height 1: eoa = 100 wei; journal the prior (absent). Height 2: eoa = 250.
        RocksDbWorldUpdater w1 = new RocksDbWorldUpdater(state);
        w1.createAccount(eoa, 0L, Wei.of(100));
        List<EvmStateJournal.Entry> j1 = new ArrayList<>();
        w1.commitAndDigest(j1);
        journal.putHeightJournal(1, j1);

        RocksDbWorldUpdater w2 = new RocksDbWorldUpdater(state);
        w2.getAccount(eoa).setBalance(Wei.of(250));
        List<EvmStateJournal.Entry> j2 = new ArrayList<>();
        w2.commitAndDigest(j2);
        journal.putHeightJournal(2, j2);

        meta.putHeightRecord(2, Bytes32.ZERO, Bytes32.ZERO, 1, 0); // evmHead = 2
        HistoricalStateReader historical = new HistoricalStateReader(state, journal, 128);
        Blockchain bc = Mockito.mock(Blockchain.class);
        EthRequestHandler h = new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, null, null,
                new EvmTxStore(new InMemoryKVSource()), meta, 1024L, historical);

        // Latest -> 250; height 0x1 -> 100.
        assertEquals("0xfa", h.handle(request("eth_getBalance", eoaHex(), "latest"))); // 250
        assertEquals("0x64", h.handle(request("eth_getBalance", eoaHex(), "0x1")));    // 100
    }

    @Test
    public void supports_only_eth_namespace_methods() {
        assertTrue(handler.supportsMethod("eth_chainId"));
        assertTrue(handler.supportsMethod("net_version"));
        assertTrue(!handler.supportsMethod("xdag_blockNumber"));
    }

    @Test
    public void chain_and_node_methods() throws Exception {
        assertEquals("0xcafe", handler.handle(request("eth_chainId")));
        assertEquals("51966", handler.handle(request("net_version")));
        assertEquals("0x1000", handler.handle(request("eth_blockNumber")));
        assertEquals("0x3b9aca00", handler.handle(request("eth_gasPrice")));
        assertEquals(List.of(), handler.handle(request("eth_accounts")));
        assertEquals(Boolean.TRUE, handler.handle(request("net_listening")));
        assertTrue(((String) handler.handle(request("web3_clientVersion"))).startsWith("xdagj/"));
    }

    @Test
    public void eth_call_runs_against_ephemeral_state_and_returns_data() throws Exception {
        InMemoryKVSource store = new InMemoryKVSource();
        seedState(store);
        EthRequestHandler h = handlerOver(store, 10L);

        java.util.Map<String, Object> call = new java.util.HashMap<>();
        call.put("to", contractHex());
        call.put("data", "0x");
        String result = (String) h.handle(request("eth_call", call, "latest"));
        assertEquals(BigInteger.valueOf(42), EthHex.decodeQuantity(result)); // contract returns 42

        // Calling an EOA (no code) returns empty data.
        java.util.Map<String, Object> toEoa = new java.util.HashMap<>();
        toEoa.put("to", eoaHex());
        assertEquals("0x", h.handle(request("eth_call", toEoa, "latest")));
    }

    @Test
    public void eth_estimateGas_is_at_least_intrinsic() throws Exception {
        InMemoryKVSource store = new InMemoryKVSource();
        seedState(store);
        EthRequestHandler h = handlerOver(store, 10L);

        java.util.Map<String, Object> call = new java.util.HashMap<>();
        call.put("to", contractHex());
        String result = (String) h.handle(request("eth_estimateGas", call));
        assertTrue(EthHex.decodeQuantity(result).longValueExact() >= 21_000L);
    }

    /** Builds a handler with meta+tx stores and a blockchain stub that returns a block per height. */
    private EthRequestHandler queryHandler(InMemoryKVSource stateStore, EvmTxStore txStore,
                                           EvmMetaStore metaStore, long head) {
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(head);
        Block block = Mockito.mock(Block.class);
        Mockito.when(block.getHash()).thenReturn(Bytes32.fromHexString("0x" + "ab".repeat(32)));
        Mockito.when(block.getTimestamp()).thenReturn(0L);
        Mockito.when(bc.getBlockByHeight(Mockito.anyLong())).thenReturn(block);
        return new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc,
                null, null, txStore, metaStore, 1024L,
                new HistoricalStateReader(stateStore, new EvmStateJournal(new InMemoryKVSource()), 128));
    }

    @Test
    public void eth_getTransactionByHash_returns_located_tx() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        txStore.put(tx);
        metaStore.putTxList(5L, List.of(tx.getHash()));
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        Map<?, ?> obj = (Map<?, ?>) h.handle(request("eth_getTransactionByHash",
                tx.getHash().getBytes().toHexString()));
        assertEquals(tx.getHash().getBytes().toHexString(), obj.get("hash"));
        assertEquals("0x5", obj.get("blockNumber"));
        assertEquals("0x0", obj.get("transactionIndex"));
        assertEquals("0x0", obj.get("nonce"));
        assertEquals(tx.getSender().getBytes().toHexString(), obj.get("from"));

        // v must be the EIP-155 canonical value (chainId*2 + 35 + recId), not the bare recId.
        BigInteger expectedV = BigInteger.valueOf(0xCAFE).shiftLeft(1)
                .add(BigInteger.valueOf(35L + tx.getSignature().getRecId()));
        assertEquals(EthHex.quantity(expectedV), obj.get("v"));

        assertNull(h.handle(request("eth_getTransactionByHash", "0x" + "11".repeat(32))));
    }

    @Test
    public void eth_getBlockByNumber_earliest_and_missing_block_do_not_500() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(10L);
        // Height 0 has no block (pruned/genesis); height 1 exists but its parent (0) does not.
        Block block1 = Mockito.mock(Block.class);
        Mockito.when(block1.getHash()).thenReturn(Bytes32.fromHexString("0x" + "cd".repeat(32)));
        Mockito.when(block1.getTimestamp()).thenReturn(0L);
        Mockito.when(bc.getBlockByHeight(0L)).thenReturn(null);
        Mockito.when(bc.getBlockByHeight(1L)).thenReturn(block1);
        EthRequestHandler h = new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc,
                null, null, txStore, metaStore, 1024L,
                new HistoricalStateReader(stateStore, new EvmStateJournal(new InMemoryKVSource()), 128));

        assertNull("earliest with no block-0 must return null, not 500",
                h.handle(request("eth_getBlockByNumber", "earliest", false)));
        Map<?, ?> block1Obj = (Map<?, ?>) h.handle(request("eth_getBlockByNumber", "0x1", false));
        assertEquals("0x1", block1Obj.get("number"));
        assertEquals("null parent falls back to the zero hash", "0x" + "0".repeat(64),
                block1Obj.get("parentHash"));
    }

    @Test
    public void eth_getTransactionReceipt_returns_status_and_logs() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        txStore.put(tx);
        metaStore.putTxList(5L, List.of(tx.getHash()));
        Address created = Address.fromHexString("0x3333333333333333333333333333333333333333");
        metaStore.putReceipt(tx.getHash(), new EvmReceipt(1, 53_000L, Optional.of(created), List.of()));
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        Map<?, ?> r = (Map<?, ?>) h.handle(request("eth_getTransactionReceipt",
                tx.getHash().getBytes().toHexString()));
        assertEquals("0x1", r.get("status"));
        assertEquals("0xcf08", r.get("gasUsed")); // 53000
        assertEquals("0xcf08", r.get("cumulativeGasUsed"));
        assertEquals(created.getBytes().toHexString(), r.get("contractAddress"));
        assertEquals("0x5", r.get("blockNumber"));
        assertTrue(((List<?>) r.get("logs")).isEmpty());

        assertNull(h.handle(request("eth_getTransactionReceipt", "0x" + "11".repeat(32))));
    }

    @Test
    public void eth_getBlockByNumber_hash_array_and_full_tx() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        txStore.put(tx);
        metaStore.putTxList(3L, List.of(tx.getHash()));
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        Map<?, ?> hashesBlock = (Map<?, ?>) h.handle(request("eth_getBlockByNumber", "0x3", false));
        assertEquals("0x3", hashesBlock.get("number"));
        assertEquals(List.of(tx.getHash().getBytes().toHexString()), hashesBlock.get("transactions"));

        Map<?, ?> fullBlock = (Map<?, ?>) h.handle(request("eth_getBlockByNumber", "0x3", true));
        List<?> full = (List<?>) fullBlock.get("transactions");
        assertEquals(1, full.size());
        assertEquals(tx.getHash().getBytes().toHexString(), ((Map<?, ?>) full.get(0)).get("hash"));
    }

    @Test
    public void eth_getBlockByNumber_empty_and_out_of_range() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        Map<?, ?> empty = (Map<?, ?>) h.handle(request("eth_getBlockByNumber", "0x2", false));
        assertTrue(((List<?>) empty.get("transactions")).isEmpty()); // no EVM txs at height 2
        assertNull(h.handle(request("eth_getBlockByNumber", "0x63", false))); // 99 > head 10
    }

    @Test
    public void eth_getLogs_filters_by_address_and_topic_and_caps_range() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction tx = signedTx(0, BigInteger.valueOf(0xCAFE));
        txStore.put(tx);
        metaStore.putTxList(4L, List.of(tx.getHash()));
        Address emitter = Address.fromHexString("0x4444444444444444444444444444444444444444");
        Bytes32 topic = Bytes32.fromHexString("0x" + "aa".repeat(32));
        Log log = new Log(emitter, Bytes.fromHexString("0xbeef"), List.of(LogTopic.wrap(topic)));
        metaStore.putReceipt(tx.getHash(), new EvmReceipt(1, 21_000L, Optional.empty(), List.of(log)));
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        java.util.Map<String, Object> filter = new java.util.HashMap<>();
        filter.put("fromBlock", "0x4");
        filter.put("toBlock", "0x4");
        filter.put("address", emitter.toHexString());
        List<?> hits = (List<?>) h.handle(request("eth_getLogs", filter));
        assertEquals(1, hits.size());
        assertEquals(emitter.getBytes().toHexString(), ((Map<?, ?>) hits.get(0)).get("address"));

        // address that emitted nothing -> no hits
        java.util.Map<String, Object> other = new java.util.HashMap<>(filter);
        other.put("address", "0x5555555555555555555555555555555555555555");
        assertTrue(((List<?>) h.handle(request("eth_getLogs", other))).isEmpty());

        // range over the cap -> error (maxLogScanRange default 1024)
        java.util.Map<String, Object> wide = new java.util.HashMap<>();
        wide.put("fromBlock", "0x0");
        wide.put("toBlock", "0x2000"); // 8192 > 1024
        assertThrows(JsonRpcException.class, () -> h.handle(request("eth_getLogs", wide)));
    }

    // C5 eth_getLogs: full positional topic matching + per-height bloom skip. SIG stands in for an event
    // signature (topic[0]); ALICE/BOB are indexed-address args left-padded to 32-byte topics (topic[1]).
    private static final Address EMITTER =
            Address.fromHexString("0x4444444444444444444444444444444444444444");
    private static final Bytes32 SIG = Bytes32.fromHexString("0x" + "11".repeat(32));
    private static final Bytes32 ALICE = Bytes32.fromHexString("0x" + "22".repeat(32));
    private static final Bytes32 BOB = Bytes32.fromHexString("0x" + "33".repeat(32));
    private static final Bytes32 OTHER_SIG = Bytes32.fromHexString("0x" + "99".repeat(32));

    /** Seeds one height with a single-tx receipt carrying one log (emitter + given topics) AND its bloom. */
    private void seedLogHeight(EvmMetaStore metaStore, long height, EvmTransaction txForHash,
                               Address emitter, Bytes32... topics) {
        Log log = new Log(emitter, Bytes.EMPTY, Arrays.stream(topics).map(LogTopic::wrap).toList());
        Hash txHash = txForHash.getHash();
        metaStore.putTxList(height, List.of(txHash));
        metaStore.putReceipt(txHash, new EvmReceipt(1, 21_000L, Optional.empty(), List.of(log)));
        metaStore.putHeightBloom(height, LogsBloomFilter.builder().insertLog(log).build().getBytes());
    }

    @Test
    public void eth_getLogs_filters_on_an_indexed_topic1() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction txA = signedTx(0, BigInteger.valueOf(0xCAFE));
        EvmTransaction txB = signedTx(1, BigInteger.valueOf(0xCAFE));
        seedLogHeight(metaStore, 1L, txA, EMITTER, SIG, ALICE);
        seedLogHeight(metaStore, 2L, txB, EMITTER, SIG, BOB);
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        // topics = [SIG, ALICE] must match ONLY height 1 (topic[0]-only would have matched both).
        Object result = h.handle(request("eth_getLogs", java.util.Map.of(
                "fromBlock", "0x1", "toBlock", "0x2",
                "topics", List.of(SIG.toHexString(), ALICE.toHexString()))));
        assertEquals(1, ((List<?>) result).size());
    }

    @Test
    public void eth_getLogs_skips_a_non_matching_bloom_height() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction txA = signedTx(0, BigInteger.valueOf(0xCAFE));
        EvmTransaction txB = signedTx(1, BigInteger.valueOf(0xCAFE));
        seedLogHeight(metaStore, 1L, txA, EMITTER, SIG, ALICE);
        seedLogHeight(metaStore, 2L, txB,
                Address.fromHexString("0x000000000000000000000000000000000000beef"), OTHER_SIG);
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        // Filter for SIG over [1,2]: height 2's bloom (only beef+OTHER_SIG) cannot match -> skipped.
        Object result = h.handle(request("eth_getLogs", java.util.Map.of(
                "fromBlock", "0x1", "toBlock", "0x2", "topics", List.of(SIG.toHexString()))));
        assertEquals(1, ((List<?>) result).size());
    }

    @Test
    public void eth_getLogs_falls_back_when_a_height_has_no_bloom() throws Exception {
        InMemoryKVSource stateStore = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmTransaction txA = signedTx(0, BigInteger.valueOf(0xCAFE));
        // Seed a matching receipt but OMIT its bloom so getHeightBloom is empty.
        Hash txHash = txA.getHash();
        Log log = new Log(EMITTER, Bytes.EMPTY, List.of(LogTopic.wrap(SIG)));
        metaStore.putTxList(1L, List.of(txHash));
        metaStore.putReceipt(txHash, new EvmReceipt(1, 21_000L, Optional.empty(), List.of(log)));
        // NO putHeightBloom -> getHeightBloom empty -> fallback scan must still find the log.
        EthRequestHandler h = queryHandler(stateStore, txStore, metaStore, 10L);

        Object result = h.handle(request("eth_getLogs", java.util.Map.of(
                "fromBlock", "0x1", "toBlock", "0x1", "topics", List.of(SIG.toHexString()))));
        assertEquals(1, ((List<?>) result).size());
    }

    /** Initcode returning runtime `60006000a000` (PUSH1 0 PUSH1 0 LOG0 STOP): every CALL emits one
     *  topic-less log carrying the contract's address. See EvmBlockProcessorTest for the byte trace. */
    private static final Bytes LOG0_INIT = Bytes.fromHexString("0x6006600c60003960066000f360006000a000");
    private static final Address KEY1_SENDER =
            Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    /** Signs a private-key-1 tx (nonce, to, payload), stores it, executes it at {@code height}, and
     *  asserts it succeeded — so a silent revert localizes here, not as a confusing later log-count. */
    private EvmTransaction execAt(EvmTxStore txStore, EvmMetaStore metaStore, EvmBlockProcessor proc,
                                  long nonce, Optional<Address> to, Bytes payload, long height) {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 200_000L, to, Wei.ZERO, payload,
                BigInteger.valueOf(0xCAFE)).sign(key, algo);
        txStore.put(tx);
        proc.processMainBlock(List.of(Bytes32.wrap(tx.getHash().getBytes())), height, 1000L + height,
                Bytes32.leftPad(Bytes.ofUnsignedLong(height)));
        assertEquals("execAt: execution at height " + height + " must succeed", 1,
                metaStore.getReceipt(tx.getHash()).orElseThrow().status());
        return tx;
    }

    @Test
    public void eth_getLogs_reflects_real_execution_and_survives_a_reorg() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txStore, metaStore, 0L,
                List.of(new GenesisAllocEntry(KEY1_SENDER, Wei.fromEth(1))));
        proc.seedGenesisIfAbsent();

        // Deploy the LOG0 contract (h1, no log) then CALL it (h2, emits a log => real bloom at h2).
        EvmTransaction deploy = execAt(txStore, metaStore, proc, 0, Optional.empty(), LOG0_INIT, 1L);
        Address contract = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();
        execAt(txStore, metaStore, proc, 1, Optional.of(contract), Bytes.EMPTY, 2L);

        EthRequestHandler h = queryHandler(state, txStore, metaStore, 2L);
        Map<String, Object> byContract = Map.of("fromBlock", "0x1", "toBlock", "0x2",
                "address", contract.toHexString());

        // The emitted log is found (its height-2 bloom does not skip it).
        assertEquals(1, ((List<?>) h.handle(request("eth_getLogs", byContract))).size());

        // Reorg to height 1: height-2 receipt + bloom removed -> the query now finds nothing.
        proc.rollbackTo(1);
        assertEquals(0, ((List<?>) h.handle(request("eth_getLogs", byContract))).size());

        // Re-execute a new height-2 call (nonce back to 1 after replay) -> bloom regenerated -> found again.
        execAt(txStore, metaStore, proc, 1, Optional.of(contract), Bytes.EMPTY, 2L);
        assertEquals(1, ((List<?>) h.handle(request("eth_getLogs", byContract))).size());
    }

    // ---- Defect-1 Task 5: type-2 RPC surface (ethers v6.13 ground-truth vectors, chainId 0xCAFE) ----

    /** Type-2 with access list: priority 1 gwei, cap 2 gwei, 1 entry {0x3535..35, 2 storage keys}. */
    private static final Bytes VECTOR_B = Bytes.fromHexString(
            "0x02f8cc82cafe01843b9aca00847735940082ea60943535353535353535353535353535353535"
                    + "3535358084deadbeeff85bf859943535353535353535353535353535353535353535f842a00000"
                    + "000000000000000000000000000000000000000000000000000000000001a000000000000000"
                    + "0000000000000000000000000000000000000000000000000201a0ca5b5c2fad6148b287bbf1"
                    + "00bfeaa05c0ee0aa0d4034cdd8d935392b433fe32aa057a7275ae418fdbf429749d14e72a813"
                    + "4e5ccb4289da3fe62445731b7edd681f");

    /** Plain type-2 transfer (empty access list) signed by private key 1. */
    private static final Bytes VECTOR_A = Bytes.fromHexString(
            "0x02f86c82cafe800102825208943535353535353535353535353535353535353535880de0b6b3a764"
                    + "000080c080a0104918ce86f9f8d8cf9389231fd938103ff60ff2d4a6492346eaeed3b7988342"
                    + "a00b06ab51bfa66f8c44836f79ae76dfbfcf491269bea8e99ccb7228f86451f6a2");

    /** EIP-155 legacy vector (spec example): guards the type-0 JSON key-order contract. */
    private static final Bytes EIP155_RAW = Bytes.fromHexString(
            "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a764"
                    + "00008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a0"
                    + "67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83");

    @Test
    public void type2_transaction_json_has_1559_fields_and_type0_key_order_is_unchanged() {
        EvmTransaction t2 = EvmTransaction.decode(VECTOR_B);
        Map<String, Object> m = EthObjects.transaction(t2, 5L, "0xabc", 0);
        assertEquals("0x2", m.get("type"));
        assertEquals("0x77359400", m.get("maxFeePerGas"));
        assertEquals("0x3b9aca00", m.get("maxPriorityFeePerGas"));
        assertEquals("0x3b9aca00", m.get("gasPrice"));            // effective = min = 1 gwei
        assertEquals(m.get("yParity"), m.get("v"));               // same value, geth-compatible
        assertEquals("0x1", m.get("yParity"));                    // VECTOR_B's signature has yParity 1
        List<?> al = (List<?>) m.get("accessList");
        assertEquals(1, al.size());
        Map<?, ?> entry = (Map<?, ?>) al.get(0);
        assertEquals("0x3535353535353535353535353535353535353535", entry.get("address"));
        assertEquals(2, ((List<?>) entry.get("storageKeys")).size());
        // Type-0 key ORDER is part of the byte-identical contract:
        EvmTransaction legacy = EvmTransaction.decode(EIP155_RAW);
        assertEquals(List.of("hash", "nonce", "blockHash", "blockNumber", "transactionIndex",
                        "from", "to", "value", "gasPrice", "gas", "input", "chainId", "v", "r", "s", "type"),
                List.copyOf(EthObjects.transaction(legacy, 5L, "0xabc", 0).keySet()));
    }

    @Test
    public void type2_receipt_reports_type_and_effective_gas_price() {
        EvmReceipt receipt = new EvmReceipt(1, 21_000L, Optional.empty(), List.of());
        Map<String, Object> m = EthObjects.receipt(EvmTransaction.decode(VECTOR_B), receipt, 5L,
                "0xabc", 0, List.of());
        assertEquals("0x2", m.get("type"));
        assertEquals("0x3b9aca00", m.get("effectiveGasPrice"));
        // A legacy receipt still reports type 0x0 and its gasPrice as effectiveGasPrice.
        EvmTransaction legacy = EvmTransaction.decode(EIP155_RAW);
        Map<String, Object> lm = EthObjects.receipt(legacy, receipt, 5L, "0xabc", 0, List.of());
        assertEquals("0x0", lm.get("type"));
        assertEquals(EthHex.quantity(legacy.getGasPrice().getAsBigInteger()), lm.get("effectiveGasPrice"));
    }

    @Test
    public void max_priority_fee_returns_min_gas_price_and_fee_history_shape_is_honest() throws Exception {
        // Harness handler: minGasPrice = 1 gwei, latest main block = 4096.
        assertEquals("0x3b9aca00", handler.handle(request("eth_maxPriorityFeePerGas")));

        long newest = 4096L;
        long oldest = Math.max(0L, newest - 4L + 1L);
        long count = newest - oldest + 1L;
        Map<?, ?> fh = (Map<?, ?>) handler.handle(
                request("eth_feeHistory", "0x4", "latest", List.of(25.0d, 75.0d)));
        assertEquals(EthHex.quantity(oldest), fh.get("oldestBlock"));
        List<?> baseFees = (List<?>) fh.get("baseFeePerGas");
        assertEquals(count + 1, baseFees.size());
        for (Object fee : baseFees) {
            assertEquals("0x0", fee);
        }
        List<?> ratios = (List<?>) fh.get("gasUsedRatio");
        assertEquals(count, ratios.size());
        for (Object ratio : ratios) {
            assertEquals(0.0d, (Double) ratio, 0.0d);
        }
        List<?> reward = (List<?>) fh.get("reward");
        assertEquals(count, reward.size());
        for (Object rowObj : reward) {
            List<?> row = (List<?>) rowObj;
            assertEquals(2, row.size());
            assertEquals("0x3b9aca00", row.get(0));
            assertEquals("0x3b9aca00", row.get(1));
        }

        // Two params (no percentiles) -> NO reward key.
        Map<?, ?> noReward = (Map<?, ?>) handler.handle(request("eth_feeHistory", "0x4", "latest"));
        assertFalse(noReward.containsKey("reward"));

        // 5a: blockCount as a JSON Number (Integer) hits the instanceof Number branch.
        Map<?, ?> numCount = (Map<?, ?>) handler.handle(
                request("eth_feeHistory", Integer.valueOf(4), "latest", List.of(25.0d, 75.0d)));
        assertEquals(fh.get("oldestBlock"), numCount.get("oldestBlock"));
        assertEquals(((List<?>) fh.get("baseFeePerGas")).size(), ((List<?>) numCount.get("baseFeePerGas")).size());

        // 5b: Integer (not Double) percentiles — Jackson deserialises JSON numbers as Integer; must still
        // produce 3-entry reward rows.
        Map<?, ?> intPct = (Map<?, ?>) handler.handle(
                request("eth_feeHistory", "0x4", "latest", List.of(10, 20, 30)));
        List<?> intReward = (List<?>) intPct.get("reward");
        assertEquals(count, intReward.size());
        assertEquals(3, ((List<?>) intReward.get(0)).size());
    }

    @Test
    public void send_raw_rejects_type2_before_activation() {
        // Mirror writeHandler, changing ONLY the EvmConfig (type-2 activates at Long.MAX_VALUE).
        InMemoryKVSource gatedStore = new InMemoryKVSource();
        fundSender(gatedStore);
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(1L);
        EthRequestHandler gated = new EthRequestHandler(
                new EvmConfig(EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L, BigInteger.ONE, Long.MAX_VALUE),
                BigInteger.ONE, bc, poolFor(gatedStore), new ArrayList<Bytes>()::add, null, null,
                1024L, new HistoricalStateReader(gatedStore, new EvmStateJournal(new InMemoryKVSource()), 128));
        JsonRpcException gate = assertThrows(JsonRpcException.class,
                () -> gated.handle(request("eth_sendRawTransaction", VECTOR_A.toHexString())));
        assertTrue("gate must mention activation, was: " + gate.getMessage(),
                gate.getMessage() != null && gate.getMessage().contains("activated"));

        // 5c: A LEGACY (type-0) tx through the same gated handler must NOT hit the activation gate.
        // It may fail for pool reasons (wrong chain id, balance, nonce) but not "activated".
        try {
            gated.handle(request("eth_sendRawTransaction", EIP155_RAW.toHexString()));
        } catch (JsonRpcException legacyEx) {
            assertFalse("legacy tx must not be rejected with activation message, was: " + legacyEx.getMessage(),
                    legacyEx.getMessage() != null && legacyEx.getMessage().contains("activated"));
        }

        // Default (active) handler: the same tx passes the gate. Funded with 2 ETH (1 ETH value + fees),
        // it is accepted and returns its Ethereum hash.
        InMemoryKVSource activeStore = new InMemoryKVSource();
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(activeStore);
        w.createAccount(KEY1_SENDER, 0L, Wei.fromEth(2));
        w.commit();
        EthRequestHandler active = writeHandler(activeStore, poolFor(activeStore), new ArrayList<>());
        String result = (String) active.handle(request("eth_sendRawTransaction", VECTOR_A.toHexString()));
        assertEquals("0xb3fc4070c80b884bd43a25f925c2fd779f25bab8b31b4715a0fe29394b83a242", result);
    }
}
