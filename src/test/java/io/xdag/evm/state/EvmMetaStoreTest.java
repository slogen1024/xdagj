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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.bridge.BridgeWithdrawal;
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
    public void highest_height_at_most_returns_the_floor_checkpoint() {
        store.putHeightRecord(5L, rootA, blockA, 1, 1L);
        store.putHeightRecord(9L, rootA, blockA, 1, 2L);

        assertEquals(java.util.Optional.of(9L), store.highestHeightAtMost(100L)); // above all
        assertEquals(java.util.Optional.of(9L), store.highestHeightAtMost(9L));   // exact top
        assertEquals(java.util.Optional.of(5L), store.highestHeightAtMost(8L));   // between: floor is 5
        assertEquals(java.util.Optional.of(5L), store.highestHeightAtMost(5L));   // exact lower
        assertEquals(java.util.Optional.empty(), store.highestHeightAtMost(4L));  // below all
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

    @Test
    public void maturity_entry_archives_on_maturation_and_restores_on_reorg() {
        // Audit round 2, C1: a matured height's inputs are archived (0x0C), not deleted, so a shallow
        // reorg that unwinds the deciding block can re-open the height under the replacement block.
        Bytes32 ref = Bytes32.fromHexString("0x" + "ab".repeat(32));
        BridgeDeposit dep = new BridgeDeposit(Address.fromHexString("0x1111111111111111111111111111111111111111"), 5L);
        store.putMaturityEntry(7L, Bytes32.fromHexString("0x" + "07".repeat(32)), 1007L, List.of(ref), List.of(dep));
        EvmMetaStore.MaturityEntry entry = store.getMaturityEntry(7L).orElseThrow();

        store.archiveMaturityEntry(7L);
        assertTrue("archived entry leaves the live buffer", store.getMaturityEntry(7L).isEmpty());
        assertEquals(entry, store.getArchivedMaturityEntry(7L).orElseThrow());
        assertEquals(List.of(), store.maturityHeights());

        assertTrue(store.restoreMaturityEntry(7L));
        assertEquals(entry, store.getMaturityEntry(7L).orElseThrow());
        assertTrue(store.getArchivedMaturityEntry(7L).isEmpty());
        assertFalse("nothing left to restore", store.restoreMaturityEntry(7L));
        assertFalse("archiving a missing entry is a no-op", store.archiveMaturityEntry(8L));
    }

    @Test
    public void archived_entries_prune_at_or_below_a_height() {
        for (long h = 1; h <= 4; h++) {
            store.putMaturityEntry(h, Bytes32.ZERO, 1000L + h, List.of(), List.of());
            store.archiveMaturityEntry(h);
        }
        store.removeArchivedAtOrBelow(2L);
        assertTrue(store.getArchivedMaturityEntry(1L).isEmpty());
        assertTrue(store.getArchivedMaturityEntry(2L).isEmpty());
        assertTrue(store.getArchivedMaturityEntry(3L).isPresent());
        assertTrue(store.getArchivedMaturityEntry(4L).isPresent());
    }

    @Test
    public void two_boundary_removeAbove_keeps_native_journals_between_the_boundaries() {
        // Execution artifacts (height records, skip markers, fee debits, pending...) are swept above
        // the EXECUTION boundary; native-height journals (releases 0x08, maturity 0x09, archive 0x0C)
        // only above the NATIVE boundary. unWindMain re-opens (execution, native] on a delta-lag reorg.
        Bytes32 root = Bytes32.fromHexString("0x" + "cd".repeat(32));
        BridgeWithdrawal rel = new BridgeWithdrawal(Bytes.fromHexString("0x2222222222222222222222222222222222222222"), 9L);
        for (long h = 3; h <= 6; h++) {
            store.putHeightRecord(h, root, Bytes32.ZERO, 0, 1000L + h);
            store.putSkipMarker(h);
            store.putFeeDebit(h, h);
            store.putReleases(h, List.of(rel));
            store.putMaturityEntry(h, Bytes32.ZERO, 1000L + h, List.of(), List.of());
        }
        store.archiveMaturityEntry(3L);
        store.archiveMaturityEntry(4L);

        store.removeAbove(2L, 4L);

        for (long h = 3; h <= 6; h++) {
            assertTrue("height record " + h + " swept", store.getHeightRecord(h).isEmpty());
            assertFalse("skip marker " + h + " swept", store.isSkipped(h));
        }
        // Fee debits are native journals: BlockchainImpl reverses+deletes those of re-opened heights
        // itself, so the sweep must not silently drop one it did not reverse.
        assertEquals(3L, store.getFeeDebit(3L));
        assertEquals(4L, store.getFeeDebit(4L));
        assertEquals(0L, store.getFeeDebit(5L));
        assertEquals(0L, store.getFeeDebit(6L));
        assertEquals("release journal at 3 (canonical) kept", List.of(rel), store.getReleases(3L));
        assertEquals("release journal at 4 (canonical) kept", List.of(rel), store.getReleases(4L));
        assertEquals("release journal at 5 (unwound) swept", List.of(), store.getReleases(5L));
        assertEquals("release journal at 6 (unwound) swept", List.of(), store.getReleases(6L));
        assertTrue(store.getArchivedMaturityEntry(3L).isPresent());
        assertTrue(store.getArchivedMaturityEntry(4L).isPresent());
        assertEquals("buffered entries of unwound heights swept", List.of(), store.maturityHeights());

        // The single-boundary form is the legacy shape: both boundaries equal.
        store.removeAbove(3L);
        assertTrue(store.getArchivedMaturityEntry(4L).isEmpty());
        assertEquals(List.of(), store.getReleases(4L));
        assertTrue(store.getArchivedMaturityEntry(3L).isPresent());
    }

    @Test
    public void withdrawal_records_round_trip_in_order_and_clear_by_removeAbove() {
        List<BridgeWithdrawal> ws = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x3109ff8cf0be958a428c12d86c0abf64f529f7db"), 5L),
                new BridgeWithdrawal(Bytes.fromHexString("0x1111111111111111111111111111111111111111"), 7_000_000_000L));
        store.putWithdrawals(3L, ws);
        assertEquals(ws, store.getWithdrawals(3L));
        assertEquals(List.of(), store.getWithdrawals(4L));
        store.removeAbove(2L);
        assertEquals(List.of(), store.getWithdrawals(3L));
    }

    @Test
    public void release_journal_round_trips_and_deletes_explicitly() {
        List<BridgeWithdrawal> released = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x2222222222222222222222222222222222222222"), 9L));
        store.putReleases(7L, released);
        assertEquals(released, store.getReleases(7L));
        store.deleteReleases(7L);
        assertEquals(List.of(), store.getReleases(7L));
    }

    @Test
    public void release_journal_is_swept_by_removeAbove() {
        store.putReleases(5L, List.of(new BridgeWithdrawal(Bytes.fromHexString(
                "0x2222222222222222222222222222222222222222"), 1L)));
        store.removeAbove(4L);
        assertEquals(List.of(), store.getReleases(5L));
    }

    @Test
    public void maturity_entry_round_trips_refs_deposits_hash_and_timestamp() {
        Bytes32 hash = Bytes32.fromHexString("0x" + "77".repeat(32));
        Bytes32 refA = Bytes32.fromHexString("0x" + "aa".repeat(32));
        Bytes32 refB = Bytes32.fromHexString("0x" + "bb".repeat(32));
        Address target = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
        BridgeDeposit dep = new BridgeDeposit(target, 12345L);

        store.putMaturityEntry(7L, hash, 1700L, List.of(refA, refB), List.of(dep));

        EvmMetaStore.MaturityEntry back = store.getMaturityEntry(7L).orElseThrow();
        assertEquals(hash, back.blockHash());
        assertEquals(1700L, back.timestampSeconds());
        assertEquals(List.of(refA, refB), back.refs());
        assertEquals(1, back.deposits().size());
        assertEquals(target, back.deposits().get(0).target());
        assertEquals(12345L, back.deposits().get(0).amountNano());
    }

    @Test
    public void maturity_entry_handles_empty_refs_and_empty_deposits() {
        Bytes32 hash = Bytes32.fromHexString("0x" + "05".repeat(32));
        Address target = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
        store.putMaturityEntry(3L, hash, 900L, List.of(), List.of(new BridgeDeposit(target, 7L)));
        EvmMetaStore.MaturityEntry depOnly = store.getMaturityEntry(3L).orElseThrow();
        assertTrue(depOnly.refs().isEmpty());
        assertEquals(1, depOnly.deposits().size());

        Bytes32 ref = Bytes32.fromHexString("0x" + "cc".repeat(32));
        store.putMaturityEntry(4L, hash, 901L, List.of(ref), List.of());
        EvmMetaStore.MaturityEntry refOnly = store.getMaturityEntry(4L).orElseThrow();
        assertEquals(List.of(ref), refOnly.refs());
        assertTrue(refOnly.deposits().isEmpty());
    }

    @Test
    public void maturity_entry_is_absent_and_removable() {
        assertTrue(store.getMaturityEntry(99L).isEmpty());
        Bytes32 hash = Bytes32.fromHexString("0x" + "01".repeat(32));
        store.putMaturityEntry(5L, hash, 500L, List.of(), List.of());
        assertTrue(store.getMaturityEntry(5L).isPresent());
        store.removeMaturityEntry(5L);
        assertTrue(store.getMaturityEntry(5L).isEmpty());
    }

    @Test
    public void remove_above_clears_maturity_entries_beyond_the_height() {
        Bytes32 hash = Bytes32.fromHexString("0x" + "01".repeat(32));
        store.putMaturityEntry(4L, hash, 400L, List.of(), List.of());
        store.putMaturityEntry(5L, hash, 500L, List.of(), List.of());
        store.putMaturityEntry(6L, hash, 600L, List.of(), List.of());
        store.removeAbove(4L);
        assertTrue("<=4 survives", store.getMaturityEntry(4L).isPresent());
        assertTrue(">4 is swept", store.getMaturityEntry(5L).isEmpty());
        assertTrue(">4 is swept", store.getMaturityEntry(6L).isEmpty());
    }

    @Test
    public void skip_marker_round_trips_and_defaults_false() {
        assertFalse("unmarked height is not skipped", store.isSkipped(8L));
        store.putSkipMarker(8L);
        assertTrue("marked height is skipped", store.isSkipped(8L));
        assertFalse("a different height is unaffected", store.isSkipped(9L));
    }

    @Test
    public void remove_above_clears_skip_markers_beyond_the_height() {
        store.putSkipMarker(4L);
        store.putSkipMarker(5L);
        store.putSkipMarker(6L);
        store.removeAbove(4L);
        assertTrue("<=4 survives", store.isSkipped(4L));
        assertFalse(">4 is swept", store.isSkipped(5L));
        assertFalse(">4 is swept", store.isSkipped(6L));
    }

    @Test
    public void maturity_heights_are_enumerated_ascending() {
        assertEquals(List.of(), store.maturityHeights());
        Bytes32 hash = Bytes32.fromHexString("0x" + "01".repeat(32));
        store.putMaturityEntry(5L, hash, 500L, List.of(), List.of());
        store.putMaturityEntry(2L, hash, 200L, List.of(), List.of());
        store.putMaturityEntry(9L, hash, 900L, List.of(), List.of());
        assertEquals(List.of(2L, 5L, 9L), store.maturityHeights());
    }

    @Test
    public void fee_debit_journal_roundtrip_and_sweep() {
        // Absent -> 0 (no journal, nothing to reverse).
        assertEquals(0L, store.getFeeDebit(7L));

        store.putFeeDebit(7L, 123_456L);
        assertEquals(123_456L, store.getFeeDebit(7L));

        // Consumed by the unwind reversal: delete -> absent again.
        store.deleteFeeDebit(7L);
        assertEquals(0L, store.getFeeDebit(7L));

        // removeAbove sweeps records past the fork point but keeps those at or below it.
        store.putFeeDebit(5L, 11L);
        store.putFeeDebit(9L, 22L);
        store.removeAbove(5L);
        assertEquals("at the fork point survives", 11L, store.getFeeDebit(5L));
        assertEquals("past the fork point swept", 0L, store.getFeeDebit(9L));
    }

    @Test
    public void fee_debit_journal_rejects_non_positive_amounts() {
        assertThrows(IllegalArgumentException.class, () -> store.putFeeDebit(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> store.putFeeDebit(1L, -5L));
    }

    @Test
    public void fee_debit_record_with_sign_bit_set_fails_fast_on_read() {
        // A raw 0x0B record whose value field has the sign bit set decodes to a negative long; the
        // reader must fail fast like the other corrupt-record readers, instead of silently returning
        // a negative nano amount that would corrupt the unwind-reversal arithmetic in unSetMain.
        InMemoryKVSource kv = new InMemoryKVSource();
        EvmMetaStore meta = new EvmMetaStore(kv);
        byte[] value = new byte[8];
        value[0] = (byte) 0x80; // sign bit set -> getLong(0) yields a negative value
        kv.put(feeDebitKeyForTest(3L), value);
        assertThrows(IllegalStateException.class, () -> meta.getFeeDebit(3L));
    }

    /** Mirrors EvmMetaStore's private feeDebitKey layout (0x0B | height 8-byte BE) for the corrupt-value test. */
    private static byte[] feeDebitKeyForTest(long height) {
        byte[] key = new byte[9];
        key[0] = 0x0B;
        for (int i = 0; i < 8; i++) {
            key[1 + i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }

    @Test
    public void bridge_record_families_are_independent_at_a_shared_height() {
        // 0x06 (deposits), 0x07 (burns) and 0x08 (releases) legitimately coexist at one height —
        // e.g. a release height that also carries new deposits and burns. Same-height writes must
        // not clobber each other, and the explicit 0x08 delete must leave 0x06/0x07 intact.
        List<BridgeDeposit> deposits = List.of(
                new BridgeDeposit(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"), 11L));
        List<BridgeWithdrawal> withdrawals = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x1111111111111111111111111111111111111111"), 22L));
        List<BridgeWithdrawal> releases = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x2222222222222222222222222222222222222222"), 33L));
        store.putDeposits(7L, deposits);
        store.putWithdrawals(7L, withdrawals);
        store.putReleases(7L, releases);

        assertEquals(deposits, store.getDeposits(7L));
        assertEquals(withdrawals, store.getWithdrawals(7L));
        assertEquals(releases, store.getReleases(7L));

        store.deleteReleases(7L);
        assertEquals(List.of(), store.getReleases(7L));
        assertEquals("deleteReleases must not touch the 0x06 family", deposits, store.getDeposits(7L));
        assertEquals("deleteReleases must not touch the 0x07 family", withdrawals, store.getWithdrawals(7L));
    }
}
