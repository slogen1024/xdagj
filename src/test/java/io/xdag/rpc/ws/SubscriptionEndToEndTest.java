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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.EvmConfig;
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Test;

/**
 * C6 capstone: drives a REAL {@link EvmBlockProcessor} with a {@link SubscriptionManager} sink wired to
 * an {@link EmbeddedChannel} subscriber, and asserts the WebSocket log frames follow Ethereum's reorg
 * semantics — forward execution pushes {@code removed:false}, a reorg re-emits the reverted logs as
 * {@code removed:true} BEFORE the state is wiped, and the new branch pushes {@code removed:false} again.
 */
public class SubscriptionEndToEndTest {

    /** Initcode returning runtime 60006000a000 (LOG0 STOP): each CALL emits one topic-less log. */
    private static final Bytes LOG0_INIT = Bytes.fromHexString("0x6006600c60003960066000f360006000a000");
    private static final Address KEY1_SENDER =
            Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    private EvmTransaction execAt(EvmTxStore txStore, EvmMetaStore metaStore, EvmBlockProcessor proc,
                                  long nonce, Optional<Address> to, Bytes payload, long height) {
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        EvmTransaction tx = EvmTransaction.unsigned(nonce, Wei.ONE, 200_000L,
                to, Wei.ZERO, payload, BigInteger.valueOf(0xCAFE))
                .sign(key, algo);
        txStore.put(tx);
        proc.processMainBlock(List.of(Bytes32.wrap(tx.getHash().getBytes())), height, 1000L + height,
                Bytes32.leftPad(Bytes.ofUnsignedLong(height)));
        assertEquals("execution at height " + height + " must succeed", 1,
                metaStore.getReceipt(tx.getHash()).orElseThrow().status());
        return tx;
    }

    private static String drain(EmbeddedChannel ch) {
        ch.runPendingTasks();
        TextWebSocketFrame f = ch.readOutbound();
        return f == null ? null : f.text();
    }

    @Test
    public void logs_subscription_sees_forward_then_removed_then_new_branch() {
        InMemoryKVSource state = new InMemoryKVSource();
        EvmTxStore txStore = new EvmTxStore(new InMemoryKVSource());
        EvmMetaStore metaStore = new EvmMetaStore(new InMemoryKVSource());
        SubscriptionManager mgr = new SubscriptionManager();
        EvmBlockProcessor proc = new EvmBlockProcessor(EvmConfig.devnet(), state, txStore, metaStore, 0L,
                List.of(new GenesisAllocEntry(KEY1_SENDER, Wei.fromEth(1))));
        proc.seedGenesisIfAbsent();
        proc.setSubscriptionSink(mgr);

        // Deploy LOG0 at h1 (no log), then subscribe to that contract's logs.
        EvmTransaction deploy = execAt(txStore, metaStore, proc, 0, Optional.empty(), LOG0_INIT, 1L);
        Address contract = metaStore.getReceipt(deploy.getHash()).orElseThrow().contractAddress().orElseThrow();
        EmbeddedChannel ch = new EmbeddedChannel();
        mgr.subscribe(ch, "logs", Map.of("address", contract.toHexString()));

        // Forward: calling it at h2 emits a log -> removed:false frame.
        execAt(txStore, metaStore, proc, 1, Optional.of(contract), Bytes.EMPTY, 2L);
        String fwd = drain(ch);
        assertTrue("forward log frame", fwd != null && fwd.contains("\"removed\":false"));

        // Reorg to h1: h2's log re-emitted removed:true BEFORE the wipe.
        proc.rollbackTo(1);
        String reverted = drain(ch);
        assertTrue("reverted log frame", reverted != null && reverted.contains("\"removed\":true"));

        // New branch: re-call at h2 -> removed:false again.
        execAt(txStore, metaStore, proc, 1, Optional.of(contract), Bytes.EMPTY, 2L);
        String again = drain(ch);
        assertTrue("new-branch log frame", again != null && again.contains("\"removed\":false"));
    }
}
