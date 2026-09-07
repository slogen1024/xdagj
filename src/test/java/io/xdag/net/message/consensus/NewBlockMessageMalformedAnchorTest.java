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
package io.xdag.net.message.consensus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.net.message.MessageException;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.SimpleEncoder;
import io.xdag.utils.XdagTime;
import java.nio.ByteOrder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.Test;

/**
 * Audit round 2 U2: NEW_BLOCK / SYNC_BLOCK construct the Block at decode time, so a parse exception
 * becomes a MessageException and the block is dropped before import. A versioned block carrying a
 * malformed 0x0A payload must decode (a legacy node accepts it), leaving the anchor absent for the
 * verdict logic to judge after activation.
 */
public class NewBlockMessageMalformedAnchorTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    private byte[] versionedBlockBytesWithMalformedAnchor() {
        long now = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(System.currentTimeMillis()));
        Block block = new Block(config, now, null, null, false, null, null, -1, XAmount.ZERO, null);
        block.signOut(key);

        MutableBytes data = block.getXdagBlock().getData().mutableCopy();
        data.set(0, (byte) 0x01);                          // format version 1
        long typeWord = data.getLong(8, ByteOrder.LITTLE_ENDIAN);
        typeWord |= 0x0AL << (5 * 4);                       // slot 5 nibble = 0x0A
        data.set(8, Bytes.wrap(BytesUtils.longToBytes(typeWord, true)));
        data.set(5 * 32, (byte) 0x02);                      // unknown anchor flag bit
        return data.toArray();
    }

    private byte[] wireBody(byte[] blockBytes) {
        SimpleEncoder enc = new SimpleEncoder();
        enc.writeBytes(blockBytes);
        enc.writeInt(7);
        return enc.toBytes();
    }

    @Test
    public void new_block_message_decodes_a_versioned_block_with_a_malformed_anchor() throws MessageException {
        byte[] raw = versionedBlockBytesWithMalformedAnchor();
        NewBlockMessage msg = new NewBlockMessage(wireBody(raw));
        assertNotNull(msg.getBlock());
        assertNull(msg.getBlock().getEvmStateAnchor());
        assertEquals(new Block(new XdagBlock(raw)).getHash(), msg.getBlock().getHash());
        assertEquals(7, msg.getTtl());
    }

    @Test
    public void sync_block_message_decodes_a_versioned_block_with_a_malformed_anchor() throws MessageException {
        byte[] raw = versionedBlockBytesWithMalformedAnchor();
        SyncBlockMessage msg = new SyncBlockMessage(wireBody(raw));
        assertNotNull(msg.getBlock());
        assertNull(msg.getBlock().getEvmStateAnchor());
    }
}
