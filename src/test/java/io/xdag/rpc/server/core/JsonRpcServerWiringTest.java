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
package io.xdag.rpc.server.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Blockchain;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.state.EvmStateJournal;
import io.xdag.evm.state.HistoricalStateReader;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.rpc.server.handler.EthRequestHandler;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import java.math.BigInteger;
import org.junit.Test;
import org.mockito.Mockito;

/** The eth handler answers eth_* while leaving xdag_* to the existing handler. */
public class JsonRpcServerWiringTest {

    @Test
    public void eth_handler_answers_eth_namespace() throws Exception {
        Blockchain bc = Mockito.mock(Blockchain.class);
        Mockito.when(bc.getLatestMainBlockNumber()).thenReturn(1L);
        InMemoryKVSource state = new InMemoryKVSource();
        EthRequestHandler eth = new EthRequestHandler(
                new EvmConfig(org.hyperledger.besu.evm.EvmSpecVersion.SHANGHAI,
                        BigInteger.valueOf(0xCAFE), 30_000_000L), BigInteger.ONE, bc, null, null, null, null, 1024L,
                new HistoricalStateReader(state, new EvmStateJournal(new InMemoryKVSource()), 128));

        assertTrue(eth.supportsMethod("eth_chainId"));
        JsonRpcRequest r = new JsonRpcRequest();
        r.setMethod("eth_chainId");
        r.setParams(new Object[]{});
        r.setId(1);
        assertEquals("0xcafe", eth.handle(r));
    }
}
