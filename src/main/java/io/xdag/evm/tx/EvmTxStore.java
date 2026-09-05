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
package io.xdag.evm.tx;

import io.xdag.db.rocksdb.KVSource;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

/**
 * The EVM_TX store: signed EIP-155 RLP blobs keyed by transaction hash (spec §4.2).
 *
 * <pre>
 *   0x00 | txHash(32)    -> raw signed RLP blob
 *   0x01 | batchHash(32) -> RLP list of 32-byte tx hashes (batch D2)
 * </pre>
 *
 * Writes are idempotent — the key is the keccak256 of the value, so re-putting the same blob is a
 * no-op by construction.
 */
@Slf4j
public class EvmTxStore {

    private static final byte PREFIX_TX = 0x00;
    /** Batch bodies (spec §5): 0x01 | keccak256(body) -> RLP list of 32-byte tx hashes. */
    private static final byte PREFIX_BATCH = 0x01;
    /**
     * Hard cap on txs per batch (spec §2): the largest batch whose RLP body still fits one
     * EVM_BATCH_REPLY under the default {@code evm.maxP2pTxBytes} = 131072 — each hash encodes
     * to 33 bytes plus a 4-byte list header, and 33 * 3971 + 4 = 131047. Consensus-relevant:
     * nodes with different caps disagree on which batches execute, so raising it requires a
     * coordinated upgrade. An over-sized crafted body reads as a miss.
     */
    public static final int MAX_BATCH_TXS = 3971;

    private final KVSource<byte[], byte[]> store;

    public EvmTxStore(KVSource<byte[], byte[]> store) {
        this.store = store;
    }

    private static byte[] txKey(Hash txHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_TX), txHash.getBytes()).toArray();
    }

    public void put(EvmTransaction tx) {
        putRaw(tx.getHash(), tx.getRawRlp());
    }

    public void putRaw(Hash txHash, Bytes rawRlp) {
        store.put(txKey(txHash), rawRlp.toArray());
    }

    public Optional<Bytes> get(Hash txHash) {
        byte[] raw = store.get(txKey(txHash));
        return raw == null ? Optional.empty() : Optional.of(Bytes.wrap(raw));
    }

    /** Loads and re-decodes the blob; empty if absent. Throws if a stored blob is corrupt. */
    public Optional<EvmTransaction> getDecoded(Hash txHash) {
        return get(txHash).map(EvmTransaction::decode);
    }

    public boolean contains(Hash txHash) {
        return store.get(txKey(txHash)) != null;
    }

    public void remove(Hash txHash) {
        store.delete(txKey(txHash));
    }

    private static byte[] batchKey(Hash batchHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_BATCH), batchHash.getBytes()).toArray();
    }

    /** Canonical batch-body encoding: an RLP list of the ordered 32-byte tx hashes. */
    public static Bytes encodeBatch(List<Bytes32> txHashes) {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        txHashes.forEach(out::writeBytes);
        out.endList();
        return out.encoded();
    }

    /** Stores a batch body content-addressed; returns its commitment hash. Idempotent. */
    public Hash putBatch(Bytes body) {
        Hash batchHash = Hash.hash(body);
        store.put(batchKey(batchHash), body.toArray());
        return batchHash;
    }

    /** Raw body bytes for a stored batch; empty if the hash is unknown. */
    public Optional<Bytes> getBatchRaw(Hash batchHash) {
        byte[] raw = store.get(batchKey(batchHash));
        return raw == null ? Optional.empty() : Optional.of(Bytes.wrap(raw));
    }

    /** True if a batch body is stored under this commitment hash. */
    public boolean containsBatch(Hash batchHash) {
        return store.get(batchKey(batchHash)) != null;
    }

    /**
     * Decodes a stored batch body into its ordered tx hashes. A malformed, empty, or over-sized
     * body reads as a miss (with a warning) — this is the execution-side clamp on crafted
     * batches (spec §5/§9).
     */
    public Optional<List<Bytes32>> getBatch(Hash batchHash) {
        Optional<Bytes> raw = getBatchRaw(batchHash);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<Bytes32>> hashes = decodeBatchBody(raw.get());
        if (hashes.isEmpty()) {
            log.warn("Batch {} body is not a valid batch body (1..{} 32-byte items); treating as miss",
                    batchHash, MAX_BATCH_TXS);
        }
        return hashes;
    }

    /**
     * Decodes {@code body} as a batch body: an RLP list of 1..{@link #MAX_BATCH_TXS} 32-byte tx hashes.
     * Anything else (malformed RLP, wrong item width, empty, over-sized) is empty. This is THE
     * classifier for content-addressed consensus bytes (audit round 2, P1): a body is a batch iff it
     * parses as one, regardless of which P2P message delivered it. A signed tx can never parse as a
     * batch body -- its RLP list carries a 20-byte/empty {@code to} and short scalars -- so the two
     * keyspaces are disjoint by content.
     */
    public static Optional<List<Bytes32>> decodeBatchBody(Bytes body) {
        try {
            RLPInput in = RLP.input(body);
            List<Bytes32> hashes = in.readList(RLPInput::readBytes32);
            if (hashes.isEmpty() || hashes.size() > MAX_BATCH_TXS) {
                return Optional.empty();
            }
            return Optional.of(hashes);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** True iff {@code body} parses as a batch body (see {@link #decodeBatchBody}). */
    public static boolean isBatchBody(Bytes body) {
        return decodeBatchBody(body).isPresent();
    }
}
