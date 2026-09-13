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
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_HEAD_TEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_PUBLIC_KEY_0;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_PUBLIC_KEY_1;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_REMARK;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_SIGN_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_TRANSACTION_NONCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
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

    @Test
    public void mixedBlockKeepsTypeMaskAndEncodedOrderInSync() {
        List<Bytes32> ext = List.of(extField(0x03), extField(0x04));
        Address input = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()),
                XDAG_FIELD_INPUT, XAmount.of(1, XUnit.XDAG), true);
        Address outBlockRef = new Address(blockRef(5), XDAG_FIELD_OUT, false);
        Address output = new Address(BytesUtils.arrayToByte32(SampleKeys.KEY_PAIR2.toAddress().toArray()),
                XDAG_FIELD_OUTPUT, XAmount.of(2, XUnit.XDAG), true);
        List<Address> pendings = List.of(input, outBlockRef, output);

        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, pendings, false,
                List.of(key), "mix", 0, XAmount.ZERO, UInt64.ONE, ext);
        b.signOut(key);

        Block parsed = new Block(new XdagBlock(b.toBytes()));

        XdagField.FieldType[] expected = {
                XDAG_FIELD_HEAD_TEST,
                XDAG_FIELD_TRANSACTION_NONCE,
                XDAG_FIELD_INPUT,
                XDAG_FIELD_OUT,
                XDAG_FIELD_OUTPUT,
                XDAG_FIELD_REMARK,
                XDAG_FIELD_EXT,
                XDAG_FIELD_EXT,
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals("field " + i, expected[i], parsed.getXdagBlock().getField(i).getType());
        }
        XdagField.FieldType pubKeyType = parsed.getXdagBlock().getField(8).getType();
        assertTrue(pubKeyType == XDAG_FIELD_PUBLIC_KEY_0 || pubKeyType == XDAG_FIELD_PUBLIC_KEY_1);
        assertEquals(XDAG_FIELD_SIGN_OUT, parsed.getXdagBlock().getField(9).getType());
        assertEquals(XDAG_FIELD_SIGN_OUT, parsed.getXdagBlock().getField(10).getType());

        assertEquals(ext, parsed.getExtFields());
        assertEquals(1, parsed.verifiedKeys().size());
    }

    @Test
    public void chunkShapeFitsExactlySixteenFieldsAndOneMoreIsRejected() {
        List<Address> links = List.of(new Address(blockRef(7), XDAG_FIELD_OUT, false));

        List<Bytes32> twelveExt = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelveExt.add(extField(0x10 + i));
        }

        Block b = new Block(config, XdagTime.getCurrentTimestamp(), links, null, false,
                null, null, -1, XAmount.ZERO, null, twelveExt);
        byte[] raw = b.toBytes();
        assertEquals(512, raw.length);

        Block parsed = new Block(new XdagBlock(raw));
        assertEquals(twelveExt, parsed.getExtFields());

        int signOutCount = 0;
        for (int i = 0; i < XdagBlock.XDAG_BLOCK_FIELDS; i++) {
            if (parsed.getXdagBlock().getField(i).getType() == XDAG_FIELD_SIGN_OUT) {
                signOutCount++;
            }
        }
        assertEquals(2, signOutCount);
        assertNotNull(parsed.getOutsig());

        List<Bytes32> thirteenExt = new ArrayList<>(twelveExt);
        thirteenExt.add(extField(0x20));
        try {
            new Block(config, XdagTime.getCurrentTimestamp(), links, null, false,
                    null, null, -1, XAmount.ZERO, null, thirteenExt);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // block field budget exceeded, as intended
        }
    }
}
