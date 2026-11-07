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

/**
 * I1 arithmetic. {@code outboundInFlight} is outbox value the execution already debited and no
 * CLAIM has paid yet, including value whose anchor is not final yet — the chain balance has
 * already moved, so leaving it out of the sum would break the identity. What a CLAIM may release
 * is still bounded by a final anchor: {@link ClaimVerifier} refuses anything else. {@code
 * inboundInFlight} is value a destination chain has already credited for a {@code send} whose
 * {@code CLAIM} (flag bit0) has not landed on L1.
 */
public final class VaultLedger {

    private VaultLedger() {
    }

    public static long net(long balances, long outboundInFlight, long inboundInFlight) {
        return Math.subtractExact(Math.addExact(balances, outboundInFlight), inboundInFlight);
    }

    public static boolean holds(long balances, long outboundInFlight, long inboundInFlight, long vault) {
        try {
            return net(balances, outboundInFlight, inboundInFlight) == vault;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
