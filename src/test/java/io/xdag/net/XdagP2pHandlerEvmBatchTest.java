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
package io.xdag.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.spec.EvmSpec;
import io.xdag.config.spec.NodeSpec;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Blockchain;
import io.xdag.evm.EvmBlockProcessor;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.tx.EvmTxStore;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.KeyPair;
import java.util.Optional;
import io.xdag.net.message.p2p.EvmTxReplyMessage;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxPool;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.message.p2p.EvmBatchReplyMessage;
import io.xdag.net.message.p2p.EvmBatchRequestMessage;
import io.xdag.net.message.p2p.EvmTxRequestMessage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the EVM batch-fetch P2P routing added in batch D2:
 *   processEvmBatchRequest, processEvmBatchReply, and the retry tick (requestMissingEvmBlobs).
 * Construction pattern: mock Kernel returning a real EvmTxStore (in-memory) and a mock
 * EvmBlockProcessor; mock Channel carrying a mock MessageQueue; use reflection to bypass
 * handshake gate and invoke private handler methods directly.
 */
public class XdagP2pHandlerEvmBatchTest {

    // Fixed batch body: RLP list of one tx hash (0xaa * 32)
    private static final Bytes32 TX_HASH_A = Bytes32.fromHexString("0x" + "aa".repeat(32));
    private static final Bytes32 TX_HASH_B = Bytes32.fromHexString("0x" + "bb".repeat(32));
    private Kernel mockKernel;

    private static final int MAX_P2P_BYTES = 131_072;

    private EvmTxStore evmTxStore;
    private EvmBlockProcessor mockProcessor;
    private Blockchain mockChain;
    private MessageQueue mockMsgQueue;
    private XdagP2pHandler handler;

