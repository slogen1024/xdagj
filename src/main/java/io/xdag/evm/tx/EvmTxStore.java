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
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;

/**
 * The EVM_TX store: signed EIP-155 RLP blobs keyed by transaction hash (spec §4.2).
 *
 * <pre>  0x00 | txHash(32) -> raw signed RLP</pre>
 *
 * Writes are idempotent — the key is the keccak256 of the value, so re-putting the same blob is a
 * no-op by construction.
 */
public class EvmTxStore {

    private static final byte PREFIX_TX = 0x00;

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
}
