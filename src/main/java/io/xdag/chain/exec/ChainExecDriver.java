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

import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.p2p.ChainSubscriptions;

/**
 * Node-local follower of {@code onSetMainEnd}. It does not implement {@code ChainL1Hooks} and it
 * does not write {@code CHAIN_L1}. A subscribed chain is advanced from its head (or its creation
 * height) through {@code height}, waiting when a deliverable outbox is not in the exec store yet
 * so the source chain can step first. {@link #onSourceVoided} rolls a consumer back to the seq
 * before that segment was delivered. {@code onUnsetMain} does not roll the state back — that is
 * SP1-4 — and once a chain has executed a height that is being removed, further steps stop.
 * {@code D == W} does not rewind: those segments are only consumed after finality, and a final
 * anchor is not voided.
 */
public final class ChainExecDriver {

    private final ChainExecutor executor;
    private final ChainSubscriptions subscriptions;
    private final ChainL1Store l1;
    private final long window;
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
                long head = executor.appliedSeq(chain);
                long seq = head < 0 ? record.createdHeight() : head + 1;
                if (seq > height || !executor.ready(chain, seq)) {
                    continue;
                }
                executor.step(chain, seq, timestamp.applyAsLong(seq));
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
        for (Bytes chain : chains()) {
            if (executor.appliedSeq(chain) >= height) {
                blocked = true;
                return;
            }
        }
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
