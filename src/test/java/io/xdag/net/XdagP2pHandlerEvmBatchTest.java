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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
 *   processEvmBatchRequest, processEvmBatchReply, and the retry tick (requestPendingEvmBlobs).
 * Construction pattern: mock Kernel returning a real EvmTxStore (in-memory) and a mock
 * EvmBlockProcessor; mock Channel carrying a mock MessageQueue; use reflection to bypass
 * handshake gate and invoke private handler methods directly.
 */
public class XdagP2pHandlerEvmBatchTest {

    // Fixed batch body: RLP list of one tx hash (0xaa * 32)
    private static final Bytes32 TX_HASH_A = Bytes32.fromHexString("0x" + "aa".repeat(32));
    private static final Bytes32 TX_HASH_B = Bytes32.fromHexString("0x" + "bb".repeat(32));

    private static final int MAX_P2P_BYTES = 131_072;

    private EvmTxStore evmTxStore;
    private EvmBlockProcessor mockProcessor;
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
        Kernel mockKernel = mock(Kernel.class);
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockKernel.getBlockchain()).thenReturn(mock(Blockchain.class));
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
        verify(mockProcessor).onBlobsAvailable();
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
        verify(mockProcessor, never()).onBlobsAvailable();
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
        verify(mockProcessor, never()).onBlobsAvailable();
    }

    // -------------------------------------------------------------------------
    // Test 3: the_retry_tick_requests_both_kinds_for_ambiguous_refs
    // -------------------------------------------------------------------------

    /**
     * requestPendingEvmBlobs() must send EVM_TX_REQUEST for every hash in pendingMissingBlobHashes()
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

        // Invoke the private retry method
        Method tick = XdagP2pHandler.class.getDeclaredMethod("requestPendingEvmBlobs");
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
}
