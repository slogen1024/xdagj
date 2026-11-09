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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.abi.AbiCodec;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ExtResult;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.rpc.ChainCli;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;

/**
 * Modules from {@code tools/xdag-wasm}, compiled with rustc for wasm32. {@code mint} credits 50,
 * {@code transfer} moves 20 and ignores a short balance, {@code seed}/{@code swap} leave 125/80,
 * {@code ping} appends one outbox row. The token bytes also survive {@code chain deploy}.
 */
public class RustWasmTest {

    private static final long TS = 0x16a00000000L;
    private static final long GAS = 500_000L;
    private static final Bytes CHAIN = address(1);
    private static final Bytes SENDER = address(2);
    private static final Bytes TOKEN = address(3);
    private static final Bytes BOB = address(4);
    private static final Bytes POOL = address(5);
    private static final Bytes INBOX = address(6);

    @Test
    public void rustcModulesRunAfterDeployReassembly() throws IOException {
        byte[] token = wasm("token.wasm");
        byte[] pool = wasm("pool.wasm");
        byte[] inbox = wasm("inbox.wasm");
        byte[] deployed = deploy(token);
        try {
            ModuleValidator.validate(deployed);
            ModuleValidator.validate(pool);
            ModuleValidator.validate(inbox);
        } catch (InvalidModuleException e) {
            throw new AssertionError(e.getMessage(), e);
        }
        List<ChainExecutor.Tx> txs = new ArrayList<>();
        txs.add(tx(1, TOKEN, AbiCodec.selector("mint(address,u64)"), addressAmount(SENDER, 50), deployed));
        txs.add(tx(2, TOKEN, AbiCodec.selector("transfer(address,u64)"), addressAmount(BOB, 20), deployed));
        txs.add(tx(3, TOKEN, AbiCodec.selector("transfer(address,u64)"), addressAmount(BOB, 100), deployed));
        txs.add(tx(4, POOL, AbiCodec.selector("seed(u64,u64)"), pair(100, 100), pool));
        txs.add(tx(5, POOL, AbiCodec.selector("swap(u64,u64)"), pair(25, 1), pool));
        txs.add(tx(6, INBOX, AbiCodec.selector("ping(bytes)"), new byte[] {9, 8}, inbox));
        ChainExecutor exec = executor();
        List<ChainExecutor.Outcome> out = exec.apply(CHAIN, 0L, 1L, GAS, 0L, txs);
        for (int i = 0; i < out.size(); i++) {
            assertEquals("tx " + i + " gas " + out.get(i).gasUsed(), ExecStatus.OK, out.get(i).status());
        }
        assertEquals(30L, le(exec.storage(CHAIN, TOKEN, balanceKey(SENDER))));
        assertEquals(20L, le(exec.storage(CHAIN, TOKEN, balanceKey(BOB))));
        assertEquals(125L, le(exec.storage(CHAIN, POOL, new byte[] {'r', '0'})));
        assertEquals(80L, le(exec.storage(CHAIN, POOL, new byte[] {'r', '1'})));
        assertFalse(out.get(4).outboxRoot().equals(out.get(5).outboxRoot()));
    }

    private static byte[] deploy(byte[] wasm) {
        List<Block> sent = new ArrayList<>();
        ChainCli.Signer signer = new ChainCli.Signer(new DevnetConfig(),
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), 1L, TS, sent::addAll);
        String hash = ChainCli.run(null, signer, "deploy", CHAIN.toHexString(), Bytes.wrap(wasm).toHexString(),
                Long.toString(GAS));
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

    private static byte[] wasm(String name) throws IOException {
        try (InputStream in = RustWasmTest.class.getResourceAsStream("/chain-sdk/rust/" + name)) {
            if (in == null) {
                throw new IOException(name);
            }
            return in.readAllBytes();
        }
    }

    private static ChainExecutor.Tx tx(int n, Bytes contract, int selector, byte[] args, byte[] code) {
        byte[] hash = new byte[32];
        hash[31] = (byte) n;
        return new ChainExecutor.Tx(Bytes32.wrap(hash), ChainInput.Kind.CALL, null, SENDER, contract, GAS, GAS,
                selector, args, code);
    }

    private static byte[] addressAmount(Bytes to, long amount) {
        byte[] raw = new byte[28];
        System.arraycopy(to.toArray(), 0, raw, 0, 20);
        ByteBuffer.wrap(raw, 20, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount);
        return raw;
    }

    private static byte[] pair(long a, long b) {
        byte[] raw = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(a);
        buf.putLong(b);
        return raw;
    }

    private static byte[] balanceKey(Bytes who) {
        byte[] key = new byte[21];
        key[0] = 1;
        System.arraycopy(who.toArray(), 0, key, 1, 20);
        return key;
    }

    private static long le(byte[] raw) {
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static ChainExecutor executor() {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        return new ChainExecutor(ChicoryEngine.compiler(), l1, store, null, 16);
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
