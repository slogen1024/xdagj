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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.xdag.chain.InMemoryKVSource;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainExecStoreTest {

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }

    @Test
    public void prefixesRoundTripAndAMissIsNull() {
        ChainExecStore store = new ChainExecStore(new InMemoryKVSource());
        store.start();
        Bytes32 chain = id(1);
        store.putState(chain, id(2), new byte[] {9});
        assertArrayEquals(new byte[] {9}, store.getState(chain, id(2)));
        assertNull(store.getState(chain, id(3)));
        store.deleteState(chain, id(2));
        assertNull(store.getState(chain, id(2)));

        store.applyState(chain, List.of(Pair.of(id(4), new byte[] {1}), Pair.of(id(5), null)));
        assertArrayEquals(new byte[] {1}, store.getState(chain, id(4)));
        assertNull(store.getState(chain, id(5)));

        store.putHead(chain, 64, id(8));
        assertEquals(64L, store.getHead(chain).seq());
        assertEquals(id(8), store.getHead(chain).stateRoot());
        assertNull(store.getHead(id(9)));

        store.putChangeSet(chain, 64, new byte[] {2});
        store.putSnapshotPage(chain, 64, 1, new byte[] {3});
        store.putMmr(chain, id(7), new byte[] {4});
        store.putOpening(chain, 64, 3, new byte[] {5});
        store.putReceipt(chain, id(6), new byte[] {6});
        assertArrayEquals(new byte[] {2}, store.getChangeSet(chain, 64));
        assertArrayEquals(new byte[] {3}, store.getSnapshotPage(chain, 64, 1));
        assertNull(store.getSnapshotPage(chain, 64, 0));
        assertArrayEquals(new byte[] {4}, store.getMmr(chain, id(7)));
        assertArrayEquals(new byte[] {5}, store.getOpening(chain, 64, 3));
        assertArrayEquals(new byte[] {6}, store.getReceipt(chain, id(6)));
        store.stop();
    }
}
