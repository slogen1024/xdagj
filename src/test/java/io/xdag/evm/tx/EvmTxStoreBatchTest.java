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

import static org.junit.Assert.*;

import io.xdag.evm.state.InMemoryKVSource;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.Before;
import org.junit.Test;

public class EvmTxStoreBatchTest {

    private EvmTxStore store;

    @Before
    public void setUp() {
        store = new EvmTxStore(new InMemoryKVSource());
    }

    private static Bytes32 h(int i) {
        return Bytes32.leftPad(Bytes.ofUnsignedInt(i));
    }

    @Test
    public void batch_round_trip_preserves_order_and_is_content_addressed() {
        List<Bytes32> hashes = List.of(h(3), h(1), h(2)); // unsorted on purpose — order must be preserved
        Bytes body = EvmTxStore.encodeBatch(hashes);
        Hash batchHash = store.putBatch(body);
        assertEquals(Hash.hash(body), batchHash);
        assertTrue(store.containsBatch(batchHash));
        assertEquals(hashes, store.getBatch(batchHash).orElseThrow());
        assertEquals(body, store.getBatchRaw(batchHash).orElseThrow());
    }

    @Test
    public void batch_key_space_does_not_collide_with_tx_blobs() {
        Bytes body = EvmTxStore.encodeBatch(List.of(h(1)));
        Hash batchHash = store.putBatch(body);
        // the same 32-byte value looked up as a tx key must miss (0x00 vs 0x01 prefix domains)
        assertFalse(store.contains(batchHash));
        assertTrue(store.containsBatch(batchHash));
    }

    @Test
    public void malformed_or_oversized_body_reads_as_miss() {
        Bytes bad = Bytes.fromHexString("0xdeadbeef"); // not an RLP list
        Hash badHash = store.putBatch(bad);
        assertTrue(store.getBatch(badHash).isEmpty());
        List<Bytes32> tooMany = IntStream.rangeClosed(1, EvmTxStore.MAX_BATCH_TXS + 1)
                .mapToObj(EvmTxStoreBatchTest::h).toList();
        Hash bigHash = store.putBatch(EvmTxStore.encodeBatch(tooMany));
        assertTrue(store.getBatch(bigHash).isEmpty());
        Hash emptyHash = store.putBatch(EvmTxStore.encodeBatch(List.of()));
        assertTrue(store.getBatch(emptyHash).isEmpty());
    }

    @Test
    public void get_batch_for_unknown_hash_is_empty() {
        assertTrue(store.getBatch(Hash.hash(Bytes.of(1))).isEmpty());
        assertFalse(store.containsBatch(Hash.hash(Bytes.of(1))));
    }
}
