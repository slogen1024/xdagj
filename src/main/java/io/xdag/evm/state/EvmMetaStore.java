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

import io.xdag.db.rocksdb.KVSource;
import io.xdag.evm.bridge.BridgeDeposit;
import io.xdag.evm.bridge.BridgeWithdrawal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

/**
 * The EVM_META store: per-main-block execution metadata (spec §4.3).
 *
 * <pre>
 *   0x00 | mainHeight(8 BE) -> stateRoot(32) | blockHash(32) | txCount(4 BE) | timestamp(8 BE)
 *   0x01 | txHash(32)       -> receipt RLP
 *   0x02 | mainHeight(8 BE) -> concatenated txHash(32) execution order (reorg replay script)
 *   0x03 | mainHeight(8 BE) -> deferred (I4) pending block: blockHash(32) | timestamp(8 BE) | refs
 *   0x04 | txHash(32)       -> mainHeight(8 BE) | index(4 BE)  (reverse index for eth_getTransaction*)
 *   0x05 | mainHeight(8 BE) -> 256-byte logs bloom (C5 eth_getLogs skip index; advisory, not consensus)
 *   0x06 | mainHeight(8 BE) -> confirmed bridge deposits: concatenated (address20 | amountNano 8 BE) entries
 *   0x07 | mainHeight(8 BE) -> bridge burns at burn height: concatenated (nativeTarget20 | amountNano 8 BE) entries
 *   0x08 | mainHeight(8 BE) -> native releases at release height: same entry shape as 0x07
 *   0x09 | mainHeight(8 BE) -> maturity buffer (G2-T1a): blockHash(32) | timestamp(8 BE) | refCount(4 BE) | refs(32 each) | deposits(28 each)
 *   0x0A | mainHeight(8 BE) -> committed-skip marker (G2-T1b): a 1-byte presence flag (value {0x01})
 *   0x0B | mainHeight(8 BE) -> fee-debit journal (A4): nano debited from the bridge lock (8 BE)
 *   0x0C | mainHeight(8 BE) -> matured-entry archive (C1): the 0x09 record of a height AFTER it matured,
 *                              kept so a shallow reorg can re-open the height (same layout as 0x09)
 * </pre>
 *
 * Height records are the reorg checkpoints: {@link #removeAbove(long)} truncates everything past a
 * fork point (B2b's unWindMain path).
 */
public class EvmMetaStore {

