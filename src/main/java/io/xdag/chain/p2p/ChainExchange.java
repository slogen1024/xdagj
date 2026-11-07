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
package io.xdag.chain.p2p;

import java.util.concurrent.ConcurrentHashMap;

import io.xdag.net.message.Message;
import io.xdag.net.message.chain.ChainProofReplyMessage;
import io.xdag.net.message.chain.ChainProofRequestMessage;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;
import io.xdag.net.message.chain.ChainSnapshotRequestMessage;
import io.xdag.net.message.chain.ChainSubscriptionsMessage;

/**
 * One connection's chain traffic. The announcement is a routing hint. A request for a chain this
 * node does not subscribe to is answered {@code ABSENT} without consulting the source, so a source
 * cannot be talked into serving a chain the configuration declined.
 */
public final class ChainExchange {

    private final ChainSubscriptions local;
    private volatile ChainDataSource source;
    private volatile ChainSubscriptions remote = ChainSubscriptions.none();
    private final ConcurrentHashMap<Long, ChainSnapshotReplyMessage> snapshotReplies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChainProofReplyMessage> proofReplies = new ConcurrentHashMap<>();

    public ChainExchange(ChainSubscriptions local, ChainDataSource source) {
        this.local = local;
        this.source = source;
    }

    public void setSource(ChainDataSource source) {
        this.source = source;
    }

    public ChainSubscriptions local() {
        return local;
    }

    public ChainSubscriptions remote() {
        return remote;
    }

    public ChainSubscriptionsMessage announce() {
        return new ChainSubscriptionsMessage(local);
    }

    public void onRemote(ChainSubscriptionsMessage message) {
        remote = message.subscriptions();
    }

    public Message reply(ChainSnapshotRequestMessage request) {
        if (!local.covers(request.chainId())) {
            return ChainSnapshotReplyMessage.absent(request.requestId());
        }
        byte[] page = source.snapshotPage(request.chainId(), request.seq(), request.page());
        if (page == null) {
            return ChainSnapshotReplyMessage.absent(request.requestId());
        }
        if (page.length > ChainCodec.MAX_PAYLOAD) {
            return ChainSnapshotReplyMessage.oversize(request.requestId());
        }
        return ChainSnapshotReplyMessage.ok(request.requestId(), page);
    }

    public Message reply(ChainProofRequestMessage request) {
        if (!local.covers(request.chainId())) {
            return ChainProofReplyMessage.absent(request.requestId());
        }
        byte[] blob = source.proof(request.chainId(), request.seq(), request.kind(), request.key());
        if (blob == null) {
            return ChainProofReplyMessage.absent(request.requestId());
        }
        if (blob.length > ChainCodec.MAX_PAYLOAD) {
            return ChainProofReplyMessage.oversize(request.requestId());
        }
        return ChainProofReplyMessage.ok(request.requestId(), blob);
    }

    public void onSnapshotReply(ChainSnapshotReplyMessage message) {
        snapshotReplies.put(message.requestId(), message);
    }

    public void onProofReply(ChainProofReplyMessage message) {
        proofReplies.put(message.requestId(), message);
    }

    public ChainSnapshotReplyMessage snapshotReply(long requestId) {
        return snapshotReplies.get(requestId);
    }

    public ChainProofReplyMessage proofReply(long requestId) {
        return proofReplies.get(requestId);
    }
}
