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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;

/**
 * The EVM_META store: per-main-block execution metadata (spec §4.3).
 *
 * <pre>
 *   0x00 | mainHeight(8 BE) -> stateRoot(32) | blockHash(32) | txCount(4 BE)
 *   0x01 | txHash(32)       -> receipt RLP
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
    private static final int HEIGHT_RECORD_LENGTH = 32 + 32 + 4 + 8;
    private static final int PENDING_HEADER_LENGTH = 32 + 8; // blockHash(32) | timestampSeconds(8)

    private final KVSource<byte[], byte[]> store;

    public EvmMetaStore(KVSource<byte[], byte[]> store) {
        this.store = store;
    }

    /** A deferred main block: its identity, timestamp, and the tx refs awaiting blobs (I4). */
    public record PendingBlock(Bytes32 blockHash, long timestampSeconds, List<Bytes32> refs) {
    }

    /** Decoded per-height checkpoint record; the timestamp feeds deterministic replay (TIMESTAMP opcode). */
    public record HeightRecord(Bytes32 stateRoot, Bytes32 blockHash, int txCount, long timestampSeconds) {
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

    private static byte[] txListKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_TX_LIST;
        return key;
    }

    /** Persists the exact execution order of a main block's txs — the replay script for reorgs. */
    public void putTxList(long height, List<Hash> txHashes) {
        Bytes[] parts = new Bytes[txHashes.size()];
        for (int i = 0; i < txHashes.size(); i++) {
            parts[i] = txHashes.get(i).getBytes();
        }
        store.put(txListKey(height), Bytes.concatenate(parts).toArray());
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
     * Deletes every height record, tx list, per-tx receipt, and pending record strictly above
     * {@code height} (reorg truncation). Receipts must go too, otherwise a reorged-out tx keeps
     * advertising a stale
     * success/contract-address through {@link #getReceipt}.
     */
    public void removeAbove(long height) {
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_TX_LIST})) {
            if (heightFromKey(key) > height) {
                for (Hash txHash : getTxList(heightFromKey(key))) {
                    store.delete(receiptKey(txHash));
                }
                store.delete(key);
            }
        }
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_HEIGHT})) {
            if (heightFromKey(key) > height) {
                store.delete(key);
            }
        }
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_PENDING})) {
            if (heightFromKey(key) > height) {
                store.delete(key);
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
}
