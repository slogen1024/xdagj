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
package io.xdag.evm.bridge;

import io.xdag.config.spec.EvmSpec;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.evm.GenesisAllocEntry;
import java.math.BigInteger;
import java.util.List;

/**
 * A4 transfer-from-lock (design 2026-08-31 §2): the genesis alloc is a "genesis deposit" — its
 * native equivalent is credited to the bridge lock exactly once per chain lifetime, so every EVM
 * wei (deposit- or alloc-originated) is a claim on locked native and the fee credit always has
 * backing to transfer. Marker-guarded in the ADDRESS column family: an EVM_STATE wipe (which
 * re-runs the EVM-side seedGenesisIfAbsent) must NOT re-run this native credit.
 */
public final class GenesisLockSeeder {

    private GenesisLockSeeder() {
    }

    /**
     * Sum of the alloc entries' wei converted to nano — exact, or throws. Sub-nano dust would make
     * the lock seed smaller than the redeemable wei (a conservation break), so it is rejected here
     * as the runtime backstop; the config-load whole-nano fail-fast (a later A4 task) is the
     * operator-facing guard.
     */
    public static long allocTotalNano(List<GenesisAllocEntry> alloc) {
        BigInteger totalWei = BigInteger.ZERO;
        for (GenesisAllocEntry entry : alloc) {
            totalWei = totalWei.add(entry.balance().getAsBigInteger());
        }
        BigInteger[] divRem = totalWei.divideAndRemainder(BridgeConstants.WEI_PER_NANO);
        if (divRem[1].signum() != 0) {
            throw new ArithmeticException(
                    "alloc total wei is not a whole number of nano: " + totalWei);
        }
        return divRem[0].longValueExact();
    }

    /**
     * Credits the lock with the alloc's native equivalent once (idempotent via the ADDRESS-CF
     * marker). No-op when the bridge is unscheduled (no lock semantics) or the alloc is empty.
     * Returns the seeded nano, 0 when nothing was seeded.
     *
     * <p>The credit is an ADD, not a set: the lock may already hold native balance when the seed
     * runs (pre-activation deposits are plain transfers retained at the lock address, and an
     * upgrade-in-place node has organic lock balance) — overwriting would destroy those funds.
     * The trade-off is a crash window between the balance write and the marker write (two adjacent
     * ADDRESS-CF puts at startup) during which a kill would double-credit on restart; the window is
     * microscopic, and a wiped-store re-sync recovers it.
     */
    public static long seedIfAbsent(AddressStore addressStore, EvmSpec spec) {
        if (spec.getEvmBridgeActivationHeight() == Long.MAX_VALUE
                || spec.getEvmGenesisAlloc().isEmpty()
                || addressStore.isEvmGenesisLockSeeded()) {
            return 0L;
        }
        long nano = allocTotalNano(spec.getEvmGenesisAlloc());
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();
        addressStore.updateBalance(lockKey,
                addressStore.getBalanceByAddress(lockKey).add(XAmount.of(nano)));
        addressStore.markEvmGenesisLockSeeded();
        return nano;
    }
}
