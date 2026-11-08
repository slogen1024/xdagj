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
import static org.junit.Assert.assertNull;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.rpc.ChainApi;
import io.xdag.chain.rpc.ChainView;
import io.xdag.crypto.hash.HashUtils;

public class HistoricalQueryTest {

    @Test
    public void queryRefusesCodeDeployedAfterTheSeq() {
        Bytes chain = address(1);
        Bytes contract = address(2);
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        ChainExecStore exec = new ChainExecStore(new InMemoryKVSource());
        l1.start();
        exec.start();
        ChainExecutor executor = new ChainExecutor(ChicoryEngine.interpreter(), l1, exec, null, 16);
        executor.apply(chain, 0L, 0L, 1_000_000L, 0L, List.of(new ChainExecutor.Tx(Bytes32.repeat((byte) 1),
                io.xdag.chain.exec.ChainInput.Kind.CALL, null, address(3), contract, 0L, 1000L, 1, new byte[0],
                module())));
        Bytes32 code = HashUtils.sha256(Bytes.wrap(module()));
        ChainL1Batch batch = new ChainL1Batch();
        batch.putContract(contract, new ContractRecord(chain, code, 5L, Bytes32.ZERO));
        l1.commit(batch);
        ChainApi api = new ChainApi(() -> 0L, 0L, l1, () -> executor, block -> {
        });
        assertNull(api.query(chain, contract, 1, new byte[0], ChainView.CONFIRMED).data());

        ChainL1Batch earlier = new ChainL1Batch();
        earlier.putContract(contract, new ContractRecord(chain, code, 0L, Bytes32.ZERO));
        l1.commit(earlier);
        assertEquals("0x", api.query(chain, contract, 1, new byte[0], ChainView.CONFIRMED).data());
    }

    private static byte[] module() {
        byte[] body = new byte[] {0x0b};
        WasmAsm asm = new WasmAsm();
        int empty = asm.addType(new int[0], new int[0]);
        asm.addFunc(empty, body);
        asm.addFunc(empty, body);
        asm.addFunc(empty, body);
        asm.addFunc(empty, body);
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
