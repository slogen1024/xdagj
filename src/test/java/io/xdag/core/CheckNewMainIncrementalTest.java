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
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * The incremental candidate cache of {@code checkNewMain()} must give exactly the (p, i) the full
 * walk gives, after every kind of step: a best block extending the top, an empty main block, a
 * setMain, a fork that overtakes and unwinds, a non-best block. And it must actually cache — the
 * differential check alone is satisfied by a cache that always falls through, so the second test
 * counts the full walks a run of imports costs.
 *
 * <p>In {@code io.xdag.core} so it can reach {@code BlockchainImpl}'s package-private
 * {@code walkCandidate()} / {@code cachedCandidate()} / {@code candidateWalks}; the fixture it
 * extends lives in {@code io.xdag.chain.l1}, whose {@code MockBlockchain} does not inherit those,
 * hence the {@link BlockchainImpl}-typed local in {@link #chain()}.
 */
public class CheckNewMainIncrementalTest extends ChainL1TestBase {

    private ECKeyPair senderKey;
    private Address from;
    private long nonce = 1;

    /** Package-private members of BlockchainImpl are not inherited by the fixture's subclass. */
    private BlockchainImpl chain() {
        return blockchain;
    }

    private void fundedSender() {
        senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        addressStore.updateBalance(sender.toArray(), XAmount.of(10_000, XUnit.XDAG));
        from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
    }

    /** A transaction between two addresses: it links no block, so it never becomes the top. */
    private Block transfer() {
        Address to = new Address(BytesUtils.arrayToByte32(ECKeyPair.generate().toAddress().toArray()), XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, senderKey, txTime() + nonce, from, to, ONE_XDAG, UInt64.valueOf(nonce++)).toBytes()));
    }

    private void assertSameCandidate(String step) {
        BlockchainImpl chain = chain();
        BlockchainImpl.MainCandidate full;
        BlockchainImpl.MainCandidate cached;
        // cachedCandidate()'s contract is "callers hold this monitor"; checkNewMain does.
        synchronized (chain) {
            full = chain.walkCandidate();
            cached = chain.cachedCandidate();
        }
        assertEquals(step + ": count", full.count(), cached.count());
        assertEquals(step + ": candidate", full.hashLow(), cached.hashLow());
    }

    @Test
    public void cachedCandidateEqualsTheFullWalkAfterEveryStep() {
        fundedSender();
        Random r = new Random(7L);
        List<Block> mains = new ArrayList<>();
        int overtook = 0;
        int stayedBehind = 0;
        for (int i = 0; i < 4; i++) {
            mains.add(mineMain(List.of()));
            assertSameCandidate("warm-up main " + i);
        }
        for (int step = 0; step < 60; step++) {
            int pick = r.nextInt(10);
            if (pick < 5) {
                Block t = transfer();
                assertImported(t);                       // top unchanged: the equal-top cache hit
                assertSameCandidate("transfer " + step);
            } else if (pick < 8) {
                mains.add(mineMain(List.of()));          // may setMain inside checkMain(): version bump
                assertSameCandidate("main " + step);
            } else {
                // Fork below the last two mains and let the branch try to overtake: unwind + re-flag.
                Block forkPoint = mains.get(Math.max(0, mains.size() - 3));
                long resumeTime = generateTime;
                long t = generateTime - 64000L * 2;
                rewindTo(forkPoint, t);
                for (int k = 0; k < 6; k++) {
                    Block mined = mineMain(List.of(), false);
                    // mineMain() does not hand back the ImportResult, but the chain top does: it is
                    // the mined block exactly when the import was IMPORTED_BEST.
                    if (Arrays.equals(hashLow(mined).toArray(), blockchain.getXdagTopStatus().getTop())) {
                        overtook++;
                    } else {
                        stayedBehind++;
                    }
                    assertSameCandidate("fork main " + step + "." + k);
                }
                Block top = blockchain.getBlockByHash(Bytes32.wrap(blockchain.getXdagTopStatus().getTop()), false);
                mains.add(top);
                // A branch that did not overtake leaves the fixture's mining cursor off the real
                // top; point it back at whatever won, at a timestamp past both branches.
                rewindTo(top, Math.max(generateTime, resumeTime));
            }
        }
        // Both fork outcomes have to stay covered, or the reorg half of this test is fiction.
        assertTrue("no fork block was ever IMPORTED_BEST", overtook > 0);
        assertTrue("no fork block was ever IMPORTED_NOT_BEST", stayedBehind > 0);
    }

    /**
     * The cache has to cache. Everything the differential test asserts is also true of a
     * {@code cachedCandidate()} that walks every time, so this one counts the walks instead.
     *
     * <p>Two shapes, both bounded well below one walk per {@code cachedCandidate()} call:
     *
     * <ul>
     * <li>non-best imports — the top never moves, so after the first call every one is an equal-top
     * hit, however long the run is;
     * <li>best imports that move the top by one block. Each {@link #mineMain} here is two
     * {@code checkNewMain} calls (one inside {@code tryToConnect}, one from {@code checkMain}) and
     * exactly one of them is now a one-step hit, so N mains cost at most N walks instead of 2N.
     * They cannot cost fewer: a main block that arrives at the tip both gets flagged
     * {@code BI_MAIN_CHAIN} AND makes the previous candidate confirmable, so the following call
     * sees two flag changes, one of them on a block that is not the top, and conservatively walks.
     * A run of best imports with only one flag change each is not constructible in this chain:
     * difficulty accumulates only across epochs, and a candidate an epoch back is always older
     * than the two seconds {@code setMain} waits for.
     * </ul>
     */
    @Test
    public void runsOfImportsDoNotCostAWalkPerCall() {
        fundedSender();
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }

        long before = chain().candidateWalks;
        for (int i = 0; i < 10; i++) {
            assertImported(transfer());
        }
        long nonBestWalks = chain().candidateWalks - before;
        assertSameCandidate("after the non-best run");

        before = chain().candidateWalks;
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }
        long bestWalks = chain().candidateWalks - before;
        assertSameCandidate("after the best run");

        // Without the one-step path the second figure is 19: every one of the 20 calls but the
        // first walks. Reported as one comparison so a regression prints both numbers.
        assertEquals("[walks over 10 non-best imports (<=1), walks over 10 best imports (<=10)]",
                "[ok, ok]",
                "[" + (nonBestWalks <= 1 ? "ok" : nonBestWalks) + ", " + (bestWalks <= 10 ? "ok" : bestWalks) + "]");
    }
}
