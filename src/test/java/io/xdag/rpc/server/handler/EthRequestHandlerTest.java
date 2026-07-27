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
import static org.junit.Assert.assertTrue;

import io.xdag.core.Blockchain;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import java.util.List;
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
                BigInteger.valueOf(1_000_000_000L), blockchain);
    }

    private JsonRpcRequest request(String method, Object... params) {
        JsonRpcRequest r = new JsonRpcRequest();
        r.setMethod(method);
        r.setParams(params);
        r.setId(1);
        return r;
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
}