    private static final byte PREFIX_HEIGHT = 0x00;
    private static final byte PREFIX_RECEIPT = 0x01;
    private static final byte PREFIX_TX_LIST = 0x02;
    /** Main heights whose EVM execution is deferred until a referenced blob arrives (I4 stall queue). */
    private static final byte PREFIX_PENDING = 0x03;
    /** Reverse index txHash -> (height, index) so eth_getTransaction* resolves in one point lookup. */
    private static final byte PREFIX_TX_LOCATION = 0x04;
    /** Per-height 256-byte Ethereum logs bloom (C5): height -> OR of that height's logs' address+topics. */
    private static final byte PREFIX_LOG_BLOOM = 0x05;
    /** Bridge deposits (spec §2.2): height -> concatenated (address20 | amountNano 8 BE) entries. */
    private static final byte PREFIX_DEPOSITS = 0x06;
    /** Bridge burns by burn height (spec §3.2): 0x07 | height(8 BE) -> (nativeTarget20 | amountNano 8 BE)*. */
    private static final byte PREFIX_WITHDRAWALS = 0x07;
    /**
     * Native releases ACTUALLY performed at a release height: 0x08 | height(8 BE) -> same entry shape.
     * Written by setMain when it releases, consumed (read + deleted) by the unwind reversal —
     * reverse-what-you-did bookkeeping, immune to release-skip asymmetries.
     */
    private static final byte PREFIX_RELEASES = 0x08;
    /**
     * Maturity buffer (G2-T1a): 0x09 | height(8 BE) -> blockHash(32) | timestampSeconds(8 BE) |
     * refCount(4 BE) | refs(32 each) | deposits(BRIDGE_ENTRY_LENGTH each). Holds a confirmed
     * height's EVM execution inputs until it reaches finality depth delta.
     */
    private static final byte PREFIX_MATURITY = 0x09;
    /**
     * Committed-skip marker (G2-T1b): 0x0A | height(8 BE) -> 1-byte presence flag ({@code 0x01}).
     * Written once by setMain when a block-committed "skip" bit causes the height to be skipped.
     * Read by {@code EvmBlockProcessor.executeList} on live execution and reorg replay so both fold
     * the SKIP_SENTINEL identically. Swept by removeAbove on reorg.
     */
    private static final byte PREFIX_SKIP = 0x0A;
    /**
     * Fee-debit journal (A4 transfer-from-lock): 0x0B | height(8 BE) -> feeNano(8 BE). The native
     * nano ACTUALLY debited from the bridge lock when height M's net EVM fee was credited to block M.
     * Written by BlockchainImpl.creditEvmFee; consumed (read + deleted) by the unwind reversal
     * (reverse-what-you-did bookkeeping, 0x08 precedent); removeAbove-swept for hygiene.
     */
    private static final byte PREFIX_FEE_DEBIT = 0x0B;
    /**
     * Matured-entry archive (audit round 2, C1): 0x0C | height(8 BE) -> the exact 0x09 record of a
     * height once it has matured. Under delta-lagged execution height M is DECIDED (execute vs skip) by
     * block M+delta-1; if that block is unwound by a reorg shallower than delta, M's outcome must be
     * undone and its inputs re-buffered so the replacement block re-decides it. Restored into 0x09 by
     * {@code EvmBlockProcessor.rollbackForReorg}; pruned by {@link #removeArchivedAtOrBelow}; swept
     * above the NATIVE boundary of {@link #removeAbove(long, long)}.
     */
    private static final byte PREFIX_MATURITY_ARCHIVE = 0x0C;
    private static final int HEIGHT_RECORD_LENGTH = 32 + 32 + 4 + 8;
    private static final int PENDING_HEADER_LENGTH = 32 + 8; // blockHash(32) | timestampSeconds(8)
    private static final int LOCATION_RECORD_LENGTH = 8 + 4; // height(8 BE) | index(4 BE)
    private static final int LOG_BLOOM_LENGTH = 256; // fixed Ethereum logs-bloom width (2048 bits)
    // target/address(20) | amountNano(8 BE) — shared by the 0x06/0x07/0x08 record families
    private static final int BRIDGE_ENTRY_LENGTH = 20 + 8;
    // Maturity buffer (G2-T1a): blockHash(32) | timestampSeconds(8 BE) | refCount(4 BE) | refs(32 each)
    // | deposits(BRIDGE_ENTRY_LENGTH each). Holds a confirmed height's EVM execution inputs until the
    // height reaches finality depth delta and setMain matures it.
    private static final int MATURITY_HEADER_LENGTH = 32 + 8 + 4;

    private final KVSource<byte[], byte[]> store;

    public EvmMetaStore(KVSource<byte[], byte[]> store) {
        this.store = store;
    }

    /** A deferred main block: its identity, timestamp, and the tx refs awaiting blobs (I4). */
    public record PendingBlock(Bytes32 blockHash, long timestampSeconds, List<Bytes32> refs) {
    }

    /** A confirmed main block's EVM execution inputs, buffered until the height matures (G2-T1a). */
    public record MaturityEntry(Bytes32 blockHash, long timestampSeconds, List<Bytes32> refs,
                                List<BridgeDeposit> deposits) {
    }

    /** Decoded per-height checkpoint record; the timestamp feeds deterministic replay (TIMESTAMP opcode). */
    public record HeightRecord(Bytes32 stateRoot, Bytes32 blockHash, int txCount, long timestampSeconds) {
    }

    /** Where a tx sits on the canonical chain: its main height and index within that height's ordered list. */
    public record TxLocation(long height, int index) {
    }

    private static byte[] heightKey(long height) {
        byte[] key = new byte[9];
        key[0] = PREFIX_HEIGHT;
        for (int i = 0; i < 8; i++) {
            key[1 + i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }

    private static long heightFromKey(byte[] key) {
        long height = 0;
        for (int i = 0; i < 8; i++) {
            height = (height << 8) | (key[1 + i] & 0xFFL);
        }
        return height;
    }

    private static byte[] receiptKey(Hash txHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_RECEIPT), txHash.getBytes()).toArray();
    }

