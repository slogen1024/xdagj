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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.l1.ChainL1Store;

/**
 * The three modules {@code xdag_contract::wasm} writes under {@code chain-sdk/}. Selector 0 on the
 * token mints 50 to the caller. {@code transfer(address,u64)} moves a little-endian amount and
 * leaves storage alone when the balance is short. Selector 0 on the pool seeds 100/100;
 * {@code swap} takes 25 of asset 0. The inbox {@code send}s one row.
 */
public class ReferenceDappsTest {

    private static final int TRANSFER = 1433710159;
    private static final int SWAP = -488097224;
    private static final int PING = -150857028;
    static final long GAS = 500_000L;
    private static final Bytes CHAIN = address(1);
    private static final Bytes SENDER = address(2);
    private static final Bytes TOKEN = address(3);
    private static final Bytes BOB = address(4);
    private static final Bytes POOL = address(5);
    private static final Bytes INBOX = address(6);

    @Test
    public void executorRunsSdkWasm() throws IOException {
        run(module("token.wasm"), module("pool.wasm"), module("inbox.wasm"));
    }

    static void run(byte[] token, byte[] pool, byte[] inbox) {
        List<ChainExecutor.Tx> txs = new ArrayList<>();
        txs.add(tx(1, TOKEN, 0, new byte[0], token));
        txs.add(tx(2, TOKEN, TRANSFER, transferArgs(BOB, 20), token));
        txs.add(tx(3, TOKEN, TRANSFER, transferArgs(BOB, 100), token));
        txs.add(tx(4, POOL, 0, new byte[0], pool));
        txs.add(tx(5, POOL, SWAP, new byte[0], pool));
        txs.add(tx(6, INBOX, PING, new byte[0], inbox));
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

    static byte[] module(String name) throws IOException {
        try (InputStream in = ReferenceDappsTest.class.getResourceAsStream("/chain-sdk/" + name)) {
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

    private static byte[] transferArgs(Bytes to, long amount) {
        byte[] raw = new byte[28];
        System.arraycopy(to.toArray(), 0, raw, 0, 20);
        ByteBuffer.wrap(raw, 20, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount);
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
