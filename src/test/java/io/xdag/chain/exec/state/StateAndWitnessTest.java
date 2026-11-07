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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.exec.wasm.GasSchedule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class StateAndWitnessTest {

    @Test
    public void insertionOrderDoesNotChangeTheRoot() {
        List<SparseMerkleTree.Kv> kvs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            kvs.add(new SparseMerkleTree.Kv(id(i + 1), new byte[] {(byte) i, 2}));
        }
        StateTree forward = new StateTree();
        StateTree backward = new StateTree();
        for (SparseMerkleTree.Kv kv : kvs) {
            forward.put(kv.key(), kv.value());
        }
        List<SparseMerkleTree.Kv> reversed = new ArrayList<>(kvs);
        Collections.reverse(reversed);
        for (SparseMerkleTree.Kv kv : reversed) {
            backward.put(kv.key(), kv.value());
        }
        assertEquals(forward.root(), backward.root());
        assertEquals(forward.root(), SparseMerkleTree.root(kvs));
        forward.remove(id(3));
        assertFalse(forward.proof(id(3)).present());
        assertTrue(forward.proof(id(3)).verifies(id(3), forward.root()));
        assertTrue(forward.proof(id(1)).verifies(id(1), forward.root()));
    }

    @Test
    public void outboxValueIsMmrRootAndSize() {
        OutboxMap map = new OutboxMap();
        map.put(id(2), id(9), 4);
        assertEquals(4L, map.get(id(2)).size());
        assertEquals(id(9), map.get(id(2)).mmrRoot());
        assertNull(map.get(id(1)));
        assertTrue(map.proof(id(2)).verifies(id(2), map.root()));
        assertTrue(map.proof(id(1)).verifies(id(1), map.root()));
        assertFalse(map.proof(id(1)).present());
    }

    @Test
    public void witnessBytesStayInsideTheGasBound() {
        Random random = new Random(20261107L);
        StateTree tree = new StateTree();
        for (int i = 0; i < 6; i++) {
            tree.put(id(i + 1), new byte[] {(byte) i, (byte) random.nextInt()});
        }
        WitnessRecorder recorder = new WitnessRecorder();
        Set<Bytes32> seen = new HashSet<>();
        long gas = 0;
        int expected = 0;
        for (int n = 0; n < 40; n++) {
            Bytes32 key = id(1 + random.nextInt(10));
            boolean write = random.nextBoolean();
            byte[] pre = tree.get(key);
            SparseMerkleTree.Proof proof = tree.proof(key);
            if (write) {
                recorder.write(key, pre, proof);
            } else {
                recorder.read(key, pre, proof);
            }
            if (seen.add(key)) {
                int len = pre == null ? 0 : pre.length;
                int siblings = SparseMerkleTree.nonEmptySiblings(proof);
                int bytes = GasSchedule.witnessBytes(len, siblings);
                expected += bytes;
                gas += GasSchedule.witnessGas(len, siblings);
            }
            if (write) {
                if (pre != null && random.nextBoolean()) {
                    tree.remove(key);
                } else {
                    byte[] next = new byte[1 + random.nextInt(8)];
                    random.nextBytes(next);
                    tree.put(key, next);
                }
            }
        }
        assertEquals(expected, recorder.witnessBytes());
        assertTrue(recorder.witnessBytes() <= WitnessRecorder.estimateFromGas(gas));
        assertTrue(recorder.witnessBytes() <= WitnessRecorder.estimateFromGas(gas + 5_000));
        assertEquals(GasSchedule.MAX_WITNESS_BYTES,
                WitnessRecorder.estimateFromGas(GasSchedule.MAX_CALL_GAS));
    }

    @Test
    public void aRepeatedKeyIsBilledOnce() {
        StateTree tree = new StateTree();
        tree.put(id(1), new byte[] {1, 2, 3});
        WitnessRecorder recorder = new WitnessRecorder();
        recorder.read(id(1), tree.get(id(1)), tree.proof(id(1)));
        int once = recorder.witnessBytes();
        recorder.read(id(1), tree.get(id(1)), tree.proof(id(1)));
        recorder.write(id(1), tree.get(id(1)), tree.proof(id(1)));
        assertEquals(once, recorder.witnessBytes());
        assertEquals(3, recorder.touches().size());
        assertArrayEquals(new byte[] {1, 2, 3}, recorder.touches().get(0).value());
    }

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }
}