    public void putHeightRecord(long height, Bytes32 stateRoot, Bytes32 blockHash, int txCount,
                                long timestampSeconds) {
        byte[] value = new byte[HEIGHT_RECORD_LENGTH];
        System.arraycopy(stateRoot.toArray(), 0, value, 0, 32);
        System.arraycopy(blockHash.toArray(), 0, value, 32, 32);
        for (int i = 0; i < 4; i++) {
            value[64 + i] = (byte) (txCount >>> (24 - 8 * i));
        }
        for (int i = 0; i < 8; i++) {
            value[68 + i] = (byte) (timestampSeconds >>> (56 - 8 * i));
        }
        store.put(heightKey(height), value);
    }

    public Optional<HeightRecord> getHeightRecord(long height) {
        byte[] raw = store.get(heightKey(height));
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.length != HEIGHT_RECORD_LENGTH) {
            throw new IllegalStateException("corrupt EVM_META height record: " + raw.length + " bytes");
        }
        Bytes b = Bytes.wrap(raw);
        int txCount = b.getInt(64);
        long timestampSeconds = b.getLong(68);
        return Optional.of(new HeightRecord(Bytes32.wrap(b.slice(0, 32)), Bytes32.wrap(b.slice(32, 32)),
                txCount, timestampSeconds));
    }

    /** The highest checkpointed main height, or empty before the first EVM main block. */
    public Optional<Long> highestHeight() {
        long max = -1;
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_HEIGHT})) {
            max = Math.max(max, heightFromKey(key));
        }
        return max < 0 ? Optional.empty() : Optional.of(max);
    }

    /**
     * The highest checkpointed main height that is {@code <= ceiling}, or empty if none. Bounded
     * variant of {@link #highestHeight()} — used to read the chained root "as of" a height (empty
     * and deposit-only heights inherit the prior checkpoint, since the chained root only advances
     * on checkpointed heights).
     */
    public Optional<Long> highestHeightAtMost(long ceiling) {
        long max = -1;
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_HEIGHT})) {
            long h = heightFromKey(key);
            if (h <= ceiling) {
                max = Math.max(max, h);
            }
        }
        return max < 0 ? Optional.empty() : Optional.of(max);
    }

    private static byte[] txListKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_TX_LIST;
        return key;
    }

    /** Persists the exact execution order of a main block's txs — the replay script for reorgs. */
    public void putTxList(long height, List<Hash> txHashes) {
        Bytes[] parts = new Bytes[txHashes.size()];
        for (int i = 0; i < txHashes.size(); i++) {
            Hash txHash = txHashes.get(i);
            parts[i] = txHash.getBytes();
            // Maintain the reverse index in lock-step with the tx list (its sole writer): a tx is
            // deduplicated across the chain, so each txHash maps to exactly one (height, index).
            store.put(locationKey(txHash), encodeLocation(height, i));
        }
        store.put(txListKey(height), Bytes.concatenate(parts).toArray());
    }

    private static byte[] locationKey(Hash txHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_TX_LOCATION), txHash.getBytes()).toArray();
    }

    private static byte[] encodeLocation(long height, int index) {
        byte[] value = new byte[LOCATION_RECORD_LENGTH];
        for (int i = 0; i < 8; i++) {
            value[i] = (byte) (height >>> (56 - 8 * i));
        }
        for (int i = 0; i < 4; i++) {
            value[8 + i] = (byte) (index >>> (24 - 8 * i));
        }
        return value;
    }

    /**
     * O(1) reverse lookup of a tx's canonical (height, index); empty if the tx is not on the chain.
     * Backs eth_getTransactionByHash / eth_getTransactionReceipt, replacing a full-history scan of
     * every height's tx list — the previous scan let a bogus hash force an unbounded walk per call.
     */
    public Optional<TxLocation> findTxLocation(Hash txHash) {
        byte[] raw = store.get(locationKey(txHash));
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.length != LOCATION_RECORD_LENGTH) {
            throw new IllegalStateException("corrupt EVM_META tx location: " + raw.length + " bytes");
        }
        Bytes b = Bytes.wrap(raw);
        return Optional.of(new TxLocation(b.getLong(0), b.getInt(8)));
    }

    /** All heights that have a recorded tx list, ascending — the replay schedule. */
    public List<Long> txListHeights() {
        List<Long> heights = new ArrayList<>();
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_TX_LIST})) {
            heights.add(heightFromKey(key));
        }
        heights.sort(Long::compareTo);
        return heights;
    }

    /** The ordered tx hashes executed at {@code height}; empty when none were recorded. */
    public List<Hash> getTxList(long height) {
        byte[] raw = store.get(txListKey(height));
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        if (raw.length % 32 != 0) {
            throw new IllegalStateException("corrupt EVM_META tx list: " + raw.length + " bytes");
        }
        List<Hash> hashes = new ArrayList<>(raw.length / 32);
        Bytes b = Bytes.wrap(raw);
        for (int off = 0; off < raw.length; off += 32) {
            hashes.add(Hash.wrap(Bytes32.wrap(b.slice(off, 32))));
        }
        return hashes;
    }

    private static byte[] pendingKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_PENDING;
        return key;
    }

    private static byte[] maturityKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_MATURITY;
        return key;
    }
    private static byte[] archiveKey(long height) {
        byte[] key = new byte[9];
        key[0] = PREFIX_MATURITY_ARCHIVE;
        for (int i = 0; i < 8; i++) {
            key[1 + i] = (byte) (height >>> (56 - 8 * i));
        }
        return key;
    }


    private static byte[] skipKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_SKIP;
        return key;
    }

    private static byte[] feeDebitKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_FEE_DEBIT;
        return key;
    }

    private static byte[] bloomKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_LOG_BLOOM;
        return key;
    }

    /** Records a main block whose EVM execution is deferred until its blobs arrive (I4). */
    public void putPending(long height, Bytes32 blockHash, long timestampSeconds, List<Bytes32> refs) {
        Bytes[] parts = new Bytes[refs.size() + 2];
        parts[0] = blockHash;
        parts[1] = Bytes.ofUnsignedLong(timestampSeconds);
        for (int i = 0; i < refs.size(); i++) {
            parts[i + 2] = refs.get(i);
        }
        store.put(pendingKey(height), Bytes.concatenate(parts).toArray());
    }

    /** Deferred main heights, ascending — execution must resume in this order (I4 stall queue). */
    public List<Long> pendingHeights() {
        List<Long> heights = new ArrayList<>();
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_PENDING})) {
            heights.add(heightFromKey(key));
        }
        heights.sort(Long::compareTo);
        return heights;
    }

    /** Buffered (in-delta-window, not-yet-matured) main heights, ascending (G2-T2 proactive fetch). */
    public List<Long> maturityHeights() {
        List<Long> heights = new ArrayList<>();
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_MATURITY})) {
            heights.add(heightFromKey(key));
        }
        heights.sort(Long::compareTo);
        return heights;
    }

    public Optional<PendingBlock> getPending(long height) {
        byte[] raw = store.get(pendingKey(height));
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.length < PENDING_HEADER_LENGTH || (raw.length - PENDING_HEADER_LENGTH) % 32 != 0) {
            throw new IllegalStateException("corrupt EVM_META pending record: " + raw.length + " bytes");
        }
        Bytes b = Bytes.wrap(raw);
        Bytes32 blockHash = Bytes32.wrap(b.slice(0, 32));
        long timestampSeconds = b.getLong(32);
        List<Bytes32> refs = new ArrayList<>((raw.length - PENDING_HEADER_LENGTH) / 32);
        for (int off = PENDING_HEADER_LENGTH; off < raw.length; off += 32) {
            refs.add(Bytes32.wrap(b.slice(off, 32)));
        }
        return Optional.of(new PendingBlock(blockHash, timestampSeconds, refs));
    }

    public void removePending(long height) {
        store.delete(pendingKey(height));
    }

    /**
     * Buffers a confirmed main block's EVM execution inputs until its height reaches finality depth
     * delta (G2-T1a). Written once per confirmed payload-bearing height; matured (and removed) by
     * {@code EvmBlockProcessor.processConfirmedBlock}. Deposit amounts are non-negative (guarded).
     */
    public void putMaturityEntry(long height, Bytes32 blockHash, long timestampSeconds,
                                 List<Bytes32> refs, List<BridgeDeposit> deposits) {
        byte[] value = new byte[MATURITY_HEADER_LENGTH + refs.size() * 32
                + deposits.size() * BRIDGE_ENTRY_LENGTH];
        System.arraycopy(blockHash.toArray(), 0, value, 0, 32);
        for (int i = 0; i < 8; i++) {
            value[32 + i] = (byte) (timestampSeconds >>> (56 - 8 * i));
        }
        int refCount = refs.size();
        for (int i = 0; i < 4; i++) {
            value[40 + i] = (byte) (refCount >>> (24 - 8 * i));
        }
        int pos = MATURITY_HEADER_LENGTH;
        for (Bytes32 ref : refs) {
            System.arraycopy(ref.toArray(), 0, value, pos, 32);
            pos += 32;
        }
        for (BridgeDeposit d : deposits) {
            if (d.amountNano() < 0) {
                throw new IllegalArgumentException(
                        "negative deposit amount " + d.amountNano() + " for " + d.target());
            }
            System.arraycopy(d.target().getBytes().toArray(), 0, value, pos, 20);
            long nano = d.amountNano();
            for (int i = 0; i < 8; i++) {
                value[pos + 20 + i] = (byte) (nano >>> (56 - 8 * i));
            }
            pos += BRIDGE_ENTRY_LENGTH;
        }
        store.put(maturityKey(height), value);
    }

    /** The buffered execution inputs at {@code height}, or empty if none is buffered. */
    public Optional<MaturityEntry> getMaturityEntry(long height) {
        return decodeMaturityEntry(height, store.get(maturityKey(height)));
    }

    /** The archived (already matured) execution inputs at {@code height}, or empty (C1). */
    public Optional<MaturityEntry> getArchivedMaturityEntry(long height) {
        return decodeMaturityEntry(height, store.get(archiveKey(height)));
    }

    /**
     * Moves {@code height}'s live buffer record (0x09) to the archive (0x0C) once the height has
     * matured. Returns false when nothing was buffered (a payload-free height).
     */
    public boolean archiveMaturityEntry(long height) {
        byte[] raw = store.get(maturityKey(height));
        if (raw == null) {
            return false;
        }
        store.put(archiveKey(height), raw);
        store.delete(maturityKey(height));
        return true;
    }

    /**
     * Re-opens {@code height}: moves its archived record back into the live buffer (0x09) so the next
     * confirming block matures -- and decides -- it again. Returns false when no archive exists.
     */
    public boolean restoreMaturityEntry(long height) {
        byte[] raw = store.get(archiveKey(height));
        if (raw == null) {
            return false;
        }
        store.put(maturityKey(height), raw);
        store.delete(archiveKey(height));
        return true;
    }

    /** Prunes archived entries at heights {@code <= height} (older than any re-openable reorg). */
    public void removeArchivedAtOrBelow(long height) {
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_MATURITY_ARCHIVE})) {
            if (heightFromKey(key) <= height) {
                store.delete(key);
            }
        }
    }

    private static Optional<MaturityEntry> decodeMaturityEntry(long height, byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.length < MATURITY_HEADER_LENGTH) {
            throw new IllegalStateException("corrupt EVM_META maturity record at height " + height
                    + ": " + raw.length + " bytes");
        }
        Bytes b = Bytes.wrap(raw);
        Bytes32 blockHash = Bytes32.wrap(b.slice(0, 32));
        long timestampSeconds = b.getLong(32);
        int refCount = b.getInt(40);
        // long arithmetic: a corrupt record could carry a refCount whose *32 overflows an int and
        // wraps back into a "valid-looking" offset (matching a header-only length), which would then
        // drive a runaway refs loop. Widening makes the corruption guard catch it.
        long depositsOffsetLong = (long) MATURITY_HEADER_LENGTH + (long) refCount * 32;
        if (refCount < 0 || depositsOffsetLong > raw.length
                || (raw.length - depositsOffsetLong) % BRIDGE_ENTRY_LENGTH != 0) {
            throw new IllegalStateException("corrupt EVM_META maturity record at height " + height
                    + ": " + raw.length + " bytes, refCount " + refCount);
        }
        int depositsOffset = (int) depositsOffsetLong; // <= raw.length after the guard, so narrowing is safe
        List<Bytes32> refs = new ArrayList<>(refCount);
        for (int i = 0; i < refCount; i++) {
            refs.add(Bytes32.wrap(b.slice(MATURITY_HEADER_LENGTH + i * 32, 32)));
        }
        List<BridgeDeposit> deposits = new ArrayList<>();
        for (int off = depositsOffset; off < raw.length; off += BRIDGE_ENTRY_LENGTH) {
            Address target = Address.wrap(Bytes.wrap(raw, off, 20));
            long nano = Bytes.wrap(raw, off + 20, 8).getLong(0);
            if (nano < 0) {
                throw new IllegalStateException("corrupt EVM_META maturity record at height " + height
                        + ": negative deposit amount " + nano);
            }
            deposits.add(new BridgeDeposit(target, nano));
        }
        return Optional.of(new MaturityEntry(blockHash, timestampSeconds, refs, deposits));
    }

    /** Drops the buffered entry at {@code height} once it has been matured (G2-T1a). */
    public void removeMaturityEntry(long height) {
        store.delete(maturityKey(height));
    }

    /**
     * Marks a main height as committed-skip (ADR-015 / G2-T1b): its EVM txs do NOT execute, but the
     * height still checkpoints with a canonical SKIP_SENTINEL root. Read by {@code EvmBlockProcessor.
     * executeList} on both live execution and reorg replay so the two fold the sentinel identically.
     * removeAbove-swept on reorg. Presence-only (value {@code 0x01}); write-once per height.
     */
    public void putSkipMarker(long height) {
        store.put(skipKey(height), new byte[]{1});
    }

    /** True iff {@code height} was committed-skip (see {@link #putSkipMarker}). */
    public boolean isSkipped(long height) {
        return store.get(skipKey(height)) != null;
    }

    /**
     * Deletes every height record, tx list, per-tx receipt, reverse-index entry, pending record,
     * logs bloom, deposits, withdrawals, releases, maturity buffer, skip markers, and fee-debit
     * journal entries strictly above {@code height} (reorg truncation). Receipts and reverse-index
     * entries must go too, otherwise a reorged-out tx keeps advertising a stale success/contract-address
     * through {@link #getReceipt} or a stale (height, index) through {@link #findTxLocation}.
     */
    /** Legacy single-boundary sweep: both boundaries equal (exact pre-C1 behavior; lag-1 shape). */
    public void removeAbove(long height) {
        removeAbove(height, height);
    }

    /**
     * Reorg truncation with two boundaries (audit round 2, C1). EXECUTION artifacts -- height records,
     * tx lists (+receipts, locations), pending queue, blooms, deposits, burns, skip markers -- are
     * derived from executing a height and are swept above {@code executionBoundary}; they are
     * re-derived when the height is re-matured. NATIVE-height journals -- releases (0x08), fee debits
     * (0x0B), the live maturity buffer (0x09) and its archive (0x0C) -- record facts tied to a main
     * block that may still be canonical, and are swept only above {@code nativeBoundary} (the lowest
     * unwound main height minus one). With delta-lagged execution unWindMain passes
     * {@code executionBoundary = lowestUnwound - delta} and {@code nativeBoundary = lowestUnwound - 1},
     * re-opening the heights in between; at delta=1 the two coincide. Releases at re-opened heights
     * stem from burns at or below the execution boundary (config invariant W >= delta-1) and stay
     * valid; fee debits at re-opened heights are reversed and deleted by BlockchainImpl before this
     * sweep, so a leftover would be a visible bug rather than a silently dropped journal.
     */
    public void removeAbove(long executionBoundary, long nativeBoundary) {
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_TX_LIST})) {
            if (heightFromKey(key) > executionBoundary) {
                for (Hash txHash : getTxList(heightFromKey(key))) {
                    store.delete(receiptKey(txHash));
                    store.delete(locationKey(txHash));
                }
                store.delete(key);
            }
        }
        for (byte prefix : new byte[]{PREFIX_HEIGHT, PREFIX_PENDING, PREFIX_LOG_BLOOM, PREFIX_DEPOSITS,
                PREFIX_WITHDRAWALS, PREFIX_SKIP}) {
            for (byte[] key : store.prefixKeyLookup(new byte[]{prefix})) {
                if (heightFromKey(key) > executionBoundary) {
                    store.delete(key);
                }
            }
        }
        for (byte prefix : new byte[]{PREFIX_RELEASES, PREFIX_FEE_DEBIT, PREFIX_MATURITY,
                PREFIX_MATURITY_ARCHIVE}) {
            for (byte[] key : store.prefixKeyLookup(new byte[]{prefix})) {
                if (heightFromKey(key) > nativeBoundary) {
                    store.delete(key);
                }
            }
        }
    }

    public void putReceipt(Hash txHash, EvmReceipt receipt) {
        store.put(receiptKey(txHash), receipt.toRlp().toArray());
    }

    public Optional<EvmReceipt> getReceipt(Hash txHash) {
        byte[] raw = store.get(receiptKey(txHash));
        return raw == null ? Optional.empty() : Optional.of(EvmReceipt.fromRlp(Bytes.wrap(raw)));
    }

    /** Stores the height's 256-byte logs bloom (C5 skip index). Advisory: never consensus data. */
    public void putHeightBloom(long height, Bytes bloom) {
        store.put(bloomKey(height), bloom.toArray());
    }

    /**
     * The height's logs bloom, or empty when none was recorded (legacy height) OR the stored value is
     * not exactly 256 bytes (corrupt optimization artifact). Empty means "fall back to the receipt scan"
     * — an advisory index must never fail a query, unlike the fail-fast receipt/height-record readers.
     */
    public Optional<Bytes> getHeightBloom(long height) {
        byte[] raw = store.get(bloomKey(height));
        if (raw == null || raw.length != LOG_BLOOM_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(Bytes.wrap(raw));
    }

    private static byte[] depositsKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_DEPOSITS;
        return key;
    }

    /**
     * Persists the height's ordered deposit list (part of the replay script; write-once per height).
     * A repeat call silently overwrites (last write wins); the processor writes at most once per height,
     * before any defer.
     */
    public void putDeposits(long height, List<BridgeDeposit> deposits) {
        byte[] value = new byte[deposits.size() * BRIDGE_ENTRY_LENGTH];
        int pos = 0;
        for (BridgeDeposit d : deposits) {
            if (d.amountNano() < 0) {
                throw new IllegalArgumentException("negative deposit amount " + d.amountNano() + " for " + d.target());
            }
            System.arraycopy(d.target().getBytes().toArray(), 0, value, pos, 20);
            long nano = d.amountNano();
            for (int i = 0; i < 8; i++) {
                value[pos + 20 + i] = (byte) (nano >>> (56 - 8 * i));
            }
            pos += BRIDGE_ENTRY_LENGTH;
        }
        store.put(depositsKey(height), value);
    }

    /** The height's ordered deposits; empty list when none were recorded. */
    public List<BridgeDeposit> getDeposits(long height) {
        byte[] raw = store.get(depositsKey(height));
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        if (raw.length % BRIDGE_ENTRY_LENGTH != 0) {
            throw new IllegalStateException(
                    "corrupt EVM_META deposit record at height " + height + ": " + raw.length + " bytes");
        }
        List<BridgeDeposit> out = new ArrayList<>(raw.length / BRIDGE_ENTRY_LENGTH);
        for (int pos = 0; pos < raw.length; pos += BRIDGE_ENTRY_LENGTH) {
            Address target = Address.wrap(Bytes.wrap(raw, pos, 20));
            long nano = Bytes.wrap(raw, pos + 20, 8).getLong(0);
            if (nano < 0) {
                throw new IllegalStateException(
                        "corrupt EVM_META deposit record at height " + height + ": negative amount " + nano);
            }
            out.add(new BridgeDeposit(target, nano));
        }
        return out;
    }

    private static byte[] withdrawalsKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_WITHDRAWALS;
        return key;
    }

    private static byte[] releasesKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_RELEASES;
        return key;
    }

    /** Encodes a list of {@link BridgeWithdrawal} into raw bytes and stores them at {@code key}. */
    private void putBridgeWithdrawals(byte[] key, List<BridgeWithdrawal> entries, String family) {
        byte[] value = new byte[entries.size() * BRIDGE_ENTRY_LENGTH];
        int pos = 0;
        for (BridgeWithdrawal w : entries) {
            if (w.amountNano() < 0) {
                throw new IllegalArgumentException(
                        "negative " + family + " amount " + w.amountNano() + " for " + w.nativeTarget20());
            }
            System.arraycopy(w.nativeTarget20().toArray(), 0, value, pos, 20);
            long nano = w.amountNano();
            for (int i = 0; i < 8; i++) {
                value[pos + 20 + i] = (byte) (nano >>> (56 - 8 * i));
            }
            pos += BRIDGE_ENTRY_LENGTH;
        }
        store.put(key, value);
    }

    /** Decodes a list of {@link BridgeWithdrawal} from raw bytes stored at {@code key}. */
    private List<BridgeWithdrawal> getBridgeWithdrawals(byte[] key, String family, long height) {
        byte[] raw = store.get(key);
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        if (raw.length % BRIDGE_ENTRY_LENGTH != 0) {
            throw new IllegalStateException(
                    "corrupt EVM_META " + family + " record at height " + height + ": " + raw.length + " bytes");
        }
        List<BridgeWithdrawal> out = new ArrayList<>(raw.length / BRIDGE_ENTRY_LENGTH);
        for (int pos = 0; pos < raw.length; pos += BRIDGE_ENTRY_LENGTH) {
            Bytes target = Bytes.wrap(raw, pos, 20).copy();
            long nano = Bytes.wrap(raw, pos + 20, 8).getLong(0);
            if (nano < 0) {
                throw new IllegalStateException(
                        "corrupt EVM_META " + family + " record at height " + height + ": negative amount " + nano);
            }
            out.add(new BridgeWithdrawal(target, nano));
        }
        return out;
    }

    /**
     * Persists the height's ordered burn list (part of the replay script; write-once per height).
     * Written by the executor at burn height; regenerated on replay; removeAbove-swept on reorg.
     */
    public void putWithdrawals(long height, List<BridgeWithdrawal> withdrawals) {
        putBridgeWithdrawals(withdrawalsKey(height), withdrawals, "withdrawal record");
    }

    /** The height's ordered withdrawals; empty list when none were recorded. */
    public List<BridgeWithdrawal> getWithdrawals(long height) {
        return getBridgeWithdrawals(withdrawalsKey(height), "withdrawal record", height);
    }

    /**
     * Persists the native releases ACTUALLY performed at {@code height} (release journal).
     * Written by setMain when it releases; consumed (read + deleted) by the unwind reversal;
     * also removeAbove-swept for hygiene.
     */
    public void putReleases(long height, List<BridgeWithdrawal> releases) {
        putBridgeWithdrawals(releasesKey(height), releases, "release record");
    }

    /** The height's release-journal entries; empty list when none were recorded or after deletion. */
    public List<BridgeWithdrawal> getReleases(long height) {
        return getBridgeWithdrawals(releasesKey(height), "release record", height);
    }

    /** Deletes the release journal for {@code height} (consumed by the unwind reversal). */
    public void deleteReleases(long height) {
        store.delete(releasesKey(height));
    }

    /**
     * Journals the nano ACTUALLY debited from the bridge lock for {@code height}'s EVM fee credit
     * (A4 transfer-from-lock). Written by creditEvmFee when it debits; consumed by the unwind
     * reversal. At most one credit per height (K1 convergence invariant), so put-once.
     */
    public void putFeeDebit(long height, long feeNano) {
        if (feeNano <= 0) {
            throw new IllegalArgumentException(
                    "non-positive fee debit " + feeNano + " at height " + height);
        }
        byte[] value = new byte[8];
        for (int i = 0; i < 8; i++) {
            value[i] = (byte) (feeNano >>> (56 - 8 * i));
        }
        store.put(feeDebitKey(height), value);
    }

    /** The journaled lock debit at {@code height}; 0 when none was recorded (or already reversed). */
    public long getFeeDebit(long height) {
        byte[] raw = store.get(feeDebitKey(height));
        if (raw == null) {
            return 0L;
        }
        if (raw.length != 8) {
            throw new IllegalStateException("corrupt EVM_META fee-debit record at height " + height
                    + ": " + raw.length + " bytes");
        }
        long nano = Bytes.wrap(raw).getLong(0);
        if (nano <= 0) {
            throw new IllegalStateException("corrupt EVM_META fee-debit record at height " + height
                    + ": non-positive amount " + nano);
        }
        return nano;
    }

    /** Deletes the fee-debit journal for {@code height} (consumed by the unwind reversal). */
    public void deleteFeeDebit(long height) {
        store.delete(feeDebitKey(height));
    }
}
