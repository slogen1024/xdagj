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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.nio.ByteOrder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.Test;

public class BlockEvmStateRootTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private final Bytes rootLow = Bytes.repeat((byte) 0xCD, EvmStateAnchor.ROOT_LOW_LENGTH);

    private long now() {
        return XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(System.currentTimeMillis()));
    }

    private Block roundTrip(Block original) {
        Block reparsed = new Block(new XdagBlock(original.getXdagBlock().getData()));
        reparsed.parse();
        return reparsed;
    }

    @Test
    public void a_legacy_block_is_version_0_with_an_all_zero_transport_header() {
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null);
        block.signOut(key);

        Block reparsed = roundTrip(block);
        assertEquals(0, reparsed.getBlockFormatVersion());
        // byte-identical guarantee: the 8-byte transport region stays all-zero as before.
        Bytes transport = reparsed.getXdagBlock().getData().slice(0, 8);
        assertEquals(Bytes.repeat((byte) 0x00, 8), transport);
    }

    @Test(expected = IllegalStateException.class)
    public void a_version_beyond_one_byte_is_rejected() {
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null);
        block.setBlockFormatVersion(256);
        block.getXdagBlock(); // triggers getEncodedHeader() -> range guard
    }

    @Test
    public void anchor_round_trips_and_marks_version_1() {
        EvmStateAnchor anchor = new EvmStateAnchor(42L, rootLow, false);
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null, null, anchor);
        block.signOut(key);

        Block reparsed = roundTrip(block);
        assertEquals(1, reparsed.getBlockFormatVersion());
        assertNotNull(reparsed.getEvmStateAnchor());
        assertEquals(42L, reparsed.getEvmStateAnchor().height());
        assertEquals(rootLow, reparsed.getEvmStateAnchor().rootLow());
        assertFalse(reparsed.getEvmStateAnchor().daSkip());
        assertTrue("anchor is not a DAG link", reparsed.getLinks().isEmpty());
        assertNotNull("signature survives the anchor field", reparsed.getOutsig());
    }

    @Test
    public void a_da_skip_anchor_round_trips() {
        EvmStateAnchor anchor = new EvmStateAnchor(9L, rootLow, true);
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null, null, anchor);
        block.signOut(key);
        assertTrue(roundTrip(block).getEvmStateAnchor().daSkip());
    }

    @Test
    public void a_block_without_an_anchor_parses_to_null() {
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null);
        block.signOut(key);
        assertNull(roundTrip(block).getEvmStateAnchor());
    }

    @Test
    public void a_legacy_v0_block_with_a_0x0A_nibble_is_not_read_as_an_anchor() {
        // Craft a legacy block, then manually flip an unused slot's nibble to 0x0A (SNAPSHOT). Because
        // the format version stays 0, parse must NOT treat it as an EVM anchor (legacy path intact).
        Block block = new Block(config, now(), null, null, false, null, null, -1,
                XAmount.ZERO, null);
        block.signOut(key);

        MutableBytes data = block.getXdagBlock().getData().mutableCopy();
        long typeWord = data.getLong(8, ByteOrder.LITTLE_ENDIAN);
        typeWord |= 0x0AL << (5 * 4);                       // slot 5 nibble = 0x0A, unused in this block
        data.set(8, Bytes.wrap(BytesUtils.longToBytes(typeWord, true)));

        Block reparsed = new Block(new XdagBlock(data));
        reparsed.parse();
        assertEquals(0, reparsed.getBlockFormatVersion());
        assertNull("a v0 0x0A stays a snapshot field, never an anchor", reparsed.getEvmStateAnchor());
    }

    @Test
    public void the_anchor_coexists_with_the_evm_tx_ref_and_a_remark() {
        Bytes32 txRef = Hash.keccak256(Bytes.wrap("batch".getBytes()));
        EvmStateAnchor anchor = new EvmStateAnchor(100L, rootLow, false);
        Block block = new Block(config, now(), null, null, false, null, "hi", -1,
                XAmount.ZERO, null, txRef, anchor);
        block.signOut(key);

        Block reparsed = roundTrip(block);
        assertEquals(txRef, reparsed.getEvmTxRef());
        assertEquals(100L, reparsed.getEvmStateAnchor().height());
        assertNotNull(reparsed.getInfo().getRemark());
        assertNotNull(reparsed.getOutsig());
    }
}
