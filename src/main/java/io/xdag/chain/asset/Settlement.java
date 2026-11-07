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
package io.xdag.chain.asset;

import java.util.Arrays;

import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;

/**
 * Paired AddressStore debit/credit. {@code subtractAmount} swallows a short balance and still
 * returns, so a claim cannot call it and then credit the recipient. This checks first and writes
 * both sides only when the debit fits and the credit does not overflow. A transfer onto the same
 * address is a no-op success. The all-balance counter is left alone, matching {@code addAmount}.
 */
public final class Settlement {

    private Settlement() {
    }

    public static boolean move(AddressStore store, byte[] from, byte[] to, long amount) {
        if (store == null || from == null || to == null || from.length != 20 || to.length != 20 || amount < 0) {
            return false;
        }
        XAmount amt = XAmount.of(amount);
        XAmount bal = store.getBalanceByAddress(from);
        if (bal.lessThan(amt)) {
            return false;
        }
        if (Arrays.equals(from, to)) {
            return true;
        }
        XAmount nextFrom;
        XAmount nextTo;
        try {
            nextFrom = bal.subtract(amt);
            nextTo = store.getBalanceByAddress(to).add(amt);
            nextFrom.toXAmount();
            nextTo.toXAmount();
        } catch (ArithmeticException e) {
            return false;
        }
        store.updateBalance(from, nextFrom);
        store.updateBalance(to, nextTo);
        return true;
    }
}
