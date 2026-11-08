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

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.BondVault;
import io.xdag.chain.anchor.ChallengeRecord;
import io.xdag.chain.anchor.TriggerList;
import io.xdag.chain.anchor.TriggerOps;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.ext.ChallengeExt;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.fraud.Witness;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.core.Block;
import io.xdag.utils.XdagTime;

/**
 * CHALLENGE. Filing writes a pending record and queues it at the current height. The verdict,
 * the slash and the void happen in {@link io.xdag.chain.anchor.HeightTriggers}, not here.
 * A non-canonical anchor is not queued: the deposit is returned immediately.
 */
public final class ChallengeHandler implements ChainKindHandler {

    private final ChainL1Store store;
    private final ChunkChain.RawBlockLookup lookup;
    private final ClaimHandler.Settlement settlement;
    private final long minBond;
    private final int maxWitness;
    private final int maxChunks;

    public ChallengeHandler(ChainL1Store store, ChunkChain.RawBlockLookup lookup, ClaimHandler.Settlement settlement,
            long minBond, int maxWitness, int maxChunks) {
        this.store = store;
        this.lookup = lookup;
        this.settlement = settlement;
        this.minBond = minBond;
        this.maxWitness = maxWitness;
        this.maxChunks = maxChunks;
    }

    @Override
    public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
        ChallengeExt ext = classified.as(ChallengeExt.class);
        long minEpoch = XdagTime.getEpoch(block.getTimestamp()) - 1;
        var assembled = ChunkChain.assemble(ext.witnessHead(), lookup, maxChunks, minEpoch);
        if (!assembled.isOk()) {
            return;
        }
        file(ext, AnchorBody.hashLow(block.getHash()), BondHandler.firstInput(block), BondHandler.paidToVault(block),
                assembled.value().toArray(), ctx.height(), batch);
    }

    @Override
    public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        undo(AnchorBody.hashLow(block.getHash()), batch);
    }

    public boolean file(ChallengeExt ext, Bytes32 challengeHash, byte[] challenger, long paid, byte[] witness,
            long height, ChainL1Batch batch) {
        if (ext == null || challenger == null || challenger.length != 20 || challengeHash == null) {
            return false;
        }
        long minDeposit = minBond / 10;
        if (Long.compareUnsigned(ext.deposit(), minDeposit) < 0 || paid != ext.deposit()) {
            return false;
        }
        Found found = find(ext.anchor());
        if (found == null || found.body.status() != AnchorRecord.Status.CANONICAL) {
            if (!settlement.transfer(BondVault.ADDRESS.toArray(), challenger, paid, challengeHash)) {
                return false;
            }
            batch.putChallenge(challengeHash, new ChallengeRecord(ChallengeRecord.INVALID, ext.inputIndex(),
                    ext.deposit(), challenger, found == null ? new byte[20] : found.body.attester(),
                    found == null ? new byte[20] : found.chain, found == null ? 0L : found.seq, 0L, Bytes32.ZERO,
                    new byte[20]).encode());
            return true;
        }
        if (Long.compareUnsigned(ext.inputIndex(), found.body.inputCount()) >= 0) {
            return false;
        }
        int type = Witness.type(witness);
        if (witness.length > maxWitness || (type != Witness.E1 && type != Witness.E2 && type != Witness.E3)) {
            return false;
        }
        batch.putChallenge(challengeHash, new ChallengeRecord(ChallengeRecord.PENDING, ext.inputIndex(), ext.deposit(),
                challenger, found.body.attester(), found.chain, found.seq, 0L, Bytes32.ZERO, new byte[20]).encode());
        TriggerOps.append(store, batch, height, new TriggerList.Item(TriggerList.CHALLENGE, found.chain, found.seq,
                found.body.attester(), ext.deposit(), 0L, challenger, 0L, challengeHash, 0L, false));
        return true;
    }

    public void undo(Bytes32 challengeHash, ChainL1Batch batch) {
        ChallengeRecord record = ChallengeRecord.decode(store.getChallenge(challengeHash));
        if (record == null) {
            return;
        }
        if (record.status() == ChallengeRecord.INVALID) {
            settlement.reverse(BondVault.ADDRESS.toArray(), record.challenger(), record.deposit(), challengeHash);
            batch.deleteChallenge(challengeHash);
            return;
        }
        if (record.status() != ChallengeRecord.PENDING) {
            throw new IllegalStateException("challenge unwind before its verdict was reversed");
        }
        batch.deleteChallenge(challengeHash);
        // The file height is not on the record. A deferred copy was pulled back by
        // HeightTriggers.unwind before this runs, so the item is on a committed trigger list.
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 9 || key[0] != ChainL1Keys.HEIGHT_TRIGGER) {
                continue;
            }
            long height = ExtCodec.u64(key, 1);
            TriggerOps.removeFirst(store, batch, height, item -> item.kind() == TriggerList.CHALLENGE
                    && challengeHash.equals(item.headHash()));
        }
    }

    private Found find(Bytes32 anchorHash) {
        Found found = null;
        for (byte[] key : store.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            byte[] chain = Arrays.copyOfRange(key, 1, 21);
            long seq = ExtCodec.u64(key, 21);
            AnchorBody body = AnchorBody.decode(store.getAnchorRaw(Bytes.wrap(chain), seq));
            if (body == null || !body.anchorHash().equals(anchorHash)) {
                continue;
            }
            if (body.status() == AnchorRecord.Status.CANONICAL) {
                return new Found(chain, seq, body);
            }
            found = new Found(chain, seq, body);
        }
        return found;
    }

    private record Found(byte[] chain, long seq, AnchorBody body) {
    }
}
