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
package io.xdag.chain.exec.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.exec.state.SparseMerkleTree.Proof;
import io.xdag.chain.exec.wasm.GasSchedule;

/**
 * One call's read set and write set. A key is billed once, on its first touch, using the proof's
 * own sibling count and the pre-write value length. A later read or write of that key is recorded
 * and does not add bytes.
 *
 * <p>{@link #estimateFromGas(long)} is the §8.3 upper bound: {@code gasUsed / gasPerWitnessByte +
 * 32KiB}. A call whose witness gas was actually charged stays under it.
 */
public final class WitnessRecorder {

    /** The fixed overhead in the §8.3 inequality. */
    public static final int FIXED_OVERHEAD = 32 * 1024;

    public record Touch(Bytes32 key, boolean write, boolean present, byte[] value, Proof proof) {
    }

    private final Map<Bytes32, Integer> billed = new LinkedHashMap<>();
    private final List<Touch> touches = new ArrayList<>();
    private int total;

    public void read(Bytes32 key, byte[] valueOrNull, Proof proof) {
        touch(key, false, valueOrNull, proof);
    }

    /** {@code preValueOrNull} is the value before this write, {@code null} when the key was absent. */
    public void write(Bytes32 key, byte[] preValueOrNull, Proof proof) {
        touch(key, true, preValueOrNull, proof);
    }

    public int witnessBytes() {
        return total;
    }

    public List<Touch> touches() {
        return List.copyOf(touches);
    }

    public static long estimateFromGas(long gasUsed) {
        if (gasUsed < 0) {
            throw new IllegalArgumentException("gasUsed");
        }
        return gasUsed / GasSchedule.GAS_PER_WITNESS_BYTE + FIXED_OVERHEAD;
    }

    private void touch(Bytes32 key, boolean write, byte[] valueOrNull, Proof proof) {
        if (proof == null) {
            throw new IllegalArgumentException("witness proof");
        }
        byte[] value = valueOrNull == null ? null : valueOrNull.clone();
        touches.add(new Touch(key, write, value != null, value == null ? new byte[0] : value, proof));
        if (billed.containsKey(key)) {
            return;
        }
        int bytes = GasSchedule.witnessBytes(value == null ? 0 : value.length,
                SparseMerkleTree.nonEmptySiblings(proof));
        if (total > Integer.MAX_VALUE - bytes) {
            throw new IllegalStateException("witness bytes");
        }
        billed.put(key, bytes);
        total += bytes;
    }
}
