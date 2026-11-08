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
package io.xdag.chain.l1.handlers;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.BondAccount;
import io.xdag.chain.anchor.BondVault;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.core.Address;
import io.xdag.core.Block;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

/**
 * BOND. A stake records the OUTPUT paid to {@link BondVault#ADDRESS}; applyBlock already credited
 * the vault. An unbond records a request and queues a payout at {@code height + W}. The bond stays
 * slashable until that height. A second pending unbond is rejected.
 */
public final class BondHandler implements ChainKindHandler {

    private final ChainL1Store store;
    private final long window;

    public BondHandler(ChainL1Store store, long window) {
        this.store = store;
        this.window = window;
    }

    @Override
    public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
        apply(classified.as(BondExt.class), firstInput(block), paidToVault(block), ctx.height(), batch);
    }

    @Override
    public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        undo(classified.as(BondExt.class), firstInput(block), paidToVault(block), batch);
    }

    public boolean apply(BondExt ext, byte[] attester, long paid, long height, ChainL1Batch batch) {
        if (ext == null || attester == null || attester.length != 20) {
            return false;
        }
        Bytes who = Bytes.wrap(attester);
        BondAccount account = BondAccount.decode(store.getBond(who, ext.chainId()));
        if (account == null) {
            account = new BondAccount(0L, 0L, 0L);
        }
        if (!ext.unbond()) {
            if (paid <= 0) {
                return false;
            }
            long next;
            try {
                next = Math.addExact(account.amount(), paid);
            } catch (ArithmeticException e) {
                return false;
            }
            batch.putBond(who, ext.chainId(), new BondAccount(next, account.unbondHeight(), account.unbondAmount()).encode());
            return true;
        }
        if (account.pending() || ext.amount() <= 0 || ext.amount() > account.amount()) {
            return false;
        }
        long due = height + window;
        if (window != 0 && Long.compareUnsigned(due, height) < 0) {
            return false;
        }
        batch.putBond(who, ext.chainId(), new BondAccount(account.amount(), height, ext.amount()).encode());
        TriggerOps.append(store, batch, due, new TriggerList.Item(TriggerList.UNBOND, ext.chainId().toArray(), 0L,
                attester, ext.amount(), 0L, new byte[20], 0L, Bytes32.ZERO, 0L, false));
        return true;
    }

    /**
     * The request height is not on the block. The account stores it, and the trigger sits at
     * {@code request + W}. Undo of a stake subtracts the same OUTPUT the apply added.
     */
    public void undo(BondExt ext, byte[] attester, long paid, ChainL1Batch batch) {
        if (ext == null || attester == null) {
            return;
        }
        Bytes who = Bytes.wrap(attester);
        BondAccount account = BondAccount.decode(store.getBond(who, ext.chainId()));
        if (account == null) {
            return;
        }
        if (!ext.unbond()) {
            if (paid <= 0 || account.amount() < paid) {
                throw new IllegalStateException("bond stake unwind short");
            }
            long left = account.amount() - paid;
            if (left == 0 && !account.pending()) {
                batch.deleteBond(who, ext.chainId());
            } else {
                batch.putBond(who, ext.chainId(), new BondAccount(left, account.unbondHeight(), account.unbondAmount()).encode());
            }
            return;
        }
        if (account.unbondAmount() != ext.amount()) {
            throw new IllegalStateException("bond unbond unwind without its request");
        }
        long due = account.unbondHeight() + window;
        batch.putBond(who, ext.chainId(), new BondAccount(account.amount(), 0L, 0L).encode());
        TriggerOps.removeFirst(store, batch, due, item -> item.kind() == TriggerList.UNBOND && !item.done()
                && Arrays.equals(item.attester(), attester) && Arrays.equals(item.chain(), ext.chainId().toArray())
                && item.amount() == ext.amount());
    }

    static byte[] firstInput(Block block) {
        if (block.getInputs() == null) {
            return null;
        }
        for (Address in : block.getInputs()) {
            if (in.getIsAddress() && in.getType() == XDAG_FIELD_INPUT) {
                return ChainIds.address20(in).toArray();
            }
        }
        return null;
    }

    static long paidToVault(Block block) {
        if (block.getOutputs() == null) {
            return 0L;
        }
        byte[] vault = BondVault.ADDRESS.toArray();
        long sum = 0L;
        for (Address out : block.getOutputs()) {
            if (!out.getIsAddress() || out.getType() != XDAG_FIELD_OUTPUT) {
                continue;
            }
            if (!Arrays.equals(vault, ChainIds.address20(out).toArray())) {
                continue;
            }
            sum += Long.parseLong(out.getAmount().toString());
        }
        return sum;
    }
}
