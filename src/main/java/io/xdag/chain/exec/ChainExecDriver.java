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
package io.xdag.chain.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongUnaryOperator;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.p2p.ChainPull;
import io.xdag.chain.p2p.ChainRecords;
import io.xdag.chain.p2p.ChainSubscriptions;

/**
 * Node-local follower of {@code onSetMainEnd}. It does not implement {@code ChainL1Hooks} and it
 * does not write {@code CHAIN_L1}. A subscribed chain is advanced from its head (or its creation
 * height) through {@code height}, waiting when a deliverable outbox is not in the exec store yet
 * so the source chain can step first. {@link #onSourceVoided} rolls a consumer back to the seq
 * before that segment was delivered. {@code onUnsetMain} rolls every subscribed chain back to the
 * seq before the height leaving main. A missing change-set stops the driver. {@code D == W} does
 * not rewind: those segments are only consumed after finality, and a final anchor is not voided.
 */
public final class ChainExecDriver {

    private final ChainExecutor executor;
    private final ChainSubscriptions subscriptions;
    private final ChainL1Store l1;
    private final long window;
    private SegmentProofs proofs;
    private ChainPull pull;
    private ChainRecords records;
    private boolean blocked;

    public ChainExecDriver(ChainExecutor executor, ChainSubscriptions subscriptions, ChainL1Store l1) {
        this(executor, subscriptions, l1, -1L);
    }

    public ChainExecDriver(ChainExecutor executor, ChainSubscriptions subscriptions, ChainL1Store l1, long window) {
        this.executor = executor;
        this.subscriptions = subscriptions;
        this.l1 = l1;
        this.window = window;
    }

    /** Proofs already pulled from a peer. When set, this wins over {@link #setPull}. */
    public void setProofs(SegmentProofs proofs) {
        this.proofs = proofs;
    }

    public ChainExecutor executor() {
        return executor;
    }

    /** Peers to ask with {@code 0x1C} and {@code 0x1E}, and the records this node serves after a step. */
    public void setNetwork(ChainPull pull, ChainRecords records) {
        this.pull = pull;
        this.records = records;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void onSetMainEnd(long height, LongUnaryOperator timestamp) {
        if (blocked) {
            return;
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Bytes chain : chains()) {
                ChainRecord record = l1.getChain(chain);
                if (record == null) {
                    continue;
                }
                if (executor.appliedSeq(chain) < 0 && !bootstrap(chain, height)) {
                    continue;
                }
                long head = executor.appliedSeq(chain);
                long seq = head < 0 ? record.createdHeight() : head + 1;
                if (seq > height) {
                    continue;
                }
                if (!executor.ready(chain, seq)) {
                    executor.fetchDeliveries(chain, seq, proofs());
                }
                if (!executor.ready(chain, seq)) {
                    continue;
                }
                executor.step(chain, seq, timestamp.applyAsLong(seq));
                executor.publish(chain, records, chainIds());
                progress = true;
            }
        }
    }

    /**
     * A source anchor was voided. Every subscribed destination that already executed
     * {@code confirmHeight + D} goes back to the seq before that delivery. The chain's own
     * execution is left alone. {@code D == window} is a no-op.
     */
    public void onSourceVoided(byte[] src, long srcSeq, long confirmHeight) {
        if (blocked || src == null) {
            return;
        }
        for (Bytes dest : chains()) {
            if (Arrays.equals(dest.toArray(), src)) {
                continue;
            }
            ChainRecord record = l1.getChain(dest);
            if (record == null) {
                continue;
            }
            long delay = record.deliveryDelayD();
            if (window >= 0L && delay == window) {
                continue;
            }
            long ready = confirmHeight + delay;
            if (Long.compareUnsigned(ready, confirmHeight) < 0) {
                continue;
            }
            long applied = executor.appliedSeq(dest);
            if (applied < 0L || Long.compareUnsigned(applied, ready) < 0) {
                continue;
            }
            try {
                executor.rewindTo(dest, ready == 0L ? -1L : ready - 1L);
            } catch (RuntimeException e) {
                blocked = true;
                return;
            }
        }
    }

    public void onUnsetMain(long height) {
        long target = height == 0L ? -1L : height - 1L;
        for (Bytes chain : chains()) {
            if (executor.appliedSeq(chain) < height) {
                continue;
            }
            try {
                executor.rewindTo(chain, target);
            } catch (RuntimeException e) {
                blocked = true;
                return;
            }
        }
    }

    private SegmentProofs proofs() {
        if (proofs != null) {
            return proofs;
        }
        if (pull == null) {
            return null;
        }
        return (src, seq, dst, root) -> WireProofs.fetch(pull, executor.store(), src, seq, dst, root);
    }

    /**
     * Latest FINAL anchor at or before {@code height} becomes the head when its snapshot rebuilds
     * {@code stateRoot}. No such anchor, or a peer with no snapshot, replays from creation. A pull
     * that has not answered yet leaves the chain where it is.
     */
    private boolean bootstrap(Bytes chain, long height) {
        if (pull == null) {
            return true;
        }
        Final fin = latestFinal(chain, height);
        if (fin == null) {
            return true;
        }
        try {
            executor.bootstrap(chain, fin.seq, fin.body.stateRoot(),
                    page -> pull.snapshot(ChainExecutor.chain32(chain), fin.seq, page));
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Final latestFinal(Bytes chain, long height) {
        byte[] raw = chain.toArray();
        Final best = null;
        for (byte[] key : l1.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(raw, 0, 20, key, 1, 21)) {
                continue;
            }
            long seq = ExtCodec.u64(key, 21);
            if (Long.compareUnsigned(seq, height) > 0) {
                continue;
            }
            AnchorBody body = AnchorBody.decode(l1.getAnchorRaw(chain, seq));
            if (body == null || body.status() != AnchorRecord.Status.FINAL) {
                continue;
            }
            if (best == null || Long.compareUnsigned(seq, best.seq) > 0) {
                best = new Final(seq, body);
            }
        }
        return best;
    }

    private List<Bytes32> chainIds() {
        List<Bytes32> ids = new ArrayList<>();
        for (byte[] key : l1.sortedKeys()) {
            if (key.length == 21 && key[0] == ChainL1Keys.CHAIN) {
                ids.add(ChainExecutor.chain32(Bytes.wrap(Arrays.copyOfRange(key, 1, 21))));
            }
        }
        return ids;
    }

    private record Final(long seq, AnchorBody body) {
    }

    private List<Bytes> chains() {
        List<Bytes> out = new ArrayList<>();
        if (subscriptions.subscribeAll()) {
            for (byte[] key : l1.sortedKeys()) {
                if (key.length == 1 + 20 && key[0] == ChainL1Keys.CHAIN) {
                    byte[] id = new byte[20];
                    System.arraycopy(key, 1, id, 0, 20);
                    out.add(Bytes.wrap(id));
                }
            }
            return out;
        }
        for (Bytes32 id : subscriptions.chainIds()) {
            byte[] raw = new byte[20];
            System.arraycopy(id.toArray(), 12, raw, 0, 20);
            Bytes chain = Bytes.wrap(raw);
            if (l1.hasChain(chain)) {
                out.add(chain);
            }
        }
        return out;
    }
}
