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
package io.xdag.chain.exec.wasm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.CommitmentChain;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.fraud.Arbiter;
import io.xdag.chain.fraud.E2Replay;
import io.xdag.chain.fraud.Witness;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;

/**
 * One interpreter arbitration of a call whose gas limit is {@link GasSchedule#MAX_CALL_GAS}.
 * The bound is 2 seconds. The timed region is {@link E2Replay#replay}, not the setup execution.
 */
public class E2ArbitrationBenchTest {

    private static final long TS = 0x16a00000000L;
    private static final long GAS = GasSchedule.MAX_CALL_GAS;

    @Test
    public void oneArbitrationOfAMaxCallFitsInTwoSeconds() {
        ECKeyPair attesterKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] attester = attesterKey.toAddress().toArray();
        Bytes chain = address(1);
        Bytes contract = address(2);
        byte[] code = spin();
        long value = Long.parseLong(ChainBlockBuilder.requiredValue(XAmount.ZERO, 0).toString());
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        Map<Bytes32, Block> blocks = new HashMap<>();
        var built = ChainBlockBuilder.call(new DevnetConfig(), TS, attesterKey, UInt64.valueOf(1), chain, contract, 1,
                GAS, XAmount.of(value), XAmount.ZERO, Bytes.EMPTY);
        assertTrue(built.isOk());
        Block call = built.value().block();
        Bytes32 callHash = Bytes32.wrap(call.getHashLow().toArray());
        blocks.put(callHash, call);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(chain, new ChainRecord(1L, Bytes32.ZERO, 0L, 0L, GAS, 0L));
        Bytes32 codeHash = HashUtils.sha256(Bytes.wrap(code));
        batch.putContract(contract, new ContractRecord(chain, codeHash, 1L, Bytes32.ZERO));
        batch.putCode(codeHash, 1L, Bytes.wrap(code));
        batch.putCallCount(chain, 2L, 1L);
        batch.putInput(chain, 2L, 0L, new InputRecord(callHash, ExtKind.CALL, InputStatus.OK, contract));
        l1.commit(batch);
        ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        exec.start();
        ChainExecutor.Outcome outcome = new ChainExecutor(ChicoryEngine.interpreter(), l1, exec, blocks::get, 64)
                .apply(chain, 2L, 0L, GAS, TS, List.of(new ChainExecutor.Tx(callHash, ChainInput.Kind.CALL, null,
                        Bytes.wrap(attester), contract, value, GAS, 1, new byte[0], code)))
                .get(0);
        CallExt ext = ChainBlockClassifier.classify(call).as(CallExt.class);
        assertEquals(GAS, ext.gasLimit());
        assertTrue("gas " + outcome.gasUsed(), outcome.gasUsed() > GAS / 2);
        byte[] witness = Witness.encodeE2(new Witness.E2Body(List.of(), code, attester, contract.toArray(), value, GAS,
                0L, 1, new byte[0], TS, callHash, new byte[0]));
        AnchorBody anchor = new AnchorBody(AnchorRecord.Status.CANONICAL, Bytes32.ZERO, Bytes32.ZERO, Bytes32.ZERO, 1L,
                1L, 0L, 3L, Bytes32.ZERO, attester, Bytes32.ZERO);
        CommitmentChain.Body commitment = new CommitmentChain.Body(List.of(), List.of(outcome.commitment()));
        long started = System.nanoTime();
        Arbiter.Verdict verdict = E2Replay.replay(l1, chain.toArray(), 3L, anchor, commitment, 0L, witness, blocks::get,
                64);
        long elapsed = System.nanoTime() - started;
        assertEquals(Arbiter.Verdict.FAIL, verdict);
        assertTrue("e2 arbitration took " + elapsed + " ns", elapsed < 2_000_000_000L);
    }

    private static byte[] spin() {
        // WasmAsm prefixes the local-count byte. loop / br 0 / end burns gas until the meter traps.
        byte[] loop = new byte[] {0x03, 0x40, 0x0c, 0x00, 0x0b, 0x0b};
        WasmAsm asm = new WasmAsm();
        int empty = asm.addType(new int[0], new int[0]);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, loop);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
