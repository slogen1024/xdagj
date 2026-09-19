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

package io.xdag.core;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * The self-heal at the acceptance rule in {@code BlockchainImpl.applyBlock}: a block whose nonce
 * has run further ahead than the sender's executed count is rejected, AND the sender's
 * issued-nonce counter is deliberately pushed back down onto the executed one, so the address can
 * be used again from the next nonce instead of being wedged behind a number nothing will ever
 * reach.
 *
 * <p>That rollback is the one caller of the counter that LOWERS it, and it shares nothing but a
 * name with the raises every submit path performs. Making the raise monotonic - which is what
 * stops a stale writer dragging the counter back over a nonce already in flight - would silently
 * turn this rollback into a no-op if it were left on the same method, which is why it now has its
 * own: {@code resetTxQuantity}. This test is what keeps the two apart honest.
 */
public class TxNonceRollbackTest extends ChainL1TestBase {

    @Test
    public void aNonceThatRanAheadIsRejectedAndTheCounterHealsBackOntoTheExecutedOne() {
        byte[] sender = poolKey.toAddress().toArray();
        assertEquals(UInt64.ZERO, addressStore.getExecutedNonceNum(sender));

        // A submit was issued nonce 5 for this sender: the counter says 5, nothing has executed.
        addressStore.updateTxQuantity(sender, UInt64.valueOf(5));
        assertEquals(UInt64.valueOf(5), addressStore.getTxQuantity(sender));

        Block ahead = transfer(UInt64.valueOf(5));
        assertImported(ahead);
        mineMain(List.of(hashLow(ahead)));
        settle();

        assertEquals("a block whose nonce ran ahead of the executed count must not execute",
                0, applied(ahead) & BI_APPLIED);
        assertEquals("nothing executed, so the executed counter must not move",
                UInt64.ZERO, addressStore.getExecutedNonceNum(sender));
        assertEquals("the rejection must push the issued-nonce counter back onto the executed one",
                UInt64.ZERO, addressStore.getTxQuantity(sender));

        // And the address is usable again straight away, which is the whole point of the rollback:
        // the next nonce the submit path would hand out is 1, and 1 executes.
        Block healed = transfer(UInt64.ONE);
        assertImported(healed);
        mineMain(List.of(hashLow(healed)));
        settle();

        assertNotEquals("the sender never healed: the next nonce was rejected too",
                0, applied(healed) & BI_APPLIED);
        assertEquals(UInt64.ONE, addressStore.getExecutedNonceNum(sender));
        assertEquals(UInt64.ONE, addressStore.getTxQuantity(sender));
    }

    /** One XDAG from the pool account to an arbitrary address, carrying the given tx nonce. */
    private Block transfer(UInt64 nonce) {
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(Bytes.repeat((byte) 0x11, 20).toArray()), XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, to, ONE_XDAG, nonce).toBytes()));
    }

    /**
     * Confirms whatever the last {@code mineMain} linked. Not {@code confirm()}: that one mines
     * until the block is applied and fails if it never is, and here the first block is supposed to
     * be rejected rather than applied.
     */
    private void settle() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
    }

    private int applied(Block block) {
        return blockchain.getBlockByHash(block.getHashLow(), false).getInfo().getFlags();
    }
}