    @Before
    public void setUp() throws Exception {
        // Real in-memory store
        evmTxStore = new EvmTxStore(new InMemoryKVSource());

        // Mock EvmBlockProcessor
        mockProcessor = mock(EvmBlockProcessor.class);

        // Mock MessageQueue
        mockMsgQueue = mock(MessageQueue.class);

        // Mock Channel
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.getMessageQueue()).thenReturn(mockMsgQueue);
        when(mockChannel.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 7001));

        // Mock EvmSpec
        EvmSpec mockEvmSpec = mock(EvmSpec.class);
        when(mockEvmSpec.getEvmMaxP2pTxBytes()).thenReturn(MAX_P2P_BYTES);

        // Mock NodeSpec (needed by constructor)
        NodeSpec mockNodeSpec = mock(NodeSpec.class);

        // Mock Config
        Config mockConfig = mock(Config.class);
        when(mockConfig.getEvmSpec()).thenReturn(mockEvmSpec);
        when(mockConfig.getNodeSpec()).thenReturn(mockNodeSpec);

        // Mock Kernel
        mockKernel = mock(Kernel.class);
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        mockChain = mock(Blockchain.class);
        when(mockKernel.getBlockchain()).thenReturn(mockChain);
        when(mockKernel.getChannelMgr()).thenReturn(mock(ChannelManager.class));
        when(mockKernel.getNodeMgr()).thenReturn(mock(io.xdag.net.node.NodeManager.class));
        when(mockKernel.getClient()).thenReturn(mock(PeerClient.class));
        when(mockKernel.getSyncMgr()).thenReturn(mock(SyncManager.class));
        when(mockKernel.getNetDBMgr()).thenReturn(mock(NetDBManager.class));
        when(mockKernel.getEvmTxStore()).thenReturn(evmTxStore);
        when(mockKernel.getEvmBlockProcessor()).thenReturn(mockProcessor);
        when(mockKernel.getEvmTxPool()).thenReturn(null); // not needed by batch tests

        handler = new XdagP2pHandler(mockChannel, mockKernel);

        // Bypass the handshake gate so onXdag dispatches
        Field handshakeDone = XdagP2pHandler.class.getDeclaredField("isHandshakeDone");
        handshakeDone.setAccessible(true);
        ((AtomicBoolean) handshakeDone.get(handler)).set(true);
    }

    // -------------------------------------------------------------------------
    // Helper: invoke a private void method by name with a single Message arg
    // -------------------------------------------------------------------------

    private void invokePrivate(String methodName, Class<?> argType, Object arg) throws Exception {
        Method m = XdagP2pHandler.class.getDeclaredMethod(methodName, argType);
        m.setAccessible(true);
        m.invoke(handler, arg);
    }

    // -------------------------------------------------------------------------
    // Test 1: batch_request_replies_with_the_stored_body
    // -------------------------------------------------------------------------

    /**
     * When a remote peer sends EVM_BATCH_REQUEST for a hash that is present in our store, the handler
     * must respond with EVM_BATCH_REPLY carrying the exact body.
     */
    @Test
    public void batch_request_replies_with_the_stored_body() throws Exception {
        // Arrange: build and store a batch body
        Bytes body = EvmTxStore.encodeBatch(List.of(TX_HASH_A));
        Hash batchHash = evmTxStore.putBatch(body);

        // Wrap the batch hash back into Bytes32 for the message
        Bytes32 hashBytes32 = Bytes32.wrap(batchHash.getBytes());

        // Act: dispatch the request through the handler (private method)
        EvmBatchRequestMessage req = new EvmBatchRequestMessage(hashBytes32);
        invokePrivate("processEvmBatchRequest", EvmBatchRequestMessage.class, req);

        // Assert: the queue received an EvmBatchReplyMessage with the correct body
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(mockMsgQueue, atLeastOnce()).sendMessage(captor.capture());

        List<Message> sent = captor.getAllValues();
        boolean foundReply = sent.stream()
                .filter(m -> m instanceof EvmBatchReplyMessage)
                .map(m -> (EvmBatchReplyMessage) m)
                .anyMatch(r -> body.equals(r.getBatchBody()));
        assertTrue("Expected EvmBatchReplyMessage with the stored batch body", foundReply);
    }

    // -------------------------------------------------------------------------
    // Test 2: batch_reply_is_ingested_only_when_awaited_and_keccak_matches
    // -------------------------------------------------------------------------

    /**
     * (a) Reply is awaited and body hashes to the awaited commitment → stored and onBlobsAvailable invoked.
     */
    @Test
    public void batch_reply_stored_and_processor_notified_when_awaited() throws Exception {
        Bytes body = EvmTxStore.encodeBatch(List.of(TX_HASH_A));
        Hash batchHash = Hash.hash(body);

        when(mockProcessor.isAwaitingBatch(batchHash)).thenReturn(true);

        EvmBatchReplyMessage reply = new EvmBatchReplyMessage(body);
        invokePrivate("processEvmBatchReply", EvmBatchReplyMessage.class, reply);

        assertTrue("Batch body must be stored when awaited", evmTxStore.containsBatch(batchHash));
        // K1: the drain is now driven via the blockchain (which credits the payload block), not the
        // processor directly.
        verify(mockChain).onEvmBlobsAvailable();
    }

    /**
     * (b) Reply arrives but nothing is awaiting that commitment → NOT stored.
     */
    @Test
    public void batch_reply_is_not_stored_when_not_awaited() throws Exception {
        Bytes body = EvmTxStore.encodeBatch(List.of(TX_HASH_B));
        Hash batchHash = Hash.hash(body);

        when(mockProcessor.isAwaitingBatch(batchHash)).thenReturn(false);

        EvmBatchReplyMessage reply = new EvmBatchReplyMessage(body);
        invokePrivate("processEvmBatchReply", EvmBatchReplyMessage.class, reply);

        assertFalse("Batch body must NOT be stored when not awaited", evmTxStore.containsBatch(batchHash));
        verify(mockChain, never()).onEvmBlobsAvailable();
    }

    /**
     * (c) Reply body exceeds the P2P size cap → silently dropped even if the processor claims awaiting.
     */
    @Test
    public void batch_reply_oversized_is_dropped_even_when_awaited() throws Exception {
        // Build a body of exactly MAX_P2P_BYTES + 1 bytes (arbitrary content)
        byte[] big = new byte[MAX_P2P_BYTES + 1];
        Bytes oversizedBody = Bytes.wrap(big);
        Hash fakeHash = Hash.hash(oversizedBody);

        when(mockProcessor.isAwaitingBatch(fakeHash)).thenReturn(true);

        EvmBatchReplyMessage reply = new EvmBatchReplyMessage(oversizedBody);
        invokePrivate("processEvmBatchReply", EvmBatchReplyMessage.class, reply);

        assertFalse("Oversized batch body must NOT be stored", evmTxStore.containsBatch(fakeHash));
        verify(mockChain, never()).onEvmBlobsAvailable();
    }

    // -------------------------------------------------------------------------
    // Audit round 2, P1: classification by CONTENT, not by delivery message type
    // -------------------------------------------------------------------------

    private EvmTxPool realPool() {
        return new EvmTxPool(evmTxStore, new InMemoryKVSource(), java.math.BigInteger.valueOf(0xCAFE),
                30_000_000L, org.hyperledger.besu.datatypes.Wei.ONE, 3600L, () -> 1000L);
    }

    /**
     * An unknown 0x0F ref is awaited both as a tx blob and as a batch commitment. A peer that sends the
     * real batch body inside an EVM_TX_REPLY must not get it stored in the tx keyspace (where expandRefs
     * would read it as an undecodable single tx and every later honest batch reply would be dropped).
     */
    @Test
    public void a_batch_body_delivered_as_a_tx_reply_is_stored_as_a_batch() throws Exception {
        when(mockKernel.getEvmTxPool()).thenReturn(realPool());
        Bytes body = EvmTxStore.encodeBatch(List.of(TX_HASH_A, TX_HASH_B));
        Hash hash = Hash.hash(body);
        when(mockProcessor.isAwaitingBlob(hash)).thenReturn(true);
        when(mockProcessor.isAwaitingBatch(hash)).thenReturn(true);

        invokePrivate("processEvmTxReply", EvmTxReplyMessage.class, new EvmTxReplyMessage(body));

        assertFalse("a batch-shaped body must never land in the tx keyspace", evmTxStore.contains(hash));
        assertTrue("it is stored as the batch it is", evmTxStore.containsBatch(hash));
        assertEquals(List.of(TX_HASH_A, TX_HASH_B), evmTxStore.getBatch(hash).orElseThrow());
        verify(mockChain).onEvmBlobsAvailable();
    }

    /** Mirror image: a tx-shaped blob inside an EVM_BATCH_REPLY lands in the tx keyspace. */
    @Test
    public void a_tx_blob_delivered_as_a_batch_reply_is_stored_as_a_tx_blob() throws Exception {
        when(mockKernel.getEvmTxPool()).thenReturn(realPool());
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(java.math.BigInteger.ONE));
        EvmTransaction tx = EvmTransaction.unsigned(0L, org.hyperledger.besu.datatypes.Wei.ONE, 21_000L,
                Optional.of(org.hyperledger.besu.datatypes.Address.ZERO), org.hyperledger.besu.datatypes.Wei.ZERO,
                Bytes.EMPTY, java.math.BigInteger.valueOf(0xCAFE)).sign(key, algo);
        Bytes body = tx.getRawRlp();
        Hash hash = tx.getHash();
        when(mockProcessor.isAwaitingBlob(hash)).thenReturn(true);
        when(mockProcessor.isAwaitingBatch(hash)).thenReturn(true);

        invokePrivate("processEvmBatchReply", EvmBatchReplyMessage.class, new EvmBatchReplyMessage(body));

        assertTrue("a tx-shaped blob is a tx blob whatever message carried it", evmTxStore.contains(hash));
        assertFalse(evmTxStore.containsBatch(hash));
        verify(mockChain).onEvmBlobsAvailable();
    }

    /**
     * Bytes that are neither a batch body nor a decodable tx still satisfy an awaited ref as a tx blob:
     * a miner that references garbage and serves it gets a deterministic failed receipt on every node
     * (liveness), rather than stalling the height forever.
     */
    @Test
    public void undecodable_bytes_still_satisfy_an_awaited_ref_as_a_tx_blob() throws Exception {
        when(mockKernel.getEvmTxPool()).thenReturn(realPool());
        Bytes garbage = Bytes.fromHexString("0xdeadbeefdeadbeef");
        Hash hash = Hash.hash(garbage);
        when(mockProcessor.isAwaitingBlob(hash)).thenReturn(true);
        when(mockProcessor.isAwaitingBatch(hash)).thenReturn(true);

        invokePrivate("processEvmTxReply", EvmTxReplyMessage.class, new EvmTxReplyMessage(garbage));

        assertTrue(evmTxStore.contains(hash));
        assertFalse(evmTxStore.containsBatch(hash));
    }

    // -------------------------------------------------------------------------
    // Test 3: the_retry_tick_requests_both_kinds_for_ambiguous_refs
    // -------------------------------------------------------------------------

    /**
     * requestMissingEvmBlobs() must send EVM_TX_REQUEST for every hash in pendingMissingBlobHashes()
     * and EVM_BATCH_REQUEST for every hash in pendingMissingBatchHashes(), using the same ref set
     * (an ambiguous ref X appears in both lists → both message types must be queued for X).
     */
    @Test
    public void retry_tick_requests_both_message_types_for_ambiguous_refs() throws Exception {
        // X is ambiguous: appears in both blob list and batch list
        Bytes32 refX = Bytes32.fromHexString("0x" + "cc".repeat(32));
        // Y is a known-missing tx blob (only in blob list)
        Bytes32 refY = Bytes32.fromHexString("0x" + "dd".repeat(32));

        List<Bytes32> blobHashes = new ArrayList<>();
        blobHashes.add(refX);
        blobHashes.add(refY);

        List<Bytes32> batchHashes = new ArrayList<>();
        batchHashes.add(refX);

        when(mockProcessor.pendingMissingBlobHashes()).thenReturn(blobHashes);
        when(mockProcessor.pendingMissingBatchHashes()).thenReturn(batchHashes);
        when(mockProcessor.bufferedMissingBlobHashes()).thenReturn(new ArrayList<>());
        when(mockProcessor.bufferedMissingBatchHashes()).thenReturn(new ArrayList<>());

        // Invoke the private retry method
        Method tick = XdagP2pHandler.class.getDeclaredMethod("requestMissingEvmBlobs");
        tick.setAccessible(true);
        tick.invoke(handler);

        // Capture all messages sent to the queue
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(mockMsgQueue, atLeastOnce()).sendMessage(captor.capture());

        List<Message> sent = captor.getAllValues();

        // Must include EVM_TX_REQUEST for X and Y
        boolean txReqForX = sent.stream()
                .filter(m -> m instanceof EvmTxRequestMessage)
                .map(m -> (EvmTxRequestMessage) m)
                .anyMatch(m -> refX.equals(m.getTxHash()));
        boolean txReqForY = sent.stream()
                .filter(m -> m instanceof EvmTxRequestMessage)
                .map(m -> (EvmTxRequestMessage) m)
                .anyMatch(m -> refY.equals(m.getTxHash()));

        // Must include EVM_BATCH_REQUEST for X (the ambiguous ref)
        boolean batchReqForX = sent.stream()
                .filter(m -> m instanceof EvmBatchRequestMessage)
                .map(m -> (EvmBatchRequestMessage) m)
                .anyMatch(m -> refX.equals(m.getBatchHash()));

        assertTrue("Expected EvmTxRequestMessage for ambiguous ref X", txReqForX);
        assertTrue("Expected EvmTxRequestMessage for blob-only ref Y", txReqForY);
        assertTrue("Expected EvmBatchRequestMessage for ambiguous ref X", batchReqForX);
    }

    // -------------------------------------------------------------------------
    // Test 4: batch_request_for_unknown_hash_sends_no_reply
    // -------------------------------------------------------------------------

    /**
     * When a remote peer sends EVM_BATCH_REQUEST for a hash that is NOT present in our store, the
     * handler must not send any reply at all.
     */
    @Test
    public void batch_request_for_unknown_hash_sends_no_reply() throws Exception {
        // Use a hash that was never stored in evmTxStore
        Bytes32 unknownHash = Bytes32.fromHexString("0x" + "ee".repeat(32));

        EvmBatchRequestMessage req = new EvmBatchRequestMessage(unknownHash);
        invokePrivate("processEvmBatchRequest", EvmBatchRequestMessage.class, req);

        verify(mockMsgQueue, never()).sendMessage(any());
    }

    // -------------------------------------------------------------------------
    // Test 5: buffered refs are proactively fetched and backoff throttles repeats
    // -------------------------------------------------------------------------

    @Test
    public void retry_tick_requests_buffered_refs_and_backs_off_on_repeat() throws Exception {
        Bytes32 bufBlob = Bytes32.fromHexString("0x" + "77".repeat(32));
        when(mockProcessor.pendingMissingBlobHashes()).thenReturn(new ArrayList<>());
        when(mockProcessor.pendingMissingBatchHashes()).thenReturn(new ArrayList<>());
        when(mockProcessor.bufferedMissingBlobHashes()).thenReturn(new ArrayList<>(List.of(bufBlob)));
        when(mockProcessor.bufferedMissingBatchHashes()).thenReturn(new ArrayList<>());

        Method tick = XdagP2pHandler.class.getDeclaredMethod("requestMissingEvmBlobs");
        tick.setAccessible(true);

        tick.invoke(handler); // tick 1: fresh -> due -> EVM_TX_REQUEST for the buffered ref
        tick.invoke(handler); // tick 2: eligible at 2 -> due again

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(mockMsgQueue, atLeastOnce()).sendMessage(captor.capture());
        long txReqForBuf = captor.getAllValues().stream()
                .filter(m -> m instanceof EvmTxRequestMessage)
                .map(m -> (EvmTxRequestMessage) m)
                .filter(m -> bufBlob.equals(m.getTxHash()))
                .count();
        assertTrue("buffered ref proactively fetched", txReqForBuf >= 1);

        tick.invoke(handler); // tick 3: next eligible = 4 > 3 -> NOT due -> backoff throttles
        ArgumentCaptor<Message> after = ArgumentCaptor.forClass(Message.class);
        verify(mockMsgQueue, atLeastOnce()).sendMessage(after.capture());
        long txReqForBufAfter = after.getAllValues().stream()
                .filter(m -> m instanceof EvmTxRequestMessage)
                .map(m -> (EvmTxRequestMessage) m)
                .filter(m -> bufBlob.equals(m.getTxHash()))
                .count();
        assertEquals("backoff throttles the 3rd immediate tick", txReqForBuf, txReqForBufAfter);
    }
}
