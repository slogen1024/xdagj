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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.lane.ext.ExtKind;
import io.xdag.lane.ext.LaneBlockBuilder;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class LaneL1HooksIntegrationTest extends LaneL1TestBase {

    @Test
    public void deployAndCallsAreRecordedInDfsOrderAfterConfirmation() {
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }
        assertTrue(blockchain.getXdagStats().nmain >= 8);

        Bytes wasm = payload(100_000, 42);
        LaneBlockBuilder.Built deploy = deployNewLane(wasm, payload(50, 1));
        assertEquals(285, deploy.chunks().size());
        importBuilt(deploy);
        Block mDeploy = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());

        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());
        long hDeploy = heightOf(mDeploy);
        LaneRecord lane = laneStore.getLane(laneId);
        assertNotNull("lane registered", lane);
        assertEquals(hDeploy, lane.createdHeight());
        assertEquals(1L, lane.contractCount());
        assertEquals(wasm, laneStore.getCode(HashUtils.sha256(wasm)));
        assertEquals(1L, laneStore.getCodeRefCount(HashUtils.sha256(wasm)));
        assertEquals(List.of(new InputRef(laneId, hDeploy, 0)), laneStore.getReverse(deploy.block().getHash()));
        InputRecord deployInput = laneStore.getInput(laneId, hDeploy, 0);
        assertEquals(ExtKind.DEPLOY, deployInput.kind());
        assertEquals(InputStatus.OK, deployInput.status());
        assertEquals(contract, deployInput.contract());
        assertEquals(1L, laneStore.getCallCount(laneId, hDeploy));

        LaneBlockBuilder.Built ok = call(laneId, contract, payload(20, 2), FEE);
        LaneBlockBuilder.Built badContract = call(laneId, Bytes.random(20), payload(20, 3), FEE);
        LaneBlockBuilder.Built lowFee = call(laneId, contract, payload(1000, 4), XAmount.of(20, XUnit.MILLI_XDAG));
        LaneBlockBuilder.Built goodFee = call(laneId, contract, payload(1000, 5), XAmount.of(30, XUnit.MILLI_XDAG));
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address vault = new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, vault, ONE_XDAG, nextNonce()).toBytes()));
        importBuilt(ok);
        importBuilt(badContract);
        importBuilt(lowFee);
        importBuilt(goodFee);
        assertImported(plain);

        Block mCalls = mineMain(List.of(hashLow(ok.block()), hashLow(badContract.block()), hashLow(lowFee.block()),
                hashLow(goodFee.block()), hashLow(plain)));
        confirm(plain);
        long h = heightOf(mCalls);

        assertEquals(5L, laneStore.getCallCount(laneId, h));
        assertEquals(InputStatus.OK, laneStore.getInput(laneId, h, 0).status());
        assertEquals(ok.block().getHash(), laneStore.getInput(laneId, h, 0).blockHash());
        assertEquals(contract, laneStore.getInput(laneId, h, 0).contract());
        assertEquals(InputStatus.INVALID_FORMAT, laneStore.getInput(laneId, h, 1).status());
        assertEquals(badContract.block().getHash(), laneStore.getInput(laneId, h, 1).blockHash());
        assertEquals(InputStatus.INVALID_FEE, laneStore.getInput(laneId, h, 2).status());
        assertEquals(lowFee.block().getHash(), laneStore.getInput(laneId, h, 2).blockHash());
        assertEquals(InputStatus.OK, laneStore.getInput(laneId, h, 3).status());
        assertEquals(goodFee.block().getHash(), laneStore.getInput(laneId, h, 3).blockHash());
        assertEquals(InputStatus.INVALID_FORMAT, laneStore.getInput(laneId, h, 4).status());
        assertEquals(plain.getHash(), laneStore.getInput(laneId, h, 4).blockHash());
        assertNull(laneStore.getInput(laneId, h, 5));

        // Value settled by the unchanged L1 rules: each of the five 1-XDAG deposits credits the vault
        // 1 XDAG - outPutLimit(block), where outPutLimit = max(MIN_GAS, getTxFee/outPutNum),
        // getTxFee = headerFee + MIN_GAS * outputs.size() and MIN_GAS = 0.1 XDAG. OUT chain links
        // count as outputs, so lowFee and goodFee (1000-byte args, one args-chain link each) have
        // two outputs while ok, badContract and plain have one:
        //   ok:          fee 0.1 + 0.1*1 = 0.2 over 1 output -> limit 0.2   -> 0.8
        //   badContract: fee 0.1 + 0.1*1 = 0.2 over 1 output -> limit 0.2   -> 0.8
        //   lowFee:      fee 0.02 + 0.1*2 = 0.22 over 2 outputs -> limit 0.11  -> 0.89
        //   goodFee:     fee 0.03 + 0.1*2 = 0.23 over 2 outputs -> limit 0.115 -> 0.885
        //   plain:       fee 0.1 + 0.1*1 = 0.2 over 1 output -> limit 0.2   -> 0.8
        // 0.8 + 0.8 + 0.89 + 0.885 + 0.8 = 4.175 XDAG.
        assertEquals(XAmount.of(4_175_000_000L, XUnit.NANO_XDAG), balanceOf(laneId));
        assertEquals(UInt64.valueOf(6), addressStore.getExecutedNonceNum(poolKey.toAddress().toArray()));
    }
}
