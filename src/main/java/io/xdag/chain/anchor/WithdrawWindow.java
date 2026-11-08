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
package io.xdag.chain.anchor;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;

import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;

/**
 * Per-chain claim cap. {@code f} is the sum of this chain's bond amounts and every global bond.
 * The window is the claim's confirm height aligned down to {@code W}. A missing head fails closed.
 * {@link #unlimited()} is what the SP2 claim tests use: they never touch {@code 0x05}.
 */
public final class WithdrawWindow {

    private final ChainL1Store store;
    private final long window;

    private WithdrawWindow(ChainL1Store store, long window) {
        this.store = store;
        this.window = window;
    }

    public static WithdrawWindow unlimited() {
        return new WithdrawWindow(null, 0L);
    }

    public static WithdrawWindow of(ChainL1Store store, long window) {
        return new WithdrawWindow(store, window);
    }

    public boolean allows(Bytes chain, long amount, long height) {
        if (store == null) {
            return true;
        }
        if (amount < 0) {
            return false;
        }
        AnchorHead head = AnchorHead.decode(store.getAnchorHead(chain));
        if (head == null) {
            return false;
        }
        long cap = cap(chain);
        long base = align(height);
        long withdrawn = head.windowBase() == base ? head.withdrawn() : 0L;
        long next;
        try {
            next = Math.addExact(withdrawn, amount);
        } catch (ArithmeticException e) {
            return false;
        }
        return Long.compareUnsigned(next, cap) <= 0;
    }

    public void note(Bytes chain, long amount, long height, ChainL1Batch batch) {
        if (store == null) {
            return;
        }
        AnchorHead head = AnchorHead.decode(store.getAnchorHead(chain));
        if (head == null) {
            throw new IllegalStateException("withdraw note without a head");
        }
        long base = align(height);
        long withdrawn = head.windowBase() == base ? head.withdrawn() : 0L;
        batch.putAnchorHead(chain, new AnchorHead(head.seq(), head.anchorHash(), base, withdrawn + amount).encode());
    }

    /** Subtracts {@code amount} from the current window, floored at 0. A window roll between the claim and the undo is lossy. */
    public void undo(Bytes chain, long amount, ChainL1Batch batch) {
        if (store == null) {
            return;
        }
        AnchorHead head = AnchorHead.decode(store.getAnchorHead(chain));
        if (head == null) {
            return;
        }
        long withdrawn = Long.compareUnsigned(head.withdrawn(), amount) < 0 ? 0L : head.withdrawn() - amount;
        batch.putAnchorHead(chain, new AnchorHead(head.seq(), head.anchorHash(), head.windowBase(), withdrawn).encode());
    }

    private long cap(Bytes chain) {
        byte[] want = chain.toArray();
        long sum = 0L;
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 41 || key[0] != ChainL1Keys.BOND) {
                continue;
            }
            byte[] id = Arrays.copyOfRange(key, 21, 41);
            if (!Arrays.equals(id, want) && !BondVault.isGlobal(id)) {
                continue;
            }
            BondAccount account = BondAccount.decode(store.getBond(Bytes.wrap(Arrays.copyOfRange(key, 1, 21)), Bytes.wrap(id)));
            if (account == null || account.amount() < 0) {
                continue;
            }
            try {
                sum = Math.addExact(sum, account.amount());
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
        return sum;
    }

    private long align(long height) {
        if (window <= 0) {
            return height;
        }
        return height - Long.remainderUnsigned(height, window);
    }
}
