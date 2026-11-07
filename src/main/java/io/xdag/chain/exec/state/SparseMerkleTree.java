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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.crypto.hash.HashUtils;

/**
 * 256-level binary sparse Merkle tree, SHA-256. The key is the path from the root: bit 0 is the
 * high bit of {@code key[0]}. An empty subtree at depth {@code d} hashes to {@link #empty(int)},
 * precomputed, so a proof is 256 sibling hashes and nothing else.
 *
 * <p>Domain bytes: {@code 0x00} empty leaf, {@code 0x01 ‖ key ‖ value} occupied leaf,
 * {@code 0x02 ‖ left ‖ right} branch. Insertion order does not change the root.
 */
public final class SparseMerkleTree {

    public static final int DEPTH = 256;

    private static final Bytes32[] EMPTY = new Bytes32[DEPTH + 1];

    static {
        EMPTY[DEPTH] = HashUtils.sha256(Bytes.of((byte) 0x00));
        for (int d = DEPTH - 1; d >= 0; d--) {
            EMPTY[d] = branch(EMPTY[d + 1], EMPTY[d + 1]);
        }
    }

    private SparseMerkleTree() {
    }

    public record Kv(Bytes32 key, byte[] value) {
    }

    /** Sibling at depth {@code d} is the other child of the node at that depth. */
    public record Proof(boolean present, byte[] value, Bytes32[] siblings) {
        public Proof {
            siblings = siblings.clone();
            value = value.clone();
            if (siblings.length != DEPTH) {
                throw new IllegalArgumentException("smt proof width");
            }
        }

        public boolean verifies(Bytes32 key, Bytes32 expectedRoot) {
            return fold(key, present, value, siblings).equals(expectedRoot);
        }
    }

    public static Bytes32 emptyRoot() {
        return EMPTY[0];
    }

    /** Empty-subtree hash at {@code depth} (0 = empty root, 256 = empty leaf). */
    public static Bytes32 empty(int depth) {
        return EMPTY[depth];
    }

    public static Bytes32 root(List<Kv> kvs) {
        List<Kv> sorted = sorted(kvs);
        return walk(sorted, 0, sorted.isEmpty() ? null : sorted.get(0).key(), null, null);
    }

    public static Proof proof(Bytes32 key, List<Kv> kvs) {
        List<Kv> sorted = sorted(kvs);
        Bytes32[] siblings = new Bytes32[DEPTH];
        boolean[] hit = {false};
        walk(sorted, 0, key, siblings, hit);
        byte[] value = new byte[0];
        if (hit[0]) {
            for (Kv kv : sorted) {
                if (kv.key().equals(key)) {
                    value = kv.value();
                    break;
                }
            }
        }
        return new Proof(hit[0], value, siblings);
    }

    /**
     * How many siblings on {@code key}'s path are not an empty subtree. Witness billing
     * ({@code nonEmptySiblings}) reads this and nothing else about the tree.
     */
    public static int nonEmptySiblings(Bytes32 key, List<Kv> kvs) {
        return nonEmptySiblings(proof(key, kvs));
    }

    /** Sibling count of an already-built proof. Witness billing uses this, not a caller-supplied number. */
    public static int nonEmptySiblings(Proof proof) {
        int n = 0;
        Bytes32[] siblings = proof.siblings();
        for (int d = 0; d < DEPTH; d++) {
            if (!siblings[d].equals(EMPTY[d + 1])) {
                n++;
            }
        }
        return n;
    }

    public static Bytes32 fold(Bytes32 key, boolean present, byte[] value, Bytes32[] siblings) {
        Bytes32 h = present ? leaf(key, value) : EMPTY[DEPTH];
        for (int d = DEPTH - 1; d >= 0; d--) {
            h = bit(key, d) == 0 ? branch(h, siblings[d]) : branch(siblings[d], h);
        }
        return h;
    }

    private static Bytes32 walk(List<Kv> slice, int depth, Bytes32 target, Bytes32[] siblings, boolean[] hit) {
        if (slice.isEmpty()) {
            if (siblings != null) {
                for (int d = depth; d < DEPTH; d++) {
                    siblings[d] = EMPTY[d + 1];
                }
            }
            return EMPTY[depth];
        }
        if (depth == DEPTH) {
            if (hit != null && slice.get(0).key().equals(target)) {
                hit[0] = true;
            }
            return leaf(slice.get(0).key(), slice.get(0).value());
        }
        List<Kv> left = new ArrayList<>();
        List<Kv> right = new ArrayList<>();
        for (Kv kv : slice) {
            if (bit(kv.key(), depth) == 0) {
                left.add(kv);
            } else {
                right.add(kv);
            }
        }
        int side = target == null ? 0 : bit(target, depth);
        Bytes32 lh = side == 0 ? walk(left, depth + 1, target, siblings, hit) : hashOnly(left, depth + 1);
        Bytes32 rh = side == 1 ? walk(right, depth + 1, target, siblings, hit) : hashOnly(right, depth + 1);
        if (siblings != null && target != null) {
            siblings[depth] = side == 0 ? rh : lh;
        }
        return branch(lh, rh);
    }

    private static Bytes32 hashOnly(List<Kv> slice, int depth) {
        return walk(slice, depth, null, null, null);
    }

    private static List<Kv> sorted(List<Kv> kvs) {
        List<Kv> sorted = new ArrayList<>(kvs);
        sorted.sort((a, b) -> a.key().compareTo(b.key()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).key().equals(sorted.get(i - 1).key())) {
                throw new IllegalArgumentException("duplicate state key");
            }
        }
        return sorted;
    }

    static int bit(Bytes32 key, int depth) {
        return (key.get(depth / 8) >> (7 - (depth % 8))) & 1;
    }

    static Bytes32 leaf(Bytes32 key, byte[] value) {
        ByteBuffer buf = ByteBuffer.allocate(1 + Bytes32.SIZE + Integer.BYTES + value.length);
        buf.put((byte) 0x01);
        buf.put(key.toArray());
        buf.putInt(value.length);
        buf.put(value);
        return HashUtils.sha256(Bytes.wrap(buf.array()));
    }

    static Bytes32 branch(Bytes32 left, Bytes32 right) {
        byte[] raw = new byte[1 + Bytes32.SIZE + Bytes32.SIZE];
        raw[0] = 0x02;
        System.arraycopy(left.toArray(), 0, raw, 1, Bytes32.SIZE);
        System.arraycopy(right.toArray(), 0, raw, 1 + Bytes32.SIZE, Bytes32.SIZE);
        return HashUtils.sha256(Bytes.wrap(raw));
    }

    /** Used by the proof decoder, which already range-checks the sibling array. */
    public static Proof proof(boolean present, byte[] value, Bytes32[] siblings) {
        if (siblings.length != DEPTH) {
            throw new IllegalArgumentException("smt proof width");
        }
        if (!present && value.length != 0) {
            throw new IllegalArgumentException("absent smt leaf carries a value");
        }
        return new Proof(present, value, Arrays.copyOf(siblings, siblings.length));
    }
}
