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

package io.xdag.core;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_EXT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.junit.Test;

public class BlockExtFieldTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    private static Bytes32 extField(int kind) {
        byte[] b = new byte[32];
        b[0] = (byte) kind;
        b[31] = 0x7f;
        return Bytes32.wrap(b);
    }

    private static Bytes32 blockRef(int seed) {
        MutableBytes32 h = MutableBytes32.create();
        byte[] tail = new byte[24];
        tail[0] = (byte) seed;
        h.set(8, Bytes.wrap(tail));
        return h;
    }

    @Test
    public void extFieldsSurviveEncodeParseAndKeepSignatureValid() {
        List<Bytes32> ext = List.of(extField(0x01), extField(0x02));
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                List.of(key), "remark", 0, XAmount.ZERO, null, ext);
        b.signOut(key);

        Block parsed = new Block(new XdagBlock(b.toBytes()));

        assertEquals(ext, parsed.getExtFields());
        // field order: header(0) remark(1) ext(2) ext(3) pubkey(4) sign(5) sign(6)
        assertEquals(XDAG_FIELD_EXT, parsed.getXdagBlock().getField(2).getType());
        assertEquals(XDAG_FIELD_EXT, parsed.getXdagBlock().getField(3).getType());
        assertEquals(1, parsed.verifiedKeys().size());
        assertEquals(b.recalcHash(), parsed.getHash());
    }

    @Test
    public void legacyConstructorHasNoExtFields() {
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                List.of(key), null, 0, XAmount.ZERO, null);
        b.signOut(key);
        Block parsed = new Block(new XdagBlock(b.toBytes()));
        assertTrue(parsed.getExtFields().isEmpty());
    }

    @Test
    public void blockLinksReturnsOnlyBlockOutRefsInFieldOrder() {
        Bytes32 a = blockRef(1);
        Bytes32 c = blockRef(3);
        Address addrOut = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()),
                XDAG_FIELD_OUTPUT, XAmount.of(1, XUnit.XDAG), true);
        List<Address> pendings = List.of(
                new Address(a, XDAG_FIELD_OUT, false),
                addrOut,
                new Address(c, XDAG_FIELD_OUT, false));
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, pendings, false,
                null, null, -1, XAmount.ZERO, null, null);
        Block parsed = new Block(new XdagBlock(b.toBytes()));

        List<Address> links = parsed.getBlockLinks();
        assertEquals(2, links.size());
        assertEquals(a, links.get(0).getAddress());
        assertEquals(c, links.get(1).getAddress());
    }
}
