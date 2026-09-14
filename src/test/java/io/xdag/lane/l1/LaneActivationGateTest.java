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

package io.xdag.lane.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.lane.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.lane.ext.LaneBlockBuilder;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class LaneActivationGateTest extends LaneL1TestBase {

    @Test
    public void belowActivationNothingIsRecordedAndValueSettlesLikeAPlainTransfer() {
        config.getLaneSpec().setLaneActivationHeight(Long.MAX_VALUE);
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }

        LaneBlockBuilder.Built deploy = deployNewLane(payload(2_000, 31), payload(10, 32));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        assertEquals(1, laneStore.sortedKeys().size());
        assertFalse(laneStore.hasLane(laneId));

        // a CALL-shaped block: 1 XDAG to the vault address, header fee 0.1 -> recipient gets 1 - (0.1 + MIN_GAS) = 0.8
        LaneBlockBuilder.Built callShaped = call(laneId, Bytes.random(20), payload(20, 33), FEE);
        importBuilt(callShaped);
        mineMain(List.of(hashLow(callShaped.block())));
        confirm(callShaped.block());
        assertEquals(XAmount.of(800, XUnit.MILLI_XDAG), balanceOf(laneId));

        // a plain transfer of the same shape moves exactly the same amount
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, to, ONE_XDAG, nextNonce()).toBytes()));
        assertImported(plain);
        mineMain(List.of(hashLow(plain)));
        confirm(plain);
        assertEquals(XAmount.of(1600, XUnit.MILLI_XDAG), balanceOf(laneId));

        assertEquals(1, laneStore.sortedKeys().size());
        assertEquals(UInt64.valueOf(3), addressStore.getExecutedNonceNum(poolKey.toAddress().toArray()));
    }

    /**
     * Same activation predicate ({@code height >= spec.getLaneActivationHeight()}), but here the
     * height is reachable rather than {@code Long.MAX_VALUE}: a DEPLOY that confirms below the
     * configured activation height must stay unrecorded, and the very next DEPLOY, which confirms
     * exactly at that height, must be recorded — pinning the gate open at exactly the configured
     * height, not one block early or late.
     *
     * <p>The exact confirming heights below are not guessed: in this fixture's fake-PoW chain
     * (verified empirically against {@link LaneL1TestBase#mineMain}), the main block produced by the
     * k-th {@code mineMain()} call made in a test (0-indexed) is confirmed at main height k + 2, once
     * exactly one further {@code mineMain()} call has run afterward ({@code checkNewMain}'s constant
     * one-block confirmation lag). {@code confirm(...)} supplies exactly that one further call here
     * (the target block is not yet applied when it returns from being mined), so every height below
     * follows deterministically from the call index alone; {@link #heightOf} is still used to assert
     * it rather than trusting the arithmetic blindly.
     */
    @Test
    public void gateOpensAtExactlyTheConfiguredHeight() {
        // A little runway so the boundary under test is not right at genesis (calls 0..4).
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        int kBefore = 5; // this test's 6th mineMain() call
        long expectedHeightBefore = kBefore + 2L;
        // The after-DEPLOY's own mineMain() call is kBefore + 2 (one for confirm()'s before-filler,
        // one for the after-DEPLOY itself), so it confirms at (kBefore + 2) + 2 == expectedHeightBefore + 2.
        long h = expectedHeightBefore + 2;
        config.getLaneSpec().setLaneActivationHeight(h);

        LaneBlockBuilder.Built before = deployNewLane(payload(2_000, 51), payload(10, 52));
        importBuilt(before);
        Block mBefore = mineMain(List.of(hashLow(before.block()))); // call kBefore
        confirm(before.block()); // call kBefore + 1 (filler)
        long heightBefore = heightOf(mBefore);
        assertEquals("confirming height did not match the predicted call-index arithmetic",
                expectedHeightBefore, heightBefore);
        assertTrue(heightBefore < h);
        Bytes laneIdBefore = LaneIds.laneIdOf(before.block().getHash());
        assertFalse("a DEPLOY confirming below the activation height must not register a lane",
                laneStore.hasLane(laneIdBefore));
        assertEquals("nothing but the schema META key should exist below activation",
                1, laneStore.sortedKeys().size());

        LaneBlockBuilder.Built after = deployNewLane(payload(2_000, 53), payload(10, 54));
        importBuilt(after);
        Block mAfter = mineMain(List.of(hashLow(after.block()))); // call kBefore + 2
        confirm(after.block()); // call kBefore + 3 (filler)
        long heightAfter = heightOf(mAfter);
        assertEquals("expected the post-activation DEPLOY to confirm at exactly the activation height",
                h, heightAfter);
        Bytes laneIdAfter = LaneIds.laneIdOf(after.block().getHash());
        assertTrue("a DEPLOY confirming at or above the activation height must register a lane",
                laneStore.hasLane(laneIdAfter));
        assertEquals(heightAfter, laneStore.getLane(laneIdAfter).createdHeight());
        assertTrue("sortedKeys must grow only once the gate opens",
                laneStore.sortedKeys().size() > 1);
    }
}
