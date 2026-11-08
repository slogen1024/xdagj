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
package io.xdag.chain.l1.handlers;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.WithdrawWindow;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.ClaimVerifier;
import io.xdag.chain.asset.SettlementJournal;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.ClaimExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.core.Block;
import io.xdag.utils.XdagTime;

/**
 * CLAIM. A passing proof writes {@code 0x08} and {@code 0x10} into the processor's batch and moves
 * {@code vault(srcChain) → recipient}. A refusal writes nothing, so the index stays claimable.
 * {@link #onUnapplied} reverses only a journal that is already committed; a block whose apply never
 * landed is a no-op. The processor commits the batch after this returns. The balance move happens
 * first, in AddressStore, which is the same crash window as the rest of {@code applyBlock}.
 */
public final class ClaimHandler implements ChainKindHandler {

    /**
     * Balance move. {@code transfer} returns false when the debit cannot be paid and writes nothing.
     * {@code reverse} undoes a transfer that returned true and throws if the credit can no longer
     * be taken back.
     */
    public interface Settlement {
        boolean transfer(byte[] from20, byte[] to20, long amount, Bytes32 journalKey);

        void reverse(byte[] from20, byte[] to20, long amount, Bytes32 journalKey);
    }

    private final ChainL1Store store;
    private final ChunkChain.RawBlockLookup lookup;
    private final int maxChunks;
    private final Settlement settlement;
    private final WithdrawWindow window;

    public ClaimHandler(ChainL1Store store, ChunkChain.RawBlockLookup lookup, int maxChunks, Settlement settlement) {
        this(store, lookup, maxChunks, settlement, WithdrawWindow.unlimited());
    }

    public ClaimHandler(ChainL1Store store, ChunkChain.RawBlockLookup lookup, int maxChunks, Settlement settlement,
            WithdrawWindow window) {
        this.store = store;
        this.lookup = lookup;
        this.maxChunks = maxChunks;
        this.settlement = settlement;
        this.window = window;
    }

    @Override
    public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
        ClaimExt claim = classified.as(ClaimExt.class);
        byte[] proof = proofOf(block, claim);
        if (proof == null) {
            return;
        }
        settle(claim, proof, block.getHash(), ctx.height(), batch);
    }

    @Override
    public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        undo(block.getHash(), batch);
    }

    public boolean settle(ClaimExt claim, byte[] proof, Bytes32 blockHash, ChainL1Batch batch) {
        return settle(claim, proof, blockHash, 0L, batch);
    }

    public boolean settle(ClaimExt claim, byte[] proof, Bytes32 blockHash, long height, ChainL1Batch batch) {
        AnchorRecord anchor = store.getAnchor(claim.srcChain(), claim.seq());
        ClaimVerifier.Verdict verdict = ClaimVerifier.verify(claim, proof, anchor,
                (dst, index) -> store.getClaimed(claim.srcChain(), Bytes.wrap(dst), index) != null);
        if (!verdict.ok()) {
            return false;
        }
        if (!window.allows(claim.srcChain(), claim.amount(), height)) {
            return false;
        }
        byte[] from = claim.srcChain().toArray();
        byte[] to = claim.recipient().toArray();
        if (!settlement.transfer(from, to, claim.amount(), blockHash)) {
            return false;
        }
        window.note(claim.srcChain(), claim.amount(), height, batch);
        batch.putJournal(blockHash, SettlementJournal.encode(from, to, claim.amount(), from, verdict.dst(),
                claim.index()));
        batch.putClaimed(claim.srcChain(), Bytes.wrap(verdict.dst()), claim.index(), blockHash);
        return true;
    }

    public void undo(Bytes32 blockHash, ChainL1Batch batch) {
        byte[] raw = store.getJournal(blockHash);
        if (raw == null) {
            return;
        }
        SettlementJournal.Record rec = SettlementJournal.decode(raw);
        if (rec == null) {
            throw new IllegalStateException("claim journal corrupt: " + blockHash);
        }
        settlement.reverse(rec.from(), rec.to(), rec.amount(), blockHash);
        window.undo(Bytes.wrap(rec.src()), rec.amount(), batch);
        batch.deleteJournal(blockHash);
        batch.deleteClaimed(Bytes.wrap(rec.src()), Bytes.wrap(rec.dst()), rec.index());
    }

    private byte[] proofOf(Block block, ClaimExt claim) {
        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        var assembled = ChunkChain.assemble(claim.proofHead(), lookup, maxChunks, minEpoch);
        return assembled.isOk() ? assembled.value().toArray() : null;
    }
}
