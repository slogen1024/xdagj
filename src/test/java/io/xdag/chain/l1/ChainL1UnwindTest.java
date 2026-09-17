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

package io.xdag.chain.l1;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.hash.HashUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

/**
 * SP0a principle P3 end to end: a competing branch that overtakes the chain must unwind every chain
 * write the confirmed DEPLOY/CALL made, leaving {@code CHAIN_L1} at exactly its empty state, and the
 * same blocks re-linked on the new branch must reproduce equivalent records at their new heights.
 */
public class ChainL1UnwindTest extends ChainL1TestBase {

    @Test
    public void reorgRemovesChainStateAndReapplyRestoresIt() {
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
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, payload(10, 22));
        importBuilt(deploy);
        Block mDeploy = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());

        ChainBlockBuilder.Built call = call(chainId, contract, payload(20, 23), FEE);
        importBuilt(call);
        Block mCall = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());

        assertTrue(chainStore.hasChain(chainId));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mDeploy)));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mCall)));
        int keysWhenApplied = chainStore.sortedKeys().size();
        assertTrue(keysWhenApplied > 1);
        // The CALL settled 1 XDAG into the vault under the unchanged L1 rules (minus its fee share).
        assertTrue(balanceOf(chainId).greaterThan(XAmount.ZERO));

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

        // unwind of the confirmed main blocks removed every chain record symmetrically
        List<byte[]> leftover = chainStore.sortedKeys();
        assertEquals("chain state left behind after the reorg: " + describeKeys(leftover), 1, leftover.size());
        assertFalse(chainStore.hasChain(chainId));
        assertFalse(chainStore.hasCode(HashUtils.sha256(wasm)));
        assertTrue(chainStore.getReverse(deploy.block().getHash()).isEmpty());
        assertTrue(chainStore.getReverse(call.block().getHash()).isEmpty());
        // The hook order (unapply inside unApplyBlock's BI_APPLIED branch) matches value unwinding:
        // the CALL's settlement into the vault was reversed together with its chain record.
        assertEquals(XAmount.ZERO, balanceOf(chainId));

        // The same blocks re-linked on the new branch produce equivalent records at their new heights.
        // The re-linked DEPLOY/CALL keep their original timestamps, and the chunk-chain age rule compares
        // chunk epochs against the PAYING block, never against the main block, so re-confirmation many
        // epochs later on the new branch is still OK - the OK statuses asserted below prove exactly that.
        Block mDeploy2 = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Block mCall2 = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());
        assertTrue(chainStore.hasChain(chainId));
        assertNotEquals(heightOf(mDeploy), heightOf(mDeploy2));
        assertEquals(heightOf(mDeploy2), chainStore.getChain(chainId).createdHeight());
        assertEquals(1L, chainStore.getChain(chainId).contractCount());
        assertEquals(chainId, chainStore.getContract(contract).chainId());
        assertEquals(1L, chainStore.getCodeRefCount(HashUtils.sha256(wasm)));
        assertEquals(wasm, chainStore.getCode(HashUtils.sha256(wasm)));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mDeploy2)));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mCall2)));
        assertEquals(1, chainStore.getReverse(deploy.block().getHash()).size());
        assertEquals(1, chainStore.getReverse(call.block().getHash()).size());
        assertEquals(InputStatus.OK, chainStore.getInput(chainId, heightOf(mCall2), 0).status());
        assertEquals(contract, chainStore.getInput(chainId, heightOf(mCall2), 0).contract());
        assertEquals(keysWhenApplied, chainStore.sortedKeys().size());
    }

    /** Leftover CHAIN_L1 keys as hex, for diagnosing an unwind asymmetry from the failure message alone. */
    private static String describeKeys(List<byte[]> keys) {
        List<String> out = new ArrayList<>();
        for (byte[] k : keys) {
            out.add(Bytes.wrap(k).toHexString());
        }
        return out.toString();
    }
}
