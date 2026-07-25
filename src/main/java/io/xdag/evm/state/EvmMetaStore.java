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
    private static final int HEIGHT_RECORD_LENGTH = 32 + 32 + 4;

    private final KVSource<byte[], byte[]> store;

    public EvmMetaStore(KVSource<byte[], byte[]> store) {
        this.store = store;
    }

    /** Decoded per-height checkpoint record. */
    public record HeightRecord(Bytes32 stateRoot, Bytes32 blockHash, int txCount) {
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

    public void putHeightRecord(long height, Bytes32 stateRoot, Bytes32 blockHash, int txCount) {
        byte[] value = new byte[HEIGHT_RECORD_LENGTH];
        System.arraycopy(stateRoot.toArray(), 0, value, 0, 32);
        System.arraycopy(blockHash.toArray(), 0, value, 32, 32);
        for (int i = 0; i < 4; i++) {
            value[64 + i] = (byte) (txCount >>> (24 - 8 * i));
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
        return Optional.of(new HeightRecord(Bytes32.wrap(b.slice(0, 32)), Bytes32.wrap(b.slice(32, 32)), txCount));
    }

    /** The highest checkpointed main height, or empty before the first EVM main block. */
    public Optional<Long> highestHeight() {
        long max = -1;
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_HEIGHT})) {
            max = Math.max(max, heightFromKey(key));
        }
        return max < 0 ? Optional.empty() : Optional.of(max);
    }

    /** Deletes every height record strictly above {@code height} (reorg truncation). */
    public void removeAbove(long height) {
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_HEIGHT})) {
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
