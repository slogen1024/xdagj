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
package io.xdag.chain.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.exec.wasm.ChicoryEngine;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.p2p.SnapshotPages;
import io.xdag.chain.rpc.ChainApi;
import io.xdag.chain.rpc.ChainView;

public class RetentionTest {

    private static final Bytes CHAIN = address(1);
    private static final Bytes WHO = address(2);

    @Test
    public void windowDropsOldChangeSetsAndSnapshotRebuildsTheRoot() {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        ChainExecutor exec = new ChainExecutor(ChicoryEngine.interpreter(), l1, store, null, 16);
        credit(exec, 0L, 10L);
        for (long seq = 1; seq <= ChainExecutor.CHANGESET_WINDOW + 1; seq++) {
            exec.apply(CHAIN, seq, 0L, 1L, 0L, List.of());
        }
        Bytes32 id = ChainExecutor.chain32(CHAIN);
        assertNull(store.getChangeSet(id, 0L));
        assertNull(store.getChangeSet(id, 1L));
        assertNotNull(store.getChangeSet(id, 2L));
        assertNotNull(store.getSnapshotPage(id, 0L, 0));
        assertNull(store.getSnapshotPage(id, 63L, 0));
        assertNotNull(store.getSnapshotPage(id, ChainExecutor.SNAPSHOT_INTERVAL, 0));
        assertEquals(exec.stateRoot(CHAIN), loadedRoot(store, id, 0L));
        assertEquals(exec.stateRoot(CHAIN), loadedRoot(store, id, ChainExecutor.SNAPSHOT_INTERVAL));
        assertEquals(10L, exec.balanceAt(CHAIN, 0L, WHO.toArray()));
        exec.rewindTo(CHAIN, 1L);
        assertEquals(1L, exec.appliedSeq(CHAIN));
        assertEquals(10L, exec.balance(CHAIN, WHO.toArray()));
        assertThrows(IllegalStateException.class, () -> exec.rewindTo(CHAIN, 0L));
    }

    @Test
    public void rewindAndReplayMatchTheDirectApply() {
        ChainExecutor exec = executor();
        credit(exec, 0L, 10L);
        credit(exec, 1L, 20L);
        credit(exec, 2L, 30L);
        assertEquals(60L, exec.balance(CHAIN, WHO.toArray()));
        exec.rewindTo(CHAIN, 0L);
        assertEquals(10L, exec.balance(CHAIN, WHO.toArray()));
        credit(exec, 1L, 20L);
        credit(exec, 2L, 30L);
        assertEquals(60L, exec.balance(CHAIN, WHO.toArray()));
        assertEquals(30L, exec.balanceAt(CHAIN, 1L, WHO.toArray()));
    }

    @Test
    public void pendingCreditsStayOffTheExecutedHead() {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        ChainExecutor exec = new ChainExecutor(ChicoryEngine.interpreter(), l1, store, null, 16);
        credit(exec, 0L, 50L);
        exec.setPending(chain -> List.of(creditTx(Bytes32.repeat((byte) 9), 7L)));
        assertEquals(57L, exec.pendingBalance(CHAIN, WHO.toArray()));
        assertEquals(50L, exec.balance(CHAIN, WHO.toArray()));
        assertEquals(0L, exec.appliedSeq(CHAIN));
        assertNull(store.getChangeSet(ChainExecutor.chain32(CHAIN), 1L));
        ChainApi api = new ChainApi(() -> 1L, 0L, l1, () -> exec, block -> {
        });
        assertEquals("57", api.getBalance(CHAIN, WHO.toArray(), ChainView.PENDING).balance());
        assertEquals("50", api.getBalance(CHAIN, WHO.toArray(), ChainView.CONFIRMED).balance());
    }

    private static void credit(ChainExecutor exec, long seq, long value) {
        exec.apply(CHAIN, seq, 0L, 1L, 0L, List.of(creditTx(Bytes32.repeat((byte) (seq + 1)), value)));
    }

    private static ChainExecutor.Tx creditTx(Bytes32 hash, long value) {
        return new ChainExecutor.Tx(hash, ChainInput.Kind.CALL, ExecStatus.OK, WHO, address(3), value, 0L, 0,
                new byte[0], null);
    }

    private static ChainExecutor executor() {
        ChainL1Store l1 = new ChainL1Store(new InMemoryKVSource());
        l1.start();
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        return new ChainExecutor(ChicoryEngine.interpreter(), l1, store, null, 16);
    }

    private static Bytes32 loadedRoot(ChainExecStore store, Bytes32 chain, long seq) {
        List<Pair<Bytes32, byte[]>> rows = new ArrayList<>();
        for (int page = 0;; page++) {
            byte[] raw = store.getSnapshotPage(chain, seq, page);
            if (raw == null) {
                break;
            }
            for (SparseMerkleTree.Kv kv : SnapshotPages.decode(raw)) {
                rows.add(Pair.of(kv.key(), kv.value()));
            }
        }
        ExecWorld world = new ExecWorld();
        world.load(rows);
        return world.rootHash();
    }

    private static Bytes address(int n) {
        byte[] raw = new byte[20];
        raw[19] = (byte) n;
        return Bytes.wrap(raw);
    }
}
