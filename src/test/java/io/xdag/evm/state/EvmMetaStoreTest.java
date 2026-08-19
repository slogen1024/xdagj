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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.bridge.BridgeDeposit;
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

        store.putHeightRecord(5L, rootA, blockA, 2, 1234L);
        store.putHeightRecord(9L, blockA, rootA, 0, 5678L);

        EvmMetaStore.HeightRecord rec = store.getHeightRecord(5L).orElseThrow();
        assertEquals(rootA, rec.stateRoot());
        assertEquals(blockA, rec.blockHash());
        assertEquals(2, rec.txCount());
        assertEquals(1234L, rec.timestampSeconds());
        assertEquals(Optional.of(9L), store.highestHeight());
    }

    @Test
    public void removeAbove_deletes_only_higher_heights() {
        store.putHeightRecord(5L, rootA, blockA, 1, 1L);
        store.putHeightRecord(6L, rootA, blockA, 1, 2L);
        store.putHeightRecord(7L, rootA, blockA, 1, 3L);

        store.removeAbove(5L);

        assertTrue(store.getHeightRecord(5L).isPresent());
        assertTrue(store.getHeightRecord(6L).isEmpty());
        assertTrue(store.getHeightRecord(7L).isEmpty());
        assertEquals(Optional.of(5L), store.highestHeight());
    }

    @Test
    public void tx_list_round_trips_in_order_and_is_removed_with_height() {
        Hash tx1 = Hash.hash(Bytes.of(1));
        Hash tx2 = Hash.hash(Bytes.of(2));
        assertTrue(store.getTxList(7L).isEmpty());

        store.putTxList(7L, List.of(tx1, tx2));
        assertEquals(List.of(tx1, tx2), store.getTxList(7L));

        store.putHeightRecord(7L, rootA, blockA, 2, 4L);
        store.removeAbove(6L);
        assertTrue("tx list must be truncated together with the height record", store.getTxList(7L).isEmpty());
        assertTrue(store.getHeightRecord(7L).isEmpty());
    }

    @Test
    public void find_tx_location_returns_height_and_index_and_empty_for_unknown() {
        // The eth_getTransactionByHash / getTransactionReceipt reverse index (replaces the former
        // O(total-txs) full-history scan): putTxList records each tx's (height, position within the
        // height's ordered list); a hash never seen on chain resolves to empty in one point lookup.
        Hash tx1 = Hash.hash(Bytes.of(1));
        Hash tx2 = Hash.hash(Bytes.of(2));
        Hash unknown = Hash.hash(Bytes.of(99));
        assertTrue(store.findTxLocation(tx1).isEmpty());

        store.putTxList(7L, List.of(tx1, tx2));

        assertEquals(new EvmMetaStore.TxLocation(7L, 0), store.findTxLocation(tx1).orElseThrow());
        assertEquals(new EvmMetaStore.TxLocation(7L, 1), store.findTxLocation(tx2).orElseThrow());
        assertTrue("a hash never on chain must not be found", store.findTxLocation(unknown).isEmpty());
    }

    @Test
    public void find_tx_location_is_cleared_by_removeAbove() {
        // Reorg truncation must drop the reverse-index entries together with the tx list, or a
        // reorged-out tx would keep resolving to a stale (height, index) through findTxLocation.
        Hash keep = Hash.hash(Bytes.of(1));
        Hash drop = Hash.hash(Bytes.of(2));
        store.putTxList(5L, List.of(keep));
        store.putTxList(6L, List.of(drop));

        store.removeAbove(5L);

        assertEquals(new EvmMetaStore.TxLocation(5L, 0), store.findTxLocation(keep).orElseThrow());
        assertTrue("index for a reorged-out tx must be gone", store.findTxLocation(drop).isEmpty());
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

    @Test
    public void height_bloom_round_trips_and_absent_is_empty() {
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        byte[] bloom = new byte[256];
        bloom[3] = 0x40;
        bloom[200] = (byte) 0x81;
        meta.putHeightBloom(7, Bytes.wrap(bloom));

        assertTrue(meta.getHeightBloom(7).isPresent());
        assertArrayEquals(bloom, meta.getHeightBloom(7).orElseThrow().toArray());
        assertTrue("absent height -> empty", meta.getHeightBloom(9).isEmpty());
    }

    @Test
    public void wrong_length_bloom_is_treated_as_absent() {
        // A corrupt/short bloom is an optimization artifact, NOT a consensus record: it must degrade to
        // empty (fallback scan), never throw like the receipt/height-record readers do.
        InMemoryKVSource store = new InMemoryKVSource();
        EvmMetaStore meta = new EvmMetaStore(store);
        meta.putHeightBloom(4, Bytes.wrap(new byte[256]));
        // Overwrite the stored value with a wrong-length blob at the same key.
        store.put(bloomKeyForTest(4), new byte[]{1, 2, 3});
        assertTrue(meta.getHeightBloom(4).isEmpty());
    }

    @Test
    public void remove_above_clears_height_blooms() {
        EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        meta.putHeightBloom(1, Bytes.wrap(new byte[256]));
        meta.putHeightBloom(2, Bytes.wrap(new byte[256]));
        meta.putHeightBloom(3, Bytes.wrap(new byte[256]));
        meta.removeAbove(1);
        assertTrue(meta.getHeightBloom(1).isPresent());
        assertTrue(meta.getHeightBloom(2).isEmpty());
        assertTrue(meta.getHeightBloom(3).isEmpty());
    }

    @Test
    public void deposit_records_round_trip_in_order() {
        List<BridgeDeposit> deposits = List.of(
                new BridgeDeposit(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"), 5L),
                new BridgeDeposit(Address.fromHexString("0x3535353535353535353535353535353535353535"), 7_000_000_000L));
        store.putDeposits(3L, deposits);
        assertEquals(deposits, store.getDeposits(3L));
        assertEquals(List.of(), store.getDeposits(4L)); // absent height -> empty list
        store.putDeposits(6L, List.of());
        assertEquals(List.of(), store.getDeposits(6L)); // empty write == no record
    }

    @Test
    public void deposit_records_are_cleared_by_removeAbove() {
        store.putDeposits(2L, List.of(new BridgeDeposit(Address.ZERO, 1L)));
        store.putDeposits(5L, List.of(new BridgeDeposit(Address.ZERO, 2L)));
        store.removeAbove(2L);
        assertEquals(1, store.getDeposits(2L).size()); // kept: at the rollback point
        assertEquals(List.of(), store.getDeposits(5L)); // wiped: above it
    }

    @Test
    public void putDeposits_rejects_a_negative_amount() {
        // A producer bug (negative nano amount) must die at write time with a clear message, not be
        // encoded into a record the sign-guarded reader would only reject later, inside setMain.
        List<BridgeDeposit> bad = List.of(new BridgeDeposit(Address.ZERO, -1L));
        assertThrows(IllegalArgumentException.class, () -> store.putDeposits(9L, bad));
    }

    @Test
    public void deposit_record_with_the_sign_bit_set_fails_fast_on_read() {
        // A raw 0x06 record whose amount field has the sign bit set decodes to a negative long; the
        // reader must fail fast like the other corrupt-record readers, instead of letting the value
        // reach Wei.of (and throw) inside the setMain-inline mint path.
        InMemoryKVSource kv = new InMemoryKVSource();
        EvmMetaStore meta = new EvmMetaStore(kv);
        byte[] value = new byte[28]; // exactly one (target 20 | amountNano 8 BE) entry
        value[20] = (byte) 0x80; // the amount's high byte: sign bit set -> negative long
        kv.put(depositsKeyForTest(3L), value);
        assertThrows(IllegalStateException.class, () -> meta.getDeposits(3L));
    }

    /** Mirrors EvmMetaStore's private bloomKey layout (0x05 | height 8-byte BE) for the corrupt-value test. */
    private static byte[] bloomKeyForTest(long height) {
        byte[] key = new byte[9];
        key[0] = 0x05;
        for (int i = 0; i < 8; i++) {
            key[1 + i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }

    /** Mirrors EvmMetaStore's private depositsKey layout (0x06 | height 8-byte BE) for the corrupt-value test. */
    private static byte[] depositsKeyForTest(long height) {
        byte[] key = new byte[9];
        key[0] = 0x06;
        for (int i = 0; i < 8; i++) {
            key[1 + i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }
}
