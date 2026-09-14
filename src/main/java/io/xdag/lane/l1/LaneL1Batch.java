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

package io.xdag.lane.l1;

import io.xdag.lane.ext.ExtCodec;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Collects the LANE_L1 writes of one block; committed atomically by {@link LaneL1Store#commit}. */
public final class LaneL1Batch {

    private final List<Pair<byte[], byte[]>> puts = new ArrayList<>();
    private final List<byte[]> deletes = new ArrayList<>();

    public List<Pair<byte[], byte[]>> puts() {
        return puts;
    }

    public List<byte[]> deletes() {
        return deletes;
    }

    public boolean isEmpty() {
        return puts.isEmpty() && deletes.isEmpty();
    }

    public void putLane(Bytes laneId, LaneRecord record) {
        puts.add(Pair.of(LaneL1Keys.lane(laneId), record.encode()));
    }

    public void deleteLane(Bytes laneId) {
        deletes.add(LaneL1Keys.lane(laneId));
    }

    public void putContract(Bytes contract, ContractRecord record) {
        puts.add(Pair.of(LaneL1Keys.contract(contract), record.encode()));
    }

    public void deleteContract(Bytes contract) {
        deletes.add(LaneL1Keys.contract(contract));
    }

    /** value: refCount u32 | len u32 | bytes */
    public void putCode(Bytes32 codeHash, long refCount, Bytes code) {
        byte[] v = new byte[8 + code.size()];
        ExtCodec.putU32(v, 0, refCount);
        ExtCodec.putU32(v, 4, code.size());
        System.arraycopy(code.toArray(), 0, v, 8, code.size());
        puts.add(Pair.of(LaneL1Keys.code(codeHash), v));
    }

    public void deleteCode(Bytes32 codeHash) {
        deletes.add(LaneL1Keys.code(codeHash));
    }

    public void putCallCount(Bytes laneId, long height, long count) {
        byte[] v = new byte[4];
        ExtCodec.putU32(v, 0, count);
        puts.add(Pair.of(LaneL1Keys.callCount(laneId, height), v));
    }

    public void deleteCallCount(Bytes laneId, long height) {
        deletes.add(LaneL1Keys.callCount(laneId, height));
    }

    public void putInput(Bytes laneId, long height, long index, InputRecord record) {
        puts.add(Pair.of(LaneL1Keys.input(laneId, height, index), record.encode()));
    }

    public void deleteInput(Bytes laneId, long height, long index) {
        deletes.add(LaneL1Keys.input(laneId, height, index));
    }

    public void putReverse(Bytes32 blockHash, List<InputRef> refs) {
        puts.add(Pair.of(LaneL1Keys.reverse(blockHash), InputRef.encodeList(refs)));
    }

    public void deleteReverse(Bytes32 blockHash) {
        deletes.add(LaneL1Keys.reverse(blockHash));
    }
}
