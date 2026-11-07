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
package io.xdag.chain.asset;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.state.OutboxMap;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.ext.ClaimExt;

/**
 * Pure CLAIM check. A claim pays only when a {@link AnchorRecord.Status#FINAL} anchor exists, its
 * hash is the claim's link[0], the outbox-map proof opens that anchor's {@code outboxMapRoot}, the
 * MMR proof opens the leaf, and the leaf's amount and recipient are the claim's. The per-window
 * withdraw cap is SP3 and is not applied here. An amount with the high bit set is rejected: the
 * L1 balance type is a signed nano value and the whole XDAG supply fits below 2^63 nano.
 */
public final class ClaimVerifier {

    public enum Reason {
        OK, NO_ANCHOR, NOT_FINAL, ANCHOR_MISMATCH, BAD_PROOF, AMOUNT, RECIPIENT, DST, ALREADY_CLAIMED
    }

    public record Verdict(Reason reason, byte[] dst) {
        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    @FunctionalInterface
    public interface Claimed {
        boolean already(byte[] dst, long index);
    }

    private ClaimVerifier() {
    }

    public static Verdict verify(ClaimExt claim, byte[] proof, AnchorRecord anchor, Claimed claimed) {
        if (anchor == null) {
            return fail(Reason.NO_ANCHOR);
        }
        if (anchor.status() != AnchorRecord.Status.FINAL) {
            return fail(Reason.NOT_FINAL);
        }
        if (!anchor.anchorHash().equals(claim.anchor())) {
            return fail(Reason.ANCHOR_MISMATCH);
        }
        if (claim.amount() < 0) {
            return fail(Reason.AMOUNT);
        }
        ClaimProof.Body body = ClaimProof.decode(proof);
        if (body == null) {
            return fail(Reason.BAD_PROOF);
        }
        Bytes32 key = ChainExecutor.chain32(body.dst());
        SparseMerkleTree.Proof smt = SparseMerkleTree.proof(true, body.value(), body.siblings());
        if (!smt.verifies(key, anchor.outboxMapRoot())) {
            return fail(Reason.BAD_PROOF);
        }
        if (body.value().length != OutboxMap.VALUE_BYTES) {
            return fail(Reason.BAD_PROOF);
        }
        ByteBuffer value = ByteBuffer.wrap(body.value());
        byte[] root = new byte[Bytes32.SIZE];
        value.get(root);
        long size = value.getLong();
        if (size != body.mmr().size() || body.mmr().index() != claim.index() || claim.index() < 0) {
            return fail(Reason.BAD_PROOF);
        }
        OutboxEntry entry = OutboxEntry.decode(body.entry());
        if (entry == null || !body.mmr().root(entry.leaf()).equals(Bytes32.wrap(root))) {
            return fail(Reason.BAD_PROOF);
        }
        if (!Arrays.equals(entry.src(), claim.srcChain().toArray()) || entry.index() != claim.index()) {
            return fail(Reason.BAD_PROOF);
        }
        if (!Arrays.equals(entry.dst(), body.dst())) {
            return fail(Reason.DST);
        }
        if (entry.value() != claim.amount()) {
            return fail(Reason.AMOUNT);
        }
        byte[] recipient = claim.recipient().toArray();
        if (claim.toChainVault()) {
            if (!Arrays.equals(entry.dst(), recipient)) {
                return fail(Reason.RECIPIENT);
            }
        } else if (!isZero(entry.dst()) || !Arrays.equals(entry.contract(), recipient)) {
            return fail(Reason.RECIPIENT);
        }
        if (claimed != null && claimed.already(body.dst(), claim.index())) {
            return fail(Reason.ALREADY_CLAIMED);
        }
        return new Verdict(Reason.OK, body.dst().clone());
    }

    private static Verdict fail(Reason reason) {
        return new Verdict(reason, null);
    }

    private static boolean isZero(byte[] raw) {
        for (byte b : raw) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
