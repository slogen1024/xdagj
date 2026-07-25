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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.XdagTime;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * XDAG_FIELD_EVM_TX_REF (0x0F): a 32-byte EVM tx hash carried as a raw block field — round-trips
 * through the 512-byte wire encoding, never surfaces as a DAG link, and absent means null.
 */
public class BlockEvmTxRefTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private final Bytes32 ref = org.hyperledger.besu.crypto.Hash.keccak256(Bytes.wrap("test-tx".getBytes()));

    private Block roundTrip(Block original) {
        Block reparsed = new Block(new XdagBlock(original.getXdagBlock().getData()));
        reparsed.parse();
        return reparsed;
    }

    @Test
    public void evm_tx_ref_round_trips_through_wire_encoding() {
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(System.currentTimeMillis()));
        Block block = new Block(config, xdagTime, null, null, false, null, null, -1,
                XAmount.ZERO, null, ref);
        block.signOut(key);

        Block reparsed = roundTrip(block);
        assertEquals(ref, reparsed.getEvmTxRef());
        assertTrue("the ref is not a DAG link", reparsed.getLinks().isEmpty());
        assertTrue(reparsed.getInputs().isEmpty());
        assertTrue(reparsed.getOutputs().isEmpty());
        assertNotNull("signature survives the extra field", reparsed.getOutsig());
    }

    @Test
    public void block_without_ref_parses_to_null_and_stays_compatible() {
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(System.currentTimeMillis()));
        Block block = new Block(config, xdagTime, null, null, false, null, null, -1,
                XAmount.ZERO, null);
        block.signOut(key);

        assertNull(roundTrip(block).getEvmTxRef());
    }

    @Test
    public void old_ten_arg_constructor_still_compiles_and_produces_no_ref() {
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(System.currentTimeMillis()));
        Block block = new Block(config, xdagTime, null, null, false, null, "remark-here", -1,
                XAmount.ZERO, null);
        block.signOut(key);

        Block reparsed = roundTrip(block);
        assertNull(reparsed.getEvmTxRef());
        assertNotNull(reparsed.getInfo().getRemark());
    }
}
