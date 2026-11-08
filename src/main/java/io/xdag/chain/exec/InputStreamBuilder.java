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

import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;

/**
 * {@code systemInputs ++ deliveredMessages ++ confirmedBlocks}. System inputs are the anchor
 * rewards confirmed at {@code height}. Delivered message bodies are not expanded: a segment is
 * marked deliverable by a {@link TriggerList#DELIVER} item, readable from {@link #deliverableMarks}.
 * Confirmed blocks are the {@code 0x0C} records {@code 0 .. callCount-1}, including {@code INVALID_*}
 * ones: they occupy an index and the executor writes a status receipt instead of running them.
 */
public final class InputStreamBuilder {

    private final ChainL1Store l1;

    public InputStreamBuilder(ChainL1Store l1) {
        this.l1 = l1;
    }

    public List<ChainInput> inputs(Bytes chain, long height) {
        List<ChainInput> out = new ArrayList<>();
        out.addAll(systemInputs(chain, height));
        out.addAll(deliveredMessages(chain, height));
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
     * Message bodies are not on L1, so this stays empty. {@link #deliverableMarks} is the flag that
     * a source anchor may be consumed at {@code height}.
     */
    public List<ChainInput> deliveredMessages(Bytes chain, long height) {
        return List.of();
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
