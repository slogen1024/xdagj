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
package io.xdag.chain.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainSubscriptionsTest {

    private static Bytes32 id(int n) {
        byte[] raw = new byte[32];
        raw[31] = (byte) n;
        return Bytes32.wrap(raw);
    }

    @Test
    public void explicitListIsSortedAndCoversOnlyThoseChains() {
        ChainSubscriptions subs = ChainSubscriptions.of(List.of(id(3), id(1), id(2)));
        assertEquals(List.of(id(1), id(2), id(3)), subs.chainIds());
        assertTrue(subs.covers(id(2)));
        assertFalse(subs.covers(id(4)));
        assertFalse(subs.subscribeAll());
        assertEquals(subs.chainIds(), ChainSubscriptions.decode(subs.encode()).chainIds());
    }

    @Test
    public void subscribeAllCoversAnyChainAndCarriesNoList() {
        ChainSubscriptions subs = ChainSubscriptions.all();
        assertTrue(subs.covers(id(9)));
        assertTrue(subs.chainIds().isEmpty());
        ChainSubscriptions back = ChainSubscriptions.decode(subs.encode());
        assertTrue(back.subscribeAll());
        assertTrue(back.chainIds().isEmpty());
    }

    @Test
    public void emptyListSubscribesToNothing() {
        assertFalse(ChainSubscriptions.none().covers(id(1)));
        assertFalse(ChainSubscriptions.parse(false, List.of()).covers(id(1)));
    }

    @Test
    public void duplicatesAndSubscribeAllWithAListAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.of(List.of(id(1), id(1))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ChainSubscriptions.parse(true, List.of("01".repeat(32))));
        assertTrue(e.getMessage().contains("chain.subscribe"));
    }

    @Test
    public void hexIdsAcceptAnOptionalPrefix() {
        String hex = "ab".repeat(32);
        ChainSubscriptions subs = ChainSubscriptions.parse(false, List.of("0x" + hex, hex.replace("ab", "cd")));
        assertEquals(2, subs.chainIds().size());
        assertEquals(Bytes32.fromHexString(hex), subs.chainIds().get(0));
    }

    @Test
    public void shortAndNonHexIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.parse(false, List.of("abcd")));
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.parse(false, List.of("zz".repeat(32))));
    }

    @Test
    public void unsortedOrDuplicateWireIsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.decode(wire(0, id(2), id(1))));
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.decode(wire(0, id(1), id(1))));
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.decode(wire(1, id(1))));
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.decode(new byte[] {2, 0, 0, 0, 0}));
    }

    @Test
    public void moreThan4096IdsIsRefused() {
        List<Bytes32> ids = new ArrayList<>();
        for (int i = 0; i < ChainCodec.MAX_SUBSCRIPTIONS + 1; i++) {
            byte[] raw = new byte[32];
            raw[0] = (byte) (i >> 8);
            raw[1] = (byte) i;
            ids.add(Bytes32.wrap(raw));
        }
        assertThrows(IllegalArgumentException.class, () -> ChainSubscriptions.of(ids));
    }

    private static byte[] wire(int flag, Bytes32... ids) {
        ByteBuffer buf = ByteBuffer.allocate(5 + ids.length * 32);
        buf.put((byte) flag);
        buf.putInt(ids.length);
        for (Bytes32 id : ids) {
            buf.put(id.toArray());
        }
        return buf.array();
    }
}
