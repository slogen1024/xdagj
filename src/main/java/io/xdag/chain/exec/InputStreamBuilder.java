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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.OutboxEntry;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.crypto.hash.HashUtils;

/**
 * {@code systemInputs ++ deliveredMessages ++ confirmedBlocks} at one height. System inputs are
 * the anchor rewards confirmed at {@code height}. Delivered messages are the outbox entries of
 * each source segment whose {@code confirmHeight + D} is this height. The body is the source
 * chain's MMR entry in {@code CHAIN_EXEC}; a node that has not executed that source is not
 * {@link #ready}. Confirmed blocks are the {@code 0x0C} records {@code 0 .. callCount-1}, including
 * {@code INVALID_*} ones: they occupy an index and the executor writes a status receipt instead of
 * running them.
 */
public final class InputStreamBuilder {

    private final ChainL1Store l1;
    private final ChainExecStore exec;

    public InputStreamBuilder(ChainL1Store l1) {
        this(l1, null);
    }

    public InputStreamBuilder(ChainL1Store l1, ChainExecStore exec) {
        this.l1 = l1;
        this.exec = exec;
    }

    public List<ChainInput> inputs(Bytes chain, long height) {
        List<ChainInput> out = new ArrayList<>();
        out.addAll(systemInputs(chain, height));
        for (ChainInput message : deliveredMessages(chain, height)) {
            out.add(new ChainInput(out.size(), message.kind(), message.inputHash(), message.l1Status(),
                    message.contract(), message.message()));
        }
        long n = l1.getCallCount(chain, height);
        if (n > Integer.MAX_VALUE) {
            throw new IllegalStateException("call count");
        }
        for (int i = 0; i < n; i++) {
            InputRecord record = l1.getInput(chain, height, i);
            if (record == null) {
                throw new IllegalStateException("missing input " + i);
            }
            ChainInput.Kind kind = record.kind() == ExtKind.CALL ? ChainInput.Kind.CALL
                    : record.kind() == ExtKind.DEPLOY ? ChainInput.Kind.DEPLOY : ChainInput.Kind.OTHER;
            out.add(new ChainInput(out.size(), kind, record.blockHash(), record.status(), record.contract()));
        }
        return out;
    }

    /** Anchor rewards confirmed at {@code height}, ordered by seq then anchor hash. */
    public List<ChainInput> systemInputs(Bytes chain, long height) {
        List<ChainInput> out = new ArrayList<>();
        for (RewardInputs.Reward reward : RewardInputs.at(l1, chain.toArray(), height)) {
            out.add(new ChainInput(out.size(), ChainInput.Kind.SYSTEM, RewardInputs.hash(reward.body().anchorHash()),
                    InputStatus.OK, Bytes.wrap(reward.body().attester())));
        }
        return out;
    }

    /**
     * One input per outbox entry that becomes deliverable at {@code height}. Order is the
     * {@link TriggerList#DELIVER} mark order, then entry index. A voided source is skipped. Without
     * an exec store the list stays empty.
     */
    public List<ChainInput> deliveredMessages(Bytes chain, long height) {
        if (exec == null) {
            return List.of();
        }
        List<ChainInput> out = new ArrayList<>();
        Bytes32 dst = ChainExecutor.chain32(chain);
        for (TriggerList.Item mark : deliverableMarks(chain, height)) {
            AnchorBody body = AnchorBody.decode(l1.getAnchorRaw(Bytes.wrap(mark.attester()), mark.seq()));
            if (body == null || !live(body)) {
                continue;
            }
            Bytes32 src = ChainExecutor.chain32(mark.attester());
            int from = exec.outboxSize(src, dst, body.prevSeq());
            int to = exec.outboxSize(src, dst, mark.seq());
            if (from < 0 || to < from) {
                continue;
            }
            if (from == to) {
                continue;
            }
            byte[] blob = exec.getMmr(src, dst);
            List<byte[]> entries = blob == null ? List.of() : ChainExecutor.decodeMmr(blob);
            int end = Math.min(to, entries.size());
            for (int i = from; i < end; i++) {
                byte[] raw = entries.get(i);
                OutboxEntry entry = OutboxEntry.decode(raw);
                Bytes32 leaf = HashUtils.sha256(Bytes.wrap(raw));
                if (entry == null) {
                    out.add(new ChainInput(out.size(), ChainInput.Kind.MESSAGE, leaf, InputStatus.INVALID_FORMAT,
                            Bytes.wrap(new byte[20])));
                } else {
                    out.add(new ChainInput(out.size(), ChainInput.Kind.MESSAGE, leaf, InputStatus.OK,
                            Bytes.wrap(entry.contract()),
                            new ChainInput.Message(entry.sender(), entry.value(), entry.selector(), entry.args())));
                }
            }
        }
        return out;
    }

    List<TriggerList.Item> marks(Bytes chain, long height) {
        return deliverableMarks(chain, height);
    }

    /**
     * False when a live deliverable mark's source outbox is not in the exec store yet. A fetched
     * segment fills that gap; until it does the driver leaves the chain at the previous seq.
     * No exec store is ready only when this height has no marks.
     */
    public boolean ready(Bytes chain, long height) {
        if (exec == null) {
            return deliverableMarks(chain, height).isEmpty();
        }
        Bytes32 dst = ChainExecutor.chain32(chain);
        for (TriggerList.Item mark : deliverableMarks(chain, height)) {
            AnchorBody body = AnchorBody.decode(l1.getAnchorRaw(Bytes.wrap(mark.attester()), mark.seq()));
            if (body == null || !live(body)) {
                continue;
            }
            Bytes32 src = ChainExecutor.chain32(mark.attester());
            int from = exec.outboxSize(src, dst, body.prevSeq());
            int to = exec.outboxSize(src, dst, mark.seq());
            if (from < 0 || to < 0 || to < from) {
                return false;
            }
            if (to > from && ChainExecutor.mmrCount(exec.getMmr(src, dst)) < to) {
                return false;
            }
        }
        return true;
    }

    private static boolean live(AnchorBody body) {
        return body.status() == AnchorRecord.Status.CANONICAL || body.status() == AnchorRecord.Status.FINAL;
    }

    /** {@link TriggerList#DELIVER} items written for {@code chain} at {@code height}. */
    public List<TriggerList.Item> deliverableMarks(Bytes chain, long height) {
        List<TriggerList.Item> out = new ArrayList<>();
        byte[] want = chain.toArray();
        for (TriggerList.Item item : TriggerOps.read(l1, height)) {
            if (item.kind() == TriggerList.DELIVER && Arrays.equals(item.chain(), want)) {
                out.add(item);
            }
        }
        return out;
    }
}
