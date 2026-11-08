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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.google.common.primitives.UnsignedBytes;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.CommitmentChain;
import io.xdag.chain.anchor.RewardInputs;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.wasm.ChicoryEngine;
import io.xdag.chain.exec.wasm.GasSchedule;
import io.xdag.chain.exec.wasm.WasmEngine;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.p2p.ProofBodies;
import io.xdag.core.Address;
import io.xdag.core.XdagField;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;

/**
 * E2. The interpreter re-executes the call bound to input {@code k}. A malformed witness, a
 * pre-state that does not rebuild {@code R_prev}, or a trap is {@link Arbiter.Verdict#FAIL}
 * (deposit to the attester, no slash). {@code C' != C_k} is {@link Arbiter.Verdict#SUCCESS}.
 * A slot that is not a call has nothing to re-execute and is {@link Arbiter.Verdict#INVALID}.
 */
public final class E2Replay {

    private E2Replay() {
    }

    public static Arbiter.Verdict replay(ChainL1Store l1, byte[] chain, long seq, AnchorBody anchor,
            CommitmentChain.Body commitment, long k, byte[] witness, ChunkChain.RawBlockLookup lookup, int maxChunks) {
        try {
            return run(l1, chain, seq, anchor, commitment, k, witness, lookup, maxChunks);
        } catch (RuntimeException e) {
            return Arbiter.Verdict.FAIL;
        }
    }

    private static Arbiter.Verdict run(ChainL1Store l1, byte[] chain, long seq, AnchorBody anchor,
            CommitmentChain.Body commitment, long k, byte[] witness, ChunkChain.RawBlockLookup lookup, int maxChunks) {
        Witness.E2Body body = Witness.decodeE2(witness);
        if (body == null || lookup == null) {
            return Arbiter.Verdict.FAIL;
        }
        Bytes32 expected = Arbiter.expectedCall(l1, chain, anchor.prevSeq(), seq, commitment, k);
        if (expected == null) {
            return Arbiter.Verdict.INVALID;
        }
        if (!expected.equals(body.inputHash())) {
            return Arbiter.Verdict.FAIL;
        }
        long height = callHeight(l1, chain, anchor.prevSeq(), seq, commitment, k);
        if (height < 0) {
            return Arbiter.Verdict.FAIL;
        }
        Bytes32 pre = preRoot(l1, chain, anchor, commitment, k, body);
        if (pre == null) {
            return Arbiter.Verdict.FAIL;
        }
        Bytes32 rebuilt = WitnessBackedStore.root(body.rows());
        if (rebuilt == null || !rebuilt.equals(pre)) {
            return Arbiter.Verdict.FAIL;
        }
        ChainRecord record = l1.getChain(Bytes.wrap(chain));
        if (record == null || body.gasPrice() != record.gasPriceNano()) {
            return Arbiter.Verdict.FAIL;
        }
        if (body.gasLimit() < 0 || body.gasLimit() > record.maxCallGas() || body.gasLimit() > GasSchedule.MAX_CALL_GAS) {
            return Arbiter.Verdict.FAIL;
        }
        io.xdag.core.Block call = lookup.get(expected);
        if (call == null || call.getTimestamp() != body.timestamp()) {
            return Arbiter.Verdict.FAIL;
        }
        Classified classified = ChainBlockClassifier.classify(call);
        if (!classified.isOk() || classified.kind() != ExtKind.CALL) {
            return Arbiter.Verdict.FAIL;
        }
        CallExt ext = classified.as(CallExt.class);
        if (!ext.contract().equals(Bytes.wrap(body.contract())) || ext.selector() != body.selector()
                || ext.gasLimit() != body.gasLimit()) {
            return Arbiter.Verdict.FAIL;
        }
        byte[] args = argsOf(ext, call, lookup, maxChunks);
        if (args == null || !Arrays.equals(args, body.args())) {
            return Arbiter.Verdict.FAIL;
        }
        byte[] sender = senderOf(call);
        if (sender == null || !Arrays.equals(sender, body.sender()) || valueOf(call, chain) != body.value()) {
            return Arbiter.Verdict.FAIL;
        }
        ContractRecord contract = l1.getContract(ext.contract());
        Bytes code = contract == null ? null : l1.getCode(contract.codeHash());
        if (code == null || !Arrays.equals(code.toArray(), body.code())) {
            return Arbiter.Verdict.FAIL;
        }
        WasmEngine engine = ChicoryEngine.interpreter();
        if (!engine.isInterpreterMode()) {
            return Arbiter.Verdict.FAIL;
        }
        ChainExecStore exec = new ChainExecStore(new MapSource());
        exec.start();
        if (!body.rows().isEmpty()) {
            if (height == 0L) {
                return Arbiter.Verdict.FAIL;
            }
            Bytes32 id = ChainExecutor.chain32(chain);
            for (Witness.Row row : body.rows()) {
                exec.putState(id, row.key(), row.blob());
            }
            exec.putHead(id, height - 1, pre);
        }
        ChainL1Store scratch = new ChainL1Store(new MapSource());
        scratch.start();
        ChainExecutor executor = new ChainExecutor(engine, scratch, exec, null, maxChunks);
        List<ChainExecutor.Outcome> out = executor.apply(Bytes.wrap(chain), height, body.gasPrice(),
                record.maxCallGas(), body.timestamp(), List.of(new ChainExecutor.Tx(expected, ChainInput.Kind.CALL, null,
                        Bytes.wrap(sender), Bytes.wrap(body.contract()), body.value(), body.gasLimit(), body.selector(),
                        args, body.code())));
        Bytes32 produced = out.get(0).commitment();
        return produced.equals(commitment.commitments().get((int) k)) ? Arbiter.Verdict.FAIL : Arbiter.Verdict.SUCCESS;
    }

