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
package io.xdag.rpc.ws;

import static org.junit.Assert.assertTrue;

import io.xdag.config.spec.RPCSpec;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

public class RpcWebSocketServerBoundPortTest {

    @Test
    public void binds_an_ephemeral_port_and_reports_it() {
        RPCSpec rpc = Mockito.mock(RPCSpec.class);
        Mockito.when(rpc.getRpcWsHost()).thenReturn("127.0.0.1");
        Mockito.when(rpc.getRpcWsPort()).thenReturn(0);
        Mockito.when(rpc.getRpcHttpBossThreads()).thenReturn(1);
        Mockito.when(rpc.getRpcHttpWorkerThreads()).thenReturn(1);
        Mockito.when(rpc.getRpcHttpMaxContentLength()).thenReturn(1_048_576);
        Mockito.when(rpc.getRpcHttpCorsOrigins()).thenReturn("*");
        Mockito.when(rpc.getRpcHttpApiToken()).thenReturn("");

        RpcWebSocketServer server = new RpcWebSocketServer(rpc, new SubscriptionManager(), List.of());
        try {
            server.start();
            assertTrue("bound port must be a real assigned port", server.boundPort() > 0);
        } finally {
            server.stop();
        }
    }
}
