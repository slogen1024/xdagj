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
package io.xdag.chain.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.AnchorHead;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainLedger;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.wasm.ChicoryEngine;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.core.Block;
import io.xdag.rpc.error.JsonRpcError;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.handler.ChainRequestHandler;
import io.xdag.rpc.server.protocol.JsonRpcRequest;

public class ChainApiTest {

    @Test
    public void beforeActivationEveryMethodIsRefused() {
        World w = new World(10L);
        assertInactive(() -> w.api.getBalance(w.chain, w.who, ChainView.CONFIRMED));
        assertInactive(() -> w.api.getHead(w.chain));
        assertInactive(() -> w.api.sendCall(List.of("00")));
        assertInactive(() -> w.api.subscribe("receipts"));
        JsonRpcRequest request = new JsonRpcRequest();
        request.setMethod("chain_getBalance");
        request.setParams(new Object[] {"0x" + w.chain.toUnprefixedHexString(), "0x" + Bytes.wrap(w.who).toUnprefixedHexString(),
                "confirmed"});
        try {
            w.handler.handle(request);
            throw new AssertionError("handler");
        } catch (JsonRpcException e) {
            assertEquals(JsonRpcError.ERR_SERVER, e.getCode());
            assertEquals("inactive", e.getMessage());
        }
    }

    @Test
    public void confirmedHeadReadsBalanceStateAndAnAnchorAtThatSeq() {
        World w = new World(0L);
        w.executor.apply(w.chain, 0L, 0L, 1_000_000L, 0L, List.of());
        Bytes32 root = w.executor.stateRoot(w.chain);
        AnchorBody body = new AnchorBody(AnchorRecord.Status.FINAL, Bytes32.repeat((byte) 4), Bytes32.ZERO, root, -1L,
                0L, 0L, 1L, Bytes32.ZERO, new byte[20], Bytes32.ZERO);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putAnchor(w.chain, 0L, body.encode());
        batch.putAnchorHead(w.chain, new AnchorHead(0L, Bytes32.repeat((byte) 4), 0L, 0L).encode());
        w.l1.commit(batch);

        ChainApi.Balance balance = w.api.getBalance(w.chain, w.who, ChainView.CONFIRMED);
        assertEquals("0", balance.balance());
        assertEquals(0L, balance.seq());
        assertEquals("pending", w.api.getBalance(w.chain, w.who, ChainView.PENDING).view());
        assertEquals("final", w.api.getBalance(w.chain, w.who, ChainView.FINAL).view());
        assertNull(w.api.getState(w.chain, w.contract, new byte[] {7}, ChainView.ANCHORED).value());
        assertNull(w.api.query(w.chain, w.contract, 1, new byte[0], ChainView.CONFIRMED).data());
        assertEquals(0L, w.api.estimateGas(w.chain, w.contract, 1, new byte[0]));

        ChainApi.Anchor anchor = w.api.getAnchor(w.chain, 0L);
        assertEquals("final", anchor.status());
        assertEquals(root.toHexString(), anchor.stateRoot());
        ChainApi.Head head = w.api.getHead(w.chain);
        assertEquals(0L, head.anchorSeq());
        assertEquals(0L, head.appliedSeq());

        ChainApi.Proof proof = w.api.getProof(w.chain, w.contract, new byte[] {7}, ChainView.CONFIRMED);
        assertFalse(proof.present());
        assertEquals(256, proof.siblings().size());
        Bytes32[] siblings = new Bytes32[proof.siblings().size()];
        for (int i = 0; i < siblings.length; i++) {
            siblings[i] = Bytes32.fromHexString(proof.siblings().get(i));
        }
        assertTrue(SparseMerkleTree.proof(false, new byte[0], siblings)
                .verifies(ChainLedger.smtKey(w.contract, new byte[] {7}), root));

        w.executor.apply(w.chain, 1L, 0L, 1_000_000L, 0L, List.of());
        try {
            w.api.getBalance(w.chain, w.who, ChainView.FINAL);
            throw new AssertionError("view");
        } catch (IllegalStateException e) {
            assertEquals("view", e.getMessage());
        }
        assertEquals(1L, w.api.getBalance(w.chain, w.who, ChainView.CONFIRMED).seq());
    }

    @Test
    public void receiptOpeningAndSendCall() {
        World w = new World(0L);
        w.executor.apply(w.chain, 0L, 0L, 1_000_000L, 0L, List.of());
        Bytes32 hash = Bytes32.repeat((byte) 9);
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 + 32 + 4 + 2);
        buf.put((byte) 0);
        buf.putLong(12L);
        buf.put(Bytes32.repeat((byte) 3).toArray());
        buf.putInt(2);
        buf.put(new byte[] {4, 5});
        w.exec.putReceipt(ChainExecutor.chain32(w.chain), hash, buf.array());
        w.exec.putOpening(ChainExecutor.chain32(w.chain), 0L, 3L, new byte[] {9, 8});

        ChainApi.Receipt receipt = w.api.getReceipt(hash);
        assertEquals("ok", receipt.status());
        assertEquals(12L, receipt.gasUsed());
        assertEquals("0x0405", receipt.returnData());
        assertEquals("confirmed", receipt.confirm());
        assertEquals("0x0908", w.api.buildClaim(w.chain, 0L, 3L));
        assertNull(w.api.getReceipt(Bytes32.repeat((byte) 1)));

        List<Block> sent = new ArrayList<>();
        World live = new World(0L, sent::add);
        live.executor.apply(live.chain, 0L, 0L, 1_000_000L, 0L, List.of());
        List<String> hashes = live.api.sendCall(List.of(Bytes.wrap(new byte[512]).toHexString()));
        assertEquals(1, sent.size());
        assertEquals(sent.get(0).getHash().toHexString(), hashes.get(0));
        try {
            live.api.subscribe("anchors");
            throw new AssertionError("socket");
        } catch (IllegalStateException e) {
            assertEquals("websocket", e.getMessage());
        }
    }

    private static void assertInactive(Runnable call) {
        try {
            call.run();
            throw new AssertionError("active");
        } catch (ChainInactive e) {
            assertEquals("inactive", e.getMessage());
        }
    }

    private static final class World {
        final Bytes chain = address(1);
        final Bytes contract = address(2);
        final byte[] who = address(3).toArray();
        final ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        final ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        final ChainExecutor executor;
        final ChainApi api;
        final ChainRequestHandler handler;

        World(long activation) {
            this(activation, block -> {
            });
        }

        World(long activation, java.util.function.Consumer<Block> send) {
            l1.start();
            exec.start();
            executor = new ChainExecutor(ChicoryEngine.interpreter(), l1, exec, null, 16);
            api = new ChainApi(() -> 0L, activation, l1, () -> executor, send);
            handler = new ChainRequestHandler(api);
        }

        static Bytes address(int n) {
            byte[] raw = new byte[20];
            raw[19] = (byte) n;
            return Bytes.wrap(raw);
        }
    }
}
