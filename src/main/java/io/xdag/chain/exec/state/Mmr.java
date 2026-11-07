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
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.crypto.hash.HashUtils;

/**
 * Append-only Merkle mountain range. Peaks are the set bits of {@code size}, highest first
 * (leftmost mountain first). A single peak's root is the peak itself. Several peaks bag as
 * {@code node(p0, node(p1, …))}. Branch tag {@code 0x03}, so an MMR node is not an SMT node.
 *
 * <p>The list this tree stores is already the leaf hashes ({@code sha256} of an outbox entry, or
 * of an event). An empty range hashes to {@link #emptyRoot()}.
 */
public final class Mmr {

    private static final Bytes32 EMPTY = HashUtils.sha256(Bytes.of((byte) 0x03));

    private final List<Bytes32> leaves = new ArrayList<>();

    public static Bytes32 emptyRoot() {
        return EMPTY;
    }

    public int size() {
        return leaves.size();
    }

    public void append(Bytes32 leaf) {
        leaves.add(leaf);
    }

    public Bytes32 root() {
        if (leaves.isEmpty()) {
            return EMPTY;
        }
        return bag(peaks(leaves));
    }

    public Proof proof(int index) {
        if (index < 0 || index >= leaves.size()) {
            throw new IllegalArgumentException("mmr index");
        }
        int n = leaves.size();
        int start = 0;
        int peakSlot = 0;
        List<Step> path = null;
        int highest = 31 - Integer.numberOfLeadingZeros(n);
        for (int h = highest; h >= 0; h--) {
            if (((n >> h) & 1) == 0) {
                continue;
            }
            int count = 1 << h;
            if (index < start + count) {
                path = new ArrayList<>();
                mountainPath(leaves, start, count, index, path);
                break;
            }
            start += count;
            peakSlot++;
        }
        List<Bytes32> all = peaks(leaves);
        return new Proof(n, index, path, peakSlot, all);
    }

    /**
     * @param side 0 when this leaf sits on the left of the sibling
     */
    public record Step(int side, Bytes32 hash) {
    }

    public record Proof(int size, int index, List<Step> path, int peakSlot, List<Bytes32> peaks) {

        public Bytes32 root(Bytes32 leaf) {
            Bytes32 h = leaf;
            for (int i = path.size() - 1; i >= 0; i--) {
                Step s = path.get(i);
                h = s.side() == 0 ? node(h, s.hash()) : node(s.hash(), h);
            }
            List<Bytes32> bagged = new ArrayList<>(peaks);
            bagged.set(peakSlot, h);
            return bag(bagged);
        }

        public byte[] encode() {
            int bytes = Long.BYTES + Long.BYTES + Integer.BYTES + path.size() * (1 + Bytes32.SIZE)
                    + Integer.BYTES + peaks.size() * (1 + Bytes32.SIZE);
            ByteBuffer buf = ByteBuffer.allocate(bytes);
            buf.putLong(size);
            buf.putLong(index);
            buf.putInt(path.size());
            for (Step s : path) {
                buf.put((byte) s.side());
                buf.put(s.hash().toArray());
            }
            buf.putInt(peaks.size());
            for (int i = 0; i < peaks.size(); i++) {
                buf.put((byte) (i == peakSlot ? 1 : 0));
                buf.put(peaks.get(i).toArray());
            }
            return buf.array();
        }

        public static Proof decode(ByteBuffer buf) {
            if (buf.remaining() < Long.BYTES + Long.BYTES + Integer.BYTES) {
                throw new IllegalArgumentException("mmr proof truncated");
            }
            int size = Math.toIntExact(buf.getLong());
            int index = Math.toIntExact(buf.getLong());
            int pathLen = buf.getInt();
            if (pathLen < 0 || pathLen > 62 || size < 0 || index < 0 || index >= size) {
                throw new IllegalArgumentException("mmr proof range");
            }
            List<Step> path = new ArrayList<>(pathLen);
            for (int i = 0; i < pathLen; i++) {
                if (buf.remaining() < 1 + Bytes32.SIZE) {
                    throw new IllegalArgumentException("mmr proof truncated");
                }
                int side = buf.get() & 0xff;
                if (side > 1) {
                    throw new IllegalArgumentException("mmr proof side");
                }
                byte[] hash = new byte[Bytes32.SIZE];
                buf.get(hash);
                path.add(new Step(side, Bytes32.wrap(hash)));
            }
            if (buf.remaining() < Integer.BYTES) {
                throw new IllegalArgumentException("mmr proof truncated");
            }
            int nPeaks = buf.getInt();
            if (nPeaks <= 0 || nPeaks > 63) {
                throw new IllegalArgumentException("mmr proof peaks");
            }
            int mine = -1;
            List<Bytes32> peaks = new ArrayList<>(nPeaks);
            for (int i = 0; i < nPeaks; i++) {
                if (buf.remaining() < 1 + Bytes32.SIZE) {
                    throw new IllegalArgumentException("mmr proof truncated");
                }
                int flag = buf.get() & 0xff;
                byte[] hash = new byte[Bytes32.SIZE];
                buf.get(hash);
                if (flag == 1) {
                    if (mine != -1) {
                        throw new IllegalArgumentException("mmr proof peaks");
                    }
                    mine = i;
                } else if (flag != 0) {
                    throw new IllegalArgumentException("mmr proof peaks");
                }
                peaks.add(Bytes32.wrap(hash));
            }
            if (mine < 0) {
                throw new IllegalArgumentException("mmr proof peaks");
            }
            return new Proof(size, index, path, mine, peaks);
        }
    }

    static Bytes32 node(Bytes32 left, Bytes32 right) {
        byte[] raw = new byte[1 + Bytes32.SIZE + Bytes32.SIZE];
        raw[0] = 0x03;
        System.arraycopy(left.toArray(), 0, raw, 1, Bytes32.SIZE);
        System.arraycopy(right.toArray(), 0, raw, 1 + Bytes32.SIZE, Bytes32.SIZE);
        return HashUtils.sha256(Bytes.wrap(raw));
    }

    private static Bytes32 bag(List<Bytes32> peaks) {
        Bytes32 acc = peaks.get(peaks.size() - 1);
        for (int i = peaks.size() - 2; i >= 0; i--) {
            acc = node(peaks.get(i), acc);
        }
        return acc;
    }

    private static List<Bytes32> peaks(List<Bytes32> leaves) {
        List<Bytes32> out = new ArrayList<>();
        int n = leaves.size();
        int start = 0;
        int highest = 31 - Integer.numberOfLeadingZeros(n);
        for (int h = highest; h >= 0; h--) {
            if (((n >> h) & 1) == 0) {
                continue;
            }
            int count = 1 << h;
            out.add(mountain(leaves, start, count));
            start += count;
        }
        return out;
    }

    private static Bytes32 mountain(List<Bytes32> leaves, int start, int count) {
        if (count == 1) {
            return leaves.get(start);
        }
        int half = count / 2;
        return node(mountain(leaves, start, half), mountain(leaves, start + half, half));
    }

    private static void mountainPath(List<Bytes32> leaves, int start, int count, int index, List<Step> path) {
        if (count == 1) {
            return;
        }
        int half = count / 2;
        if (index < start + half) {
            path.add(new Step(0, mountain(leaves, start + half, half)));
            mountainPath(leaves, start, half, index, path);
        } else {
            path.add(new Step(1, mountain(leaves, start, half)));
            mountainPath(leaves, start + half, half, index, path);
        }
    }
}
