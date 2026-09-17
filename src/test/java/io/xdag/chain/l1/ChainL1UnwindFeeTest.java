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
import static io.xdag.config.Constants.MIN_GAS;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * Regression for the L1 unwind fee asymmetry that chain blocks exposed.
 *
 * <p>{@code applyBlock} credits every {@code XDAG_FIELD_OUTPUT} address link {@code amount - L}
 * with {@code L = outPutLimit(block) = max(MIN_GAS, getTxFee / outputs)}, where {@code outputs}
 * counts OUTPUT address links <em>and</em> {@code XDAG_FIELD_OUT} block links, and persists
 * {@code info.fee = k * L} for the {@code k} OUTPUT address links. {@code unApplyBlock} used to
 * debit {@code amount - fee / outputs = amount - k * L / (k + m)}, which only equals the credit
 * when the block has no OUT block links ({@code m = 0}, every legacy wallet transfer). A chain
 * DEPLOY (self OUTPUT + code-chain OUT) and a chunked CALL (vault OUTPUT + args-chain OUT) have
 * {@code k = m = 1}, so a reorg over-debited each by {@code L / 2}; when that drove a vault below
 * zero the debit was silently skipped. Either way the balances after unwind no longer matched the
 * balances before apply.
 *
 * <p>The exact reversal is {@code amount - fee / k}: the persisted fee is the ground truth of what
 * apply charged, and {@code fee / k} is exact in nano. This test pins both shapes: the chain
 * blocks ({@code m = 1}) must unwind to the exact pre-transaction balances, and a plain transfer
 * ({@code m = 0}) must keep unwinding byte-identically to before.
 */
public class ChainL1UnwindFeeTest extends ChainL1TestBase {

    private Block forkPoint;
    private long forkTime;
    private long nmainAtFork;

