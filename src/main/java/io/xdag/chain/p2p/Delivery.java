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
package io.xdag.chain.p2p;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.net.message.chain.ChainProofReplyMessage;

/**
 * A target chain does not execute a height whose source segment it could not fetch. HOLD is the
 * liveness stall (retry). REJECT is a body that arrived and does not prove the root the anchor
 * already committed to. Neither one is a consensus input.
 */
public final class Delivery {

    public enum Status {
        READY, HOLD, REJECT
    }

    private Delivery() {
    }

    public static Status segment(ChainProofReplyMessage reply, Bytes32 expectedMmrRoot) {
        if (reply == null || reply.absent() || reply.oversize()) {
            return Status.HOLD;
        }
        try {
            ProofBodies.Segment segment = ProofBodies.decodeSegment(reply.proof());
            if (!segment.verifies() || !segment.mmrRoot().equals(expectedMmrRoot)) {
                return Status.REJECT;
            }
            return Status.READY;
        } catch (RuntimeException e) {
            return Status.REJECT;
        }
    }
}
