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
package io.xdag.evm.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.junit.Before;
import org.junit.Test;

/** Round-trips per-main-block execution metadata and receipts through the EVM_META layout. */
public class EvmMetaStoreTest {

    private EvmMetaStore store;

    private final Bytes32 rootA = Bytes32.fromHexString("0x" + "11".repeat(32));
    private final Bytes32 blockA = Bytes32.fromHexString("0x" + "22".repeat(32));

    @Before
    public void setUp() {
        store = new EvmMetaStore(new InMemoryKVSource());
    }

    @Test
    public void height_record_round_trip_and_highest() {
        assertTrue(store.highestHeight().isEmpty());

        store.putHeightRecord(5L, rootA, blockA, 2);
        store.putHeightRecord(9L, blockA, rootA, 0);

        EvmMetaStore.HeightRecord rec = store.getHeightRecord(5L).orElseThrow();
        assertEquals(rootA, rec.stateRoot());
        assertEquals(blockA, rec.blockHash());
        assertEquals(2, rec.txCount());
        assertEquals(Optional.of(9L), store.highestHeight());
    }

    @Test
    public void removeAbove_deletes_only_higher_heights() {
        store.putHeightRecord(5L, rootA, blockA, 1);
        store.putHeightRecord(6L, rootA, blockA, 1);
        store.putHeightRecord(7L, rootA, blockA, 1);

        store.removeAbove(5L);

        assertTrue(store.getHeightRecord(5L).isPresent());
        assertTrue(store.getHeightRecord(6L).isEmpty());
        assertTrue(store.getHeightRecord(7L).isEmpty());
        assertEquals(Optional.of(5L), store.highestHeight());
    }

    @Test
    public void receipt_round_trip_with_logs_and_contract_address() {
        Hash txHash = Hash.hash(Bytes.of(1, 2, 3));
        Address contract = Address.fromHexString("0x1111111111111111111111111111111111111111");
        Log log = new Log(contract, Bytes.fromHexString("0xbeef"),
                List.of(LogTopic.wrap(Bytes32.fromHexString("0x" + "aa".repeat(32))),
                        LogTopic.wrap(Bytes32.fromHexString("0x" + "bb".repeat(32)))));
        EvmReceipt receipt = new EvmReceipt(1, 53_000L, Optional.of(contract), List.of(log));

        store.putReceipt(txHash, receipt);
        EvmReceipt loaded = store.getReceipt(txHash).orElseThrow();

        assertEquals(1, loaded.status());
        assertEquals(53_000L, loaded.gasUsed());
        assertEquals(Optional.of(contract), loaded.contractAddress());
        assertEquals(1, loaded.logs().size());
        assertEquals(log.getLogger(), loaded.logs().getFirst().getLogger());
        assertEquals(log.getTopics(), loaded.logs().getFirst().getTopics());
        assertEquals(log.getData(), loaded.logs().getFirst().getData());
    }

    @Test
    public void failed_call_receipt_without_contract_or_logs() {
        Hash txHash = Hash.hash(Bytes.of(9));
        store.putReceipt(txHash, new EvmReceipt(0, 21_000L, Optional.empty(), List.of()));

        EvmReceipt loaded = store.getReceipt(txHash).orElseThrow();
        assertEquals(0, loaded.status());
        assertTrue(loaded.contractAddress().isEmpty());
        assertTrue(loaded.logs().isEmpty());
    }
}
