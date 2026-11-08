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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.net.message.Message;
import io.xdag.net.message.chain.ChainProofReplyMessage;
import io.xdag.net.message.chain.ChainProofRequestMessage;
import io.xdag.net.message.chain.ChainSnapshotReplyMessage;
import io.xdag.net.message.chain.ChainSnapshotRequestMessage;

/**
 * Sends {@code 0x1C} and {@code 0x1E} to peers that announced a covering subscription. A peer that
 * can answer now returns the reply. A peer that only queued the request returns null, and the same
 * request id is reused until that reply is taken, so the inflight cap is one slot per query.
 */
public final class ChainPull {

    /** One remote. {@link #exchange} returns the reply, or null when it has been sent and not answered. */
    public interface Peer {
        ChainSubscriptions remote();

        Message exchange(Message request);
    }

    private final AtomicLong ids = new AtomicLong(1);
    private final CopyOnWriteArrayList<Peer> peers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<SnapId, Long> snapIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProofId, Long> proofIds = new ConcurrentHashMap<>();

    public Peer add(Peer peer) {
        peers.add(peer);
        return peer;
    }

    public void remove(Peer peer) {
        peers.remove(peer);
    }

    /**
     * A connection whose replies arrive later. The send writes {@code 0x1C} or {@code 0x1E}. The
     * exchange keeps the reply until the next {@link #exchange} for the same request id.
     */
    public Peer wire(ChainExchange exchange, Consumer<Message> send) {
        return add(new Wire(exchange, send));
    }

    /** A peer in this process. The request is encoded and the reply is decoded before it is returned. */
    public static Peer local(ChainSubscriptions remote, ChainDataSource source) {
        ChainExchange exchange = new ChainExchange(remote, source);
        return new Peer() {
            @Override
            public ChainSubscriptions remote() {
                return remote;
            }

            @Override
            public Message exchange(Message request) {
                if (request instanceof ChainSnapshotRequestMessage req) {
                    return new ChainSnapshotReplyMessage(exchange.reply(req).getBody());
                }
                if (request instanceof ChainProofRequestMessage req) {
                    return new ChainProofReplyMessage(exchange.reply(req).getBody());
                }
                return null;
            }
        };
    }

    /**
     * @return the page reply, ABSENT when no covering peer has it, or null when a request is in flight
     */
    public ChainSnapshotReplyMessage snapshot(Bytes32 chain, long seq, int page) {
        SnapId id = new SnapId(chain, seq, page);
        long requestId = snapIds.computeIfAbsent(id, k -> ids.getAndIncrement());
        ChainSnapshotRequestMessage req = new ChainSnapshotRequestMessage(requestId, chain, seq, page);
        for (Peer peer : peers) {
            if (!peer.remote().covers(chain)) {
                continue;
            }
            Message raw = peer.exchange(req);
            if (raw == null) {
                return null;
            }
            snapIds.remove(id);
            ChainSnapshotReplyMessage reply = raw instanceof ChainSnapshotReplyMessage message ? message
                    : new ChainSnapshotReplyMessage(raw.getBody());
            if (!reply.absent() || page > 0) {
                return reply;
            }
        }
        snapIds.remove(id);
        return ChainSnapshotReplyMessage.absent(requestId);
    }

    /**
     * @return the proof body, or null when every covering peer is ABSENT or the reply has not arrived
     */
    public byte[] proof(Bytes32 chain, long seq, ProofKind kind, byte[] key) {
        ProofId id = new ProofId(chain, seq, kind, Bytes.wrap(key));
        long requestId = proofIds.computeIfAbsent(id, k -> ids.getAndIncrement());
        ChainProofRequestMessage req = new ChainProofRequestMessage(requestId, chain, seq, kind, key);
        for (Peer peer : peers) {
            if (!peer.remote().covers(chain)) {
                continue;
            }
            Message raw = peer.exchange(req);
            if (raw == null) {
                return null;
            }
            proofIds.remove(id);
            ChainProofReplyMessage reply = raw instanceof ChainProofReplyMessage message ? message
                    : new ChainProofReplyMessage(raw.getBody());
            if (reply.ok()) {
                return reply.proof();
            }
        }
        proofIds.remove(id);
        return null;
    }

    /** The production connection. Request ids stay open until {@link ChainExchange#takeProof} sees the reply. */
    private static final class Wire implements Peer {
        private final ChainExchange exchange;
        private final Consumer<Message> send;

        private Wire(ChainExchange exchange, Consumer<Message> send) {
            this.exchange = exchange;
            this.send = send;
        }

        @Override
        public ChainSubscriptions remote() {
            return exchange.remote();
        }

        @Override
        public Message exchange(Message request) {
            if (request instanceof ChainSnapshotRequestMessage req) {
                ChainSnapshotReplyMessage ready = exchange.takeSnapshot(req.requestId());
                if (ready != null) {
                    return ready;
                }
                if (exchange.openSnapshot(req.requestId())) {
                    send.accept(request);
                }
                return null;
            }
            if (request instanceof ChainProofRequestMessage req) {
                ChainProofReplyMessage ready = exchange.takeProof(req.requestId());
                if (ready != null) {
                    return ready;
                }
                if (exchange.openProof(req.requestId())) {
                    send.accept(request);
                }
                return null;
            }
            return null;
        }
    }

    public record SnapId(Bytes32 chain, long seq, int page) {
    }

    /** Kept so a retry reuses one request id until the reply is taken. */
    public record ProofId(Bytes32 chain, long seq, ProofKind kind, Bytes key) {
        public ProofId {
            Objects.requireNonNull(chain);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(key);
        }
    }
}