    @Test
    public void chainBlocksWithChunkChainLinksUnwindToTheExactPreTransactionBalances() {
        // The fixture funds poolKey with 1000 XDAG; mining never touches it (the coinbase link
        // carries no amount), so the sender's balance only moves through the blocks made here.
        Bytes sender = poolKey.toAddress();
        XAmount before = balanceOf(sender);
        assertEquals(XAmount.of(1000, XUnit.XDAG), before);
        mineForkPoint();

        // DEPLOY: k = 1 (the sender's own OUTPUT of `self`), m = 1 (the code-chain OUT link).
        // 600 bytes of wasm is two chunks -> header fee 0.02 XDAG; getTxFee = 0.02 + 0.1 * 2 over
        // two outputs -> L = max(0.1, 0.11) = 0.11 XDAG. The sender nets exactly -L.
        Bytes wasm = payload(600, 31);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, payload(10, 32));
        assertEquals(2, deploy.chunks().size());
        assertEquals(1, deploy.chainLinks());
        XAmount deployHeaderFee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), 2);
        XAmount deployFee = perOutputFee(deployHeaderFee, 2);
        assertEquals(XAmount.of(110, XUnit.MILLI_XDAG), deployFee);
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());

        // CALL: k = 1 (1 XDAG OUTPUT into the vault), m = 1 (the args-chain OUT link for 1000
        // bytes of args). getTxFee = 0.1 + 0.1 * 2 over two outputs -> L = 0.15 XDAG: the sender
        // pays the full 1 XDAG, the vault receives 0.85.
        ChainBlockBuilder.Built call = call(chainId, contract, payload(1000, 33), FEE);
        assertEquals(1, call.chainLinks());
        XAmount callFee = perOutputFee(FEE, 2);
        assertEquals(XAmount.of(150, XUnit.MILLI_XDAG), callFee);
        importBuilt(call);
        mineMain(List.of(hashLow(call.block())));
        confirm(call.block());

        // What apply charged: the persisted fee is k * L with k = 1, and the balances moved by L.
        assertEquals(deployFee, feeOf(deploy.block()));
        assertEquals(callFee, feeOf(call.block()));
        assertEquals(before.subtract(deployFee).subtract(ONE_XDAG), balanceOf(sender));
        assertEquals(ONE_XDAG.subtract(callFee), balanceOf(chainId));

        reorgAwayEverythingAboveTheForkPoint();

        // Unwind reverses exactly what apply charged. Before the fix the sender ended L / 2 =
        // 0.055 XDAG short (its own DEPLOY output was over-debited) and the vault kept its
        // 0.85 XDAG (the CALL's over-debit of 0.925 would have gone below zero and was skipped).
        assertEquals("[sender, vault] balances after the reorg",
                List.of(before, XAmount.ZERO), List.of(balanceOf(sender), balanceOf(chainId)));
    }

    @Test
    public void plainTransferStillUnwindsByteIdentically() {
        // m = 0: the legacy wallet shape, where fee / outputs already equalled fee / k.
        ECKeyPair senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        Bytes recipient = ECKeyPair.generate().toAddress();
        XAmount before = XAmount.of(500, XUnit.XDAG);
        addressStore.updateBalance(sender.toArray(), before);
        mineForkPoint();

        Address from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(recipient.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, senderKey, txTime(), from, to, ONE_XDAG, UInt64.ONE).toBytes()));
        assertImported(plain);
        mineMain(List.of(hashLow(plain)));
        confirm(plain);

        // getTxFee = 0.1 + 0.1 * 1 over one output -> L = 0.2 XDAG.
        XAmount plainFee = perOutputFee(XAmount.of(100, XUnit.MILLI_XDAG), 1);
        assertEquals(XAmount.of(200, XUnit.MILLI_XDAG), plainFee);
        assertEquals(plainFee, feeOf(plain));
        assertEquals(before.subtract(ONE_XDAG), balanceOf(sender));
        assertEquals(ONE_XDAG.subtract(plainFee), balanceOf(recipient));

        reorgAwayEverythingAboveTheForkPoint();

        assertEquals("[sender, recipient] balances after the reorg",
                List.of(before, XAmount.ZERO), List.of(balanceOf(sender), balanceOf(recipient)));
    }

    /** {@code outPutLimit(block)} as applyBlock computes it: {@code max(MIN_GAS, (headerFee + MIN_GAS * outputs) / outputs)}. */
    private static XAmount perOutputFee(XAmount headerFee, int outputs) {
        XAmount share = headerFee.add(MIN_GAS.multiply(outputs)).divide(outputs);
        return share.compareTo(MIN_GAS) < 0 ? MIN_GAS : share;
    }

    /** The fee applyBlock persisted for the block. */
    private XAmount feeOf(Block b) {
        Block stored = blockchain.getBlockByHash(b.getHashLow(), false);
        assertNotNull("block was never stored: " + b.getHashLow(), stored);
        return stored.getInfo().getFee();
    }

    /** A few empty mains, then the main the competing branch forks from. */
    private void mineForkPoint() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        forkPoint = mineMain(List.of());
        forkTime = generateTime;
        nmainAtFork = blockchain.getXdagStats().nmain;
    }

    /**
     * Mines an empty competing branch from the fork point until it is the top. Branch A above the
     * fork point has N mains of weight below 2^47 each; 2N + 2 branch mains of weight at least
     * 2^46 each are strictly heavier (the same argument as {@link ChainL1UnwindTest}). Once the top
     * flips every old-branch main is unset, so every block they confirmed has been unapplied and
     * nothing was re-applied in its place.
     */
    private void reorgAwayEverythingAboveTheForkPoint() {
        long branchA = blockchain.getXdagStats().nmain - nmainAtFork;
        rewindTo(forkPoint, forkTime);
        Block last = null;
        for (long i = 0; i < 2 * branchA + 2; i++) {
            last = mineMain(List.of(), false);
        }
        assertNotNull(last);
        assertArrayEquals("fork branch did not overtake", hashLow(last).toArray(), blockchain.getXdagTopStatus().getTop());
    }
}
