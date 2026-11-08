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
package io.xdag.chain.fraud;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.ChainLedger;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.state.StateTree;

/**
 * Rebuilds the pre-state from the E2 witness rows. A row is the exec-store blob
 * {@code contract20 ‖ u32 userKeyLen ‖ userKey ‖ value}. The rebuilt root has to equal
 * {@code R_prev}; a missing key makes the root differ and the replay stops, which is the
 * unproven-read failure (the deposit goes to the attester).
 */
public final class WitnessBackedStore {

    private WitnessBackedStore() {
    }

    /** {@code null} when a row is not a state blob or its SMT key does not match the blob. */
    public static Bytes32 root(List<Witness.Row> rows) {
        StateTree tree = new StateTree();
        for (Witness.Row row : rows) {
            Decoded decoded = decode(row.blob());
            if (decoded == null) {
                return null;
            }
            Bytes32 expect = ChainLedger.smtKey(decoded.contract(), decoded.userKey());
            if (!expect.equals(row.key())) {
                return null;
            }
            tree.put(row.key(), decoded.value());
        }
        return tree.root();
    }

    public static Bytes32 emptyRoot() {
        return SparseMerkleTree.emptyRoot();
    }

    private static Decoded decode(byte[] blob) {
        if (blob == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        if (buf.remaining() < 20 + Integer.BYTES) {
            return null;
        }
        byte[] contract = new byte[20];
        buf.get(contract);
        int n = buf.getInt();
        if (n < 0 || n > buf.remaining()) {
            return null;
        }
        byte[] userKey = new byte[n];
        buf.get(userKey);
        byte[] value = new byte[buf.remaining()];
        buf.get(value);
        return new Decoded(Bytes.wrap(contract), userKey, value);
    }

    private record Decoded(Bytes contract, byte[] userKey, byte[] value) {
    }
}
