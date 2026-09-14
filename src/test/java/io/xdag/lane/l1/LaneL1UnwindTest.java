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

import static io.xdag.lane.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.lane.ext.LaneBlockBuilder;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

/**
 * SP0a principle P3 end to end: a competing branch that overtakes the chain must unwind every lane
 * write the confirmed DEPLOY/CALL made, leaving {@code LANE_L1} at exactly its empty state, and the
 * same blocks re-linked on the new branch must reproduce equivalent records at their new heights.
 */
public class LaneL1UnwindTest extends LaneL1TestBase {

    @Test
    public void reorgRemovesLaneStateAndReapplyRestoresIt() {
        Block forkPoint = null;
        long forkTime = 0;
        long nmainAtFork = 0;
        for (int i = 0; i < 12; i++) {
            Block m = mineMain(List.of());
            if (i == 7) {
                forkPoint = m;
                forkTime = generateTime;
                nmainAtFork = blockchain.getXdagStats().nmain;
            }
        }
        assertNotNull(forkPoint);

        Bytes wasm = payload(5_000, 21);
        LaneBlockBuilder.Built deploy = deployNewLane(wasm, payload(10, 22));
        importBuilt(deploy);
        Block mDeploy = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());

        LaneBlockBuilder.Built call = call(laneId, contract, payload(20, 23), FEE);
        importBuilt(call);
        Block mCall = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());

        assertTrue(laneStore.hasLane(laneId));
        assertEquals(1L, laneStore.getCallCount(laneId, heightOf(mDeploy)));
        assertEquals(1L, laneStore.getCallCount(laneId, heightOf(mCall)));
        int keysWhenApplied = laneStore.sortedKeys().size();
        assertTrue(keysWhenApplied > 1);
        // The CALL settled 1 XDAG into the vault under the unchanged L1 rules (minus its fee share).
        assertTrue(balanceOf(laneId).greaterThan(XAmount.ZERO));

        // A competing branch from the fork point. Branch A above the fork point has at most 12 blocks of weight
        // < 2^47 each; 24 blocks of weight >= 2^46 each are strictly heavier, so the reorg is deterministic.
        long branchABlocks = blockchain.getXdagStats().nmain - nmainAtFork;
        assertTrue("branch A grew past the 12-block bound the fork weight argument relies on: " + branchABlocks,
                branchABlocks <= 12);
        rewindTo(forkPoint, forkTime);
        Block last = null;
        for (int i = 0; i < 24; i++) {
            last = mineMain(List.of(), false);
        }
        assertNotNull(last);
        assertArrayEquals("fork branch did not overtake", hashLow(last).toArray(), blockchain.getXdagTopStatus().getTop());

        // unwind of the confirmed main blocks removed every lane record symmetrically
        List<byte[]> leftover = laneStore.sortedKeys();
        assertEquals("lane state left behind after the reorg: " + describeKeys(leftover), 1, leftover.size());
        assertFalse(laneStore.hasLane(laneId));
        assertFalse(laneStore.hasCode(HashUtils.sha256(wasm)));
        assertTrue(laneStore.getReverse(deploy.block().getHash()).isEmpty());
        assertTrue(laneStore.getReverse(call.block().getHash()).isEmpty());
        // The hook order (unapply inside unApplyBlock's BI_APPLIED branch) matches value unwinding:
        // the CALL's settlement into the vault was reversed together with its lane record.
        assertEquals(XAmount.ZERO, balanceOf(laneId));

        // The same blocks re-linked on the new branch produce equivalent records at their new heights.
        // The re-linked DEPLOY/CALL keep their original timestamps, and the chunk-chain age rule compares
        // chunk epochs against the PAYING block, never against the main block, so re-confirmation many
        // epochs later on the new branch is still OK - the OK statuses asserted below prove exactly that.
        Block mDeploy2 = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Block mCall2 = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());
        assertTrue(laneStore.hasLane(laneId));
        assertNotEquals(heightOf(mDeploy), heightOf(mDeploy2));
        assertEquals(heightOf(mDeploy2), laneStore.getLane(laneId).createdHeight());
        assertEquals(1L, laneStore.getLane(laneId).contractCount());
        assertEquals(laneId, laneStore.getContract(contract).laneId());
        assertEquals(1L, laneStore.getCodeRefCount(HashUtils.sha256(wasm)));
        assertEquals(wasm, laneStore.getCode(HashUtils.sha256(wasm)));
        assertEquals(1L, laneStore.getCallCount(laneId, heightOf(mDeploy2)));
        assertEquals(1L, laneStore.getCallCount(laneId, heightOf(mCall2)));
        assertEquals(1, laneStore.getReverse(deploy.block().getHash()).size());
        assertEquals(1, laneStore.getReverse(call.block().getHash()).size());
        assertEquals(InputStatus.OK, laneStore.getInput(laneId, heightOf(mCall2), 0).status());
        assertEquals(contract, laneStore.getInput(laneId, heightOf(mCall2), 0).contract());
        assertEquals(keysWhenApplied, laneStore.sortedKeys().size());
    }

    /** Leftover LANE_L1 keys as hex, for diagnosing an unwind asymmetry from the failure message alone. */
    private static String describeKeys(List<byte[]> keys) {
        List<String> out = new ArrayList<>();
        for (byte[] k : keys) {
            out.add(Bytes.wrap(k).toHexString());
        }
        return out.toString();
    }
}