    private static Bytes32 preRoot(ChainL1Store l1, byte[] chain, AnchorBody anchor, CommitmentChain.Body commitment,
            long k, Witness.E2Body body) {
        if (k == 0) {
            if (body.prevOpening().length != 0) {
                return null;
            }
            AnchorBody parent = AnchorBody.decode(l1.getAnchorRaw(Bytes.wrap(chain), anchor.prevSeq()));
            return parent == null ? WitnessBackedStore.emptyRoot() : parent.stateRoot();
        }
        try {
            if (!HashUtils.sha256(Bytes.wrap(body.prevOpening())).equals(commitment.commitments().get((int) k - 1))) {
                return null;
            }
            return ProofBodies.decodeCommitment(body.prevOpening()).postStateRoot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long callHeight(ChainL1Store store, byte[] chain, long prevSeq, long seq,
            CommitmentChain.Body commitment, long k) {
        long messages = 0L;
        for (CommitmentChain.Segment segment : commitment.segments()) {
            if (Long.compareUnsigned(segment.to(), segment.from()) < 0) {
                return -1L;
            }
            messages += segment.to() - segment.from();
        }
        if (Long.compareUnsigned(k, messages) < 0) {
            return -1L;
        }
        long left = k - messages;
        long h = prevSeq;
        Bytes id = Bytes.wrap(chain);
        while (Long.compareUnsigned(h, seq) < 0) {
            if (h == Long.MAX_VALUE) {
                return -1L;
            }
            h++;
            int rewards = RewardInputs.at(store, chain, h).size();
            if (Long.compareUnsigned(left, rewards) < 0) {
                return -1L;
            }
            left -= rewards;
            long n = store.getCallCount(id, h);
            if (Long.compareUnsigned(left, n) < 0) {
                return h;
            }
            left -= n;
        }
        return -1L;
    }

    private static byte[] argsOf(CallExt ext, io.xdag.core.Block call, ChunkChain.RawBlockLookup lookup, int maxChunks) {
        if (!ext.argsByChain()) {
            return ext.inlineArgs().toArray();
        }
        long minEpoch = XdagTime.getEpoch(call.getTimestamp()) - 1;
        var assembled = ChunkChain.assemble(ext.argsChainHead(), lookup, maxChunks, minEpoch);
        return assembled.isOk() ? assembled.value().toArray() : null;
    }

    private static byte[] senderOf(io.xdag.core.Block call) {
        for (Address in : call.getInputs()) {
            if (in.getIsAddress() && in.getType() == XdagField.FieldType.XDAG_FIELD_INPUT) {
                return ChainIds.address20(in).toArray();
            }
        }
        return null;
    }

    private static long valueOf(io.xdag.core.Block call, byte[] chain) {
        Bytes want = Bytes.wrap(chain);
        for (Address out : call.getOutputs()) {
            if (out.getIsAddress() && out.getType() == XdagField.FieldType.XDAG_FIELD_OUTPUT
                    && want.equals(ChainIds.address20(out))) {
                return Long.parseLong(out.getAmount().toString());
            }
        }
        return 0L;
    }

    /** Private scratch map. The replay must not write the node's exec store. */
    private static final class MapSource implements KVSource<byte[], byte[]> {
        private final TreeMap<byte[], byte[]> map = new TreeMap<>(UnsignedBytes.lexicographicalComparator());
        private boolean alive;

        @Override
        public String getName() {
            return "e2";
        }

        @Override
        public void setName(String name) {
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void init() {
            alive = true;
        }

        @Override
        public void close() {
            alive = false;
        }

        @Override
        public void reset() {
            map.clear();
            alive = true;
        }

        @Override
        public void put(byte[] key, byte[] val) {
            if (val == null) {
                map.remove(key);
            } else {
                map.put(Arrays.copyOf(key, key.length), Arrays.copyOf(val, val.length));
            }
        }

        @Override
        public byte[] get(byte[] key) {
            byte[] v = map.get(key);
            return v == null ? null : Arrays.copyOf(v, v.length);
        }

        @Override
        public void delete(byte[] key) {
            map.remove(key);
        }

        @Override
        public Set<byte[]> keys() {
            Set<byte[]> out = new HashSet<>();
            for (byte[] key : map.keySet()) {
                out.add(key.clone());
            }
            return out;
        }

        @Override
        public List<byte[]> prefixKeyLookup(byte[] prefix) {
            List<byte[]> out = new ArrayList<>();
            for (byte[] key : map.keySet()) {
                if (BytesUtils.keyStartsWith(key, prefix)) {
                    out.add(key.clone());
                }
            }
            return out;
        }

        @Override
        public void fetchPrefix(byte[] prefix, Function<Pair<byte[], byte[]>, Boolean> func) {
        }

        @Override
        public List<byte[]> prefixValueLookup(byte[] prefix) {
            return List.of();
        }

        @Override
        public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] prefix) {
            return List.of();
        }
    }
}
