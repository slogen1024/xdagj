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

    /** Main blocks mined before the boundary under test, so it is not right at genesis. */
    private static final int RUNWAY = 5;

    /**
     * Same activation predicate ({@code height >= spec.getLaneActivationHeight()}), but here the
     * height is reachable rather than {@code Long.MAX_VALUE}: a DEPLOY that confirms at exactly
     * {@code h - 1} must stay unrecorded, and the DEPLOY that confirms at exactly {@code h} must be
     * recorded - pinning the gate open at exactly the configured height, one block early and one
     * block late both being observable (the {@code >} and {@code >= h - 1} mutants of
     * {@code LaneActivation.isActive} each fail here).
     *
     * <p>Heights are chain positions ({@code setMain} assigns {@code nmain + 1} in order), so in this
     * fixture the main block produced by the k-th {@code mineMain()} call of a test (0-indexed, the
     * address block holding height 1) always takes height k + 2 - independent of how many filler
     * calls {@code confirm(...)} has to burn before {@code checkNewMain} promotes it. The two DEPLOY
     * main blocks are therefore mined back to back (calls {@code RUNWAY} and {@code RUNWAY + 1}) so
     * they occupy {@code h - 1} and {@code h} with nothing in between, and one {@code confirm} of the
     * later block settles both. {@link #heightOf} asserts the arithmetic rather than trusting it.
     */
    @Test
    public void gateOpensAtExactlyTheConfiguredHeight() {
        for (int i = 0; i < RUNWAY; i++) {
            mineMain(List.of());
        }
        long h = RUNWAY + 3L; // call RUNWAY -> height RUNWAY + 2 == h - 1; call RUNWAY + 1 -> height h
        config.getLaneSpec().setLaneActivationHeight(h);

        LaneBlockBuilder.Built before = deployNewLane(payload(2_000, 51), payload(10, 52));
        importBuilt(before);
        Block mBefore = mineMain(List.of(hashLow(before.block()))); // call RUNWAY
        LaneBlockBuilder.Built after = deployNewLane(payload(2_000, 53), payload(10, 54));
        importBuilt(after);
        Block mAfter = mineMain(List.of(hashLow(after.block()))); // call RUNWAY + 1
        confirm(after.block()); // settles mBefore (promoted first) and mAfter

        assertEquals("the pre-activation DEPLOY must confirm exactly one height below activation",
                h - 1, heightOf(mBefore));
        assertEquals("the post-activation DEPLOY must confirm at exactly the activation height",
                h, heightOf(mAfter));

        Bytes laneIdBefore = LaneIds.laneIdOf(before.block().getHash());
        Bytes laneIdAfter = LaneIds.laneIdOf(after.block().getHash());
        assertFalse("a DEPLOY confirming one height below activation must not register a lane",
                laneStore.hasLane(laneIdBefore));
        assertTrue("a DEPLOY confirming at the activation height must register a lane",
                laneStore.hasLane(laneIdAfter));
        assertEquals(h, laneStore.getLane(laneIdAfter).createdHeight());
        assertTrue(laneStore.getReverse(before.block().getHash()).isEmpty());
        assertEquals(1, laneStore.getReverse(after.block().getHash()).size());
        assertEquals("only the post-activation DEPLOY may contribute keys beyond META",
                0L, laneStore.getCallCount(laneIdBefore, h - 1));
        assertEquals(1L, laneStore.getCallCount(laneIdAfter, h));
    }
}
