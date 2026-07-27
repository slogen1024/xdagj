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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.evm.tx.EvmTxStore;
import io.xdag.rpc.eth.EthHex;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.ArrayList;
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
import org.hyperledger.besu.datatypes.Wei;
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
        handler = new EthRequestHandler(new InMemoryKVSource(),
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L),
                BigInteger.valueOf(1_000_000_000L), blockchain, null, null, null, null, 1024L);
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
        return new EthRequestHandler(store,
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, null, null, null, null, 1024L);
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
        return new EthRequestHandler(stateStore,
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, pool, broadcaster, null, null, 1024L);
    }

    private void fundSender(InMemoryKVSource stateStore) {
        RocksDbWorldUpdater w = new RocksDbWorldUpdater(stateStore);
        w.createAccount(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"),
                0L, Wei.fromEth(1));
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
    public void historical_block_height_is_rejected() {
        EthRequestHandler h = handlerOver(new InMemoryKVSource(), 10L);
        // A past height (5 != head 10) has no archival state.
        assertThrows(JsonRpcException.class,
                () -> h.handle(request("eth_getBalance", eoaHex(), "0x5")));
        // The head height as an explicit hex is fine.
        assertEquals("0x0", h.handle(request("eth_getBalance", eoaHex(), "0xa")));
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
        return new EthRequestHandler(stateStore,
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc,
                null, null, txStore, metaStore, 1024L);
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
        EthRequestHandler h = new EthRequestHandler(stateStore,
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc,
                null, null, txStore, metaStore, 1024L);

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
}
