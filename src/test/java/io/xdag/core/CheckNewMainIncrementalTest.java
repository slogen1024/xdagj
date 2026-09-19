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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * The incremental candidate cache of {@code checkNewMain()} must give exactly the (p, i) the full
 * walk gives, after every kind of step: a best block extending the top, an empty main block, a
 * setMain, a fork that overtakes and unwinds, a non-best block.
 *
 * <p>In {@code io.xdag.core} so it can reach {@code BlockchainImpl}'s package-private
 * {@code walkCandidate()} / {@code cachedCandidate()}; the fixture it extends lives in
 * {@code io.xdag.chain.l1}, whose {@code MockBlockchain} does not inherit those, hence the
 * {@link BlockchainImpl}-typed local in {@link #assertSameCandidate}.
 */
public class CheckNewMainIncrementalTest extends ChainL1TestBase {

    private ECKeyPair senderKey;
    private Address from;
    private long nonce = 1;

    private Block transfer() {
        Address to = new Address(BytesUtils.arrayToByte32(ECKeyPair.generate().toAddress().toArray()), XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, senderKey, txTime() + nonce, from, to, ONE_XDAG, UInt64.valueOf(nonce++)).toBytes()));
    }

    private void assertSameCandidate(String step) {
        BlockchainImpl chain = blockchain;
        BlockchainImpl.MainCandidate full = chain.walkCandidate();
        BlockchainImpl.MainCandidate cached = chain.cachedCandidate();
        assertEquals(step + ": count", full.count(), cached.count());
        assertEquals(step + ": candidate", full.hashLow(), cached.hashLow());
    }

    @Test
    public void cachedCandidateEqualsTheFullWalkAfterEveryStep() {
        senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        addressStore.updateBalance(sender.toArray(), XAmount.of(10_000, XUnit.XDAG));
        from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
        Random r = new Random(7L);
        List<Block> mains = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mains.add(mineMain(List.of()));
            assertSameCandidate("warm-up main " + i);
        }
        for (int step = 0; step < 60; step++) {
            int pick = r.nextInt(10);
            if (pick < 5) {
                Block t = transfer();
                assertImported(t);                       // extends the top: the O(1) path
                assertSameCandidate("transfer " + step);
            } else if (pick < 8) {
                mains.add(mineMain(List.of()));          // may setMain inside checkMain(): version bump
                assertSameCandidate("main " + step);
            } else {
                // Fork below the last two mains and let the branch overtake: unwind + re-flag.
                Block forkPoint = mains.get(Math.max(0, mains.size() - 3));
                long resumeTime = generateTime;
                long t = generateTime - 64000L * 2;
                rewindTo(forkPoint, t);
                for (int k = 0; k < 6; k++) {
                    mineMain(List.of(), false);
                    assertSameCandidate("fork main " + step + "." + k);
                }
                Block top = blockchain.getBlockByHash(Bytes32.wrap(blockchain.getXdagTopStatus().getTop()), false);
                mains.add(top);
                // A branch that did not overtake leaves the fixture's mining cursor off the real
                // top; point it back at whatever won, at a timestamp past both branches.
                rewindTo(top, Math.max(generateTime, resumeTime));
            }
        }
    }
}
