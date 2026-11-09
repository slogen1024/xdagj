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
package io.xdag.chain.exec.wasm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ExtResult;
import io.xdag.chain.rpc.ChainCli;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;

/** {@code chain deploy} of an SDK module reassembles to the same bytes, and those bytes still run. */
public class SdkDeployTest {

    private static final long TS = 0x16a00000000L;
    private static final Bytes CHAIN = address(1);

    @Test
    public void deployReassemblesSdkWasmAndTheExecutorRunsIt() throws IOException {
        byte[] token = deploy("token.wasm");
        byte[] pool = deploy("pool.wasm");
        byte[] inbox = deploy("inbox.wasm");
        ReferenceDappsTest.run(token, pool, inbox);
    }

    private static byte[] deploy(String name) throws IOException {
        byte[] wasm = ReferenceDappsTest.module(name);
        List<Block> sent = new ArrayList<>();
        ChainCli.Signer signer = new ChainCli.Signer(new DevnetConfig(),
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), 1L, TS, sent::addAll);
        String hash = ChainCli.run(null, signer, "deploy", CHAIN.toHexString(), Bytes.wrap(wasm).toHexString(),
                Long.toString(ReferenceDappsTest.GAS));
        assertTrue(hash, hash.startsWith("0x"));
        assertEquals(ExtKind.DEPLOY, ChainBlockClassifier.classify(sent.get(0)).kind());
        DeployExt ext = ChainBlockClassifier.classify(sent.get(0)).as(DeployExt.class);
        Map<Bytes32, Block> idx = new HashMap<>();
        for (int i = 1; i < sent.size(); i++) {
            idx.put(Bytes32.wrap(sent.get(i).getHashLow().toArray()), sent.get(i));
        }
        ExtResult<Bytes> back = ChunkChain.assemble(ext.codeChainHead(), idx::get, 64);
        assertTrue(String.valueOf(back.error()), back.isOk());
        assertArrayEquals(wasm, back.value().toArray());
        return back.value().toArray();
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
