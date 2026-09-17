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

package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Collects the CHAIN_L1 writes of one block; committed atomically by {@link ChainL1Store#commit}.
 *
 * <p>Writes are keyed by their CHAIN_L1 key: writing the same key twice — put-then-put,
 * put-then-delete, or delete-then-put — keeps only the last operation, so a caller can freely
 * overwrite or retract an earlier write within the same batch without producing duplicate or
 * conflicting entries for that key. {@link #puts()} returns the surviving puts and
 * {@link #deletes()} the surviving deletes, both in the order each key was first touched; both
 * lists are unmodifiable. {@link ChainL1Store#commit} does not clear or otherwise reset the batch
 * afterwards — reusing an instance after a commit would replay the same writes.
 */
public final class ChainL1Batch {

    private final LinkedHashMap<Bytes, byte[]> ops = new LinkedHashMap<>();

    private void put(byte[] key, byte[] value) {
        ops.put(Bytes.wrap(key), value);
    }

    private void delete(byte[] key) {
        ops.put(Bytes.wrap(key), null);
    }

    public List<Pair<byte[], byte[]>> puts() {
        List<Pair<byte[], byte[]>> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : ops.entrySet()) {
            if (e.getValue() != null) {
                out.add(Pair.of(e.getKey().toArray(), e.getValue()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<byte[]> deletes() {
        List<byte[]> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : ops.entrySet()) {
            if (e.getValue() == null) {
                out.add(e.getKey().toArray());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    public void putChain(Bytes chainId, ChainRecord record) {
        put(ChainL1Keys.chain(chainId), record.encode());
    }

    public void deleteChain(Bytes chainId) {
        delete(ChainL1Keys.chain(chainId));
    }

    public void putContract(Bytes contract, ContractRecord record) {
        put(ChainL1Keys.contract(contract), record.encode());
    }

    public void deleteContract(Bytes contract) {
        delete(ChainL1Keys.contract(contract));
    }

    /** Writes both the raw code blob (under {@code CODE}) and its reference count (under {@code CODE_REF}). */
    public void putCode(Bytes32 codeHash, long refCount, Bytes code) {
        put(ChainL1Keys.code(codeHash), code.toArray());
        putCodeRef(codeHash, refCount);
    }

    /** Updates only the reference count, leaving a previously-written (possibly large) blob untouched. */
    public void putCodeRef(Bytes32 codeHash, long refCount) {
        byte[] v = new byte[4];
        ExtCodec.putU32(v, 0, refCount);
        put(ChainL1Keys.codeRef(codeHash), v);
    }

    /** Deletes both the code blob and its reference count. */
    public void deleteCode(Bytes32 codeHash) {
        delete(ChainL1Keys.code(codeHash));
        delete(ChainL1Keys.codeRef(codeHash));
    }

    public void putCallCount(Bytes chainId, long height, long count) {
        byte[] v = new byte[4];
        ExtCodec.putU32(v, 0, count);
        put(ChainL1Keys.callCount(chainId, height), v);
    }

    public void deleteCallCount(Bytes chainId, long height) {
        delete(ChainL1Keys.callCount(chainId, height));
    }

    public void putInput(Bytes chainId, long height, long index, InputRecord record) {
        put(ChainL1Keys.input(chainId, height, index), record.encode());
    }

    public void deleteInput(Bytes chainId, long height, long index) {
        delete(ChainL1Keys.input(chainId, height, index));
    }

    public void putReverse(Bytes32 blockHash, List<InputRef> refs) {
        put(ChainL1Keys.reverse(blockHash), InputRef.encodeList(refs));
    }

    public void deleteReverse(Bytes32 blockHash) {
        delete(ChainL1Keys.reverse(blockHash));
    }
}
